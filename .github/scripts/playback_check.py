"""Checks the playback scenarios in the emulator log (playback-test.yml). Exit code 1 lists the scenarios that failed.
- resume (movie or episode): a picture near the saved spot.
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
        if ok:
            print(f"PASS {sid} {sc['title']}: {msg(ok[0])}")
        else:
            why = msg(failed[0]) if failed else (msg(ready[0]) + " (not at the saved spot)" if ready else "no picture")
            bad.append(f"{sid} {sc['title']} (sources {sc['sources']}): {why}")
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
