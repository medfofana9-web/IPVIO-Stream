"""Turns new Fof TV problem reports (Supabase table fof_reports) into GitHub issues labeled "auto-report".

Runs hourly in .github/workflows/auto-reports.yml. One open issue per problem: a repeat adds a comment instead of a new
issue. Smooth-enough performance lines are skipped (only screens with >= 25 % slow frames or a frozen frame count).
Needs the repository secret FOF_SUPABASE_SERVICE_KEY (added by the owner); GITHUB_TOKEN creates the issues.
"""
import datetime, json, os, re, subprocess, sys, urllib.parse, urllib.request

URL = "https://msooylkizqvdrgxrdjww.supabase.co"
KEY = os.environ.get("FOF_SUPABASE_SERVICE_KEY", "")
REPO = os.environ["GITHUB_REPOSITORY"]
HOURS = int(os.environ.get("LOOKBACK_HOURS", "2") or "2")

if not KEY:
    print("FOF_SUPABASE_SERVICE_KEY is not set - nothing to do")
    sys.exit(0)

since = (datetime.datetime.utcnow() - datetime.timedelta(hours=HOURS)).strftime("%Y-%m-%dT%H:%M:%SZ")
q = urllib.parse.urlencode({"select": "kind,version,device,profile,happened_at", "happened_at": "gte." + since,
                            "order": "happened_at.desc", "limit": "500"})
req = urllib.request.Request(f"{URL}/rest/v1/fof_reports?{q}", headers={"apikey": KEY, "Authorization": "Bearer " + KEY})
rows = json.load(urllib.request.urlopen(req, timeout=30))
print(len(rows), "reports since", since)


def worth(kind):
    if not kind.startswith("perf:"):
        return True
    m = re.search(r"slow (\d+)%", kind)
    return "frozen" in kind or (m is not None and int(m.group(1)) >= 25)


def key(kind):
    # "Report lag" (double Menu on the TV): every report lands in one issue, each report a comment
    if kind.startswith("lag:"):
        return "lag: reports from the TV (double Menu)"
    # One issue per problem: numbers that change every time (frame counts, ms) stay out of the title
    if kind.startswith("perf:"):
        parts = kind.split()
        return "perf: slow screen " + (parts[1] if len(parts) > 1 else "?")
    return re.sub(r"\s+", " ", kind)[:110]


groups = {}
for r in rows:
    k = r.get("kind") or ""
    if worth(k):
        group = key(k)
        if k.startswith("reliability:"):
            version = str(r.get("version") or "unknown")[:30]
            device = re.sub(r"\\s+", " ", str(r.get("device") or "unknown"))[:75]
            group = f"{group} · {device} · {version}"
        groups.setdefault(group, []).append(r)


def gh(*args):
    return subprocess.run(["gh", *args], capture_output=True, text=True)


gh("label", "create", "auto-report", "--repo", REPO, "--color", "E60023", "--description", "Problem reported automatically by a TV")
open_issues = json.loads(gh("issue", "list", "--repo", REPO, "--label", "auto-report", "--state", "open", "--limit", "200",
                            "--json", "number,title").stdout or "[]")
by_title = {i["title"]: i["number"] for i in open_issues}

for k, rs in groups.items():
    if k.startswith("reliability:") and len({r["happened_at"] for r in rs}) < 2:
        continue
    title = ("[auto] " + k)[:250]
    lines = [f"- {r['happened_at']} · {r.get('version', '')} · {r.get('device', '')} · profile {r.get('profile', '')}\n  `{(r.get('kind') or '')[:400]}`"
             for r in rs[:20]]
    text = f"{len(rs)} report(s) in the last {HOURS} h:\n\n" + "\n".join(lines)
    if title in by_title:
        gh("issue", "comment", str(by_title[title]), "--repo", REPO, "--body", text)
        print("commented", by_title[title], title)
    else:
        body = ("Reported automatically by Fof TV (AutoReport, FrameStats or the crash log).\n\n" + text +
                "\n\nTriage this report before merging or releasing a fix.")
        r = gh("issue", "create", "--repo", REPO, "--title", title, "--label", "auto-report", "--body", body)
        print("created", (r.stdout or r.stderr).strip())
