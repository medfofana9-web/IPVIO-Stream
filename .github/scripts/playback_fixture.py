"""Playback scenario fixture for the emulator (playback-test.yml).

One HTTP server on port 8002 plays the parts of real sources that went wrong on TVs:
  /a/…  addon "Dragon Fixture" (named like DragonStreams, so it gets the same priority)
  /b/…  addon "HD Fixture"
  /media/good.mp4      answers range requests (can seek)
  /media/norange.mp4   ignores range requests and sends slowly (DragonStreams on Resume)
  /media/expired.mp4   HTTP 403 (an expired addon link)
  /media/hang.mp4      sends headers, then nothing
  /live/good.ts        a live channel (srv/live.ts)
  /live/drop.ts        a live channel whose connection ends after about 15 s every time (the player must reconnect)
Scenarios (movie ids) and what each addon returns are in SCEN (SEEK: keys pressed after the picture, then checked); fixture.json lists them for the checker. Needs
srv/movie.mp4 (made by the workflow with ffmpeg). No real provider, addon or credentials.
"""
import json, os, re, sys, threading, time
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "srv")
MOVIE = os.path.join(ROOT, "movie.mp4")
LIVE = os.path.join(ROOT, "live.ts")
ADDONS = {"a": "Dragon Fixture", "b": "HD Fixture"}
# id: (title, year, {addon: (media, delay_s)}, remembered addon or None, resume ms)
SCEN = {
    "tt9000001": ("Fixture One", 2006, {"a": ("norange", 0), "b": ("good", 0)}, "a", 60000),
    "tt9000002": ("Fixture Two", 2007, {"a": ("expired", 0), "b": ("good", 0)}, "a", 60000),
    "tt9000003": ("Fixture Three", 2008, {"a": ("hang", 0), "b": ("good", 0)}, "a", 60000),
    "tt9000004": ("Fixture Four", 2009, {"a": ("good", 9), "b": ("hang", 0)}, None, 60000),
    "tt9000005": ("Fixture Five", 2010, {"a": ("expired", 0), "b": ("good", 6)}, "a", 60000),
    # Episodes (id:season:episode): the same problems on a show
    "tt9100001:1:2": ("Fixture Show", 2015, {"a": ("norange", 0), "b": ("good", 0)}, "a", 60000),
    "tt9100001:2:3": ("Fixture Show", 2015, {"a": ("expired", 0), "b": ("good", 5)}, "a", 60000),
    # Seeking after the start (Right x3): the jump lands and plays on from there (#50: the seek glide no longer reads
    # the position while composing)
    "tt9000006": ("Fixture Six", 2011, {"b": ("good", 0)}, "b", 30000),
    # New control bar (27 Nov 2026): OK pauses and shows the bar with Play focused; OK on the bar's Play button resumes
    "tt9000007": ("Fixture Seven", 2012, {"b": ("good", 0)}, "b", 30000),
    # Skip intro without known times (27 Nov 2026): Down opens the bar on "Skip intro", OK jumps 60 s and plays on
    "tt9100002:1:1": ("Fixture Series", 2016, {"b": ("good", 0)}, "b", 10000),
    # Intro by sound (27 Nov 2026): three episodes share a 30 s theme between different noise (intro_*.mp4, made at
    # start). Episodes 1 and 2 teach the intro; in episode 3 the theme (15-45 s) is recognised and OK skips it.
    "tt9100003:1:1": ("Fixture Theme", 2017, {"b": ("introa", 0)}, "b", 1000),
    "tt9100003:1:2": ("Fixture Theme", 2017, {"b": ("introb", 0)}, "b", 1000),
    "tt9100003:1:3": ("Fixture Theme", 2017, {"b": ("introc", 0)}, "b", 1000),
}
# Seconds to wait after starting a scenario before its keys (default 55). Episode 3: the theme (15 s) is heard about
# 1 s in and its button shows for 10 s, so OK comes at about 19 s
WAIT = {"tt9100003:1:3": 20}
# Intro the app must hear by itself (start s, end s), then OK skips it
HEARD = {"tt9100003:1:3": [15, 45]}
# file, seeds and lengths (noise before, theme 30 s, noise after; 120 s in all)
INTRO_MEDIA = {"introa": ("intro_a.mp4", 1, 10, 2, 80), "introb": ("intro_b.mp4", 3, 20, 4, 70), "introc": ("intro_c.mp4", 5, 15, 6, 75)}
SEEK = {"tt9000006": [22, 22, 22]}
BAR = {"tt9000007": [23, "sleep 4; adb exec-out screencap -p > p_tt9000007_bar.png", 23]}
# The bar hides 4 s after the last key while playing: a first Down for the screenshot, then Down and OK back to back
INTRO = {"tt9100002:1:1": [20, "adb exec-out screencap -p > p_tt9100002_intro.png", "sleep 6", 20, 23, 23],
         "tt9100003:1:3": [23]}
# Live channels in srv/test.m3u (name, path) and the live scenarios: play, a connection that keeps dropping, zapping
CHANNELS = [("Fixture Live A", "good"), ("Fixture Live B", "good"), ("Fixture Live C", "good"), ("Fixture Live Drop", "drop")]
LIVE_SCEN = [
    {"id": "live-a", "kind": "live", "channel": "Fixture Live A", "wait": 30},
    {"id": "live-drop", "kind": "live", "channel": "Fixture Live Drop", "wait": 70},
    {"id": "zap", "kind": "zap", "channel": "Fixture Live B", "wait": 30, "keys": [19, 19, 20, 19, 20, 20]},
]
HOST = "http://10.0.2.2:8002"


def stream_title(sid):
    title, year = SCEN[sid][0], SCEN[sid][1]
    if ":" in sid:
        _, se, ep = sid.split(":")
        return f"{title} S{int(se):02d}E{int(ep):02d} 1080p"
    return f"{title} {year} 1080p"


def label(addon, sid):
    return f"{ADDONS[addon]} · {stream_title(sid)}"


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        sys.stderr.write("fixture " + (a[0] % a[1:]) + "\n")

    def send_json(self, obj):
        b = json.dumps(obj).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        p = self.path.split("?")[0]
        m = re.fullmatch(r"/([ab])/manifest\.json", p)
        if m:
            k = m.group(1)
            return self.send_json({"id": "fixture." + k, "version": "1.0.0", "name": ADDONS[k], "resources": ["stream"],
                                   "types": ["movie", "series"], "idPrefixes": ["tt"], "catalogs": []})
        m = re.fullmatch(r"/([ab])/stream/(?:movie|series)/(tt\d+(?::\d+:\d+)?)\.json", p.replace("%3A", ":"))
        if m:
            k, sid = m.group(1), m.group(2)
            sc = SCEN.get(sid)
            if not sc or k not in sc[2]:
                return self.send_json({"streams": []})
            media, delay = sc[2][k]
            time.sleep(delay)
            return self.send_json({"streams": [{"name": ADDONS[k], "title": stream_title(sid),
                                                "url": f"{HOST}/media/{media}.mp4?s={sid}"}]})
        m = re.fullmatch(r"/media/(\w+)\.mp4", p)
        if m:
            return self.media(m.group(1))
        m = re.fullmatch(r"/live/(\w+)\.ts", p)
        if m:
            return self.live(m.group(1))
        self.send_response(404); self.send_header("Content-Length", "0"); self.end_headers()

    def live(self, kind):
        size = os.path.getsize(LIVE)
        # Live: no length, sent at about real speed; "drop" ends the connection after about 15 s
        self.send_response(200); self.send_header("Content-Type", "video/mp2t"); self.send_header("Connection", "close"); self.end_headers()
        rate = max(size // 180, 50_000)
        limit = rate * 15 if kind == "drop" else size
        sent = 0
        try:
            with open(LIVE, "rb") as f:
                # A head start so the picture comes quickly, then real time
                while sent < limit:
                    chunk = f.read(min(64 * 1024, limit - sent))
                    if not chunk:
                        break
                    self.wfile.write(chunk); sent += len(chunk)
                    if sent > rate * 3:
                        time.sleep(len(chunk) / rate)
        except (BrokenPipeError, ConnectionResetError):
            pass
        self.close_connection = True

    def media(self, kind):
        if kind == "expired":
            self.send_response(403); self.send_header("Content-Length", "0"); self.end_headers(); return
        path = os.path.join(ROOT, INTRO_MEDIA[kind][0]) if kind in INTRO_MEDIA else MOVIE
        if not os.path.exists(path):
            self.send_response(404); self.send_header("Content-Length", "0"); self.end_headers(); return
        size = os.path.getsize(path)
        ranged = kind == "good" or kind in INTRO_MEDIA
        if kind == "hang":
            self.send_response(200); self.send_header("Content-Type", "video/mp4"); self.send_header("Content-Length", str(size)); self.end_headers()
            time.sleep(600); return
        rng = self.headers.get("Range")
        start, end = 0, size - 1
        if ranged and rng:
            mm = re.match(r"bytes=(\d+)-(\d*)", rng)
            if mm:
                start = int(mm.group(1)); end = int(mm.group(2)) if mm.group(2) else size - 1
                if start >= size:
                    self.send_response(416); self.send_header("Content-Range", f"bytes */{size}"); self.send_header("Content-Length", "0"); self.end_headers(); return
                end = min(end, size - 1)
            self.send_response(206); self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        else:
            self.send_response(200)
        if ranged:
            self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Type", "video/mp4"); self.send_header("Content-Length", str(end - start + 1)); self.end_headers()
        # norange: about 400 KB/s, so resuming has to read for a while before the picture (like a server that cannot seek)
        rate = 400 * 1024 if kind == "norange" else 0
        try:
            with open(path, "rb") as f:
                f.seek(start); left = end - start + 1
                while left > 0:
                    chunk = f.read(min(64 * 1024, left))
                    if not chunk:
                        break
                    self.wfile.write(chunk); left -= len(chunk)
                    if rate:
                        time.sleep(len(chunk) / rate)
        except (BrokenPipeError, ConnectionResetError):
            pass


def make_intro_media():
    """The three themed episodes (ffmpeg, about 20 s; the emulator needs minutes to start anyway)."""
    import subprocess
    theme = os.path.join(ROOT, "theme.wav")
    expr = ("aevalsrc='0.45*sin(2*PI*t*(420+160*sin(1.3*t)+90*sin(0.37*t)))+"
            "0.3*sin(2*PI*t*(830+120*sin(0.71*t)+60*sin(2.3*t)))':s=48000:d=30")
    try:
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", expr, theme], check=True)
        for name, s1, pre, s2, post in INTRO_MEDIA.values():
            out = os.path.join(ROOT, name)
            tmp = out + ".part.mp4"
            subprocess.run(["ffmpeg", "-loglevel", "error", "-y",
                            "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=25:d=120",
                            "-f", "lavfi", "-i", f"anoisesrc=c=pink:seed={s1}:a=0.25:r=48000:d={pre}",
                            "-i", theme,
                            "-f", "lavfi", "-i", f"anoisesrc=c=pink:seed={s2}:a=0.25:r=48000:d={post}",
                            "-filter_complex", "[1:a][2:a][3:a]concat=n=3:v=0:a=1[a]", "-map", "0:v", "-map", "[a]",
                            "-t", "120", "-c:v", "libx264", "-preset", "veryfast", "-b:v", "300k", "-pix_fmt", "yuv420p",
                            "-c:a", "aac", "-movflags", "+faststart", tmp], check=True)
            os.replace(tmp, out)
        sys.stderr.write("fixture intro media ready\n")
    except Exception as e:
        sys.stderr.write(f"fixture intro media failed: {e}\n")


def fixture_json():
    out = []
    for sid, (title, year, srcs, good, pos) in SCEN.items():
        out.append({"id": sid, "kind": "resume", "title": title, "year": year, "resume_ms": pos,
                    "good_key": (ADDONS[good] + "|" + label(good, sid)) if good else None,
                    "expect": "good" if "good" in [m for m, _ in srcs.values()] else None,
                    "sources": {ADDONS[k]: m for k, (m, _) in srcs.items()}, "seek_keys": SEEK.get(sid, []), "bar_keys": BAR.get(sid, []), "intro_keys": INTRO.get(sid, []),
                    "wait": WAIT.get(sid, 55), "heard": HEARD.get(sid)})
    return out + LIVE_SCEN


def playlist():
    lines = ["#EXTM3U"]
    for i, (name, path) in enumerate(CHANNELS):
        lines.append(f'#EXTINF:-1 tvg-id="fixture{i}" group-title="Fixture",{name}')
        # Own address per channel (same media), so each is its own channel
        lines.append(f"{HOST}/live/{path}.ts?c={i}")
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "m3u":
        print(playlist(), end=""); sys.exit(0)
    if len(sys.argv) > 1 and sys.argv[1] == "json":
        print(json.dumps({"addons": [f"{HOST}/{k}/manifest.json" for k in ADDONS], "scenarios": fixture_json()}, indent=1))
        sys.exit(0)
    threading.Thread(target=make_intro_media, daemon=True).start()
    ThreadingHTTPServer(("0.0.0.0", 8002), H).serve_forever()
