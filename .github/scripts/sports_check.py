"""Reads the FofStart log lines of the sports fixture and prints CHECK lines (exit 1 on a failed check)."""
import json, re, sys
fx = json.load(open(sys.argv[1]))
log = open(sys.argv[2], errors="replace").read().splitlines()
lines = [l.split("FofStart: ", 1)[1] for l in log if "FofStart: " in l]
print("\n".join("  " + l for l in lines))
if not fx.get("live"):
    print("CHECK SKIP no live NBA game right now (fixture needs one)"); sys.exit(0)
frames = [l for l in lines if l.startswith("first frame: fixture")]
ok = True
def check(name, cond, detail=""):
    global ok
    print(("CHECK PASS " if cond else "CHECK FAIL ") + name + (f" ({detail})" if detail else ""))
    ok = ok and cond
check("cold start showed a picture", len(frames) >= 1, frames[0] if frames else "no first frame")
check("repeat start showed a picture", len(frames) >= 2, frames[1] if len(frames) > 1 else "no second first frame")
for i, f in enumerate(frames[:2]):
    on = f.split(" · on ", 1)[1] if " · on " in f else ""
    check(f"start {i+1} on the right game", on in fx["expected"], on)
    check(f"start {i+1} never on another game or the dead feed", on not in fx["forbidden"], on)
    m = re.search(r"fixture ([0-9.]+)s", f)
    if m: print(f"TIMING start {i+1}: {m.group(1)} s OK to first frame (emulator, synthetic feeds)")
sys.exit(0 if ok else 1)
