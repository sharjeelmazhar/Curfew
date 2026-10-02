#!/usr/bin/env bash
# Helpers for looking at the app in the emulator.
#   tools/emu.sh start | stop | shot NAME | install
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; TC="$ROOT/.toolchain"
export ANDROID_SDK_ROOT="$TC/android-sdk" ANDROID_HOME="$TC/android-sdk" ANDROID_AVD_HOME="$TC/avd"
ADB="$TC/android-sdk/platform-tools/adb -s ${CURFEW_DEVICE:-emulator-5554}"
case "$1" in
  start) nohup "$TC/android-sdk/emulator/emulator" -avd curfew -no-window -no-audio -no-boot-anim -no-snapshot \
           -gpu swiftshader_indirect > "$ROOT/logs/emulator.log" 2>&1 &
         $ADB wait-for-device; until [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done; echo booted ;;
  stop) $ADB emu kill ;;
  install) $ADB install -r "$ROOT/Curfew.apk" ;;
  shot) mkdir -p "$ROOT/logs/shots"; $ADB exec-out screencap -p > "$ROOT/logs/shots/$2-full.png"
        ~/.local/bin/uv run -q --with pillow python -c "
from PIL import Image; im=Image.open('$ROOT/logs/shots/$2-full.png'); w=${3:-480}; im.convert('RGB').resize((w, im.height*w//im.width)).save('$ROOT/logs/shots/$2.png')" ;;
  tap) $ADB shell input tap $(($2*3)) $(($3*3)) ;;
  *) $ADB "$@" ;;
esac
