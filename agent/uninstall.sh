#!/usr/bin/env bash
# Removes Curfew and everything it stored, including the paired phones:  sudo ./uninstall.sh
# (the same as: sudo apt purge curfew)
set -u
[ "$(id -u)" = 0 ] || { echo "Please run this with sudo:  sudo ./uninstall.sh"; exit 1; }
if dpkg -s curfew >/dev/null 2>&1; then
  apt-get purge -y curfew
else                                            # installed from the folder, before there was a package
  systemctl disable --now curfew.service >/dev/null 2>&1
  [ -f /etc/pam.d/gdm-password ] && sed -i '/curfew\.py pam-login/d' /etc/pam.d/gdm-password
  for t in iptables ip6tables; do
    command -v "$t" >/dev/null || continue
    while "$t" -w 5 -D OUTPUT -j CURFEW 2>/dev/null; do :; done
    "$t" -w 5 -F CURFEW 2>/dev/null; "$t" -w 5 -X CURFEW 2>/dev/null
  done
  rm -f /etc/systemd/system/curfew.service /etc/avahi/services/curfew.service /usr/local/bin/curfew
  rm -f /etc/polkit-1/rules.d/10-curfew-network.rules /etc/polkit-1/localauthority/50-local.d/curfew-network.pkla
  rm -rf /usr/local/lib/curfew /var/lib/curfew /run/curfew
  systemctl daemon-reload
  if command -v ufw >/dev/null && ufw status 2>/dev/null | grep -q "787/tcp"; then
    ufw delete allow 787/tcp >/dev/null 2>&1
  fi
fi
echo "Curfew has been removed from this computer. In the phone app, open this computer and tap Remove this computer."
