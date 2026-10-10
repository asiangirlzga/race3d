#!/usr/bin/env bash
# Installs the game on an Android TV over the network (ADB) and starts it.
# On the first launch the game itself downloads its theme pack.
#
# Usage:
#   ./scripts/install_tv.sh <TV_IP> [TvRacerLite.apk | github_user/repo]
#
#   ./scripts/install_tv.sh 192.168.1.50                       # uses ./TvRacerLite.apk
#   ./scripts/install_tv.sh 192.168.1.50 myuser/TvRacerLite    # downloads latest GitHub Release
#
# TV side: Settings > Device Preferences > About > tap "Build" 7 times, then
#          Developer options > enable "USB debugging" / "Network debugging".
set -euo pipefail
cd "$(dirname "$0")/.."

TV_IP="${1:?Usage: $0 <TV_IP> [apk | github_user/repo]}"
SRC="${2:-TvRacerLite.apk}"
APK="TvRacerLite.apk"

# --- required tool: adb (downloaded automatically if missing) -------------------------------
if ! command -v adb >/dev/null 2>&1; then
  echo ">> adb not found, downloading platform-tools..."
  case "$(uname -s)" in Linux) P=linux ;; Darwin) P=darwin ;; *) echo "Unsupported OS"; exit 1 ;; esac
  mkdir -p .tools
  curl -fL -o .tools/pt.zip "https://dl.google.com/android/repository/platform-tools-latest-${P}.zip"
  unzip -q -o .tools/pt.zip -d .tools && rm .tools/pt.zip
  export PATH="$PWD/.tools/platform-tools:$PATH"
fi

# --- get the APK ------------------------------------------------------------------------------
if [[ "$SRC" == */* && "$SRC" != *.apk ]]; then
  echo ">> Downloading latest release from github.com/$SRC ..."
  URL=$(curl -fsSL "https://api.github.com/repos/$SRC/releases/latest" | grep -o '"browser_download_url": *"[^"]*\.apk"' | head -1 | cut -d'"' -f4)
  [ -n "$URL" ] || { echo "No APK found in the latest release of $SRC"; exit 1; }
  curl -fL -o "$APK" "$URL"
else
  APK="$SRC"
fi
[ -f "$APK" ] || { echo "APK not found: $APK"; exit 1; }

# --- install + launch -------------------------------------------------------------------------
echo ">> Connecting to $TV_IP ..."
adb connect "$TV_IP:5555"
echo ">> Installing $APK ..."
adb -s "$TV_IP:5555" install -r -d "$APK"
echo ">> Starting game (it downloads its theme pack on first launch)..."
adb -s "$TV_IP:5555" shell am start -n com.tvracer.lite/.MainActivity
echo "Done."
