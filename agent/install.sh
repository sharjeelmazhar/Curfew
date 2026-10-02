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

# Logging in from the phone: the login screen asks Curfew whether a phone approved this login
# before it asks for the password ("curfew pam-login"). Only the login screen is touched, not
# sudo or the text console. If the line cannot be added, passwords simply work as before.
PAMF=/etc/pam.d/gdm-password
if [ ! -f "$PAMF" ] || [ -z "$(find /usr/lib -name pam_exec.so 2>/dev/null | head -n 1)" ]; then
  echo "Note: this is not the GNOME login screen, so logging in from the phone is not available here."
elif ! grep -q 'curfew\.py pam-login' "$PAMF"; then
  tmp="$(mktemp)"
  awk '!done && /^@include[[:space:]]+common-auth/ {
         print "auth\tsufficient\tpam_exec.so quiet /usr/bin/python3 /usr/local/lib/curfew/curfew.py pam-login"; done = 1 }
       { print }' "$PAMF" > "$tmp"
  if [ "$(grep -c 'curfew\.py pam-login' "$tmp")" = 1 ] && [ "$(wc -l < "$tmp")" = "$(( $(wc -l < "$PAMF") + 1 ))" ]; then
    cp -p "$PAMF" /var/lib/curfew/gdm-password.before
    cat "$tmp" > "$PAMF"                       # keeps the owner and permissions of the file
    echo "Logging in from the phone is set up."
  else
    echo "Note: could not set up logging in from the phone (unexpected $PAMF); everything else works."
  fi
  rm -f "$tmp"
fi

# Only administrators may change the network: joining another Wi-Fi, typing a new Wi-Fi password,
# editing or deleting a connection, turning Wi-Fi or networking off. Everyone else is asked for an
# administrator's password, in the desktop and in a terminal alike. Saved networks still connect
# by themselves. Without this, a child could move the computer to a neighbour's Wi-Fi, out of the
# phone's reach.
if [ -d /etc/polkit-1/rules.d ]; then
  cat > /etc/polkit-1/rules.d/10-curfew-network.rules <<'RULES'
// Installed by Curfew: only administrators may change the network. Remove this file to undo.
polkit.addRule(function(action, subject) {
    if (action.id.indexOf("org.freedesktop.NetworkManager.") == 0 &&
        action.id != "org.freedesktop.NetworkManager.wifi.scan" &&
        subject.user != "root" && !subject.isInGroup("sudo") && !subject.isInGroup("admin")) {
        return polkit.Result.AUTH_ADMIN;
    }
});
RULES
  chmod 644 /etc/polkit-1/rules.d/10-curfew-network.rules
  echo "Network changes now need an administrator's password."
fi
if [ -d /etc/polkit-1/localauthority/50-local.d ]; then      # Ubuntu 22.04 and older
  NM=org.freedesktop.NetworkManager
  ACTIONS="$NM.network-control;$NM.enable-disable-network;$NM.enable-disable-wifi;$NM.enable-disable-wwan;$NM.enable-disable-wimax;$NM.settings.modify.own;$NM.settings.modify.system;$NM.settings.modify.hostname;$NM.settings.modify.global-dns;$NM.wifi.share.open;$NM.wifi.share.protected;$NM.reload;$NM.checkpoint-rollback;$NM.enable-disable-connectivity-check;$NM.enable-disable-statistics"
  cat > /etc/polkit-1/localauthority/50-local.d/curfew-network.pkla <<PKLA
[Curfew: changing the network needs an administrator's password]
Identity=unix-user:*
Action=$ACTIONS
ResultAny=auth_admin
ResultInactive=auth_admin
ResultActive=auth_admin

[Curfew: administrators keep control of the network]
Identity=unix-user:root;unix-group:sudo;unix-group:admin
Action=$ACTIONS
ResultAny=auth_admin
ResultInactive=auth_admin
ResultActive=yes
PKLA
  chmod 644 /etc/polkit-1/localauthority/50-local.d/curfew-network.pkla
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
