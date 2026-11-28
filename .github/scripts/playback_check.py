"""Checks the playback scenarios in the emulator log (playback-test.yml). Exit code 1 lists the scenarios that failed.
- resume (movie or episode): a picture near the saved spot; with seek_keys also a forward jump that plays on.
- live / zap: at the end of the scenario the player is playing (last "tick" state=3, READY) and the position moved.
Usage: playback_check.py fixture.json logcat.txt"""
import json, re, sys

fx = json.load(open(sys.argv[1]))
log = open(sys.argv[2], errors="replace").read().splitlines()
lines = [l for l in log if "FofPlay" in l or "FATAL EXCEPTION" in l]
print("\n".join(l for l in lines if " tick " not in l)[-6000:])
bad = []
if any("FATAL EXCEPTION" in l for l in log) and any("Process: com.fof.tv" in l for l in log):
    bad.append("app crashed (FATAL EXCEPTION in the log)")


def segment(marker):
    idx = [i for i, l in enumerate(lines) if marker in l]
    if not idx:
        return None
    after = lines[idx[0] + 1:]
    nxt = next((i for i, l in enumerate(after) if "FofPlay : scenario " in l or "FofPlay: scenario " in l), len(after))
    return after[:nxt]


def msg(l):
    return l.split("FofPlay")[-1].strip(" :")


for sc in fx["scenarios"]:
    sid = sc["id"]
    if sc["kind"] == "resume":
        seg = segment(f"scenario {sid} ")
        if seg is None:
            bad.append(f"{sid} {sc['title']}: scenario never started"); continue
        ready = [l for l in seg if " ready " in l]
        failed = [l for l in seg if " failed " in l]
        want_s = sc["resume_ms"] // 1000 - 8
        ok = [l for l in ready if (m := re.search(r" at (\d+)s", l)) and int(m.group(1)) >= want_s]
        if ok and sc.get("seek_keys"):
            # The jump must land ahead of where it played and the movie must play on from there
            si = next((i for i, l in enumerate(seg) if " seek to " in l), None)
            tick = lambda l: (m := re.search(r" tick \S+ state=(\d) pos=(-?\d+)", l)) and (int(m.group(1)), int(m.group(2)))
            before = [t for t in map(tick, seg[:si] if si is not None else seg) if t]
            after = [t for t in map(tick, seg[si + 1:]) if t] if si is not None else []
            to = int(re.search(r" seek to (\d+)s", seg[si]).group(1)) if si is not None else -1
            last_before = before[-1][1] if before else 0
            if si is None:
                bad.append(f"{sid} {sc['title']}: no seek after Right x{len(sc['seek_keys'])}")
            elif to <= last_before + 5 or not after or after[-1][1] < to - 2 or after[-1][0] != 3:
                bad.append(f"{sid} {sc['title']}: seek to {to}s from {last_before}s, then ticks {after[-3:]}")
            else:
                print(f"PASS {sid} {sc['title']}: {msg(ok[0])}; seek {last_before}s -> {to}s, playing at {after[-1][1]}s")
        elif ok and sc.get("cut"):
            # The first source stops mid-play: the other copy must give a picture past the saved spot, and play at the end
            later = [l for l in ready[ready.index(ok[0]) + 1:] if (m := re.search(r" at (\d+)s", l)) and int(m.group(1)) >= sc["resume_ms"] // 1000]
            tick = lambda l: (m := re.search(r" tick \S+ state=(\d) pos=(-?\d+)", l)) and (int(m.group(1)), int(m.group(2)))
            ticks = [t for t in map(tick, seg) if t]
            if not later:
                bad.append(f"{sid} {sc['title']}: the source stopped mid-play and no other copy took over (log: {[msg(l) for l in seg if ' tick ' not in l][-5:]})")
            elif not ticks or ticks[-1][0] != 3:
                bad.append(f"{sid} {sc['title']}: other copy started ({msg(later[0])}) but not playing at the end (ticks {ticks[-3:]})")
            else:
                print(f"PASS {sid} {sc['title']}: {msg(ok[0])}; source cut, then {msg(later[0])}, playing at {ticks[-1][1]}s")
        elif ok and sc.get("heard"):
            # Intro by sound: the theme is recognised by itself near where it plays, and OK skips to its end
            want_a, want_b = sc["heard"]
            hi = next((i for i, l in enumerate(seg) if " intro heard from " in l), None)
            tick = lambda l: (m := re.search(r" tick \S+ state=(\d) pos=(-?\d+)", l)) and (int(m.group(1)), int(m.group(2)))
            learned = [msg(l) for l in lines if " intro learned " in l or " intro shared " in l or " intro print heard " in l or " intro template " in l]
            if hi is None:
                bad.append(f"{sid} {sc['title']}: theme not recognised (intro log: {learned[-6:]})")
            else:
                m = re.search(r" intro heard from (\d+)s to (\d+)s", seg[hi])
                a, b = int(m.group(1)), int(m.group(2))
                after = [t for t in map(tick, seg[hi + 1:]) if t]
                if abs(a - want_a) > 3 or abs(b - want_b) > 3:
                    bad.append(f"{sid} {sc['title']}: heard {a}s-{b}s, theme is at {want_a}s-{want_b}s")
                elif not after or after[-1][0] != 3 or after[-1][1] < b - 2:
                    bad.append(f"{sid} {sc['title']}: heard {a}s-{b}s, but OK did not skip it (ticks {after[-3:]})")
                else:
                    print(f"PASS {sid} {sc['title']}: theme heard {a}s-{b}s by itself, OK skipped to {after[-1][1]}s")
        elif ok and sc.get("intro_keys"):
            # Episode without intro times: Down opens the bar on "Skip intro"; OK jumps forward 60 s and plays on
            ii = next((i for i, l in enumerate(seg) if " intro skip from " in l), None)
            tick = lambda l: (m := re.search(r" tick \S+ state=(\d) pos=(-?\d+)", l)) and (int(m.group(1)), int(m.group(2)))
            if ii is None:
                bad.append(f"{sid} {sc['title']}: no Skip intro after Down + OK (button missing or not focused)")
            else:
                m = re.search(r" intro skip from (\d+)s to (\d+)s", seg[ii])
                a, b = int(m.group(1)), int(m.group(2))
                after = [t for t in map(tick, seg[ii + 1:]) if t]
                if b - a < 55 or not after or after[-1][0] != 3 or after[-1][1] < b - 2:
                    bad.append(f"{sid} {sc['title']}: Skip intro {a}s -> {b}s, then ticks {after[-3:]}")
                elif sc["intro_keys"].count(23) > 1 and not any(" intro more to " in l for l in seg[ii + 1:]):
                    bad.append(f"{sid} {sc['title']}: Skip intro {a}s -> {b}s, but the second OK did not skip 10 s more")
                else:
                    print(f"PASS {sid} {sc['title']}: {msg(ok[0])}; Skip intro {a}s -> {b}s, playing at {after[-1][1]}s")
        elif ok and sc.get("bar_keys"):
            # OK pauses (bar opens, Play focused); OK on the bar's Play button resumes, and the movie plays on
            pi = next((i for i, l in enumerate(seg) if " toggle pause" in l), None)
            ri = next((i for i, l in enumerate(seg) if " toggle play bar" in l and (pi is None or i > pi)), None)
            tick = lambda l: (m := re.search(r" tick \S+ state=(\d) pos=(-?\d+)", l)) and (int(m.group(1)), int(m.group(2)))
            after = [t for t in map(tick, seg[ri + 1:]) if t] if ri is not None else []
            if pi is None:
                bad.append(f"{sid} {sc['title']}: OK did not pause")
            elif ri is None:
                bad.append(f"{sid} {sc['title']}: the control bar's Play button did not resume (focus not on Play?)")
            elif len(after) < 2 or after[-1][0] != 3 or after[-1][1] <= after[0][1]:
                bad.append(f"{sid} {sc['title']}: resumed from the bar but not playing on (ticks {after[-3:]})")
            else:
                print(f"PASS {sid} {sc['title']}: {msg(ok[0])}; paused, resumed from the bar's Play button, playing at {after[-1][1]}s")
        elif ok:
            print(f"PASS {sid} {sc['title']}: {msg(ok[0])}")
        else:
            why = msg(failed[0]) if failed else (msg(ready[0]) + " (not at the saved spot)" if ready else "no picture")
            bad.append(f"{sid} {sc['title']} (sources {sc['sources']}): {why}")
    elif sc["kind"] == "dead":
        seg = segment(f"scenario live {sc['channel']}")
        if seg is None:
            bad.append(f"{sid}: scenario never started"); continue
        d = next((l for l in seg if " live dead after " in l), None)
        m = d and re.search(r" live dead after (\d+)s", d)
        if not m:
            bad.append(f"{sid} ({sc['channel']}): no 'not sending a picture' message (log: {[msg(l) for l in seg if ' tick ' not in l][-4:]})")
        elif int(m.group(1)) > 9:
            bad.append(f"{sid} ({sc['channel']}): message only after {m.group(1)} s")
        else:
            print(f"PASS {sid} ({sc['channel']}): dead channel reported after {m.group(1)} s")
    else:
        seg = segment(f"scenario live {sc['channel']}")
        if seg is None:
            bad.append(f"{sid}: scenario never started"); continue
        if any(f"live {sc['channel']} not in the playlist" in l for l in lines):
            bad.append(f"{sid}: channel {sc['channel']} not in the test playlist"); continue
        ticks = [(int(m.group(1)), int(m.group(2))) for l in seg if (m := re.search(r" tick \S+ state=(\d) pos=(-?\d+)", l))]
        if len(ticks) < 2:
            bad.append(f"{sid} ({sc['channel']}): player did not run (ticks: {ticks})"); continue
        last = ticks[-1]
        moved = any(p > 0 for _, p in ticks[-3:])
        if last[0] == 3 and moved:
            print(f"PASS {sid} ({sc['channel']}): playing at the end, {len(ticks)} ticks")
        else:
            bad.append(f"{sid} ({sc['channel']}): not playing at the end (last ticks {ticks[-4:]}, state 3 = playing)")
if bad:
    print("FAILED:\n- " + "\n- ".join(bad)); sys.exit(1)
print("All playback scenarios passed")
