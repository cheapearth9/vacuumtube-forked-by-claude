# VacuumTube for Android TV

An unofficial Android TV port of [VacuumTube](https://github.com/shy1132/VacuumTube) by shy.

It opens the regular YouTube TV interface (`youtube.com/tv`) in a full-screen WebView, so you get the normal TV layout with **no ads and no SmartTube-style third-party client**, plus VacuumTube's own settings menu.

> This fork only adds the Android app (everything under [`android/`](android)). The VacuumTube code that does the real work, the ad blocker and the settings menu, is upstream's and is used unchanged. All credit for it goes to [shy1132/VacuumTube](https://github.com/shy1132/VacuumTube). The original desktop README is [here](https://github.com/shy1132/VacuumTube#readme).

## Install

1. Open the [**android-latest** release](../../releases/tag/android-latest) and download `VacuumTube-AndroidTV.apk`.
2. Put it on your TV (the Downloader app, `adb install`, or a USB stick) and install it. You'll need to allow installs from unknown sources for whichever app opens the file.
3. Launch **VacuumTube** from the TV's app row and sign in the usual way (activate with a code from your phone).

**Requires Android 5.0 (API 21) or newer.** Android 4 can't run YouTube TV: its built-in WebView dates from 2013. On Android 5 to 7 boxes, update **Android System WebView** from the Play Store first (and Chrome, on some devices). Otherwise the page may be blank.

## Using it

- Navigate with the remote's D-pad, **OK** to select, **Back** to go back. Pressing Back on the home screen leaves the app.
- Open YouTube's own **Settings** screen and choose **VacuumTube** to reach VacuumTube's settings.
- Voice search asks for microphone permission the first time.

## What works

| Feature | Android TV |
| --- | --- |
| Ad blocking (home feed, search, video, Shorts) | Yes |
| VacuumTube settings menu | Yes |
| SponsorBlock, DeArrow, Return YouTube Dislike | Yes |
| Hide Shorts, codec filtering, playback speed/volume controls | Yes |
| Casting from your phone to the TV (DIAL) | **No** (a WebView has no raw sockets) |
| Custom CSS folder, auto-update, window options | **No** (desktop-only) |

To update, download a newer APK from the releases page and install it over the old one.

## How it works

The desktop app is Electron and its features are "preload" scripts that run before YouTube's. Android WebView has no preload step and no Node, so this port:

1. **Bundles upstream's modules as-is** with [`android/build-js.mjs`](android/build-js.mjs). Electron, `fs`, `path` and friends are replaced by small stand-ins in [`android/js/shims`](android/js/shims), and the stylesheets and locales are embedded in the bundle.
2. **Injects the bundle first** by having [`MainActivity`](android/app/src/main/java/io/github/cheapearth9/vacuumtube/MainActivity.java) fetch the `/tv` page itself, remove its content-security policy, and add a `<script>` tag at the top of `<head>`. That reproduces desktop's "run before YouTube" timing.
3. **Identifies as a TV app** with a Cobalt-style user agent (same format as upstream's `src/ua.js`), which is what makes YouTube serve the TV interface.
4. **Bridges the rest**: [`Bridge`](android/app/src/main/java/io/github/cheapearth9/vacuumtube/Bridge.java) stores settings and handles reload/exit, and the Back button is converted into Cobalt's back key.

Since upstream's files aren't edited, pulling in upstream updates is normally just `git fetch upstream && git merge upstream/main` (apart from this README).

## Building it yourself

GitHub Actions builds the APK on every push ([`android.yml`](.github/workflows/android.yml)). To build locally you need Node 20+, JDK 17 and the Android SDK:

```sh
node android/build-js.mjs                  # bundle upstream's modules for the WebView
gradle -p android :app:assembleRelease     # Gradle 8.9 or newer
# APK: android/app/build/outputs/apk/release/app-release.apk
```

`node android/test/smoke.mjs` loads the bundle into headless Chromium with a mock YouTube TV page and checks that the modules load and ads are stripped (needs Playwright).

## Notes

- Not affiliated with or endorsed by YouTube or Google. Using a modified client may be against YouTube's terms of service. Use it at your own risk.
- The APK is signed with the debug key, so it's for sideloading only.
- Licensed under the MIT License, same as upstream. See [LICENSE](LICENSE).
