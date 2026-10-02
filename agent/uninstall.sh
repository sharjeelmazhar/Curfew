#!/usr/bin/env bash
# Removes the Curfew agent and everything it installed. Run:  sudo ./uninstall.sh
set -u
[ "$(id -u)" = 0 ] || { echo "Please run this with sudo:  sudo ./uninstall.sh"; exit 1; }
systemctl disable --now curfew.service >/dev/null 2>&1
rm -f /etc/systemd/system/curfew.service /etc/avahi/services/curfew.service /usr/local/bin/curfew
rm -rf /usr/local/lib/curfew /var/lib/curfew
systemctl daemon-reload
if command -v ufw >/dev/null && ufw status 2>/dev/null | grep -q "787/tcp"; then
  ufw delete allow 787/tcp >/dev/null 2>&1
fi
echo "Curfew has been removed from this computer. In the phone app, open this computer and tap Remove this computer."
