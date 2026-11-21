# IoT Scanner for Android

Version **0.1.2**. A native Kotlin Android app built from the scanner reference files in `files/`. It discovers nearby Bluetooth LE advertisers and Wi-Fi access points, classifies advertised signatures, records a baseline, and highlights new signals worth reviewing. The supplied reference files remain unchanged.

## Install the built app

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk` after building. Copy it to an Android phone running Android 8.0 or newer (API 26+) and open it to install, or use:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is signed for development and testing. Store publication needs a separate release signing key and store configuration.

## Build

Open this directory in Android Studio, allow Gradle to sync, and run the `app` module. The project uses JDK 17, Android SDK 36, AGP 8.13.1, Kotlin 2.2.20, and Gradle 8.14.3. Dependencies are pinned; there is no third-party UI framework or app network dependency.

Install Android SDK Platform 36 through Android Studio. Set `ANDROID_HOME` or create a machine-local, git-ignored `local.properties` containing `sdk.dir=/absolute/path/to/Android/sdk`. Then:

```sh
./scripts/build.sh
# Or, with JAVA_HOME set to JDK 17:
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug

# With an emulator/device connected:
./scripts/build.sh :app:connectedDebugAndroidTest
```

`scripts/build.sh` uses `JAVA_HOME` when configured and also discovers the Homebrew JDK 17 installation on this Mac. The first Gradle build may download dependencies. `local.properties` is specific to this computer and is not part of the portable project.

## Use

1. In **Devices**, tap **Start scanning**. Grant Nearby devices and **Precise** location when Android asks. Enable Bluetooth, Wi-Fi, and Location services. A channel that is unavailable does not prevent another available channel from scanning.
2. Tap **Scan range** below the scan panel (also available in **Session**) to choose **Nearby (−60 dBm)**, **Balanced (−75 dBm)**, **Wide (−90 dBm)**, **All signals**, or a **Custom** minimum between −100 and −40 dBm. The choice is saved and takes effect immediately without restarting either radio. It filters both BLE/Wi-Fi lists and gates new alerts and newly recorded baseline entries using smoothed RSSI. This is a signal-strength filter, not a radius in meters or a directional measurement. Existing observations and past alerts are retained; CSV reports include the selected minimum and an in-range flag for every observation.

   Browse **All**, **Bluetooth LE**, **Wi-Fi**, **Priority**, or **Baseline**. Search by name, address, or signature label. Tap a device for RSSI history, timestamps, matched rule, manufacturer IDs, and advertised services. The radar is a scan animation, not a map of device positions.
3. In **Session**, record the baseline in the environment you want to monitor. Tap **Finish baseline** when ready. Baseline devices remain visible without triggering new-device alerts. Individual device details also let you add/remove baseline membership.
4. Review **Alerts** for newly observed high/medium priority matches with a smoothed signal above your threshold (default −80 dBm). Change the threshold and optional alert tone in **Session**. Each qualifying address alerts once unless removed from baseline or the session is reset.
5. **Export CSV** saves the whole session, including devices no longer visible, through Android's file picker. Session observations and baseline are held in memory; export before resetting or closing the app process. Scan range, alert threshold, and tone settings persist locally. A new alert must satisfy both the selected scan range and the alert threshold; changing the range does not create retroactive alerts. Opening another app pauses scanning; return and tap **Resume scanning**.
6. Use **Try demo** to explore seven simulated devices without permissions or hardware. Demo mode uses a separate session, displays a persistent banner, and labels every report row `DEMO`. Switching between modes asks before clearing an existing session.

## Find one device while moving

Tap a device, then **Find this device**. Scanning resumes if paused; a dedicated screen follows that exact BLE address or Wi-Fi BSSID and shows:

- **Signal getting stronger**, **Signal getting weaker**, or **Signal holding steady** based on fresh, smoothed readings, with a 3 dB tolerance for small fluctuations.
- Current RSSI, change in dB, the best signal received during this finding session, recent signal history, and the last signal time.
- Explicit **Collecting**, **Paused**, **Scanner unavailable**, and **Signal not received** states. Old values are dimmed and the strength gauge is cleared when a live reading is unavailable.

Move a few steps, then pause and compare. For BLE, the latest three seconds of readings are compared with the preceding three-to-ten-second window after there are enough distinct samples. At most one point per second is kept so high advertising rates do not dominate. Wi-Fi compares consecutive fresh scan observations and can take 35 seconds or longer to update. Reusing a cached result never creates a fresh comparison.

The selected device remains tracked even if it falls below the **Scan range** filter or expires from the general list. Without a fresh packet, BLE shows a missing signal after 15 seconds and Wi-Fi after 90 seconds. These are freshness indicators, not proof that the device is off. A returning signal starts a fresh comparison window after a long gap.

**Pause scanning** pauses finding. Leaving the app also pauses radio scanning and the finding refresh timer. **Back to devices** or Android Back exits finding while leaving an active scan running. Session reset and switching between live/demo sessions clear the selected target. Rotating the phone keeps the target but pauses scanning; resume when ready.

A stronger signal can suggest an approach, but interference, walls, antenna orientation, or transmitter behavior can change RSSI without movement. This feature does not calculate bearing, direction, a meter distance, or a device owner's identity. If an advertiser rotates its address, select its new entry manually.

## Detection limits

- BLE must be advertising to be visible. Wi-Fi scanning sees access points, hotspots, and advertised Wi-Fi Direct groups, not arbitrary clients on a network or powered-off/silent devices.
- Names, MAC addresses, and advertising signatures can be changed, randomized, or shared by multiple device types. Matches do not identify an owner, confirm an exact model, or establish what someone is doing.
- Supplied rules marked `verify: true` remain explicitly **Unverified** and cannot produce automatic alerts. The reference manufacturer/service assignments for glasses and Fast Pair are preserved as unverified hypotheses. Manufacturer-only Apple, Samsung, and Microsoft matches use low priority. A hidden network or locally administered MAC alone does not establish a hotspot.
- RSSI is a relative signal measurement affected by obstacles and interference. It is not a distance, direction, or room-boundary measurement.
- Wi-Fi requests are spaced 35 seconds apart and cached observations retain their original measurement times. Android may throttle requests; the app displays this status and ignores duplicate/expired observations. Devices expire from the visible list after 60 seconds without a fresh signal.
- Scanning runs only while this Activity is in the foreground. The app keeps the screen awake during a scan and stops radio callbacks/receivers when paused or destroyed. It never pairs, connects to a discovered device, or joins a discovered Wi-Fi network.

Android's [Wi-Fi scanning guide](https://developer.android.com/develop/connectivity/wifi/wifi-scan) documents scan limits and location requirements. The [Bluetooth permissions guide](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions) explains Nearby devices permissions and beacon filtering when `neverForLocation` is used. This app keeps location access for discovery and does not set that flag; `BLUETOOTH_CONNECT` is used to read adapter state. Nearby Wi-Fi permission does not replace the fine-location requirement for the scanning APIs used here.

## Privacy

No account, telemetry, server, Internet permission, or background service. Scan observations remain on the phone, and reports are written only to a destination you choose. App backup and device transfer of app data are disabled. Demo data never mixes with a live session.

## Project layout

- `files/`: untouched reference Kotlin, manifest, and signatures supplied by the owner.
- `app/src/main/java/com/examscan/scan/`: platform scanners, permissions, session repository, CSV export.
- `app/src/main/java/com/examscan/signature/`: JSON loader and AND-match classifier.
- `app/src/main/java/com/examscan/ui/`: retained scan controller, Activity, custom radar/icons/signal chart.
- `app/src/main/assets/signatures.json`: adapted rules with English display labels, original Korean labels, and explicit verification metadata.
- `app/src/main/res/values/strings.xml`: UI copy; the default interface is English.
- `app/src/test/`: deterministic core tests using synthetic signals and an injected clock.
- `app/src/androidTest/`: real-Activity emulator tests for demo discovery, filters, baseline, range settings, device finding, navigation, and foreground cleanup.

Tests do not establish real-world recognition accuracy. Real BLE/Wi-Fi discovery, permission behavior on different vendors, and signature assignments still need testing on physical Android hardware.
