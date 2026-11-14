"""Prints what is on screen and focused in a uiautomator dump; optional words to look for (comma separated)."""
import sys
import xml.etree.ElementTree as ET

label = sys.argv[2] if len(sys.argv) > 2 else ""
want = [w for w in (sys.argv[3].split(",") if len(sys.argv) > 3 else []) if w]
try:
    root = ET.parse(sys.argv[1]).getroot()
except Exception as e:
    print(f"FOCUS[{label}]: no dump ({e})")
    sys.exit(0)
texts = [(x.get("text") or "").strip() for x in root.iter("node")]
texts = [t for t in texts if t]
print(f"SCREEN[{label}]: " + " | ".join(texts[:4]))
for w in want:
    print(f"HAS[{label}] {w}: " + ("yes" if any(w.lower() in t.lower() for t in texts) else "no"))
hits = [n for n in root.iter("node") if n.get("focused") == "true"]
if not hits:
    print(f"FOCUS[{label}]: nothing focused")
for n in hits:
    sub = [x.get("text") or x.get("content-desc") or "" for x in n.iter("node")]
    sub = [t for t in sub if t.strip()]
    print(f"FOCUS[{label}]: " + " | ".join(sub[:6]))
