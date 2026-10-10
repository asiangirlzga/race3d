# TV Racer Lite

A small 3D-style car racing game for **Android TV**, controlled entirely with the **TV remote**.
No game engine, no libraries, no image or sound files: everything is drawn and synthesized in code.

## Controls (TV remote)

| Key | Action |
|---|---|
| UP | Accelerator |
| DOWN | Brake |
| LEFT / RIGHT | Steering |
| OK / Enter (in race) | Toggle auto-gas |
| BACK / Play-Pause | Pause |
| LEFT / RIGHT (menu) | Change theme |
| UP (menu) | Music on/off |

Gamepads also work (A = gas, B = brake, D-pad = steer). Keyboard: arrows or WASD.

## Why it is light

- Pure Java, **zero dependencies**: APK is roughly 100 KB.
- Draws at 960x540 and lets Android scale it; capped at 30 fps; RGB_565 surface; no hardware-GL context.
- Road, cars and trees are vector shapes drawn with Canvas (no bitmaps in RAM).
- Engine, music and effects are synthesized at 11 kHz mono (no audio files).
- Themes are a few KB of JSON.

## Theme download at install time

Android cannot run code while an APK installs, so the game does it on the **first launch**:
it downloads `theme/themes.json` from this repo (raw.githubusercontent.com), caches it in app storage and refreshes it weekly.
If the TV is offline a built-in theme is used, so the game always starts.
Add or edit themes in `theme/themes.json`, push, and every TV gets them (see the file for the fields).

## Build the APK on GitHub (no local setup)

1. Create a GitHub repo and push this folder.
2. Open the **Actions** tab, then **Build APK**. Download `TvRacerLite-apk` from the run's Artifacts.
3. For a permanent download link: `git tag v1.0 && git push --tags`. The APK is attached to a GitHub Release.

The workflow rewrites the theme URL to your repo automatically.

## Install on the TV

- **Easiest:** install the *Downloader* app on the TV, enter your release URL, install the APK.
- **ADB script:** `./scripts/install_tv.sh <TV_IP> yourname/yourrepo`
  (downloads adb and the latest release APK if needed, installs, launches).

## Build locally (optional)

```
./scripts/setup_build_env.sh          # downloads JDK check, Android SDK, Gradle
.tools/gradle-8.7/bin/gradle assembleRelease
```

## Tuning

`GameView.java` constants: `W`/`H` (render size), `DRAW` (draw distance), `FRAME_MS` (fps cap), `LAPS`, `CARS`.
Lower `DRAW` and `CARS` for very old TV boxes.
