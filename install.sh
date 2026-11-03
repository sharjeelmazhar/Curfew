#!/bin/sh
# Installs Curfew on this computer:  curl -fsSL https://sharjeelmazhar.github.io/Curfew/install.sh | sudo sh
# Later versions then arrive with the system's own updates (Arch: from the AUR, or by running this again).
set -eu
SITE=https://sharjeelmazhar.github.io/Curfew
RELEASE=https://github.com/sharjeelmazhar/Curfew/releases/latest/download
[ "$(id -u)" = 0 ] || { echo "Please run this with sudo."; exit 1; }
case "$(uname -m)" in
  x86_64) DEB=amd64; ARCH=x86_64 ;;
  aarch64) DEB=arm64; ARCH=aarch64 ;;
  *) echo "Curfew does not run on this kind of computer yet ($(uname -m))."; exit 1 ;;
esac
command -v systemctl >/dev/null || { echo "Curfew needs systemd, which this computer does not use."; exit 1; }
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
chmod 755 "$T"
if command -v apt-get >/dev/null; then
  curl -fsSL -o "$T/curfew.deb" "$RELEASE/curfew-$DEB.deb"
  apt-get install -y "$T/curfew.deb"
elif command -v dnf >/dev/null; then
  cat > /etc/yum.repos.d/curfew.repo <<REPO
[curfew]
name=Curfew
baseurl=$SITE/rpm
enabled=1
gpgcheck=0
repo_gpgcheck=1
gpgkey=$SITE/key.asc
REPO
  dnf install -y curfew || { dnf clean all; dnf install -y curfew; }      # a mirror can hand out a broken file
elif command -v pacman >/dev/null; then
  curl -fsSL -o "$T/curfew.pkg.tar.zst" "$RELEASE/curfew-$ARCH.pkg.tar.zst"
  pacman -U --noconfirm "$T/curfew.pkg.tar.zst"
else
  echo "This system has no apt, dnf or pacman, so Curfew cannot install here yet."
  exit 1
fi
echo
echo "Done. To connect a phone, run:  sudo curfew pair"
