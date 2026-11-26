"""Checks the playback scenarios in the emulator log (playback-test.yml): every scenario must reach a picture near its
saved spot. Exit code 1 lists the scenarios that failed. Usage: playback_check.py fixture.json logcat.txt"""
import json, re, sys

fx = json.load(open(sys.argv[1]))
log = open(sys.argv[2], errors="replace").read().splitlines()
lines = [l for l in log if "FofPlay" in l or "FATAL EXCEPTION" in l]
print("\n".join(lines[-120:]))
bad = []
if any("FATAL EXCEPTION" in l for l in log):
    bad.append("app crashed (FATAL EXCEPTION in the log)")
for sc in fx["scenarios"]:
    sid = sc["id"]
    idx = [i for i, l in enumerate(lines) if f"scenario {sid} " in l]
    if not idx:
        bad.append(f"{sid} {sc['title']}: scenario never started"); continue
    after = lines[idx[0] + 1:]
    nxt = next((i for i, l in enumerate(after) if "scenario tt" in l), len(after))
    seg = after[:nxt]
    ready = [l for l in seg if " ready " in l]
    failed = [l for l in seg if " failed " in l]
    want_s = sc["resume_ms"] // 1000 - 8
    ok = [l for l in ready if (m := re.search(r" at (\d+)s", l)) and int(m.group(1)) >= want_s]
    if ok:
        print(f"PASS {sid} {sc['title']}: {ok[0].split('FofPlay')[-1].strip()}")
    else:
        why = failed[0].split("FofPlay")[-1].strip() if failed else (ready[0].split("FofPlay")[-1].strip() + " (not at the saved spot)" if ready else "no picture")
        bad.append(f"{sid} {sc['title']} (sources {sc['sources']}): {why}")
if bad:
    print("FAILED:\n- " + "\n- ".join(bad)); sys.exit(1)
print("All playback scenarios passed")
