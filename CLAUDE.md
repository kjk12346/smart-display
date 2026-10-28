# Smart display for Home Assistant (working name)

An Android app that turns an old tablet into a Nest Hub–style smart display for Home Assistant: install, sign in to
Home Assistant, and get an always-on screen with a clock, weather, room controls and (later) voice. Nothing to build
or configure by hand, unlike a dashboard in a kiosk browser.

All v0.1 steps (1–7) are built, plus guest-room mode (below). Checked on a device (2026-09-24): screen pinning,
Home app and landscape (by the owner), the release build's first-run flow from a fresh install, and guest mode
(PIN required, room choice, unticking, extras, the guest's controls screen). Still to check: controls against real
devices, and anything on an old or Fire tablet. A test release APK (before guest mode) went to a second household's
Home Assistant.

## Guest-room mode (added after v0.1 step 7)

- Settings > "Who uses this display": Owner (all rooms) or Guest room. Guest mode needs the exit PIN (like Home app
  and pinning), so switching back is always behind it.
- A guest display has one Home Assistant area: everything controllable there shows unless the owner unticks it, and
  new devices in that area appear automatically. The owner can add individual devices from other areas (e.g. a shared
  bathroom speaker); those appear under their area's name. Stored in `kiosk/GuestConfig.kt` via `KioskStore`;
  filtering in `ui/controls/GuestModel.kt`.
- The guest's controls screen has no room list. `ControlsViewModel.call()` also refuses any entity outside the guest's
  set. This is a limit in the app, not a security boundary: the tablet's Home Assistant account can still reach
  everything, and Home Assistant has no per-entity permissions. Settings suggests a separate non-admin user.
- Standalone: not tied to guest-panel passes (decided 2026-09-24).

## Wallpapers and alarms (added 2026-09-24)

- **Wallpapers (done):** photos behind the clock from one folder in Home Assistant's media browser
  (`media_source/browse_media`, then `resolve_media` for a signed URL; `ha/Media.kt`). Chosen in Settings with
  `MediaBrowserDialog`; shuffled rounds without repeats (`PhotoQueue`), each downloaded with Coil before a slow
  crossfade, under a dark gradient; plain black in quiet hours. Only images directly in the folder, not subfolders.
  The folder is listed again at each round's start and every 10 minutes; added photos join the current round. Each
  photo slowly pans and zooms (`PanZoom`, 1.05–1.15×, over its interval clamped to 20 s–2 min, then eases back).
- **Google Photos (planned, owner decided yes):** only via Google's Photos Picker API (since March 2025 apps can't
  read albums); the app keeps copies of picked photos, and new album photos don't appear by themselves. Needs the
  owner's Google Cloud project + Android OAuth client, and Google Play services (so not on Fire tablets). Until
  Google verifies the app, only test users can sign in.
- **Alarms (built):** settable in owner and guest modes; a real Android alarm (rings with the screen off or the
  app closed) with a full-screen Snooze/Dismiss. Per display, the owner picks any of: sound on the tablet, turn on
  chosen lights, play a chosen sound (from the media browser) on a chosen speaker. Settings (behind the PIN) has
  "Clear all alarms" for between guests.
  - Code in `alarm/`. Only the next ring is scheduled (`AlarmManager.setAlarmClock`); each ring schedules the next.
    `AlarmReceiver` reschedules after boot, clock/time zone changes and app updates. `AlarmService` is a foreground
    service of type `systemExempted` (allowed for apps holding USE_EXACT_ALARM); it plays the sound, runs the Home
    Assistant actions over the live connection, and starts `AlarmActivity` (over the lock screen, same task, so it
    works while pinned). Rings stop after 10 minutes; snooze is 10 minutes.
  - Checked on a Pixel 6 (Android 17 beta): rings on time with the app showing, and with the app in the background
    and the screen off (wakes it); Snooze reschedules, Dismiss stops everything; a pending snooze survived an app
    update; the alarm turns on the chosen lights (two on/off lamps, 2026-10-09). Not yet checked: the speaker
    action, and old/Fire tablets.
- **Sunrise before an alarm (added 2026-10-09):** Settings > "Sunrise before the alarm": off, 10, 20, 30 (default) or
  45 min. A second exact alarm (`AlarmReceiver.ACTION_SUNRISE`) starts `AlarmService`'s sunrise: the alarm's lights
  that are off go from 1% at 2000 K to 100% at 4000 K (`alarm/Sunrise.kt`; brightness rises with progress², steps
  every 30 s, with `transition` on lights that support it; colour lights without colour temperature get it
  converted by Home Assistant). Lights already on are left alone; one turned off during it stays off. On/off-only
  lights just come on at the ring. The ambient screen glows (`SunriseGlow`, over the night dimming and idle dim)
  with a large "I'm up" button (also in the notification), which brings the lights up and skips that ring
  (`Skip`; a one-time alarm turns off). The ring itself is at normal volume: the owner wants the gentle part to be
  light only. An alarm set for sooner than the sunrise squeezes it into the time left. Checked on the Pixel with two
  on/off lamps (glow, ring, lamps on at the ring, "I'm up"); the fade itself waits for dimmable bulbs.
- **Wind down (added 2026-10-09):** the evening mirror of the sunrise, in owner and kitchen modes (not guest).
  Settings > "Winding down": lights to dim, how long (15–60 min, default 45), "Start by itself", sleep length
  (7–9 h, default 8) and a fallback bedtime for nights without a morning alarm (3 AM–noon), plus things elsewhere
  turned off at the start (`homeassistant.turn_off`). Bedtime is the next morning's alarm minus the sleep length
  (`alarm/WindDown.kt`, `nextWindDown`). The chosen lights that are on fade to 2% at 2200 K (brightness falls by
  (1−p)², never brighter or cooler than they were), then go off at bedtime; lights that were off stay off, and one
  turned off during it stays off. Runs in its own foreground service, `WindDownService` (systemExempted, notification
  id 2), so it and `AlarmService` never stop each other; scheduled by `Alarms.reschedule()` (request code 2). A
  Wind down button (kitchen dashboard, Controls header) starts one now and counts as tonight's
  (`Alarms.windDownDone`). While it runs, `WindDownOverlay` shows lights-off time, tomorrow's alarm and first event,
  and a bedtime check (`ui/winddown/BedtimeChecks.kt`: unlocked locks, open garage covers, open door/window sensors,
  lights and TVs on, with one-tap fixes), with Hide, Stop and Lights off now. Each display has its own settings;
  displays don't share them (that waits for the mobile_app registration). Checked on the Pixel with a placeholder
  light (button, panel, checks, hide, stop); the fade on real dimmable lights is still to check.
- **Wake window (idea, not built):** ring early within a window if the phone app's "Sleep confidence" sensor (Google
  Sleep API, ~10 min updates) shows stirring. Health Connect only gives sleep duration after the night, so true
  light-sleep detection isn't available through Home Assistant.

## Family / kitchen display (added 2026-10-09)

- A third display type (Settings > "Who uses this display"). By day a dashboard (`ui/dashboard/`): clock, weather
  and forecast, the family calendar, who's home with Home and Away buttons, the owner's scene/script buttons, over the
  wallpaper photos (dimmed, darker behind the header; cards 85% opaque via `dashCardColor()`; added 2026-10-28,
  replacing a photo tile). At night the clock over the photos, dimmed.
- **Family calendar (Skylight-style):** events from every `calendar.*` entity via `calendar.get_events` (from the
  start of today, 7 days, refreshed every 5 min; `CalendarModel.kt`, `CalendarCard.kt`). Google Calendar comes in
  through Home Assistant's own integration (the owner's Google Cloud OAuth client, published so sign-ins don't expire
  after 7 days), not from the app. In Settings each calendar is a person's (`person.*`), the family's (the default)
  or not shown; each person has a color (`PersonColor`, handed out in entity ID order unless chosen; `FamilyColor` is
  neutral), also used for their avatar in Who's home. Filter chips show one or more people's events. The same event
  in several calendars shows once, with each owner's color.
- **Events given to people (added 2026-10-09):** tapping an event opens its details (`EventDialog`) with the
  household's people as chips; the ticked people then own it on this display, overriding its calendar's owner. Saved
  only on the display (`KitchenConfig.eventPeople`, decided by the owner), never written to Google. Keyed by the
  event's `uid` (every time) or `uid#recurrence_id` (just this time of a repeating one); events without a uid fall
  back to title + start. That's why events come from the REST API (`GET /api/calendars/<id>`,
  `HomeAssistantClient.calendarEvents`): `calendar.get_events` leaves out the uid. Old assignments aren't pruned.
- Layout: wide screens give the calendar most of the width (a column per day, as many as fit at 100 dp up to 7; a list
  if under 3) with Who's home and Buttons beside it. Narrow screens
  stack calendar first and scroll; cards must not scroll by themselves there (nested vertical scrolling crashes).
- Checked on the Pixel 6 in portrait and landscape (2026-10-09), and on the owner's TCL tablet (Android 15,
  1200×1920 at 320 dpi, landscape 960×600 dp) on 2026-10-23: a full week fits (84 dp minimum per day), Who's home
  takes the height it needs (up to 60% of the side column) with Home and Away in equal halves. Not yet with calendars
  assigned to people. On a 15.6" tablet (1280×720 dp, 2026-10-28) the week and side column fit with room to spare; the
  dashboard clock scales with the screen (56–80 dp) and the clock screen's is capped at 150 dp.
- TCL's Smart Manager blocks "background auto-launch" for new apps, which would stop alarms, the sunrise and the
  wind-down from starting the app (and rescheduling after a reboot). The owner has to allow it in Smart Manager; the
  app can't. Other makers (Xiaomi, Huawei, Samsung's sleeping apps) do the same: worth a first-run hint later.
- **Arranging cards (added 2026-10-28):** the cards sit on a grid, 12×8 cells in landscape (`WIDE_GRID`) and 6×12 in
  portrait (`TALL_GRID`, tablets ≥ 600 dp wide); phones stack them in the portrait order. Each card has a place on
  both (`kiosk/DashLayout.kt`, saved in `KitchenConfig.layout`; the default is the old fixed layout). A long press on
  the dashboard (behind the exit PIN) starts arranging (`CardGrid`): drag to move, the corner to resize, snapping to
  cells and refusing overlaps; Hide; Sources (`CardSources.kt`: calendars and whose, who's shown and Home/Away,
  buttons, and the weather entity, which the clock screen shares); Add card. Calendar, Who's home and Buttons are one
  each and use `KitchenConfig`; Devices (an area's controls plus picked ones, via `ControlCard`), Sensors, Now
  playing and Camera (`entity_picture` refreshed every 10 s) keep their own sources and can be added more than
  once. A new card must fit on the grid on screen; on the other, the biggest cards shrink a row or column to make
  room. Checked on the 15.6" tablet: PIN prompt, resize, move, Add card's list. The new card types aren't yet
  checked against real devices.
- **Burn-in:** by day the dashboard gives way to the clock over the photos (the ambient screen, which already drifts)
  after `KitchenConfig.screensaverMinutes` untouched (Settings, default 10, or never); at night after 2 minutes, as
  before. A tap brings the dashboard back. The dashboard itself also drifts by `burnInOffset` each minute.

**This repo is public.** The owner's personal setup (paths, test devices, home network, other projects) is in
`CLAUDE.local.md`, which is not committed. Keep personal details out of committed files and commit messages.

## Why this exists

- Google smart displays are barely supported any more, and old Android tablets are cheap and plentiful.
- Existing options: **Fully Kiosk Browser** (paid; shows a dashboard you build yourself), the **Home Assistant
  companion app** (dashboards and Assist, but no always-on ambient mode or kiosk), community setups like
  **View Assist** (several add-ons plus Fully Kiosk; fiddly). The gap is a turnkey, polished display.

## Decisions so far

- **Talks to Home Assistant directly.** No separate server.
- **Native UI** (Kotlin + Jetpack Compose + AndroidX) for everything core. Old tablets are slow, and many (Fire tablets,
  devices without Play) have an outdated WebView. A WebView is only for "show any Home Assistant dashboard".
- **minSdk 26 (Android 8.0).** Fire HD tablets (Fire OS, no Play Store) are a key target: test on them.
- **Its own repo** (`kjk12346/smart-display`), with its own releases. Public, so it can host the sign-in page.
- **Visual design (base theme):** always dark, mostly neutral surfaces with pastel pink / blue / lavender highlights, in
  `app/src/main/kotlin/.../ui/theme/`. System font for now. Ask before changing the look further.

## Not decided yet (ask the owner before anything that locks these in)

- **Product name and application ID.** Use a placeholder (`dev.smartdisplay.app`) until decided; the ID can't change
  after a store release. Don't use "Google", "Nest" or "Home Assistant" as the name ("for Home Assistant" is fine).
- **Open source or paid**, and the license. No LICENSE file until decided, so the public code is all rights reserved.

## v0.1 scope

1. **New Android project** in this folder (single `:app` module to start), Kotlin + Compose, Gradle version catalog.
2. **Find Home Assistant**: zeroconf/NSD discovery of `_home-assistant._tcp` (TXT records carry `base_url` /
   `internal_url`), plus a "type the address" fallback.
3. **Sign in with Home Assistant's own login** (no tokens pasted by hand):
   - Open `GET {ha}/auth/authorize?response_type=code&client_id={CLIENT_ID}&redirect_uri={REDIRECT}&state=…` in a
     Custom Tab / browser.
   - `client_id` must be a URL; `redirect_uri` must share its host **or** be listed on the `client_id` page as
     `<link rel="redirect_uri" href="…">`. For an app scheme like `smartdisplay://auth-callback`, that means hosting a
     tiny page (GitHub Pages from this repo) with that link tag.
   - Exchange the code: `POST {ha}/auth/token` (`grant_type=authorization_code`, `code`, `client_id`) → access token
     (about 30 minutes) + refresh token. Refresh with `grant_type=refresh_token`. Store the refresh token encrypted.
   - Google or other single sign-on isn't built in: whatever Home Assistant's login page offers (including SSO added
     by a Home Assistant integration) works through this same flow.
   - **Done:** `client_id` is `https://kjk12346.github.io/smart-display/` (GitHub Pages from `docs/` on `main`),
     redirect `smartdisplay://auth-callback`. Changing either signs every display out (refresh tokens are tied to the
     client_id), so do it once, with the product name. Home Assistant has no PKCE; a random `state` guards the
     redirect. The refresh token is AES-GCM encrypted with an Android Keystore key (`auth/TokenCipher.kt`); access
     tokens live in memory only. Code in `auth/`; `Session.accessToken()` is what step 4 should use.
4. **Live connection**: WebSocket `{ha}/api/websocket` (`auth` with the access token, then `subscribe_events`
   `state_changed`, `get_states`, `get_config`, and the area/device/entity registry lists), reconnect with backoff,
   refresh the token when it expires.
   - **Done:** `ha/HomeAssistantClient.kt`. Screens read `app.home.state` (a `HomeState`); collecting it is what
     keeps the connection open (it stops 5 s after the last collector). Data is kept while reconnecting; check
     `status`. Send commands with `command()` / `callService(..., returnResponse = true)`. Registry and core-config
     update events trigger a reload of that list. Tests run the real client against a fake Home Assistant
     (`FakeHomeAssistant.kt`, OkHttp MockWebServer).
   - Later, for big installs on slow tablets: `subscribe_entities` (compressed diffs) instead of `state_changed`, and
     persistent maps instead of copying `entities` on every event.
5. **Ambient screen** (the default screen): large clock and date, weather from the first `weather.*` entity
   (current conditions from its state; today's high/low via `weather.get_forecasts` with `type: daily` and
   `return_response: true`, refreshed every 30 min), gentle pixel shift against burn-in, dim at night.
   - **Done:** `ui/ambient/`. Weather is the first `weather.*` entity by ID that isn't hidden or disabled. "Night"
     for dimming is fixed quiet hours, 22:00–07:00 (not sunset, which is too early in winter for a kitchen); a
     setting later. `sun.sun` only picks night icons. Screen brightness 0.08 plus text at 55% at night. Weather
     icons are drawn in code (`WeatherIcon.kt`). Long-press opens Settings (connection details, sign out); tap is
     free for the controls screen.
6. **Controls screen**: rooms from Home Assistant areas; native cards for lights (on/off, brightness), switches/plugs,
   media players (play/pause, volume hidden until the name is tapped), thermostats (mode, setpoints).
   - **Done:** `ui/controls/`. Tap the clock to open; Done, Back or 2 minutes idle returns. Rooms come from areas
     (entity's area, else its device's), plus "Other"; hidden, disabled and config/diagnostic entities are skipped.
     Names drop the room prefix and a doubled device name. No optimistic UI: cards follow Home Assistant's state
     events; sliders and thermostat steppers hold the sent value until the state confirms it (or 5 s).
   - Home Assistant rejects a command id lower than one it has seen (`id_reuse`); `LiveConnection.command()` numbers
     and queues under one lock for that reason. `FakeHomeAssistant` enforces the same rule.
7. **Kiosk basics** (the owner's earlier kiosk app shows how; see `CLAUDE.local.md`): keep screen on, use as Home app,
   screen pinning, PIN to exit settings, dim when idle (first tap only wakes).
   - **Done:** options in `kiosk/KioskStore.kt`, shown in Settings. Exit PIN is 4–8 digits, PBKDF2-hashed, 5 tries
     then 30 s lockout; with a PIN, opening Settings asks for it. Home app and pinning can't be turned on without a
     PIN. Pinning is released while Settings is open (the way out). The Home app is the manifest's disabled
     `HomeAlias`. Android runs it in a separate Home task, so `MainActivity` keeps one display at a time: the
     Home copy closes any other, an ordinary launch while this is Home goes to Home, and turning Home off reopens an
     ordinary copy from the Home copy's `onDestroy` (Android applies the alias change about a second later, so the
     launcher flashes briefly). Fire tablets have no Home app chooser (`ACTION_HOME_SETTINGS`), so that needs the
     later device-owner or adb route. Idle dim is in `MainActivity.dispatchTouchEvent` (the waking touch is
     swallowed there, before any screen sees it). All brightness requests go through `WindowBrightness`; the darkest
     wins. Power button in a kiosk (Home app, pinned or guest, with "Keep screen on"): apps can't catch the key, so
     `MainActivity.onScreenOff` (a screen-off broadcast while the display was showing) goes back to the clock and
     wakes the screen with a brief `ACQUIRE_CAUSES_WAKEUP` wake lock; the display shows over the lock screen. Holding
     power still gives Android's power menu. While pinned, Android shows its "To unpin" reminder on each wake; only
     the later device-owner kiosk avoids that.

## Later (v0.2 and on; don't build in v0.1)

- Register the tablet in Home Assistant as a device via the mobile_app API
  (`POST /api/mobile_app/registrations`, then sensors over `POST /api/webhook/{webhook_id}`): battery, charging, screen
  on/off, brightness; commands to wake, dim, show a page. Lets owners automate the display (e.g. a smart plug that stops
  the battery sitting at 100%).
- Device-owner kiosk (set up once over adb), boot start, foreground service, proximity wake, night schedule.
- Doorbell/camera pop-ups. Assist voice: push-to-talk first, wake word much later.
- Possibly: link guest-room mode to the owner's guest-panel project (its passes).
- Release: Play Console (personal accounts need a 12-tester, 14-day closed test first), privacy policy, data-safety
  form; Amazon Appstore for Fire tablets; direct APK.

## Build notes

- JDK 17 (`JAVA_HOME`), Android SDK via `local.properties` (not committed). No Android Studio assumed: build from the
  command line (`gradlew.bat assembleDebug`) and install with `adb install -r`.
- **Android Gradle plugin 9.4.1**, **Gradle 9.7.1**. AGP 9 has built-in Kotlin support, but bundles Kotlin Gradle
  plugin 2.2.10; the root `build.gradle.kts` pins the catalog's Kotlin version (2.4.21) with `kotlin.android`
  `apply false`. Keep `kotlin` and the Compose compiler plugin on the same version.
- Current AndroidX releases need compileSdk 37, so the app compiles against 37.2 and targets 36. `sdkmanager` is now
  a shim; install SDK packages with `cmdline-tools/latest/bin/android.exe --sdk=<sdk dir> sdk install
  platforms/android-XX`.
- As of 2026-09-23: Compose BOM `2026.09.00`, OkHttp 5.5.0, lifecycle 2.11.0. Check the current stable versions
  before pinning new ones, and do not pick betas.
- Android 17 (API 37) makes `ACCESS_LOCAL_NETWORK` a runtime permission, and enforces it once the manifest declares
  it even while targeting 36 (NSD fails with `FAILURE_PERMISSION_DENIED`, code 7). Anything touching the LAN must
  go through `rememberLocalNetworkAccess()` first.
- After renaming resource folders, a stale incremental build can fail to find resources; `gradlew clean` fixes it.

## Working style for this repo

- The owner reviews on real devices; say what was tested and on what (emulator vs tablet).
- Commit in small steps with clear messages; ask before pushing to a new remote.
