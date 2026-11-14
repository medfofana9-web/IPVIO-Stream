"""Prints the text of the focused element in a uiautomator dump (the focused node and its descendants)."""
import sys
import xml.etree.ElementTree as ET

label = sys.argv[2] if len(sys.argv) > 2 else ""
try:
    root = ET.parse(sys.argv[1]).getroot()
except Exception as e:
    print(f"FOCUS[{label}]: no dump ({e})")
    sys.exit(0)
hits = [n for n in root.iter("node") if n.get("focused") == "true"]
if not hits:
    print(f"FOCUS[{label}]: nothing focused")
    sys.exit(0)
node = hits[-1]
texts = [x.get("text") or x.get("content-desc") or "" for x in node.iter("node")]
texts = [t for t in texts if t.strip()]
print(f"FOCUS[{label}]: " + " | ".join(texts[:6]))
