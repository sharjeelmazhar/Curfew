#!/usr/bin/env bash
# Installs Curfew on this computer from this folder, without the internet:  sudo ./install.sh
# It builds the same package that the GitHub release offers and installs it, so later versions
# arrive with "sudo apt update && sudo apt upgrade". Safe to run again; paired phones are kept.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
[ "$(id -u)" = 0 ] || { echo "Please run this with sudo:  sudo ./install.sh"; exit 1; }
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
DEB="$("$HERE/build-deb.sh" "$T")"
chmod 755 "$T"                                  # apt reads the file as its own unprivileged user
echo "Installing Curfew..."
apt-get install -y --allow-downgrades "$DEB"
systemctl is-active --quiet curfew.service || { echo "Curfew did not start. See: journalctl -u curfew -n 30"; exit 1; }

echo
if [ -t 0 ]; then
  read -r -p "Pair a phone now? [Y/n] " ans
  case "$ans" in n*|N*) echo "Later, run:  sudo curfew pair" ;; *) /usr/bin/curfew pair || true ;; esac
else
  echo "To pair a phone run:  sudo curfew pair"
fi
