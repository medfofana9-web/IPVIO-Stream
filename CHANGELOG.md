# Changelog

## Fof TV 1.3.40 (November 2026)
**Sports**
- Games ESPN has no live data for (smaller cups and leagues) no longer stay "Upcoming" after kickoff: they show "Started", and drop off three hours later
- Opening Sports no longer shows "No games found today" while leagues are still loading (an old empty cache counted as an answer)
- League answers are collected and the page is updated at most every 400 ms instead of after every single league (dozens of rebuilds in the first seconds)

## Fof TV 1.3.39 (November 2026)
- Player: "Other streams" button on live channels lists every channel found for the game being watched (channels that worked before are marked); also works for a channel opened directly, by finding the live game it shows

## Fof TV 1.3.38 (November 2026)
**Speed**
- The compile profile now covers the app's own code (the rule had a syntax error, so only libraries were listed)
- Updates install through an installer session that hands the profile to the system (base.dm): the new version is compiled during the install instead of running uncompiled until the device compiles it while idle. Falls back to the installer screen if the session fails

## Fof TV 1.3.37 (November 2026)
- Menus opened with a long press (Multiview tiles, profiles) no longer close the moment the OK button is released; only a new press picks an option

## Fof TV 1.3.36 (November 2026)
**Sports**
- Soccer and NBA lead the sports page: their games of the coming week, live first
- Soccer covers 35 competitions: the big leagues, cups, women's leagues, Champions/Europa/Conference League, Libertadores, Concacaf, Nations League and international friendlies

**Search**
- Fixed a crash while typing (duplicate list keys from repeated catalog or guide entries); sports search now uses one request per league and keeps its result 3 minutes

## Fof TV 1.3.35 (November 2026)
**24/7 shows**
- New "24/7" button on a show's page: the show plays without end, in order (from the episode last watched) or shuffled; each episode gets a fresh addon link, an episode that will not play is skipped, at the end it starts over
- Next key on the remote skips to the next episode of the channel

## Fof TV 1.3.34 (November 2026)
**Profiles**
- Manage Profiles > a profile > Change avatar: a gallery of 22 modern illustrated avatars, among them Black men and Black women in several looks; the choice is kept per profile and cleared when the profile is deleted

## Fof TV 1.3.33 (November 2026)
**Search**
- One search finds everything: movies and shows (Cinemeta), titles of a person (TMDB, e.g. an actor's name), live and upcoming games, teams and leagues (ESPN, next three days), live channels and shows on TV now or later today
- Live games play at once on the best matching channel; other games, teams and leagues open their page in the sports area
- Search is shown in the menu right below Home (one-time menu update); filters: All, Movies, Shows, Live TV, Sports

## Fof TV 1.3.15 – 1.3.20 (November 2026)
**Catalog**
- One catalog for Home, Movies, Series, the big picture and the top buttons: Cinemeta (popular now, new this year, genres, top rated), kept on disk for instant rows (`Catalog.kt`)
- A catalog title opens as the provider's copy when the playlist has it (English, exact year and 1080p preferred), otherwise with the Stremio addon streams
- Stremio-only series load their seasons and episodes from Cinemeta; picking an episode opens its streams
- Provider category rows removed from Home, Movies and Series

**Playback**
- One-click play: the title page asks the addons ahead; Play starts the best stream (1080p direct link first). Can be turned off on the settings website
- Title logos on the big picture and the title page

**Sports**
- One press plays the best channel; no picture within 9 s or a failed stream moves to the next channel of the game; channels that worked are marked Verified and tried first (`SportSession.kt`)
- Up/Down in the player switches between live games; score bug and live ticker over the picture
- Close-game / overtime popup: OK switches to that game
- Multiview (2 x 2) from the sports Live tab; My Teams row with countdown and auto-tune for every game of your teams
- Team colors on game cards; spoiler-free mode hides scores everywhere

**Look and speed**
- R8 optimizes the libraries by default (`FOF_R8=0` turns it off); startup profile (`baseline-prof.txt`, profileinstaller)
- Rows glide once per key press; catalog posters and pictures need no TMDB lookup per card
- Default design: Fof Premium (red on true black) with Inter; Fof Coral stays as a preset

**Settings website**
- `http://<TV>:<port>/s` (QR via the phone icon in the menu): home rows, one-click play, big picture speed, Stremio addons. The server starts with the app

## Unreleased
**Playlists (T-009)**
- Xtream: any number of backup server addresses per login (same username and password). If the main server stops answering, the app switches to the next one by itself, for the playlist, TV guide, catch-up and streams
- Each server shows whether it is reachable or since when it is not; the playlist row in Settings > Playlists lists servers that are down
- Backup servers are included in the backup export and import

## 1.3
[PENDING: Änderung eintragen]

## 1.2
**Removed**
- Optional backup to Google Drive (sign-in with a code). Planned again for 1.4 as LiveSync. The old code is in tag V1.1

[PENDING: Änderung eintragen]

## 1.1
**Updates and your data**
- In-app updates from GitHub Releases: the app checks once a day and shows "Update available" in the bell; one click downloads and installs
- Release signing via `keystore.properties` (see docs/RELEASE.md) so updates install over the old version and keep history, favorites and settings

**Playlists**
- Xtream: if `get.php` fails or returns no playlist (e.g. custom error codes like 884), the app loads the account through `player_api.php` automatically
- Several playlists at the same time: "Also show" in Settings > Playlists; movies and series merged (duplicate movies once, same series as one series with several sources), "Source" filter in Movies/Series, source switch in Live TV
- Stalker/Ministra portals (portal address + MAC): live channels and movies
- M3U file from the device (file picker) or uploaded from the phone page

**Live TV**
- Rewind live TV (optional on-device buffer, 256 MB – 2 GB), pause without missing anything, "Back to live"
- Record the current show (from the buffer, no second connection), storage internal or USB, recordings list in Live TV
- Endless reconnect for live streams, automatic reconnect when the server ends a stream
- New "Sports" section: sports live now and coming up (from the TV guide), all sports channels

**Player**
- MPEG-DASH (.mpd) streams; unsupported formats show a message instead of crashing

**Interface**
- View options moved from the header to Settings > View, with a live preview of card size and artwork
- Sidebar on the left, right or top; icons only or always with text
- Phones: landscape by default, tap for controls, double-tap left/right to skip

**Sports, playlists, second stream**
- Sports hub with filters: sport (football, basketball, tennis, F1, MMA, ...) and time (now, today, tomorrow, next 48 h)
- In the Live TV list you can switch the playlist (or "All playlists") without going into the settings
- Second stream in the live player: picture-in-picture or split screen, sound from either stream, swap streams. Arrangement, window size (4 sizes) and corner are saved (Settings > Player > Second stream)

**Phone search**
- New button at the bottom of the sidebar: shows a QR code. Scan it and search movies, series and live channels on your phone (a web page served by the TV, same Wi-Fi, nothing to install). Tap a result to play it on the TV; pause, skip, stop and volume buttons included

**Languages**
- Dutch (Nederlands) is the 10th language

**Metadata**
- Own API keys for TMDB and OMDb in Settings > API keys
- Without keys: description, cover, rating, genres, running time and trailer straight from the Xtream provider
- OMDb ratings also without TMDB (lookup by title and year)
- Rating order for sorting and "Top rated": IMDb, Rotten Tomatoes, TMDB, provider

**Interface (new)**
- Settings > View rebuilt: 5 tabs (cards, sidebar, grid, home, categories) with a live preview of every option
- New "Donate" card in the settings (PayPal link and QR code)

**Transfer**
- Transfer history, favorites and settings to another device over Wi-Fi (optionally with playlists)
- QR codes: scan instead of typing the address (add playlist from phone, Wi-Fi transfer, Google sign-in)
- Settings > About: optional donation link (PayPal) with QR code
- Much faster loading of large Xtream accounts: series episodes are fetched when a series is opened instead of 50,000 requests while loading
- Optional backup to Google Drive (sign-in with a code, see docs/GOOGLE_DRIVE.md)
- Read the last crash report on the phone (no clipboard needed)

## 1.0 - first open-source release
- Android TV / Google TV / Fire TV IPTV player for Xtream Codes and M3U playlists
- Home, Movies, Series, Live TV with TV guide and catch-up
- Cinema and grid browse views, filters, search with typo tolerance
- Player with audio/subtitle selection, FFmpeg audio decoder, skip intro/credits, source fallback
- Profiles, parental controls, history, my list, favourites, backup and restore
- 9 languages, 12 colour themes
- Licensed under GPL-3.0
