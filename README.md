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
```

- **Server-driven:** a screen changes with a Rails deploy, without a new store release.
- **Edge-cache safe:** Cloudflare caches the public pages and doesn't vary by user agent, so the site detects the app on the client, from the user agent's `Hotwire Native` marker.
- **Sign-in:** Google refuses OAuth inside WebViews, so Google and Strava sign-in run in a Chrome Custom Tab and come back through `calcpace://auth?ticket=…`. The ticket is single-use, lasts two minutes and only works with a PKCE verifier that never leaves the app. See [`AppAuth.kt`](android/app/src/main/kotlin/app/calcpace/auth/AppAuth.kt).

## Android

Requirements: JDK 17 and the Android SDK (compileSdk 36). Builds need Android 9 (API 28) or newer.

```bash
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:installDebug                                          # device or emulator
./gradlew :app:installDebug -Pcalcpace.baseUrl=http://10.0.2.2:3001  # a local Rails server
```

Debug builds install as `app.calcpace.twa.debug`, next to the Play version. Release signing reads a properties file that stays out of the repo (`~/.calcpace/signing.properties`, or a path in `CALCPACE_SIGNING`) with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`.

## Roadmap

See [`docs/PLAN.md`](docs/PLAN.md).

## License

The code is released under the [MIT License](LICENSE). The Calcpace name, logo and icon are not covered by it.
