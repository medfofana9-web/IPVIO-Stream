"""Compares Home smoothness of this perf-test run with the last passing one (perf-test.yml). Fails when the median
frame time (50th percentile of gfx_home) is more than 25 % and 3 ms slower, or the share of frames held up by the app
("Number Slow UI thread") is more than 10 points higher. Emulator numbers are noisy; the limits leave room for that.
Usage: perf_compare.py gfx_home.txt (needs GH_TOKEN, GITHUB_REPOSITORY, GITHUB_RUN_ID)."""
import io, json, os, re, subprocess, sys, zipfile

REPO = os.environ["GITHUB_REPOSITORY"]


def parse(text):
    p50 = re.search(r"50th percentile: (\d+)ms", text)
    tot = re.search(r"Total frames rendered: (\d+)", text)
    slow = re.search(r"Number Slow UI thread: (\d+)", text)
    if not (p50 and tot and slow) or int(tot.group(1)) == 0:
        return None
    return int(p50.group(1)), int(slow.group(1)) * 100 // int(tot.group(1))


cur = parse(open(sys.argv[1], errors="replace").read())
if cur is None:
    print("CHECK FAIL Home comparison: no frame numbers in this run"); sys.exit(1)
runs = json.loads(subprocess.run(["gh", "api", f"repos/{REPO}/actions/workflows/perf-test.yml/runs?status=success&per_page=10"],
                                 capture_output=True, text=True).stdout or "{}").get("workflow_runs", [])
prev = None
for r in runs:
    if str(r["id"]) == os.environ.get("GITHUB_RUN_ID"):
        continue
    arts = json.loads(subprocess.run(["gh", "api", f"repos/{REPO}/actions/runs/{r['id']}/artifacts"], capture_output=True, text=True).stdout or "{}").get("artifacts", [])
    a = next((a for a in arts if a["name"] == "perf-test" and not a.get("expired")), None)
    if not a:
        continue
    z = subprocess.run(["gh", "api", f"repos/{REPO}/actions/artifacts/{a['id']}/zip"], capture_output=True).stdout
    try:
        prev = parse(zipfile.ZipFile(io.BytesIO(z)).read("gfx_home.txt").decode(errors="replace"))
    except Exception as e:  # noqa: BLE001
        print("previous run", r["id"], "unreadable:", e)
    if prev:
        print(f"Comparing with run {r['id']} ({r['head_sha'][:7]})")
        break
if prev is None:
    print(f"CHECK PASS Home comparison: no earlier passing run to compare (now median {cur[0]} ms, {cur[1]} % slow)"); sys.exit(0)
bad = []
if cur[0] > prev[0] * 1.25 and cur[0] - prev[0] > 3:
    bad.append(f"median frame {prev[0]} ms -> {cur[0]} ms")
if cur[1] - prev[1] > 10:
    bad.append(f"frames held up by the app {prev[1]} % -> {cur[1]} %")
if bad:
    print("CHECK FAIL Home slower than the last passing run: " + "; ".join(bad)); sys.exit(1)
print(f"CHECK PASS Home not slower: median {prev[0]} -> {cur[0]} ms, slow {prev[1]} -> {cur[1]} %")
