"""Compose state in app-wide objects (pr-quality.yml).

A Compose state (mutableStateOf …) that lives in an `object` is created when the object is first used. If that first
use happens on a background thread while a screen is drawn, reading the state crashes the app ("Reading a state that
was created after the snapshot was taken", auto-report #63). Every such object must therefore be created on the main
thread at start: listed in StateWarmUp.kt. Objects that existed before this check are in tools/compose_state_baseline.txt
(existing findings, see CLAUDE.md section 11). A new object with state that is in neither fails the check.
Usage: check_compose_state.py [--update-baseline]
"""
import os, re, sys

SRC = "app/src/main/java/com/ipvio/stream"
BASE = "tools/compose_state_baseline.txt"
STATE = re.compile(r"^\s{4}(?:@\w+\s+)*(?:private\s+|internal\s+)?va[rl]\s+\w+.*\b(mutableStateOf|mutableIntStateOf|mutableLongStateOf|mutableFloatStateOf|mutableDoubleStateOf|mutableStateListOf|mutableStateMapOf)\b")
OBJ = re.compile(r"^(?:internal\s+|private\s+)?object\s+(\w+)")

found = set()
for f in sorted(os.listdir(SRC)):
    if not f.endswith(".kt"):
        continue
    cur = None
    for line in open(os.path.join(SRC, f), encoding="utf-8"):
        m = OBJ.match(line)
        if m:
            cur = m.group(1); continue
        if line.startswith(("class ", "fun ", "@Composable", "private fun ", "internal fun ", "data class ", "enum class ", "sealed class ", "interface ", "private class ", "internal class ")):
            cur = None; continue
        if cur and STATE.match(line):
            found.add(cur)

warm = set(re.findall(r"\b([A-Z]\w+)\.", open(os.path.join(SRC, "StateWarmUp.kt"), encoding="utf-8").read()))
if "--update-baseline" in sys.argv:
    with open(BASE, "w") as fh:
        fh.write("# Objects with Compose state that existed before tools/check_compose_state.py (not yet in StateWarmUp.kt).\n")
        for o in sorted(found - warm):
            fh.write(o + "\n")
    print(f"baseline written: {len(found - warm)} objects"); sys.exit(0)
base = {l.strip() for l in open(BASE) if l.strip() and not l.startswith("#")} if os.path.exists(BASE) else set()
new = sorted(found - warm - base)
print(f"{len(found)} objects hold Compose state: {len(found & warm)} created at start (StateWarmUp.kt), {len(found & base)} existing (baseline)")
if new:
    print("CHECK FAIL new objects with Compose state that are not created on the main thread at start:\n  " + "\n  ".join(new) +
          "\nAdd each to StateWarmUp.kt (touch one of its states there), or - only if it is never used off the main thread - "
          "to tools/compose_state_baseline.txt with a comment why.")
    sys.exit(1)
print("CHECK PASS no new object with Compose state outside StateWarmUp.kt")
