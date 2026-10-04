#!/usr/bin/env bash
# Builds the Curfew package from this folder:  ./build-deb.sh [output folder]
# The result is curfew_<version>_all.deb; the version comes from the file VERSION.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$(mkdir -p "${1:-$HERE/../dist}" && cd "${1:-$HERE/../dist}" && pwd)"
VERSION="$(tr -d '[:space:]' < "$HERE/VERSION")"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
P="$T/curfew"
EXT=curfew@sharjeelmazhar.github.io
install -d -m 755 "$P/DEBIAN" "$P/usr/lib/curfew" "$P/usr/bin" "$P/usr/lib/systemd/system" \
  "$P/etc/apt/sources.list.d" "$P/usr/share/keyrings" "$P/usr/share/doc/curfew" \
  "$P/usr/lib/systemd/system-sleep" "$P/usr/lib/systemd/user-environment-generators" "$P/usr/share/gnome-shell/extensions/$EXT"
install -m 755 "$HERE/curfew.py" "$P/usr/lib/curfew/curfew.py"
install -m 644 "$HERE/curfew.service" "$P/usr/lib/systemd/system/curfew.service"
install -m 644 "$HERE/debian/curfew.sources" "$P/etc/apt/sources.list.d/curfew.sources"
install -m 644 "$HERE/debian/curfew-archive-keyring.gpg" "$P/usr/share/keyrings/curfew-archive-keyring.gpg"
install -m 644 "$HERE/../README.md" "$P/usr/share/doc/curfew/README.md"
install -m 755 "$HERE/debian/curfew-sleep" "$P/usr/lib/systemd/system-sleep/curfew"
install -m 755 "$HERE/debian/curfew-env" "$P/usr/lib/systemd/user-environment-generators/90-curfew"
# no airplane-mode switch on the login screen: GNOME's radio service does not start there
install -D -m 755 "$HERE/debian/curfew-rfkill-allowed" "$P/usr/lib/curfew/rfkill-allowed"
install -d "$P/usr/lib/systemd/user/org.gnome.SettingsDaemon.Rfkill.service.d"
printf '[Service]\nExecCondition=/usr/lib/curfew/rfkill-allowed\n' > "$P/usr/lib/systemd/user/org.gnome.SettingsDaemon.Rfkill.service.d/curfew.conf"
chmod 644 "$P/usr/lib/systemd/user/org.gnome.SettingsDaemon.Rfkill.service.d/curfew.conf"
install -m 644 "$HERE/gnome/metadata.json" "$HERE/gnome/extension.js" "$P/usr/share/gnome-shell/extensions/$EXT/"
cat > "$P/usr/bin/curfew" <<'WRAP'
#!/bin/sh
[ "$(id -u)" = 0 ] || { echo "This needs administrator rights. Run it as: sudo curfew $*"; exit 1; }
exec /usr/bin/python3 /usr/lib/curfew/curfew.py "$@"
WRAP
chmod 755 "$P/usr/bin/curfew"
sed "s/@VERSION@/$VERSION/" "$HERE/debian/control" > "$P/DEBIAN/control"
echo "Installed-Size: $(du -sk --exclude=DEBIAN "$P" | cut -f1)" >> "$P/DEBIAN/control"
install -m 644 "$HERE/debian/conffiles" "$P/DEBIAN/conffiles"
install -m 755 "$HERE/debian/postinst" "$HERE/debian/prerm" "$HERE/debian/postrm" "$P/DEBIAN/"
find "$P" -type d -exec chmod 755 {} +
dpkg-deb --root-owner-group -Zxz --build "$P" "$OUT/curfew_${VERSION}_all.deb" >/dev/null
echo "$OUT/curfew_${VERSION}_all.deb"
