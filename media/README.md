# IoT Scanner — English demo assets

Created November 20, 2026 for app version 0.1.2.

## Deliverables

- `video/iot-scanner-demo-en.mp4`: 81.8-second, 1920 × 1080, 30 fps H.264 video with English AAC narration and an embedded English subtitle track. Titles and explanatory copy are in English.
- `video/iot-scanner-demo-en.srt`: the 18-cue English SRT uploaded to SubtitleKit and used for the video subtitle track.
- `video/iot-scanner-narration-subtitlekit.wav`: the original, unchanged WAV downloaded from [SubtitleKit SRT to Audio](https://subtitlekit.com/en/srt-to-audio/). Settings: English, Female voice 1, Tight sync, WAV. The final video uses this narration, normalized for listening and encoded to AAC.
- `video/narration-en.txt`: the English narration script.
- `video/demo-poster-en.jpg`: a video poster frame.
- `video/chapters.json`: chapter timestamps.
- `screenshots/`: eight genuine app screenshots, each 1080 × 2400 PNG.
- `screenshots-overview-en.jpg`: an English contact sheet of all eight screens.
- `iot-scanner-screenshots-en.zip`: the eight original screenshots and contact sheet.
- `sample-session-demo.csv`: an example report containing only simulated devices.
- `proof/subtitlekit-narration.jpg`: the completed SubtitleKit conversion and download screen.

## Screenshots

1. Nearby devices
2. Scan range selection
3. Nearby range applied
4. Review new signals
5. Record a baseline
6. Finding: signal getting stronger
7. Finding: signal getting weaker
8. Session report

## Capture and editing notes

The Android app was recorded in an Android 16 / API 36 emulator. The app UI is English. Status-bar demo mode sets a neutral 9:41 clock. All device observations are simulated and labeled as demo data. The opt-in `MediaCaptureTest` fixture supplies controlled synthetic BLE readings for the stronger/weaker scene; it does not change production app behavior or contact real nearby devices.

The source screenshots retain their original pixels. The video places actual emulator footage inside an English presentation layout. Chapter transitions and held frames support narration pacing. No direction, physical distance, or real-world identification accuracy is demonstrated.

The capture fixture only runs when explicitly invoked with `-e captureMedia true`. Normal test runs skip it. Working recordings, edit plans, and intermediate video files remain under the git-ignored `build/media-work/` directory. `scripts/render_demo_media.py` composes the footage and combines the downloaded SubtitleKit WAV with the prepared SRT.

## Verification

- Capture fixture completed successfully.
- All eight screenshots were visually reviewed in the contact sheet.
- Representative frames at 3, 16, 26, 36, 52, 62, and 76 seconds were visually checked.
- Full video decoding completed without errors.
- Final streams verified as H.264 video, English AAC audio, and English timed subtitles.
- The saved WAV matches the downloaded SubtitleKit WAV byte for byte.
- Screenshot ZIP integrity verified.
