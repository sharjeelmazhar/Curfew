#!/usr/bin/env bash
# Pairs the emulator app with a dry-run demo agent by typing the address and code.
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"; E="$ROOT/tools/emu.sh"
CODE=$("$ROOT/tools/demo/pair.sh" "$1" "$2" | grep Code | awk '{print $2}' | tr -d '-')
$E shell am start -n app.curfew/.MainActivity >/dev/null; sleep 2
$E tap 240 965; sleep 1.2; $E tap 240 545; sleep 0.8; $E tap 240 670; sleep 1
$E shell input text "10.0.2.2:$2"; $E shell input keyevent 61; sleep 0.4; $E shell input text "$CODE"; sleep 0.6
[ -n "$3" ] && $E shot "$3"
$E shell input keyevent 4; sleep 1; $E tap 240 717; sleep 3
tail -1 "$ROOT/logs/pair-$1.log"
