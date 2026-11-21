# GitHub release v0.1.2

The [v0.1.2 GitHub release](https://github.com/slee1005/IoTscanner/releases/tag/v0.1.2) contains the signed APK, signed AAB, English demo video, screenshot archive, narration, subtitles, and SHA-256 checksums. App version code is 3; Android 8.0 / API 26 or later is required.

## Build the app

Restore the existing upload key and private signing properties, then run:

```sh
./scripts/build-release.sh
```

The signing configuration is read from the git-ignored `signing.properties` file. `signing.properties.example` contains placeholders only. The keystore remains outside Git history. Use the existing key for an update; do not generate a replacement key.

The build script produces `app/build/outputs/apk/release/app-release.apk` and `app/build/outputs/bundle/release/app-release.aab`, runs release unit tests and lint, and verifies the bundle signature. Only the APK is directly installable. The release tag identifies the corresponding app source.

## Verify the download

Download `SHA256SUMS.txt` with the assets you need, then compare the SHA-256 hash of each downloaded file with its listed value. The APK signing certificate SHA-256 is:

```
b2cb640ca63dd0dfd7f29dd65f2b3fa844dca1026e02784fce4573cb0c28ad97
```

The earlier debug APK has a different signing certificate. Export any session report and uninstall that debug app before installing the signed release APK.

## Media

The English demo uses actual emulator footage and labeled simulated signals, with the user-requested SubtitleKit narration. Full media binaries are distributed as release assets. Capture and rendering source files are included for reference; working recordings and edit plans remain in the ignored build directory.
