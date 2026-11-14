# VacuumTube for Android TV

An unofficial Android TV port of [VacuumTube](https://github.com/shy1132/VacuumTube) by shy.

It opens the regular YouTube TV interface (`youtube.com/tv`) in a full-screen WebView, so you get the normal TV layout with **no ads and no SmartTube-style third-party client**, plus VacuumTube's own settings menu.

> This fork only adds the Android app (everything under [`android/`](android)) and the iOS app (under [`ios/`](ios)). The VacuumTube code that does the real work, the ad blocker and the settings menu, is upstream's and is used unchanged. All credit for it goes to [shy1132/VacuumTube](https://github.com/shy1132/VacuumTube). The original desktop README is [here](https://github.com/shy1132/VacuumTube#readme).

## Install

1. Open the [**android-latest** release](../../releases/tag/android-latest) and download `VacuumTube-AndroidTV.apk`.
2. Put it on your TV (the Downloader app, `adb install`, or a USB stick) and install it. You'll need to allow installs from unknown sources for whichever app opens the file.
3. Launch **VacuumTube** from the TV's app row and sign in the usual way (activate with a code from your phone).

**Requires Android 5.0 (API 21) or newer.** Android 4 can't run YouTube TV: its built-in WebView dates from 2013. On Android 5 to 7 boxes, update **Android System WebView** from the Play Store first (and Chrome, on some devices). Otherwise the page may be blank.

## Using it

- Navigate with the remote's D-pad, **OK** to select, **Back** to go back. Back is sent to YouTube as an Escape key, like desktop VacuumTube, and YouTube decides what it does (at the account picker it quits the app).
- Open YouTube's own **Settings** screen and choose **VacuumTube** to reach VacuumTube's settings.
- Voice search asks for microphone permission the first time.
- Press **Menu** (or the **Settings** key) on the remote to open VacuumTube's settings directly.
- A few seconds after launch, a small message shows the app version, Android and WebView versions, and whether VacuumTube's scripts are running. If something misbehaves, a photo of it tells what's wrong.
- Devices without hardware VP9 or AV1 decoding automatically get H.264-only playback (change it in VacuumTube settings). "Low memory mode" is off by default everywhere; it is available in settings under Advanced.

## VacuumTube for iOS (experimental)

An iPhone / iPad version of the same thing. **Plug in an HDMI adapter or start AirPlay, and the YouTube TV interface appears on that screen by itself**, with no toggle. The phone becomes the remote (D-pad, OK, Back, play/pause, VacuumTube settings), and an external keyboard works too (arrows, Return = OK, Esc = Back, Space, typing in search, Ctrl+O for VacuumTube's settings). With no external display connected, the TV interface fills the phone, and touching the screen shows the same on-screen arrows, Back and OK as the original VacuumTube (turn them off with "Touch Overlay" in settings). Mouse and trackpad are not supported.

**Install with AltStore or SideStore:** add this source, then install VacuumTube from it. New builds then show up as updates in AltStore.

```
https://github.com/cheapearth9/vacuumtube-forked-by-claude/releases/download/ios-latest/altstore.json
```

Or download `VacuumTube-iOS.ipa` from the [**ios-latest** release](../../releases/tag/ios-latest) and sideload it any way you like. The IPA is unsigned: AltStore (or another sideloading tool) signs it with your Apple ID, which with a free account has to be renewed every 7 days. The app has no updater of its own; AltStore does that.

Same features as the Android app: ad blocking, VacuumTube's settings menu, SponsorBlock, DeArrow, Return YouTube Dislike, and so on. Not available: casting *to* the app (DIAL), and quitting from inside the app (iOS apps don't quit themselves).

Things to know before trying it:

- **Untested on a real device so far.** It builds, and its scripts are tested in a desktop browser, but nobody has run it on an iPhone yet. Expect rough edges and please report what you see.
- **iPhone needs iOS 17.1 or newer for video.** YouTube's player needs Media Source Extensions, which iPhones only got in iOS 17.1 (iPads have had them longer). Older iOS versions can open the app, but videos will not play.
- No Widevine DRM on iOS, so movies and other protected videos won't play (the same as desktop VacuumTube). Guest accounts should work.
- iOS plays video in WebKit's own player, so codec choices (VP9 / AV1) depend on what your device supports; use VacuumTube settings to change them.

## What works

| Feature | Android TV |
| --- | --- |
| Ad blocking (home feed, search, video, Shorts) | Yes |
| VacuumTube settings menu | Yes |
| SponsorBlock, DeArrow, Return YouTube Dislike | Yes |
| Hide Shorts, codec filtering, playback speed/volume controls | Yes |
| Casting from your phone to the TV (DIAL) | **No** (a WebView has no raw sockets) |
| Custom CSS folder, auto-update, window options | **No** (desktop-only) |

## Updates

**In the app:** a few seconds after launch the app checks this repo's release. If a newer build exists, a menu pops up with **Update**, **Later** and **Skip this version**. *Update* downloads the APK and opens Android's installer, which asks for the final OK. The first time, Android also asks you to allow VacuumTube to install apps. You can check by hand from VacuumTube settings, then About, then *Check now*.

**One-time reinstall:** builds before the first one with updates were signed with a temporary key. If you installed one of those, uninstall it once before installing the new one (Android refuses to update across different keys). After that, updates install over the top and keep your login and settings.

**Staying current with VacuumTube:** a scheduled GitHub Action ([`sync-upstream.yml`](.github/workflows/sync-upstream.yml)) merges new upstream commits every day and rebuilds the APK, which your TV then offers as an update. If an upstream change can't be merged automatically, or needs a new Android shim, the run fails and shows up in the Actions tab for a manual fix.

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
- The APK is signed with a key that is committed in this repo ([`android/vacuumtube.keystore`](android/vacuumtube.keystore)) so that updates install over each other. It's a sideload-only app and the key is not secret, so only install APKs you got from this repo's releases.
- Licensed under the MIT License, same as upstream. See [LICENSE](LICENSE).
