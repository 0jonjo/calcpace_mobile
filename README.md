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
```

- **Server-driven:** a screen changes with a Rails deploy, without a new store release.
- **Edge-cache safe:** Cloudflare caches the public pages and doesn't vary by user agent, so the site detects the app on the client, from the user agent's `Hotwire Native` marker.
- **Sign-in:** Google refuses OAuth inside WebViews, so Google and Strava sign-in run in a Chrome Custom Tab and come back through `https://calcpace.app/app_auth/callback?ticket=…`, a verified App Link that only the Play-signed app receives. The ticket is single-use, lasts two minutes and only works with a PKCE verifier that never leaves the app. Links from outside can't load `/app_auth/redeem` into the WebView. See [`AppAuth.kt`](android/app/src/main/kotlin/app/calcpace/auth/AppAuth.kt) and [`IncomingLink.kt`](android/app/src/main/kotlin/app/calcpace/main/IncomingLink.kt).
- **"Your run is in" notifications:** the app has no session of its own, so the token goes in through the page. On the home card, the site's `push` bridge component asks the app for the notification status; after the athlete taps "turn on", the app shows the system prompt and replies with the FCM token, which the page posts to `/push_devices` against its session. The server sends data-only messages (`title`, `body`, `path`); the app draws the notification itself and only opens a plain path on the site. See [`push/`](android/app/src/main/kotlin/app/calcpace/push).

## Android

Requirements: JDK 17 and the Android SDK (compileSdk 36). Builds need Android 9 (API 28) or newer.

```bash
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:installDebug                                          # device or emulator
./gradlew :app:installDebug -Pcalcpace.baseUrl=http://10.0.2.2:3001  # a local Rails server
```

Debug builds install as `app.calcpace.twa.debug`, next to the Play version.

Push notifications need Firebase options, read at build time from a `google-services.json` that never enters the repo (it is public): `~/.config/calcpace/google-services.json` by default, or `-Pcalcpace.googleServices=/path/to/google-services.json`. Without the file the app builds and runs with push off (the bridge reports `unavailable`); that is how CI builds it. A release build fails without it. The file only knows the real package, so test push on a debug build with `-Pcalcpace.debugIdSuffix=`. Release signing reads a properties file that stays out of the repo (`~/.calcpace/signing.properties`, or a path in `CALCPACE_SIGNING`) with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`.

## Roadmap

See [`docs/PLAN.md`](docs/PLAN.md).

## License

The code is released under the [MIT License](LICENSE). The Calcpace name, logo and icon are not covered by it.
