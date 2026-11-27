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
}
SEEK = {"tt9000006": [22, 22, 22]}
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
        size = os.path.getsize(MOVIE)
        if kind == "hang":
            self.send_response(200); self.send_header("Content-Type", "video/mp4"); self.send_header("Content-Length", str(size)); self.end_headers()
            time.sleep(600); return
        rng = self.headers.get("Range")
        start, end = 0, size - 1
        if kind == "good" and rng:
            mm = re.match(r"bytes=(\d+)-(\d*)", rng)
            if mm:
                start = int(mm.group(1)); end = int(mm.group(2)) if mm.group(2) else size - 1
                if start >= size:
                    self.send_response(416); self.send_header("Content-Range", f"bytes */{size}"); self.send_header("Content-Length", "0"); self.end_headers(); return
                end = min(end, size - 1)
            self.send_response(206); self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        else:
            self.send_response(200)
        if kind == "good":
            self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Type", "video/mp4"); self.send_header("Content-Length", str(end - start + 1)); self.end_headers()
        # norange: about 400 KB/s, so resuming has to read for a while before the picture (like a server that cannot seek)
        rate = 400 * 1024 if kind == "norange" else 0
        try:
            with open(MOVIE, "rb") as f:
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


def fixture_json():
    out = []
    for sid, (title, year, srcs, good, pos) in SCEN.items():
        out.append({"id": sid, "kind": "resume", "title": title, "year": year, "resume_ms": pos,
                    "good_key": (ADDONS[good] + "|" + label(good, sid)) if good else None,
                    "expect": "good" if "good" in [m for m, _ in srcs.values()] else None,
                    "sources": {ADDONS[k]: m for k, (m, _) in srcs.items()}, "seek_keys": SEEK.get(sid, [])})
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
    ThreadingHTTPServer(("0.0.0.0", 8002), H).serve_forever()
