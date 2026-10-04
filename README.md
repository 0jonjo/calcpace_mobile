# Calcpace mobile

Native apps for [calcpace.app](https://calcpace.app), a running pace calculator and training log built with Rails. The apps use [Hotwire Native](https://native.hotwired.dev): the Rails site renders the screens, and native code handles what a web page can't do (signing in with Google, notifications, Health Connect).

| Platform | Folder | Status |
|---|---|---|
| Android | [`android/`](android) | In development. It will replace the current Trusted Web Activity on Google Play under the same listing. |
| iOS | `ios/` | Planned |

## How it fits together

```
calcpace_web (Rails, Heroku)          calcpace_mobile/android
─────────────────────────────         ─────────────────────────────────
HTML pages (Turbo)          ───────▶  WebFragment (Hotwire WebView)
/configurations/android_v1.json ───▶  path configuration (routing rules)
/app_auth/start, /redeem    ◀──────▶  AppAuth (Custom Tab + PKCE handoff)
bridge/push_controller.js   ◀──────▶  PushComponent (permission + FCM token)
POST /push_devices          ◀───────  (the page registers the token)
FCM data message            ───────▶  CalcpaceMessagingService → notification
bridge/health_connect_controller.js ◀▶ HealthComponent (permissions + link token)
POST /health_connect/link   ◀───────  (the page asks for the token, hands it over)
POST /health_connect/sessions ◀─────  HcSyncWorker (Bearer token, background)
```

- **Server-driven:** a screen changes with a Rails deploy, without a new store release.
- **Edge-cache safe:** Cloudflare caches the public pages and doesn't vary by user agent, so the site detects the app on the client, from the user agent's `Hotwire Native` marker.
- **Sign-in:** Google refuses OAuth inside WebViews, so Google and Strava sign-in run in a Chrome Custom Tab and come back through `https://calcpace.app/app_auth/callback?ticket=…`, a verified App Link that only the Play-signed app receives. The ticket is single-use, lasts two minutes and only works with a PKCE verifier that never leaves the app. Links from outside can't load `/app_auth/redeem` into the WebView. See [`AppAuth.kt`](android/app/src/main/kotlin/app/calcpace/auth/AppAuth.kt) and [`IncomingLink.kt`](android/app/src/main/kotlin/app/calcpace/main/IncomingLink.kt).
- **"Your run is in" notifications:** the app has no session of its own, so the token goes in through the page. On the home card, the site's `push` bridge component asks the app for the notification status; after the athlete taps "turn on", the app shows the system prompt and replies with the FCM token, which the page posts to `/push_devices` against its session. The server sends data-only messages (`title`, `body`, `path`); the app draws the notification itself and only opens a plain path on the site. See [`push/`](android/app/src/main/kotlin/app/calcpace/push).

- **Health Connect import:** for athletes without Strava, the app reads the runs and walks that Garmin Connect, Samsung Health, Polar Flow and others save to Health Connect, and sends them to the athlete's own account. Reading happens only on the phone: sessions of type running, treadmill running and walking, with distance, active duration (pauses taken off), climb and average/maximum heart rate during the session, from the app that recorded each one. No routes, steps, sleep or anything else. On the home card or the account page, the site's `health` bridge component asks the app to open Health Connect's permission screen; once exercise and distance are allowed (with a native confirmation when they were allowed before and Health Connect shows nothing), the page gets a link token from `POST /health_connect/link` (bound to its session) and hands it to the app together with the one-time grant the app put in its "enable" reply; the app takes the token only with that grant, once, within five minutes (any frame of a page can reach the bridge, only the caller gets the grant). A WorkManager job then uploads to `POST /health_connect/sessions` with that token as a bearer: the last 30 days first (never notified), then whatever the Changes API reports, on every return to the foreground and about hourly in the background when Health Connect allows background reads. Signing out, disconnecting on the account page or revoking the permission in Health Connect stops it. See [`health/`](android/app/src/main/kotlin/app/calcpace/health) and the contract in [`docs/PLAN.md`](docs/PLAN.md).

## Android

Requirements: JDK 17 and the Android SDK (compileSdk 36). Builds need Android 9 (API 28) or newer.

```bash
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:installDebug                                          # device or emulator
./gradlew :app:installDebug -Pcalcpace.baseUrl=http://10.0.2.2:3001  # a local Rails server
```

Debug builds install as `app.calcpace.twa.debug`, next to the Play version.

Push notifications need Firebase options, read at build time from a `google-services.json` that never enters the repo (it is public): `~/.config/calcpace/google-services.json` by default, or `-Pcalcpace.googleServices=/path/to/google-services.json` (a relative path resolves against `android/`). Without the file the app builds and runs with push off (the bridge reports `unavailable`); that is how CI builds it. A release build fails without it, and an unreadable file fails any build. The file only knows the real package, so a default debug build (`app.calcpace.twa.debug`) also reports `unavailable`: test push on a debug build with `-Pcalcpace.debugIdSuffix=`. Release signing reads a properties file that stays out of the repo (`~/.calcpace/signing.properties`, or a path in `CALCPACE_SIGNING`) with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`.

### Health Connect

The site drives the import through the `health` bridge component (home and account pages only):

| Event (web → app) | `data` sent | Reply `data` |
|---|---|---|
| `connect` | `{}` | `{ "status", "linked", "background" }` |
| `enable` | `{}` | `{ "status", "granted", "background", "grant" }`, where `grant` (a one-time value) comes only with `granted: true` |
| `link` | `{ "token", "grant" }` | `{ "linked": true }`, or `false` for a malformed token or a grant that isn't the latest one (any `link` uses it up) |
| `unlink` | `{}` | `{ "linked": false }` |

Uploads go to `POST /health_connect/sessions` with `Authorization: Bearer <token>`. The details are in [`docs/PLAN.md`](docs/PLAN.md).

The release manifest declares five read permissions, the same the Play Console declares: `READ_EXERCISE`, `READ_DISTANCE`, `READ_HEART_RATE`, `READ_ELEVATION_GAINED` and `READ_HEALTH_DATA_IN_BACKGROUND`. Health Connect's "privacy policy" link opens the site's privacy policy at `#health-connect`, in the phone's language. Debug builds also declare four `WRITE_*` permissions, used only by a seed tool that writes made-up sessions from adb, to test the import on an emulator (API 34+ has Health Connect built in):

```bash
P=app.calcpace.twa.debug    # app.calcpace.twa with -Pcalcpace.debugIdSuffix=
S=$P/app.calcpace.health.HcSeedActivity
adb shell am start -n $S --es action insert --es type running --ef km 10.2 --ei minutes 52 --ei pause_minutes 3 --ei hours_ago 2 --ei hr 150
adb shell am start -n $S --es action insert --ei days_ago 10      # for the initial 30-day read
adb shell am start -n $S --es action insert --es type biking      # not a run: never sent
adb shell am start -n $S --es action update --es id <id> --ef km 10.5
adb shell am start -n $S --es action delete --es id <id>
adb logcat -s HcSeed HcSync                                         # the seeded ids; debug upload results
```

The first run asks for the write permissions; run the command again after allowing them. Seeded sessions come from the app's own package (unknown to the site, so no app name) on a made-up "Calcpace Seed Watch": the site shows "via Health Connect · Calcpace Seed Watch".

## Roadmap

See [`docs/PLAN.md`](docs/PLAN.md).

## License

The code is released under the [MIT License](LICENSE). The Calcpace name, logo and icon are not covered by it.
