#!/usr/bin/env bash
# Downloads the JDK and the minimal Android SDK into .toolchain/ inside this
# folder. Nothing is installed system-wide. Safe to run again.
#   tools/setup-toolchain.sh            build tools only
#   tools/setup-toolchain.sh emulator   also the emulator + one system image
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TC="$ROOT/.toolchain"
mkdir -p "$TC" && cd "$TC"

if [ ! -x jdk/bin/javac ]; then
  echo "== JDK 17"
  curl -fsSL -o jdk.tar.gz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
  mkdir -p jdk && tar -xzf jdk.tar.gz -C jdk --strip-components=1 && rm jdk.tar.gz
fi
export JAVA_HOME="$TC/jdk" PATH="$TC/jdk/bin:$PATH"

SDK="$TC/android-sdk"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "== Android command-line tools"
  curl -fsSL -o clt.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  mkdir -p "$SDK/cmdline-tools" && rm -rf clt-tmp && unzip -q clt.zip -d clt-tmp
  rm -rf "$SDK/cmdline-tools/latest" && mv clt-tmp/cmdline-tools "$SDK/cmdline-tools/latest"
  rm -rf clt.zip clt-tmp
fi
SM="$SDK/cmdline-tools/latest/bin/sdkmanager"
yes | "$SM" --licenses >/dev/null 2>&1 || true
echo "== SDK platform + build tools"
"$SM" "platforms;android-35" "build-tools;35.0.0" "platform-tools" | grep -v '^\[' || true

if [ "${1:-}" = "emulator" ]; then
  echo "== Emulator + system image"
  "$SM" "emulator" "system-images;android-35;google_apis;x86_64" | grep -v '^\[' || true
  if [ ! -d "$TC/avd/curfew.avd" ]; then
    mkdir -p "$TC/avd"
    echo no | ANDROID_AVD_HOME="$TC/avd" "$SDK/cmdline-tools/latest/bin/avdmanager" create avd \
      -n curfew -k "system-images;android-35;google_apis;x86_64" -d pixel_7_pro >/dev/null
  fi
fi
echo "== done"; du -sh "$TC"/* 2>/dev/null
