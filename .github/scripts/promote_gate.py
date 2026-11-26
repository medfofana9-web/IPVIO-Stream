"""Promotion gate (promote.yml): a build reaches every TV only when
  1. the playback scenarios (playback-test.yml), the soak test (soak-test.yml) and the performance test (perf-test.yml)
     passed on the last commit of that build that changed the app, and
  2. the early-channel TVs running it reported no new crash, freeze, forced close or stream that did not start, and at
     most 2 playback errors (Supabase fof_reports, version = the build).
Prints the reasons and exits 1 when the build must wait; opens or updates one "[auto] promotion held" issue so the
daily fix run and the owner see it. Usage: promote_gate.py <tag>. Needs GH_TOKEN; FOF_SUPABASE_SERVICE_KEY optional
(without it only the scenario check runs).
"""
import json, os, re, subprocess, sys, urllib.parse, urllib.request

REPO = os.environ["GITHUB_REPOSITORY"]
TAG = sys.argv[1]
VERSION = TAG.lstrip("v")
KEY = os.environ.get("FOF_SUPABASE_SERVICE_KEY", "")
URL = "https://msooylkizqvdrgxrdjww.supabase.co"


def gh(*a):
    return subprocess.run(["gh", *a], capture_output=True, text=True)


def blocking(kind):
    k = kind or ""
    if k.startswith(("perf:", "lag:", "start:", "addon:", "idle-draw:", "+#")):
        return None
    if k.startswith("stream:"):
        return "stream did not start"
    if k.startswith("freeze:"):
        return "freeze"
    if k.startswith("Closed by Android: app not responding") or k.startswith("Freeze"):
        return "app not responding"
    # Closed for memory while in use (closes in the background are reported as "memory:" and do not count)
    if k.startswith("Closed by Android"):
        return "closed by Android while in use"
    if k.startswith("reliability: playback error"):
        return "playback error"
    if re.match(r"^[A-Z]\w*(Exception|Error)\b", k):
        return "crash"
    return None


reasons = []
sha = json.loads(gh("api", f"repos/{REPO}/commits/{TAG}").stdout or "{}").get("sha", "")
# The checks run on commits that change the app; a later commit that changes only the Mac/iPhone apps or docs builds the
# same TV app, so the last commit at or before the release that touched app/ is the one that must have passed
hist = json.loads(gh("api", f"repos/{REPO}/commits?sha={sha}&path=app&per_page=1").stdout or "[]")
app_sha = hist[0]["sha"] if hist else sha
CHECKS = [("playback-test.yml", "playback scenarios"), ("soak-test.yml", "one-hour soak test (app stays open, memory stable)"),
          ("perf-test.yml", "performance test (Home not slower)")]
for wf, what in CHECKS:
    runs = json.loads(gh("api", f"repos/{REPO}/actions/workflows/{wf}/runs?head_sha={app_sha}&per_page=10").stdout or "{}").get("workflow_runs", [])
    if not any(r.get("conclusion") == "success" for r in runs):
        state = ", ".join(sorted({(r.get("conclusion") or r.get("status") or "?") for r in runs})) or "not run"
        reasons.append(f"{what} has not passed on {app_sha[:7]} ({state})")

if KEY:
    q = urllib.parse.urlencode({"select": "kind,device,happened_at", "version": "eq." + VERSION, "limit": "500"})
    req = urllib.request.Request(f"{URL}/rest/v1/fof_reports?{q}", headers={"apikey": KEY, "Authorization": "Bearer " + KEY})
    try:
        rows = json.load(urllib.request.urlopen(req, timeout=30))
    except Exception as e:  # noqa: BLE001
        rows = []
        reasons.append(f"early-channel reports could not be read ({e})")
    counts = {}
    for r in rows:
        b = blocking(r.get("kind"))
        if b:
            counts[b] = counts.get(b, 0) + 1
    for what, n in counts.items():
        if what == "playback error" and n <= 2:
            continue
        reasons.append(f"{n}× {what} reported by early-channel TVs on {VERSION}")
else:
    print("FOF_SUPABASE_SERVICE_KEY not set: early-channel reports not checked")

if not reasons:
    print(f"{TAG}: gate passed")
    sys.exit(0)

text = f"{TAG} stays on the early channel:\n\n- " + "\n- ".join(reasons) + \
       "\n\nIt is promoted automatically once these are cleared by a newer build, or by hand (promote.yml, \"now\")."
print(text)
title = "[auto] promotion held"
issues = json.loads(gh("issue", "list", "--repo", REPO, "--state", "open", "--search", f'"{title}" in:title', "--json", "number,title").stdout or "[]")
same = [i for i in issues if i["title"] == title]
if same:
    # Runs hourly: comment only when the reasons changed
    last = json.loads(gh("api", f"repos/{REPO}/issues/{same[0]['number']}/comments?per_page=100").stdout or "[]")
    prev = (last[-1]["body"] if last else json.loads(gh("api", f"repos/{REPO}/issues/{same[0]['number']}").stdout or "{}").get("body", ""))
    if prev.strip() != text.strip():
        gh("issue", "comment", str(same[0]["number"]), "--repo", REPO, "--body", text)
else:
    gh("label", "create", "auto-report", "--repo", REPO, "--color", "E60023", "--description", "Problem reported automatically by a TV")
    gh("issue", "create", "--repo", REPO, "--title", title, "--label", "auto-report", "--body", text)
sys.exit(1)
