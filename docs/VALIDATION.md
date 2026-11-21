# Validation — November 20, 2026

- Version 0.1.2 debug APK assembled successfully for Android 8.0+ (min SDK 26, target/compile SDK 36).
- 44 JVM tests passed, with zero failures or errors.
- 6 Android instrumentation tests passed on the installed Android 16 / API 36 ARM64 emulator.
- The emulator tests cover demo observations, BLE filtering, name search, baseline membership, navigation, stopping when the Activity leaves the foreground, and starting/stopping the Android scanner APIs with granted permissions.
- Range validation: inclusive BLE/Wi-Fi RSSI boundaries, smoothing, widening without a restart, out-of-range alert suppression, scoped baseline recording, range retention across session reset, preference persistence, and CSV range metadata all passed. The UI picker reduced seven demo observations to three at −60 dBm and restored all seven on All signals.
- Inspected `build/screenshots/scanner-range.png`: presets, custom slider, and Apply/Cancel are usable on the 320 × 640 dp emulator.
- Finding validation: 16 pure signal-trend tests cover sustained stronger/weaker/steady changes, sparse and rapid advertisements, cached and invalid timestamps, source-specific freshness, signal loss/reacquisition, pause/unavailable states, and bounded history.
- Emulator finding checks open a selected BLE/Wi-Fi device from its details, retain the exact target outside the scan range, resume/pause scanning, clear selection on reset, pause on foreground exit, and return to Devices through Android Back.
- Inspected `build/screenshots/scanner-finder.png` on the 320 × 640 dp emulator: target identity, live RSSI, comparison/freshness status, pause control, and Back to devices are readable; history and guidance scroll below.
- Android lint passed. Nonblocking warnings include dependency update suggestions, older-platform compatibility calls, and Kotlin style suggestions.
- Inspected the screenshot at `build/screenshots/scanner-demo.png` on a 320 × 640 dp viewport. Device results are visible with the compact scan control panel; the remaining results scroll normally.
- APK signature verified using the SDK apksigner. Manifest inspected: no Internet or background-location permission.
- Physical Android hardware detection and signature accuracy have not been validated. Demo tests use synthetic advertisements; emulator startup checks do not establish physical radio coverage or vendor-specific behavior.

Build and test instructions are in the project README. Generated APKs and reports are under `app/build/`; the supplied reference files remain unchanged under `files/`.

Android 13+ finder back navigation uses the platform OnBackInvokedCallback and passed the Android 16 emulator test. The legacy onBackPressed override only supports Android 8–12; its targeted lint suppression documents that fallback.
