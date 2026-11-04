# VacuumTube for Android TV

An Android TV port of [VacuumTube](https://github.com/shy1132/VacuumTube): YouTube Leanback (youtube.com/tv) in a full-screen WebView, with VacuumTube's ad blocker and its settings menu.

## Install

Download `VacuumTube-AndroidTV.apk` from the [`android-latest` release](../../releases/tag/android-latest) and sideload it onto your TV (for example with Downloader, `adb install`, or a USB stick). Requires **Android 5.0 (API 21) or newer**.

Sign in from the TV the normal way (activate with a code on another device). Open the YouTube settings screen and press **VacuumTube** to get to the VacuumTube settings.

## Why not Android 4?

YouTube TV needs a modern browser engine. Android 4.x ships a WebView from 2013 that cannot run it, and the system WebView only became updatable (through the Play Store) with Android 5. On Android 5 to 7 boxes, make sure **Android System WebView** is updated, otherwise YouTube TV may show a blank or error screen.

## How it works

The desktop app is Electron, and all of its features live in preload modules (`src/preload`) that run before YouTube's own scripts. Those modules are used here **unchanged**:

- `android/build-js.mjs` bundles them into one script (`assets/vacuumtube.js`). Electron's API, `fs`, `path` and friends are replaced by the small shims in `android/js/shims`, and the stylesheets/locales are embedded in the bundle. The DIAL casting server is swapped for a stub (`android/js/h5vcc-android.js`) because a WebView has no raw sockets.
- `MainActivity` answers the request for `/tv` itself, drops the page's content security policy, and puts a `<script>` tag for the bundle first in the `<head>`. That gives the modules the same "run before YouTube" timing as on desktop.
- `Bridge` stores the config (SharedPreferences) and handles reload/exit. The Back button is turned into Cobalt's back key.

Because the modules are untouched, pulling in upstream VacuumTube updates is a plain `git merge`.

## What works / what doesn't

Works: ad blocking, the settings menu, SponsorBlock, DeArrow, Return YouTube Dislike, hide Shorts, codec filtering, remote/gamepad control.

Not available on Android: casting from your phone to the TV (DIAL), custom CSS from a folder, auto-update (download new APKs from the releases page).

## Building

The APK is built by GitHub Actions (`.github/workflows/android.yml`) on every push, and published as the `android-latest` release. To build locally you need Node, JDK 17 and the Android SDK:

```sh
node android/build-js.mjs
gradle -p android :app:assembleRelease     # Gradle 8.9+
```

`node android/test/smoke.mjs` runs the bundle in headless Chromium against a mock YouTube TV page (needs Playwright).
