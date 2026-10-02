#!/usr/bin/env bash
# Installs the Curfew agent on this computer. Run once:  sudo ./install.sh
# Safe to run again (it just refreshes the files and keeps paired phones).
set -euo pipefail
PORT=787
HERE="$(cd "$(dirname "$0")" && pwd)"
[ "$(id -u)" = 0 ] || { echo "Please run this with sudo:  sudo ./install.sh"; exit 1; }
command -v python3 >/dev/null || { echo "python3 is missing"; exit 1; }
command -v systemctl >/dev/null || { echo "systemd is missing"; exit 1; }

echo "Installing Curfew..."
install -d -m 700 -o root -g root /usr/local/lib/curfew /var/lib/curfew
install -m 700 -o root -g root "$HERE/curfew.py" /usr/local/lib/curfew/curfew.py
cat > /usr/local/bin/curfew <<'WRAP'
#!/bin/sh
[ "$(id -u)" = 0 ] || { echo "This needs administrator rights. Run it as: sudo curfew $*"; exit 1; }
exec /usr/bin/python3 /usr/local/lib/curfew/curfew.py "$@"
WRAP
chmod 755 /usr/local/bin/curfew
install -m 644 "$HERE/curfew.service" /etc/systemd/system/curfew.service
ID="$(/usr/local/bin/curfew id)"            # creates the stable ID on first run

if [ -d /etc/avahi/services ]; then          # lets the phone find this computer by itself
  cat > /etc/avahi/services/curfew.service <<AVAHI
<?xml version="1.0" standalone='no'?>
<!DOCTYPE service-group SYSTEM "avahi-service.dtd">
<service-group>
  <name replace-wildcards="yes">Curfew %h</name>
  <service protocol="ipv4">
    <type>_curfew._tcp</type>
    <port>$PORT</port>
    <txt-record>id=$ID</txt-record>
  </service>
</service-group>
AVAHI
else
  echo "Note: avahi is not installed; the phone will rely on the computer's last known address."
fi

if command -v ufw >/dev/null && ufw status 2>/dev/null | grep -q "Status: active"; then
  ufw allow "$PORT/tcp" comment "Curfew" >/dev/null && echo "Opened port $PORT in the firewall (ufw)."
fi

systemctl daemon-reload
systemctl enable curfew.service >/dev/null 2>&1
systemctl restart curfew.service
sleep 1
systemctl is-active --quiet curfew.service && echo "Curfew is running (computer ID $ID)." \
  || { echo "Curfew did not start. See: journalctl -u curfew -n 30"; exit 1; }

# Wi-Fi must be usable before anyone logs in, or the phone cannot reach the login screen.
if command -v nmcli >/dev/null; then
  nmcli -t -f NAME,TYPE connection show 2>/dev/null | grep ':802-11-wireless$' | sed 's/:802-11-wireless$//' |
  while IFS= read -r name; do
    perms="$(nmcli -g connection.permissions connection show "$name" 2>/dev/null || true)"
    flags="$(nmcli -g 802-11-wireless-security.psk-flags connection show "$name" 2>/dev/null || true)"
    if [ -n "$perms" ] || { [ -n "$flags" ] && [ "$flags" != 0 ]; }; then
      echo
      echo "WARNING: the Wi-Fi network \"$name\" is only available to one user, so this computer"
      echo "has no network at the login screen or in the other accounts. To fix it, run:"
      echo "  sudo nmcli connection modify \"$name\" connection.permissions \"\" 802-11-wireless-security.psk-flags 0"
      echo "(if it then asks for the Wi-Fi password again, enter it once from the admin account)"
    fi
  done || true      # no Wi-Fi connections at all is fine
fi

echo
if [ -t 0 ]; then
  read -r -p "Pair a phone now? [Y/n] " ans
  case "$ans" in n*|N*) echo "Later, run:  sudo curfew pair" ;; *) /usr/local/bin/curfew pair || true ;; esac
else
  echo "To pair a phone run:  sudo curfew pair"
fi
