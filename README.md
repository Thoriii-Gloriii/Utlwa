# Utlwa!

A black-and-gold music discovery and playback app for Android, built on the real
[NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) backend.

All songs, artwork, search results, charts and recommendations come from YouTube through
NewPipeExtractor. There is no mock or bundled sample music. Recently played, liked songs and
user playlists are stored locally on the device.

## Features

- **Home** — greeting, trending music from YouTube's real Trending Music kiosk, recently played
  and made-for-you recommendations derived from related streams.
- **Search** — live YouTube search for artists, songs and playlists, plus genre shortcuts.
- **Your Library** — real locally saved songs, playlists you create, and filter chips.
- **Account** — profile and settings UI.
- **Player** — real audio streaming via `MediaPlayer`, draggable progress bar, play/pause,
  next/previous, shuffle, repeat, like, and an ongoing now-playing notification with controls.

The app name is always **Utlwa!**. The GitHub repository name omits `!` because GitHub does not
allow that character in repository names.

## Building

This project is built without Gradle, using the on-phone Android toolchain (`aapt2`, `javac`,
`d8`, `apksigner`) and a Java 17 JDK.

Requirements:

- Android SDK with `android.jar` (set `SDK_JAR` if it is not at
  `$HOME/android-sdk/android-36/android.jar`)
- `aapt2`, `d8` and `apksigner` on `PATH`
- The bundled extractor jars in `libs/`

Build:

```sh
bash build-utlwa.sh
```

The signed APK is written to `build/app.apk`. Install it with `adb install -r build/app.apk`
or install it directly on the phone.

## Dependencies

Bundled in `libs/` (downloaded from JitPack/Maven Central):

- `extractor.jar` — TeamNewPipe/NewPipeExtractor v0.27.1
- `nanojson.jar`, `jsoup.jar`, `jsr305.jar`, `protobuf.jar`, `rhino.jar`, `rhino-engine.jar`

## Licence

GPL-3.0-or-later. Utlwa! uses NewPipeExtractor, which is GPL-3.0-or-later.
