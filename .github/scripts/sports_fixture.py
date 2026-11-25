"""Sports start fixture for the emulator (launch-test workflow, job sports-start).

Builds a playlist shaped like the maintainer's provider for tonight's live NBA game (from ESPN's public scoreboard):
  - "USA | NBA" event feed naming both teams that never sends data (hanging server)  -> must fail over
  - "USA | NBA" event feed naming both teams with a real TS stream                    -> expected picture
  - "USA NBA Teams" team channel of the home team (real stream)                       -> backup
  - an event feed naming two OTHER teams with a real stream                           -> must never play
  - 3,000 filler channels
Writes srv/test.m3u and srv/fixture.json (expected and forbidden titles). No real provider, no credentials.
"""
import json, random, sys, urllib.request

random.seed(3)
GOOD = "http://10.0.2.2:8000/live/good.ts"
HANG = "http://10.0.2.2:8001/live/hang.ts"

def scoreboard():
    url = "https://site.api.espn.com/apis/site/v2/sports/basketball/nba/scoreboard"
    try:
        with urllib.request.urlopen(url, timeout=15) as r:
            return json.load(r)
    except Exception as e:
        print("scoreboard unavailable:", e); return {"events": []}

events = scoreboard().get("events", [])
live = [e for e in events if e.get("status", {}).get("type", {}).get("state") == "in"]
teams_tonight = set()
for e in events:
    for c in e["competitions"][0]["competitors"]:
        teams_tonight.add(c["team"]["displayName"])

out = ['#EXTM3U']
fx = {"live": bool(live)}
if live:
    comp = live[0]["competitions"][0]["competitors"]
    home = next(c for c in comp if c["homeAway"] == "home")["team"]
    away = next(c for c in comp if c["homeAway"] == "away")["team"]
    match = f'{away["displayName"]} vs {home["displayName"]}'
    pool = ["Los Angeles Lakers", "Boston Celtics", "Miami Heat", "Denver Nuggets", "Phoenix Suns", "Chicago Bulls",
            "Golden State Warriors", "Milwaukee Bucks", "Dallas Mavericks", "Utah Jazz", "Portland Trail Blazers"]
    other = [t for t in pool if t not in teams_tonight] or ["Seattle SuperSonics", "Vancouver Grizzlies"]
    wrong = f"NBA 02: {other[0]} vs {other[1 % len(other)] if len(other) > 1 else 'Vancouver Grizzlies'}"
    chans = [
        ("USA | NBA", f"NBA 01: {match}", HANG),
        ("USA | NBA", f"NBA 03: {match}", GOOD),
        ("USA NBA Teams", f'NBA: {home["displayName"]}', GOOD + "?team=1"),
        ("USA | NBA", wrong, GOOD),
    ]
    fx.update(game=match, expected=[c[1] for c in chans[1:3]], forbidden=[wrong, chans[0][1]])
    for i, (grp, name, url) in enumerate(chans):
        out.append(f'#EXTINF:-1 tvg-id="fx{i}" group-title="{grp}",{name}')
        out.append(url)
for i in range(3000):
    out.append(f'#EXTINF:-1 tvg-id="ch{i}" group-title="{random.choice(["USA | Entertainment", "UK | Sports", "News"])}",US: Channel {i}')
    out.append(f"http://10.0.2.2:8000/live/x{i}.ts")
open("srv/test.m3u", "w").write("\n".join(out) + "\n")
json.dump(fx, open("srv/fixture.json", "w"), indent=1)
print(json.dumps(fx, indent=1))
