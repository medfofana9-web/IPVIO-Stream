# Changelog

## Fof TV 1.3.144 (November 2026)

- TryBox-style menu: a floating glass pill at the top center (text tabs, active tab in a dark capsule, phone search at the end) with "FOF TV" in red on the left. New default for profiles that never picked a menu position; left/right stay available in Settings.
- Spotlight cards: "We think you'll love this" (from the taste setup) and "Because you watched …" show wide scene cards with the title logo and Play / More info.
- Title page: takes on the artwork's color, new "Reviews" row (TMDB user reviews; OK opens the whole review), and "You Might Also Like" adds TMDB recommendations to the library matches.

## Fof TV 1.3.127 (November 2026)

- TryBox-style look: red is the new default accent ("Fof Red" replaces the white "Fof Premium" preset in the same slot, so saved choices keep their place); the brand mark and the old violet highlights are red.
- "Who's watching?": one black-and-white cinematic still of a popular title (its logo bottom left) instead of the poster wall, square avatar tiles with a white frame on focus, outlined "Manage Profiles" button with a pencil.
- Home hero is a rounded card inset under the header with the artwork's color glowing behind it; buttons read "Play" / "More Info".
- Today's Top 10 shows large outlined rank numbers with each cover tucked against its number.

## Fof TV 1.3.126 (November 2026)

- Settings hub has fewer tiles: "View" now lives inside "Design and color" (below the design options), and "Menu" lives inside "Customize home" (below the home rows). Nothing was removed; the reset button also shows on Customize home. Returning from the live View preview opens Design and color.

## Fof TV 1.3.125 (November 2026)

- Settings hub cleaned up: tiles are now calm dark cards with only the icon in color (no more rainbow gradients), the focused tile is clearly marked (white outline, lifts and grows), the arrows on every tile are gone, and descriptions can use two lines instead of being cut off. Tiles are grouped under section labels (Playback, Appearance, Content, People & safety, System, More); About and Donate sit together at the end. Page numbers and `SettingsNav` stay the same.

## Fof TV 1.3.124 (November 2026)

- Addon streams that need extra request headers (Stremio `proxyHeaders`, e.g. Referer/Origin – common with PenguPlay) are no longer thrown away: the player now sends those headers for that link (playlist, segments and keys). Before, such titles showed "none playable here". New file `StreamHeaders.kt`; any other stream (IPTV, plain links) is opened without them.

## Fof TV 1.3.123 (November 2026)

- Sports feeds are scored on this TV: how often a channel starts, how fast, how often it freezes and its picture size (4K, FHD, HD). For every game the best-playing feed goes first, among feeds that match the game equally surely (a channel that clearly shows the game always beats a sharper guess).
- Automatic failover during a game: a feed frozen for 8 seconds, or one that keeps stuttering, is swapped for the next-best feed of the same game ("Feed froze – switched to …"). Feeds from every playlist count, so a second IPTV subscription acts as a backup.
- Account website (web/index.html, published on GitHub Pages from the gh-pages branch) and the fof_clear_pin server function for removing a profile PIN.

## Fof TV 1.3.122 (November 2026)

- Smoother on the TV (measured on an onn 4K Plus: the graphics chip was the bottleneck): the big pictures behind Home, the top of Home and title pages are now painted once into a cached layer with their gradients, instead of four full-screen fills on every frame.
- Every title brings its own mood: a soft glow in the artwork's main color behind the top of Home and the title page.
- Faster play: when the title page is already looking up sources, Play joins that lookup instead of asking every addon again.
- Ambient screen: after 4 minutes without the remote (not while playing), large artwork slowly fades with the title and the clock. Any key returns to where you were.
- Sports game cards carry both teams' colors (away left, home right).

## Fof TV 1.3.121 (November 2026)

- Navigation follows the focus faster (stiffer spring: settles in about a quarter second, no overshoot).
- The big picture and info at the top of Home change only once the focus rests (450 ms), so no full-screen picture fades while moving through rows.
- Frame measurement on the TV itself: per screen, share of slow frames and which phase (input, animation, layout, draw, GPU) cost the time; a short summary goes to the account's problem list every few minutes and when the app is left. No titles or names are recorded.

## Fof TV 1.3.120 (November 2026)

- Smoother remote navigation: screens follow the focus with a spring instead of a 300 ms tween that restarted from standstill on every press (the uneven, jumpy movement on quick or held presses). Applies to Home, Movies, Shows, Search, details and menus.
- Home rows that load later (taste rows, Hidden gems, curated rows) keep their place while loading, so the screen no longer shifts when one appears.
- Title page: going back up to the top is one smooth move instead of four quick snaps.

## Fof TV 1.3.119 (November 2026)

- Forgot password on the TV (email → 6-digit code → new password) is built but switched off until the account service has its own email sender; see docs/RELEASE-READINESS.md.

## Fof TV 1.3.117 (November 2026)

- Player episode list: every episode shows its still, "E4 · Title", runtime, time left or Watched, two lines of synopsis, a progress bar, and Playing / Up next. Season chips at the top jump to a season.
- One shared episode store (EpisodeInfo): the player shows exactly the titles, stills and synopses of the title page (TMDB, mapped to the app's numbering; catalog episodes as fallback). Progress in both comes from the same per-profile store that syncs with the account.
- Episode list rows have fixed heights and stable keys, and stills are decoded small, so moving down the list does not re-layout or load full-size pictures.

## Fof TV 1.3.115 (November 2026)

- IPTV movies and shows: a provider title without a year is only matched when no other film of the same name is in the list (a plain "Dune" next to "Dune (1984)" is not offered for the 2021 film).
- docs/RELEASE-READINESS.md: verification results, backup and recovery, content-rights notes.

## Fof TV 1.3.114 (November 2026)

- Search: typing a genre ("comedy", "sci fi", "horor", "docs") adds rows of popular movies and shows in that genre.
- Home: "Hidden gems" (well rated titles beyond the most famous ones) and "Your teams today" (your teams' games today that are not live: upcoming, then final).
- Sports in the player: Show/Hide score button for the score box over a game (remembered).

## Fof TV 1.3.112 (November 2026)

- Sync: a seek is saved and sent to the account within a few seconds; the audio and subtitle language chosen per title now follows the profile to every TV.
- Hold OK (or Menu) on a title: new "Play" for catalog movies, "View sources" (opens the source list, at the saved episode and position when there is one) and "Show less <genre>".
- "Show less <genre>" keeps that genre out of this profile's recommendation rows.

## Fof TV 1.3.111 (November 2026)

- "Who's watching?" is cleaner: only the account's profiles, no account email, no "Owner" label, no greeting; Manage Profiles is a quiet action below the profiles.
- Profiles saved on a TV before it signed in (such as an old "Main profile") no longer show on the picker. In Manage Profiles you choose for each one: add it to your account as a new profile, or remove it from this TV. Nothing is merged on its own.
- Focus starts on the last used profile, or the first one shown.
- Kids profile setting now follows the profile to every TV.

## Fof TV 1.3.109 (November 2026)

- Continue Watching sync: progress goes to the account right away on pause, leaving the player, next episode and source change (plus every ~10 s while playing).
- Two TVs saving the same profile at once no longer overwrite each other: a save only lands over the version the TV last saw, otherwise both are merged (newest progress per title wins).

## Fof TV 1.3.107 (November 2026)

- New welcome screen on a TV that is not signed in: Sign in, Create account, or use the TV without an account (asked once).
- Sign-in screen: QR code and short code for the phone with live progress ("Waiting for your phone", "Phone connected", "Signing in"), a 10-minute expiry and "Get a new code"; email and password on the TV as the alternative, with clear errors (offline, wrong password, confirm email).
- "Who's watching?": Sign out moved into Manage profiles; the main screen only picks a profile.
- New profile: numbered steps, and a "Who is this profile for?" step (Everyone or Kids; Kids hides adult content). Cancel while naming removes the half-made profile. Adding a profile after the master PIN now runs the full setup.

## Fof TV 1.3.105 (November 2026)

- Search: small typos still find titles (1 wrong letter from 5 letters, 2 from 9).
- Home: new rows "Critically acclaimed", "Binge-worthy series", "Short movies for tonight" and "Rediscover a classic".
- Surprise me (profile menu): pick movie or show, genre, length and new or seen before; one pick with the reason, then Play, Details or Another.
- Sports: hold OK on a game for Watch live, Choose stream, Watch in multiview (this game plus other live games) and Add or remove either team from My Teams.
- Watch Together: text chat in the party panel; messages show over the picture for everyone.

## Fof TV 1.3.102 (November 2026)
**Watch Together**
- In the player: Watch together → Start a watch party gives a 6-digit code. On another TV (any Fof TV account):
  profile circle → Join a watch party → enter the code. The title opens there from that TV's own sources and follows
  the host: play, pause and seek (re-synced when more than 2.5 s apart); live channels and games share the channel
  and stay at the live edge
- Reactions (👍 😂 😮 🔥 ❤️ 👏) show over the picture for a few seconds; the chip shows the code and how many are watching
- Leave as a guest without stopping anyone; the host's "End the party" ends it for all. Only members can see a party
  (server rules); nothing is streamed through the party
- Quick menu: "Not interested" leaves a title out of this profile's recommendations

## Fof TV 1.3.101 (November 2026)
**Taste discovery is part of creating a profile**
- Also: genres rated Love / Like / Not interested; an "Actors and creators" step (TMDB popular people, search for
  anyone); more viewing questions (dark or light, serialized or standalone, preferred language). "Your taste" edits the
  saved answers instead of starting over; "Reset taste" starts blank. Home adds "Because you love <person>" rows
- Profile menu: Switch profile, Manage profiles, Profile settings, Account & devices, Sign out
- Add profile: name and picture → PIN (or none) → movies → shows → genres → how you like to watch → sports and teams
  → review → "Create my Fof TV". Every step can be skipped; the profile starts as a blank slate (nothing from another
  profile or the owner)
- Movies and shows: a balanced start of 48 titles (every genre, four decades, popular, top rated and new), search for
  any title, and "Show more". OK on a poster: Love it → Like it → Not for me → clear. Every third "Love it" adds more of
  the genres rated well plus one genre not touched yet, so the choice never narrows to one kind
- How you like to watch: movies or shows, mood, pace, real or fantasy, documentaries, reality TV, subtitles,
  episode length
- Save & exit keeps the answers as a draft of this profile only; opening the profile again continues where it stopped
- The new profile's Home: "Picked for you", "Movies you might love", "Shows matching your taste" and "Discover
  something new", from real catalog titles of the genres its ratings point to (titles rated "Not for me", already liked
  or watched are left out). A profile that skipped gets the normal varied rows

## Fof TV 1.3.100 (November 2026)
- Coming back to the app no longer throws away the screen you were on: it returns to the home screen only after more
  than 30 minutes away. Android also reports "left by the user" when the app opens another screen on top (voice
  search, installer, system settings), and every return from those reset the app to Home
- Top picture: a title you started shows "Resume · 23 min left" as its main button, and it resumes at the saved spot
- Back from a game: focus returns to that game on the sports list, Home's live games and the hub's match cards too
  (before only on the game tiles)
- New profile PINs are 4 digits and go in by themselves after the 4th digit (existing longer PINs still work)

## Fof TV 1.3.99 (November 2026)
- The update prompt never appears over playback any more (it waited only for sports before); it shows after the
  player closes, and "Later" still hides that version
- Profiles that come from the account (set up on another TV) no longer get the taste questions again on a new TV
- Taste setup posters: 7 even columns with titles under them; the focused poster lifts with a light rim, picked ones
  get a lavender frame, a tint and a check (before, focus and picked looked the same); Next shows how many are picked,
  or "Skip" when nothing is

## Fof TV 1.3.98 (November 2026)
**Profiles never mix any more**
- Cause: signing in renamed the profile in use to the account owner's name, and profiles were then linked to account
  profiles by name (or the TV's untouched first profile to the account's first profile), so a new profile could take
  over the owner's history. Now nothing is renamed on sign-in and nothing is merged by name or position
- A TV that signs in to an account that already has profiles keeps its earlier profiles apart under "On this TV only
  (not in your account)"; Manage Profiles can add one to the account as its own profile
- Who's watching: only the account's profiles, the owner's marked "Owner", Sign out next to Manage Profiles
- New profile: name, then picture, then it opens (create a PIN or skip it, then the optional taste questions)
- Profile circle at the top: "Watching as …" with Switch profile, Manage profiles and Account & devices

## Fof TV 1.3.96 (November 2026)
- Opening a movie or episode shows its sources: first your addon sources, then a "Your IPTV" section (checking,
  the provider's copy, or "not on your IPTV"). It no longer starts the first addon stream by itself before the IPTV
  check finishes; one-click play can be turned back on in Settings
- The IPTV lookup no longer needs an IMDb match, and its quality no longer mis-sorts the list

## Fof TV 1.3.95 (November 2026)
- Your IPTV as a source for addon movies and shows: the provider's movie and series names are kept as a small index
  (built once, refreshed daily; Xtream login). The source list of an addon title now also offers "Your IPTV" when the
  provider has the same title (cleaned name, year within one, English copy first) – the movie, or exactly that
  episode. The player can switch to it when an addon stream fails
- DragonStreams: when a title has only MP4 links (no MKV or CDN), they are no longer hidden completely: they appear at
  the end of the list marked "MP4 · may be wrong content" and are never started automatically

## Fof TV 1.3.94 (November 2026)
- Phone sign-in sends email and password in the request body instead of the web address (addresses end up in logs
  and browser history); the old address form is refused
- Crash and freeze reports go to the account (table fof_reports), so they arrive without a family household; the
  owner sees them on the phone page as before
- Settings > Account & devices > Security: Change password; Delete account (password asked again; removes every
  profile, PIN, addon list, device and saved progress)
- Releases keep the R8 map (90 days), so crash reports of a build can be read
- docs/INSTALL.md: one-page install guide for Fire TV and Google TV

## Fof TV 1.3.93 (November 2026)
**Playback that recovers by itself**
- Lost internet is no longer treated as a broken source: the player waits for the connection (up to 5 minutes),
  then resumes the same stream at the same spot, with growing pauses (1, 2, 4, 8, then 15 s; 12 tries). Before, a
  movie gave up after about 9 seconds, switched source or showed a dead end, and counted the source as broken
- When playback really cannot be restored, the message has Retry (same title, same spot) and Change source
  (other sources of exactly this movie or episode) instead of "press Back"
- Recovery stops when the player is closed

**Quick actions (hold OK)**
- Hold OK on a movie or show poster, or on a Continue watching card: a glass menu beside the content with
  Resume / Play next episode, Start over, View details / Seasons & episodes, My List, favorites, Mark as watched /
  unwatched (movies), Remove from Continue watching. Back closes it and focus returns to the card. One menu for the
  whole app; every action is saved in the profile and syncs to your other TVs

## Fof TV 1.3.92 (November 2026)
- Instant Continue watching between TVs: each TV listens to the account's realtime feed for the profile in use and
  syncs the moment another TV saves progress (applied 15 Nov 2026: fof_profile_data in supabase_realtime). The
  check every 20 s stays as a fallback (once a minute while the feed is up)

## Fof TV 1.3.91 (November 2026)
- Continue watching keeps titles watched on another TV or from another source: when this TV's playlist has the title
  under another id (or not yet), it is found by its exact name and kind (only one match, so similar names never mix);
  otherwise the saved entry is shown and resumes at its saved position

## Fof TV 1.3.90 (November 2026)
- Continue Watching reaches the account while you watch: progress is saved every 5 s, and each save used to restart
  the 8 s sync timer, so it only went up every 2 minutes or after stopping. Now the first change starts the timer and
  later ones join it (at most 8 s)
- Other TVs check the profile in use every 20 s (one small request when nothing changed), so progress from another
  TV shows within seconds; account checks (profiles, PINs, addons) stay every 2 minutes
- A TV that is not signed in now says so on Who's watching, with what signing in brings
- New official Fof TV logo: play ribbon on a deep indigo glass tile, "TV" in lavender. App icon, TV launcher banner,
  in-app mark and the phone pages' icon are drawn from vector sources in docs/brand/

## Fof TV 1.3.87 (November 2026)
**FOF Glass, Fof TV's own surface design**
- One shared glass style (Glass.kt) on buttons, pills, settings rows and cards, menus, the sidebar, the header, the
  player's bars and menus, the TV guide panels, Who's watching and the PIN screen: a translucent tinted pane, light
  from above, and an edge that catches light at the top
- Focused posters, game cards and tiles: no colored frame any more; the card lifts with a soft shadow and its rim
  catches light. Buttons stay white when focused (best to read from the couch)
- No live blur: three plain draw layers per surface, cached, so Fire TV stays as fast as before

## Fof TV 1.3.86 (November 2026)
- Fixed a frozen screen ("Freeze · GameDay < MainActivity"): Game Day (reminders, auto-tune for your teams, close-game
  alerts) ran on the main thread, including matching each game against every live channel. It now runs in the
  background; only the switch to the game and the alert use the main thread, and each game is matched once
- After a crash or freeze the TV shows one calm line; the details stay in Settings > Diagnostics and on the phone page
- Profile PIN is optional: "No PIN for this profile" on the create-PIN screen (a PIN can be set later in Manage profiles)

## Fof TV 1.3.84 (November 2026)
**One account for the household, like Netflix**
- Sign in once per TV (email and password on the phone); it stays signed in through restarts. Every profile of the
  account appears on every TV signed in to it
- Each profile has its own PIN (4 to 6 digits), checked by the account service with a lockout after wrong tries;
  existing profiles set theirs the first time they are opened. "Forgot PIN" asks for the account password
- The TV remembers the profile last opened (no PIN to come back to it), unless "Ask for the PIN every time" is on
- Stremio addons, the Real-Debrid token and the provider playlist belong to the account (encrypted on the server):
  added once, they are on every TV
- Continue Watching, watched episodes, My List, favorites, sports teams and taste are saved per profile in the account;
  newer progress wins, a TV that was offline cannot overwrite it
- Settings > Account & devices: signed-in TVs, rename, sign out one device, sign out all others, sign out this TV
- Earlier personal sign-ins (one per profile) are taken over: their saved data goes into the matching profile

## Fof TV 1.3.83 (November 2026)
- "Sign in with your phone" opens a short page with only email, password and Sign in; everything else syncs by
  itself (addons, lists, Continue Watching, taste). The full settings page stays on the normal phone code

## Fof TV 1.3.82 (November 2026)
- Signing in to your account on another TV now brings your Stremio addons (and your playlist, when that TV has none)
  automatically, also for the family owner. Nothing already on that TV is removed or replaced
- The owner's TVs keep the shared family setup up to date on their own: addons added on any of them are shared
  without pressing Share

## Fof TV 1.3.80 (November 2026)
- Live games also come from your Stremio addons (Pengu and other live/sports addons): their live channels and events
  are searched for the game, and matching streams are offered right after channels named after the game. Watch Live
  asks the addons first (up to 4 seconds)
- Live TV guide restyled like TiviMate: video preview top left with the show info beside it, flat slate rows with thin
  lines, channel logos without boxes, the chosen group as a light bar, light focus with dark text, thin time line

## Fof TV 1.3.79 (November 2026)
**Live games: right channel, fresh scores**
- European national teams (Spain, England, France …) are on Fubo and ViX in the US, not FOX: the app now looks for
  those channels ("fuboTV", "Fubo Sports", "ViX", "ViX+" …)
- Channels whose name lists the game ("Fubo 03: Spain vs England") are found in the whole channel list, not only in
  the sports groups. Order: your own pick → channel named after the game → TV guide → network → usual networks
- Scores refresh every 8 seconds (Sports page, the score in the corner) and every 10 seconds on Home

**Every profile is its own account**
- A new profile first asks "Do you already use Fof TV?". Yes: scan the code, sign in on your phone, and that person's
  Continue Watching, avatar, name, teams, taste and favorites come right over – no questions. No: the quick taste setup,
  with "Keep it on every TV" at the end
- Sign-in on the phone is one form: email and password (the same form creates a new account), name, and an optional
  family invite code. No avatar or mode to pick first
- Each profile on a TV signs in on its own, so everyone in the house has their own Continue Watching on every TV

## Fof TV 1.3.78 (November 2026)
- Live games: national-team soccer (Spain, France, England … in the Nations League, qualifiers and friendlies) finds
  its channels: FOX, FS1, FS2, FOX Deportes. When ESPN names no channel and nothing else matches, the channels that
  usually carry that sport are offered (tried one after another), so "no matching channel" no longer appears for a game on TV

## Fof TV 1.3.77 (November 2026)
- Live games: the score in the corner kept the score from when you started watching (it was only refreshed on the
  Sports page). It now refreshes itself every 12 seconds while you watch, so it matches the broadcast
- Your teams: no more "Points!" alert for every score. You get one alert when the game starts, and the close-game
  alerts at the end

## Fof TV 1.3.76 (November 2026)
**Make Fof TV yours (every profile)**
- The first time a profile is opened, a few quick questions: which sports you follow (NFL, NBA, WNBA, college,
  soccer), your teams, shows and movies you love, favorite genres. Everything can be skipped
- The answers shape that profile: Sports and the Home live row show only your sports, your teams' games come first,
  "Because you like …" rows from your favorites, your genres first. Redo it any time: Manage Profiles → profile → Your taste
- Travels with your account, like your teams and leagues

**Avatars like Netflix**
- Pick a scene from a show or movie (the same artwork the app shows on Home), a player from the teams in Sports (ESPN
  photos), or upload your own photo on the phone page ("Your avatar"). Search by name, preview, confirm
- The picture follows you to every TV you sign in on, your own photo included
- Profiles without a picture show their first letter on a color tile; the built-in 3D avatars are gone

## Fof TV 1.3.75 (November 2026)
- New Fof TV logo: a white F whose middle bar is a play button, on a graphite tile. App icon, Fire TV banner, menu,
  welcome screen and start screen
- Live games: when a channel does not start, the next one is tried after 6 seconds (was 9)
- Movies and episodes from the addons: a source that shows nothing for 12 seconds is replaced by the next one (was 20)
- Live TV: the Rewind button jumps back to the previous channel; Sports sits right under Favorites

## Fof TV 1.3.74 (November 2026)
**Avatars, redone**
- 73 avatars in six collections: TV Shows, Movies, NBA, Soccer, NFL and More Sports: polished 3D characters and
  athletes on cinematic color tiles (original art, no real people or studio characters: those need licenses)
- New selection screen: "Choose Your Avatar" with category tabs, search, the name of the focused avatar, a large preview
  with "Use this avatar", then the profile card (avatar, profile name, "as The Detective", change)
- The avatar shows everywhere: Who's watching, the profile button at the top of Home, Settings → Profiles, and the
  phone sign-up page; it travels with your account to other TVs
- Pictures ship inside the app (instant, also offline). Catalog: assets/avatars/catalog.json; new avatars or collections
  are a picture and a line there

## Fof TV 1.3.72 (November 2026)
**Your watching follows you**
- Signed in with a Fof account, your Continue watching (to the second), watched episodes and movies, Recently watched,
  My List, favorites and channel lists are saved to your account and come back on every TV you sign in on
- It syncs a few seconds after you stop watching or change a list, when you leave the app, when you come back, and
  every 2 minutes. If two TVs changed something at the same time, both are kept (per title the newer state wins)
- The account is linked to the profile that was active when you signed in on that TV; the phone page shows which one
  and when it last synced (Family account → Your watching)
- Only you can read your saved data (checked on the account service)

**Welcome screen and avatars**
- New avatars: bold Netflix-style color tiles with friendly faces (24 to choose from), drawn by the app, so they show
  instantly and also work offline. The phone sign-up page shows the same tiles
- "Who's watching?" redone: slowly drifting poster wall, Fof TV mark with greeting and time at the top, larger tiles and
  names, no hint lines, and a "+" tile to add a profile (asks for the master PIN when one is set)

**Playback**
- DragonStreams: MP4 links are never used any more (they often played the wrong title); only MKV and CDN links
- "Skip intro" is always in English (it showed in German until the controls opened)
- After "Skip intro" the episode keeps playing instead of stopping

**Fixed**
- Opening the first video after starting the app could freeze the screen for several seconds and Android closed the app
  ("not responding"): the check for the extra audio decoder (AC3/DTS) now runs in the background at start

## Fof TV 1.3.70 (November 2026)
**Look**
- Premium neutral look: the red accent is gone. Buttons, focus, the player's seek bar and progress bars are platinum white; intro and credits marks on the seek bar are soft amber; no glow under the played part
- Welcome screen: white logo, soft neutral light instead of the red glow

**Family**
- First start on a family member's TV: "Join my family" right on the playlist screen; the screen closes by itself once the family playlist arrives
- The owner's TV gets its setup back from the account after a reinstall (when it has no playlist yet)
- Problem reports: a crash, frozen screen or forced close on a family TV is sent to the account once; the owner sees them on the phone page (Family account → Problems on family TVs)

**Updates**
- Update channels: every new version goes to the owner's TV first (early updates). After 24 hours without problems it becomes the version everyone else gets, and the install link serves it. Settings → App update → "Early updates" switches the channel
- Install link for new TVs (Downloader): is.gd/getfoftv (always the latest tested version)
- New private signing key with key rotation: Android 9 and newer move to the new key through a normal update (no reinstall); the old, published key can no longer sign updates for them

**Sports**
- Home's live games row shows NBA and soccer only

**Fixed**
- Three text files started with a hidden byte-order character (Lint errors); now written as an escape

## Fof TV 1.3.68 (November 2026)
**Shows from the addons (Netflix-style)**
- The next episode plays by itself when one ends (with the "Next episode" countdown); in the last 3 minutes its stream is already looked up and chosen, so it starts right away
- The next episode uses the same addon and quality that worked for this one (only a source whose file name confirms that very episode); if none is confirmed, a message says so instead of playing something else
- Finished episodes are marked as watched, and Continue watching moves on to the next episode (addon shows were not linked to their episode list before)
- "Skip intro" (and credits) also for addon shows when the intro database knows the episode (the show's TMDB number now comes from Cinemeta, no key needed)

## Fof TV 1.3.67 (November 2026)
**New**
- Family account: the owner's account holds the shared setup (provider playlist and Stremio addons). Family members sign up on the phone page (Who's watching → "Sign in or join your family") with email, password, their name, their avatar and the owner's invite code; their TV then loads the family playlist and addons by itself and keeps them current (every 3 hours). Each TV keeps its own pages and settings. Owner on the phone page: "Share" (this TV's setup to the family) and "New code" (invite for one person, 14 days)
- Who's watching: poster wall from popular titles behind the profiles, the family line under the title

**Changed**
- Close-game alerts only for NBA games

## Fof TV 1.3.66 (November 2026)
**Fixed**
- Continue watching on Home showed "Connection lost", then "Playback not possible" for movies and shows from the addons: it replayed the saved address, but addon and Real-Debrid links expire after a few hours. It now finds a fresh stream for that title and episode (the last one that worked first) and starts at the saved spot, like Continue watching on the other screens already did. Right after start it also waits a moment for the addons to load

## Fof TV 1.3.65 (November 2026)
**New**
- Shared provider login (e.g. 4 streams for the family): before a channel starts, the app asks the provider how many streams run on the login right now. When all are in use, nothing starts and a message says so, with "Try again" – instead of the provider cutting off someone else's stream or blocking the login. Switching channels on a TV that is already watching never counts twice; multi view tiles count one each. If the provider does not answer within 2.5 s, playback goes ahead

## Fof TV 1.3.64 (November 2026)
**Sports**
- The overview always opens at the top (Live now): rows arriving later no longer leave the page scrolled down; once you move into the list it stays where you are
- Live scores refresh every 12 s (was 30 s), games about to start or just started every 20 s, the match page every 15 s, the score box and Home's live row every 15–20 s; each answer is fresh (past ESPN's edge cache)

**Profiles**
- Who's watching: larger rounded portrait tiles; hold OK on a profile to choose its picture right there
- New portrait avatars (16, most of them Black men and women) next to the earlier flat set; profiles without a choice get a portrait instead of a random cartoon
- Test TV checks that every avatar picture loads

## Fof TV 1.3.63 (November 2026)
**Faster**
- Playback starts with less buffered first (about 1 s instead of 1.5–2.5 s) – channel switches and Play feel quicker; after a stall it still waits for a safe buffer
- A Real-Debrid link unlocked once is reused for 30 minutes (playing the same title again starts at once)

**Polish**
- Titles without artwork (or whose image fails) show their name on a colored card instead of an empty box; channels without a logo show their short name (TNT, TBS) in the guide
- A new search starts at the top of the results
- Dark startup screen with the Fof TV mark (no grey flash)
- Calmer loading placeholders
- Diagnostics fully in English

**Reliability**
- Test TV: retries the playlist if the emulator's network was not ready, checks that the list loaded, checks smooth scrolling on Home, and shows its screens and any untranslated text in the report

## Fof TV 1.3.62 (November 2026)
**Improved**
- Search: Back from the results goes to the keyboard (then to the menu), never straight away; common short forms find the full title (GOT, HOTD, LOTR, TWD, TLOU, B99, SVU, BCS …); a search without any hit is tried again shorter (last letter or last word dropped), so a small typo still finds the title
- Sports: the channel match waits until the leagues stop answering, so the page shows its games sooner instead of recomputing in a loop

## Fof TV 1.3.61 (November 2026)
**Faster**
- A list saved before Live-TV-only still held the provider's 290,000 movies and series and was read at every start; they are dropped once and the smaller list is saved

**Fixed**
- Multi view: a tile that cannot play moves on by itself to another channel showing the same game

**Reliability**
- Phone settings page: new "App health" section with version, what is loaded, the speed of this run and the last crash (no photos of the TV needed)
- Every build is tested on a test TV shaped like the real one before release: 23,773 channels named like the provider's ("US: TNT East"), 120,000 movie and episode lines, 2 GB of memory. The run fails, and nothing is released, unless: provider movies/series are skipped, the screen never freezes, the app stays open through search and Live TV, nothing crashes, Home shows posters within 15 s of a restart, the Live TV guide shows the categories, and search finds a person

## Fof TV 1.3.60 (November 2026)
**Changed**
- Search: smaller keyboard with the search text on top and suggestions or recent searches below it, so results get most of the screen. Results in sections: Top results (best matches), Movies, TV Shows, People, Live TV, games and teams, each only when it has hits and never the same title twice. All / Movies / TV Shows / Live TV filter above the results, switching keeps the search

## Fof TV 1.3.59 (November 2026)
**Fixed**
- Games still found no channel when the provider names its channels by coast ("US: TNT East", "TBS (West)"): East/West feeds now count as the network. The usual networks of a competition are always added to what ESPN lists

## Fof TV 1.3.58 (November 2026)
**Changed**
- Live TV opens like TiviMate: the last watched channel plays in the corner above the TV guide (categories on the left, channels with their program grid below). Back once shows the channel full screen, Back again returns to where you were

## Fof TV 1.3.57 (November 2026)
**Fixed**
- "No channel found" for games ESPN lists without TV networks (USA vs Haiti on TBS/truTV/Universo): the usual US networks of the competition are tried then (USMNT, Mexico, Premier League, Champions League, LaLiga, Bundesliga, Serie A, Ligue 1, Liga MX and more)

## Fof TV 1.3.57 (November 2026)
**Changed**
- Sports follows soccer (all top leagues and cups), basketball (NBA, WNBA, college) and football (NFL, college) only: about 40 leagues instead of 60, so it loads faster
- Clean overview: Live now, Starting soon (next 3 hours), My teams, then Soccer, Basketball and Football sections (today plus the coming week), and the channels at the end. Popular leagues, sport tiles and news moved off the overview
- Flat game tiles without color gradients (cleaner, cheaper to draw), one name line under each tile
- Sports channels: ESPN networks and NBA TV only (games still play on any matching channel)
- Four tabs: Overview, Live, Schedule, Leagues

## Fof TV 1.3.56 (November 2026)
**Faster**
- The provider is used for live TV only: its 125,000 movies and 166,000 series are no longer read at start or downloaded on refresh (movies and shows come from the addons). The app works with about 24,000 titles instead of 315,000: faster start, less memory, search ready much sooner. Turning "Provider movies/series" on in the phone settings loads them again

**Changed**
- Search in the Netflix style: one poster grid for movies and shows (best match first), "Top searches" when nothing is typed, recent searches only before typing, no filter chips or empty info box

## Fof TV 1.3.54 (November 2026)
**Fixed**
- Search froze and Android closed the app ("app not responding") on the first key press: the screen waited for a lock the background index build held for seconds on a big playlist. Reading no longer waits, and the index is built in the background 12 s after start
- Sports: the Live now row appeared above the screen when live games arrived after the cached rows; the page now jumps to it if you have not scrolled. Golf-style events are listed after team games

**Diagnostics**
- Settings → diagnostics → Speed: how long this run took to show Home, open Sports, show live games and get Search ready

## Fof TV 1.3.53 (November 2026)
**Fixed**
- Search: a short query on a big provider matched tens of thousands of titles and every step after it paid for all of them; hits are now capped at 400 per kind, the heavy dedupe runs in the background, and a source that fails (network, bad data, memory) shows no hits instead of closing the app
- Sports: leagues that can have a game on right now are asked first, so live games appear before the other 60 leagues answer

**Changed**
- Sports overview starts with Live now; the "Today's results" row (finished games) is gone

**Diagnostics**
- When Android closes the app (low memory, not responding, native crash) or the screen freezes for 6 s, the next start shows what happened, with the place it got stuck

## Fof TV 1.3.51 (November 2026)
**Fixed**
- Wrong movie or episode playing: addon streams are now checked against the IMDb id, season and episode and the file name. Another episode, season or title is rejected; unclear sources are marked "Unverified". A playlist title only borrows addon streams that are confirmed
- A resume position is only used for the title it was saved for (a stale position could carry over to another show)

**New**
- Play starts only a confirmed source. If none is confirmed, the source list opens so you can choose
- Settings → Stremio addons: "Auto-select best source" or "Ask me every time"
- Sources button in the player for addon movies and episodes: switch streams without leaving, the position is kept, and a stream that fails returns you to the one before
- "Wrong content" in the player: marks the stream, skips it from now on and tries the next one
- The source list fills in while addons answer, shows quality, HDR/Dolby Vision, audio and size when the addon reports them, and tags each row (Playing, Match, Unverified, Reported wrong)
- The last stream that worked for a title is preferred next time

**Improved**
- Search: an exact title comes first, then titles starting with your words; the same title and year from two catalogs is shown once

## Fof TV 1.3.50 (November 2026)
**Fixed**
- Search closed the app on big playlists: the "On TV" guide search normalized every show title of the whole guide (millions on 20,000+ channels) on each keystroke and kept running after the next keystroke. It now compares without copies, stops when a newer query starts and collects at most 200 hits. Same fix for "Search channels" in the player
- Movies/episodes that never start no longer spin forever: after 20 s without a picture (or two failed reconnects) the next source is tried (other quality, next addon stream, next 24/7 episode), then a clear message
- Sports pages keep trying to take the focus for 1.5 s (the first element can appear a moment later), then the Back button

**Improved**
- Game tiles: background and shade built once and drawn in one pass
- Title page: Trailer moved into More, so Play stays the clear first action
- Live TV: the highlighted channel scrolls its full name
- One section title size on Home, Sports and Search (no accent bar in Search)

**Validated (emulator, 24,000 channels with a 240,000-show guide)**
- Search "lakers" (guide hits) and "denzel" (catalog and person row): results shown, app keeps running
- Home → title → Back and Sports → game → Back return to the same item; cold start 1.5 s to first frame

## Fof TV 1.3.49 (November 2026)
**Fixed**
- Sports: a game on a streaming service (e.g. WSL on "Paramount+") was matched to a TV channel with the same base name ("US: Paramount Network"). "+" now stays part of the network name
- Sports page: rows and enlarged tiles no longer slide over the tab bar
- Title page: the episode list never spins forever; after 15 s the retry screen shows
- Player: focus falls back to the first button when the button a panel came from is not on the bar

**Improved**
- Game tiles: one clear state label (live with match clock, started, final, postponed/canceled); a missing score shows VS, never 0 – 0
- Live player bar: picture format, second picture, quality, score overlay and "open externally" moved to Settings; "Back to live" only shows when behind live; "Other streams" only while games are live
- Posters: flat surface under the image in the clean look (no gradient painted under every poster)
- Startup: sports stores are read in the background; the sports warm-up starts after 6 s instead of 2.5 s

**Measured (emulator, Android TV API 30, CPU rendering, 24,000 channels + 4,000 movies)**
- Cold start to first frame 1.41 s; profile picked to first poster 0.75 s
- Journey: Home → title → Back and Sports → game → Back return to the same item

## Fof TV 1.3.47 (November 2026)
**Smoothness**
- Rows glide continuously: one follower per row moves the cards a share of the remaining distance each frame. Before, every press restarted a 230 ms animation from standstill, so held or quick presses stuttered and lagged behind the highlight
- Opening a title no longer waits on the disk or the keystore: episode progress, history, watchlist, addons and the debrid token are loaded in the background at app start (measured: 21 main-thread samples on the first open, now none)
- Performance test: main-thread sampler for test builds (tag FofProfile), triggered by the test; inactive in release builds

## Fof TV 1.3.46 (November 2026)
**Player seeking**
- The picture holds while seeking and continues from the new spot (as on Netflix); Back cancels and keeps playing
- The timeline marker glides to each new position instead of jumping
- The seek card shows the exact time you land on ("1:12:30 / 2:05:00"), the jump size underneath
- Holding left/right starts with a gentle 2.5x step before the big jumps

## Fof TV 1.3.45 (November 2026)
- When no addon stream plays, the sources window lists what each addon answered (not responding, timed out, HTTP error, no links, only links this app cannot play)

## Fof TV 1.3.44 (November 2026)
**Sports**
- The sports page shows the games first and adds the channel matches afterwards: matching every game to the playlist took seconds on a big playlist, and meanwhile the page showed "No games found today"
- "No games found today" only when the feed itself has no games

## Fof TV 1.3.43 (November 2026)
**Updates**
- The update is written into the installer off the main thread (the screen froze for a moment), with a note that Fof TV closes briefly
- After an update the app opens itself again with the new version (where the system allows it)

## Fof TV 1.3.42 (November 2026)
**Sports**
- The last ESPN answers are saved on the device: after a restart or an update the sports page opens with the last known games at once and refreshes them
- Today's games of all leagues load in the background 2.5 seconds after the app starts, before Sports is opened

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
