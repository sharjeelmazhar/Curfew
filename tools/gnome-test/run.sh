#!/usr/bin/env bash
# Checks Curfew's GNOME Shell add-on in a real GNOME Shell, without a screen and without root:
# a headless shell on a throw-away session bus, a stand-in for the airplane-mode service, and a
# probe add-on that reports what the quick settings show. Prints one PROBE line per run and saves
# pictures in $OUT. Usage: tools/gnome-test/run.sh [out-dir]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
OUT="$(mkdir -p "${1:-$ROOT/logs/gnome-test}" && cd "${1:-$ROOT/logs/gnome-test}" && pwd)"
EXT=curfew@sharjeelmazhar.github.io
once() {        # once NAME 'list,of,extensions'
  local T; T="$(mktemp -d /tmp/gsXXXX)"       # short: socket paths have a length limit
  mkdir -p "$T/home" "$T/run" "$T/data/gnome-shell/extensions" "$T/db.d"
  chmod 700 "$T/run"
  cp -r "$HERE/probe@curfew" "$T/data/gnome-shell/extensions/"
  mkdir -p "$T/data/gnome-shell/extensions/$EXT"
  cp "$ROOT/agent/gnome/metadata.json" "$ROOT/agent/gnome/extension.js" "$T/data/gnome-shell/extensions/$EXT/"
  printf "[org/gnome/shell]\nenabled-extensions=[%s]\n" "$2" > "$T/db.d/00"
  dconf compile "$T/db" "$T/db.d"
  printf "user-db:user\nfile-db:%s\n" "$T/db" > "$T/profile"
  env -i PATH="$PATH" HOME="$T/home" XDG_RUNTIME_DIR="$T/run" XDG_DATA_DIRS="$T/data:/usr/share" \
    DCONF_PROFILE="$T/profile" PROBE_SHOT="$OUT/$1.png" XDG_SESSION_TYPE=wayland \
    timeout 40 dbus-run-session -- sh -c "
      /usr/bin/python3 '$HERE/fake-rfkill.py' & sleep 1
      gnome-shell --headless --wayland --no-x11 --virtual-monitor 1280x800 2>&1 &
      S=\$!; for i in \$(seq 30); do sleep 1; grep -q 'PROBE done' '$T/log' 2>/dev/null && break; done; kill \$S" > "$T/log" 2>&1 || true
  echo "$1: $(grep -o 'PROBE {.*}' "$T/log" || echo 'no answer, see' "$T/log")"
  if grep -q "PROBE done" "$T/log"; then fusermount3 -u "$T/run/doc" 2>/dev/null || true; rm -rf "$T" 2>/dev/null || true; fi
}
once without "'probe@curfew'"
once with "'probe@curfew','$EXT'"
