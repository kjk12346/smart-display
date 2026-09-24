# Smart display for Home Assistant (working name)

An Android app that turns an old tablet into a Nest Hub–style smart display for Home Assistant: install, sign in to
Home Assistant, and get an always-on screen with a clock, weather, room controls and (later) voice. Nothing to build
or configure by hand, unlike a dashboard in a kiosk browser.

All v0.1 steps (1–7) are built. Still to check on devices before calling v0.1 done: controls against real devices,
landscape layouts, screen pinning, Home app, and anything on an old or Fire tablet.

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
     `HomeAlias`; Fire tablets have no Home app chooser (`ACTION_HOME_SETTINGS`), so that needs the later device-owner
     or adb route. Idle dim is in `MainActivity.dispatchTouchEvent` (the waking touch is swallowed there, before any
     screen sees it). All brightness requests go through `WindowBrightness`; the darkest wins.

## Later (v0.2 and on; don't build in v0.1)

- Register the tablet in Home Assistant as a device via the mobile_app API
  (`POST /api/mobile_app/registrations`, then sensors over `POST /api/webhook/{webhook_id}`): battery, charging, screen
  on/off, brightness; commands to wake, dim, show a page. Lets owners automate the display (e.g. a smart plug that stops
  the battery sitting at 100%).
- Device-owner kiosk (set up once over adb), boot start, foreground service, proximity wake, night schedule.
- Doorbell/camera pop-ups. Assist voice: push-to-talk first, wake word much later.
- Optional "guest room" mode, tied to the owner's guest-panel project.
- Release: Play Console (personal accounts need a 12-tester, 14-day closed test first), privacy policy, data-safety
  form; Amazon Appstore for Fire tablets; direct APK.

## Build notes

- JDK 17 (`JAVA_HOME`), Android SDK via `local.properties` (not committed). No Android Studio assumed: build from the
  command line (`gradlew.bat assembleDebug`) and install with `adb install -r`.
- **Android Gradle plugin 9.4.1**, **Gradle 9.7.1**. AGP 9 has built-in Kotlin support, but bundles Kotlin Gradle
  plugin 2.2.10; the root `build.gradle.kts` pins the catalog's Kotlin version (2.4.20) with `kotlin.android`
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
