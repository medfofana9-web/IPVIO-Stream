"""Turns new Fof TV problem reports (Supabase table fof_reports) into GitHub issues labeled "auto-report".

Runs hourly in .github/workflows/auto-reports.yml. One open issue per problem: a repeat adds a comment instead of a new
issue. Smooth-enough performance lines are skipped (only screens with >= 25 % slow frames or a frozen frame count).
Needs the repository secret FOF_SUPABASE_SERVICE_KEY (added by the owner); GITHUB_TOKEN creates the issues.

Reports are "<name> ‖ <details>" (see ReportLogic.kt). A long report arrives as a head row "<name> ‖#<id>" plus rows
"+#<id> <n> <text>"; they are joined here. Raw stack frames in the details ("raw: class.method:line < …") are turned
back into source lines with the R8 mapping file of that version (artifact "mapping-<run number>" of release.yml) and
R8's retrace tool. Each issue keeps a "Versions seen:" line, so a fix can be confirmed by the versions that stop
reporting.

Test without touching issues: DRY_RUN=1. Test retracing alone: RETRACE_TEST="1.3.271|class.method:12 < class.m:3".
"""
import collections, datetime, io, json, os, re, subprocess, sys, tempfile, urllib.parse, urllib.request, zipfile

URL = "https://msooylkizqvdrgxrdjww.supabase.co"
KEY = os.environ.get("FOF_SUPABASE_SERVICE_KEY", "")
REPO = os.environ.get("GITHUB_REPOSITORY", "medfofana9-web/IPVIO-Stream")
HOURS = int(os.environ.get("LOOKBACK_HOURS", "2") or "2")
DRY = os.environ.get("DRY_RUN", "") not in ("", "0", "false")
SEP = " ‖ "
WORK = tempfile.mkdtemp(prefix="retrace-")


def gh(*args):
    return subprocess.run(["gh", *args], capture_output=True, text=True)


# ---------------------------------------------------------------- retrace

_maps = {}
_r8 = {}


def mapping_for(version):
    """Local path of the R8 mapping of a release version ("1.3.271"), or None."""
    if version in _maps:
        return _maps[version]
    _maps[version] = None
    m = re.fullmatch(r"1\.3\.(\d+)", version or "")
    if not m:
        return None
    r = gh("api", f"repos/{REPO}/actions/artifacts?name=mapping-{m.group(1)}&per_page=5")
    try:
        arts = [a for a in json.loads(r.stdout or "{}").get("artifacts", []) if not a.get("expired")]
    except ValueError:
        arts = []
    if not arts:
        print("no mapping for", version, (r.stderr or "").strip()[:200])
        return None
    z = subprocess.run(["gh", "api", f"repos/{REPO}/actions/artifacts/{arts[0]['id']}/zip"], capture_output=True)
    try:
        data = zipfile.ZipFile(io.BytesIO(z.stdout)).read("mapping.txt")
    except Exception as e:  # noqa: BLE001 - a broken download only means no retrace
        print("mapping download failed for", version, e)
        return None
    path = os.path.join(WORK, f"mapping-{m.group(1)}.txt")
    with open(path, "wb") as f:
        f.write(data)
    _maps[version] = path
    return path


def r8_jar(mapping):
    """R8 (contains retrace) in the version that wrote the mapping; the newest release when that one is missing."""
    ver = None
    with open(mapping, encoding="utf-8", errors="replace") as f:
        for _ in range(20):
            m = re.search(r"compiler_version:\s*(\S+)", f.readline())
            if m:
                ver = m.group(1)
                break
    tries = [v for v in [ver] if v]
    try:
        meta = urllib.request.urlopen("https://dl.google.com/android/maven2/com/android/tools/r8/maven-metadata.xml", timeout=30).read().decode()
        rel = re.search(r"<release>([^<]+)</release>", meta)
        if rel:
            tries.append(rel.group(1))
    except Exception as e:  # noqa: BLE001
        print("r8 metadata:", e)
    for v in tries:
        if v in _r8:
            return _r8[v]
        path = os.path.join(WORK, f"r8-{v}.jar")
        try:
            data = urllib.request.urlopen(f"https://dl.google.com/android/maven2/com/android/tools/r8/{v}/r8-{v}.jar", timeout=120).read()
            with open(path, "wb") as f:
                f.write(data)
            _r8[v] = path
            return path
        except Exception as e:  # noqa: BLE001
            print("r8", v, "not available:", e)
    return None


def retrace(version, raw):
    """Readable stack (list of "Class.method(File.kt:line)") for raw frames "a.b.c.method:12 < …", or []."""
    mapping = mapping_for(version)
    frames = [f.strip() for f in raw.split(" < ") if f.strip()]
    if not mapping or not frames:
        return []
    jar = r8_jar(mapping)
    if not jar:
        return []
    lines = ["java.lang.Throwable"]
    for f in frames:
        m = re.fullmatch(r"(.+)\.([^.]+):(-?\d+)", f)
        if m:
            lines.append(f"\tat {m.group(1)}.{m.group(2)}(SourceFile:{m.group(3)})")
    stack = os.path.join(WORK, "stack.txt")
    with open(stack, "w") as fh:
        fh.write("\n".join(lines) + "\n")
    r = subprocess.run(["java", "-cp", jar, "com.android.tools.r8.retrace.Retrace", mapping, stack], capture_output=True, text=True)
    if r.returncode != 0:
        print("retrace failed:", r.stderr[:300])
        return []
    return [ln.strip().removeprefix("at ").strip() for ln in r.stdout.splitlines() if ln.strip().startswith("at ")]


def retraced_name(stack):
    """Short stable name from a retraced stack: the first app frames as "File.kt method" (no line numbers)."""
    out = []
    for f in stack:
        m = re.fullmatch(r"(?:[\w$]+\.)*([\w$]+)\.([\w$<>-]+)\(([^:)]+)(?::\d+)?\)", f)
        if m and "com.ipvio" in f:
            out.append(f"{m.group(3)} {m.group(2)}")
        if len(out) == 2:
            break
    return " < ".join(out)


if os.environ.get("RETRACE_TEST"):
    v, raw = os.environ["RETRACE_TEST"].split("|", 1)
    st = retrace(v.strip(), raw)
    print("\n".join(st) or "(nothing)")
    print("name:", retraced_name(st))
    sys.exit(0)

# ---------------------------------------------------------------- read and join

if not KEY:
    print("FOF_SUPABASE_SERVICE_KEY is not set - nothing to do")
    sys.exit(0)

since = (datetime.datetime.utcnow() - datetime.timedelta(hours=HOURS)).strftime("%Y-%m-%dT%H:%M:%SZ")
q = urllib.parse.urlencode({"select": "kind,version,device,profile,happened_at", "happened_at": "gte." + since,
                            "order": "happened_at.desc", "limit": "1500"})
req = urllib.request.Request(f"{URL}/rest/v1/fof_reports?{q}", headers={"apikey": KEY, "Authorization": "Bearer " + KEY})
rows = json.load(urllib.request.urlopen(req, timeout=30))
print(len(rows), "rows since", since)

parts = collections.defaultdict(dict)
heads = []
for r in rows:
    k = r.get("kind") or ""
    m = re.match(r"\+#(\w+) (\d+) (.*)", k, re.S)
    if m:
        parts[(m.group(1), r.get("device"), r.get("version"))][int(m.group(2))] = m.group(3)
    else:
        heads.append(r)
for r in heads:
    k = r.get("kind") or ""
    m = re.search(r" ‖#(\w+)$", k)
    if m:
        p = parts.get((m.group(1), r.get("device"), r.get("version")), {})
        r["name"] = k[:m.start()]
        r["details"] = "".join(p[i] for i in sorted(p))
    else:
        r["name"], _, r["details"] = k.partition(SEP)
print(len(heads), "reports")


def worth(name):
    if not name.startswith("perf:"):
        return True
    m = re.search(r"slow (\d+)%", name)
    return "frozen" in name or (m is not None and int(m.group(1)) >= 25)


def no_lines(s):
    # "SportWatch.kt:606 setSlots" -> "SportWatch.kt setSlots"; the same problem keeps its issue across versions
    return re.sub(r"\s+", " ", re.sub(r"(\.(?:kt|java)|r8-map-id-[0-9a-f]+):-?\d+", r"\1", s)).strip()


def key(r):
    name = r["name"]
    # "Report lag" (double Menu on the TV): every report lands in one issue, each report a comment
    if name.startswith("lag:"):
        return "lag: reports from the TV (double Menu)"
    # One issue per problem: numbers that change every time (frame counts, ms) stay out of the title
    if name.startswith("perf:"):
        p = name.split()
        return "perf: slow screen " + (p[1] if len(p) > 1 else "?")
    if name.startswith("start:"):
        # "start: sports 9.7s · pick 7.2 · …" -> "start: sports slow (pick)": the step that took longest
        origin = name.split()[1] if len(name.split()) > 1 else "?"
        phases = {m.group(1): float(m.group(2)) for m in re.finditer(r"(pick|guard|ui|failed feeds|net\+decode) ([\d.]+)", name)}
        top = max(phases, key=phases.get) if phases else "?"
        return f"start: {origin} slow ({top})"
    if name.startswith("reliability:"):
        code = r["details"].split(" · ")[0] if r["details"] else ""
        device = re.sub(r"\s+", " ", str(r.get("device") or "unknown"))[:75]
        return f"{name}{(' · ' + code) if code else ''} · {device}"
    if name.startswith("Closed by Android"):
        return re.sub(r"\s*\(memory.*", "", name)
    t = no_lines(name)
    if "r8-map-id" in t and r.get("stack"):
        nm = retraced_name(r["stack"])
        if nm:
            t = t.split(" · ")[0] + " · " + nm
    return t[:110]


for r in heads:
    m = re.search(r"raw: ([^·]+)", r["details"] or "")
    r["stack"] = retrace(str(r.get("version") or ""), m.group(1)) if m else []

groups = {}
for r in heads:
    if worth(r["name"]):
        groups.setdefault(key(r), []).append(r)

# ---------------------------------------------------------------- issues

open_issues = [] if DRY and not os.environ.get("GH_TOKEN") else json.loads(
    gh("issue", "list", "--repo", REPO, "--label", "auto-report", "--state", "open", "--limit", "300",
       "--json", "number,title,body").stdout or "[]")
by_title = {i["title"]: i for i in open_issues}
if not DRY:
    gh("label", "create", "auto-report", "--repo", REPO, "--color", "E60023", "--description", "Problem reported automatically by a TV")


def versions_line(counts):
    def vkey(v):
        return [int(x) if x.isdigit() else 0 for x in re.split(r"[.\-]", v)]
    return "Versions seen: " + ", ".join(f"{v} ×{n}" for v, n in sorted(counts.items(), key=lambda kv: vkey(kv[0]), reverse=True))


def parse_versions(body):
    m = re.search(r"^Versions seen: (.*)$", body or "", re.M)
    out = collections.Counter()
    if m:
        for v, n in re.findall(r"(\S+) ×(\d+)", m.group(1)):
            out[v] += int(n)
    return out


def render(r):
    head = f"- {r['happened_at']} · {r.get('version', '')} · {r.get('device', '')} · profile {r.get('profile', '')}\n  `{r['name'][:400]}`"
    det = [d for d in (r["details"] or "").split(" · ") if d.strip() and not d.startswith("raw:")]
    if det:
        head += "\n" + "\n".join(f"  - {d.strip()[:400]}" for d in det[:24])
    if r["stack"]:
        head += "\n\n  Retraced stack:\n  ```\n" + "\n".join("  " + s for s in r["stack"][:30]) + "\n  ```"
    else:
        m = re.search(r"raw: ([^·]+)", r["details"] or "")
        if m:
            head += f"\n  - raw (not retraced): `{m.group(1).strip()[:400]}`"
    return head


for k, rs in groups.items():
    if k.startswith("reliability:") and len({r["happened_at"] for r in rs}) < 2:
        continue
    title = ("[auto] " + k)[:250]
    vers = collections.Counter(str(r.get("version") or "?") for r in rs)
    text = f"{len(rs)} report(s) in the last {HOURS} h ({versions_line(vers)[15:]}):\n\n" + "\n\n".join(render(r) for r in rs[:12])
    if len(rs) > 12:
        text += f"\n\n…and {len(rs) - 12} more."
    text = text[:60000]
    if DRY:
        print("=" * 80, "\n", title, "\n", text[:3000])
        continue
    if title in by_title:
        issue = by_title[title]
        gh("issue", "comment", str(issue["number"]), "--repo", REPO, "--body", text)
        body = issue.get("body") or ""
        total = parse_versions(body) + vers
        body = re.sub(r"^Versions seen: .*$", versions_line(total), body, flags=re.M) if "Versions seen:" in body \
            else body + "\n\n" + versions_line(total)
        gh("issue", "edit", str(issue["number"]), "--repo", REPO, "--body", body)
        print("commented", issue["number"], title)
    else:
        body = ("Reported automatically by Fof TV (AutoReport, FrameStats or the crash log).\n\n" + versions_line(vers) +
                "\n\n" + text + "\n\nTriage this report before merging or releasing a fix.")
        r = gh("issue", "create", "--repo", REPO, "--title", title, "--label", "auto-report", "--body", body[:60000])
        print("created", (r.stdout or r.stderr).strip())
