"""Soak test check (soak-test.yml): the app must stay alive for every round (same process) and its memory must not keep
growing. Fails when the process changed or vanished, the app crashed, or memory (PSS) after the last round is more
than 35 % above round 2 while the last three rounds kept rising. Usage: soak_check.py mem.txt logcat.txt"""
import sys

rows = []
for l in open(sys.argv[1]):
    p = l.split()
    if len(p) >= 6:
        rows.append((int(p[0]), p[1], int(p[2]), int(p[3]), int(p[4]), int(p[5])))
log = open(sys.argv[2], errors="replace").read()
print("round  pid     PSS MB  Java MB  native MB  graphics MB")
for r in rows:
    print(f"{r[0]:>5}  {r[1]:<6} {r[2] / 1024:7.0f}  {r[3] / 1024:7.0f}  {r[4] / 1024:9.0f}  {r[5] / 1024:11.0f}")
bad = []
if len(rows) < 3:
    bad.append(f"too few memory samples ({len(rows)})")
pids = {r[1] for r in rows}
if "none" in pids:
    bad.append("the app was not running at some point")
elif len(pids) > 1:
    bad.append(f"the app process changed (restarted or killed): {sorted(pids)}")
if "Process: com.fof.tv" in log and "FATAL EXCEPTION" in log:
    bad.append("app crashed (FATAL EXCEPTION)")
if len(rows) >= 5:
    base = rows[2][2]
    last3 = [r[2] for r in rows[-3:]]
    rising = last3[0] < last3[1] < last3[2]
    if base > 0 and rows[-1][2] > base * 1.35 and rising:
        bad.append(f"memory keeps growing: {base // 1024} MB after round 2, {rows[-1][2] // 1024} MB at the end")
if bad:
    print("FAILED:\n- " + "\n- ".join(bad)); sys.exit(1)
print("Soak test passed: app stayed open, memory stable")
