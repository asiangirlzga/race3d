#!/usr/bin/env bash
# Installs everything needed to build the APK on your own Linux/macOS machine:
# JDK 17 (if missing), Android SDK (cmdline-tools, platform 34, build-tools 34) and Gradle 8.7.
# Everything is downloaded into ./.sdk and ./.tools (nothing system-wide except an optional JDK).
set -euo pipefail
cd "$(dirname "$0")/.."

OS=$(uname -s | tr '[:upper:]' '[:lower:]')          # linux | darwin
case "$OS" in linux) SDK_OS=linux ;; darwin) SDK_OS=mac ;; *) echo "Unsupported OS: $OS"; exit 1 ;; esac

mkdir -p .sdk .tools

if ! command -v java >/dev/null 2>&1; then
  echo ">> Java not found. Installing JDK 17..."
  if command -v apt-get >/dev/null 2>&1; then sudo apt-get update && sudo apt-get install -y openjdk-17-jdk unzip curl
  elif command -v brew >/dev/null 2>&1; then brew install openjdk@17
  else echo "Please install JDK 17 manually and re-run."; exit 1; fi
fi

if [ ! -d .sdk/cmdline-tools/latest ]; then
  echo ">> Downloading Android command-line tools..."
  curl -fL -o .tools/cmdtools.zip "https://dl.google.com/android/repository/commandlinetools-${SDK_OS}-11076708_latest.zip"
  unzip -q -o .tools/cmdtools.zip -d .tools/cmdtmp
  mkdir -p .sdk/cmdline-tools
  mv .tools/cmdtmp/cmdline-tools .sdk/cmdline-tools/latest
  rm -rf .tools/cmdtmp .tools/cmdtools.zip
fi

export ANDROID_HOME="$PWD/.sdk"
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null || true
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platform-tools" "platforms;android-34" "build-tools;34.0.0"

if [ ! -d .tools/gradle-8.7 ]; then
  echo ">> Downloading Gradle 8.7..."
  curl -fL -o .tools/gradle.zip "https://services.gradle.org/distributions/gradle-8.7-bin.zip"
  unzip -q -o .tools/gradle.zip -d .tools
  rm .tools/gradle.zip
fi

echo "sdk.dir=$ANDROID_HOME" > local.properties
cat <<EOF

Done. Build the APK with:
  .tools/gradle-8.7/bin/gradle assembleRelease
Result: app/build/outputs/apk/release/TvRacerLite.apk
EOF
