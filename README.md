# Curfew

See and control the kids' Ubuntu laptops from your Android phone, while you are at home on the same Wi-Fi.

From the phone you can:

- see whether each laptop is on, and who is using it right now
- shut it down or restart it, now or after a countdown (15, 30, 45, 60 minutes, or your own time up to 6 hours)
- lock someone's screen or log them out
- log someone in without typing their password in front of them
- turn the internet off for one account, now or after a countdown, while the computer stays on
- see what each person has open, including programs started from a terminal (common apps like Chrome, Firefox, Discord, Minecraft and Spotify show their own logo)
- see which websites each person has visited, and what they searched for, in each browser

It works whoever is logged in on the laptop, and also when nobody is logged in yet.

There are two parts: a small service that you install once on each laptop, and an app on your phone.

---

## 1. Install the service on a laptop

Do this once per laptop, from **your admin account**. Open a terminal (Ctrl+Alt+T), paste these two lines one at a time, and type your password when asked:

```
wget https://github.com/sharjeelmazhar/Curfew/releases/latest/download/curfew.deb
sudo apt install ./curfew.deb
```

It prints `Curfew is running`. Continue with step 3 below to pair a phone.

If it prints a **WARNING about the Wi-Fi network**, the Wi-Fi password is saved only for your own account, so the laptop has no network in the kids' accounts or at the login screen, and the phone cannot reach it. The warning shows one command that fixes this; copy it into the terminal and press Enter.

### Getting a newer version later

Installing the package also tells the laptop where new versions come from. To update:

```
sudo apt update
sudo apt upgrade
```

The new version is in use as soon as that finishes; there is no need to uninstall anything or restart, and paired phones and running timers are kept.

A laptop where an earlier version was installed from the `agent` folder with `install.sh` does not need uninstalling either: install the package as above and it takes over, keeping the paired phones.

### Without the internet

Copy the `agent` folder onto the laptop (a USB stick is fine), open it in the file manager, right-click an empty area, choose **Open in Terminal** and run `sudo ./install.sh`. It builds and installs the same package.

## 2. Put the app on your phone

1. Get the file `Curfew.apk` onto the phone: send it to yourself (email, WhatsApp, Google Drive) or copy it over with a USB cable.
2. Tap the file on the phone. Android asks whether to allow installing from this source: choose **Settings**, switch on **Allow from this source**, go back and tap **Install**.
3. If Google Play Protect says it does not recognise the app, tap **More details**, then **Install anyway**. This appears because the app is not from the Play Store.

The app asks for your fingerprint every time it is opened, so a child who picks up your unlocked phone cannot use it. It uses the fingerprint already set up on the phone; there is nothing to configure. (A phone without a fingerprint asks for its screen lock instead.) It asks again before a computer is removed from the app, so someone you hand the open app to cannot remove one.

## 3. Pair the phone with a laptop

1. On the laptop, in a terminal, type:

   ```
   sudo curfew pair
   ```

   A square code (QR code) appears.
2. On the phone open **Curfew**, tap **Add computer**, then **Scan the code**, and point the camera at the square code.
3. The laptop says `Paired with "<your phone>"` and the laptop appears in the app.

The code works once, for one phone, and expires after two minutes, so a photo of it is useless afterwards.

**If scanning does not work:** in the app tap **Type the address and code instead** and type the two lines shown under the square code (Address and Code).

**A second phone** (for example their mother's Android phone): run `sudo curfew pair` again and scan with that phone. Each phone gets its own key.

## Using the app

- **Home screen:** one card per laptop. A glowing green power light means it is on; a dim one means off or out of reach. The line below says who is using it. If a countdown is running you see it here.
- **Tap a laptop:** Shut down, Restart, the shut-down timer, and the list of accounts on that laptop with what each is doing (not logged in, logged in in the background, in use right now, screen locked). After "Switch user", both accounts are logged in and both are shown.
- **Tap an account:** Log in without the password, Lock screen, Log out, the internet for that account, and what that person has open at the moment.
- **The pencil** at the top of a laptop or an account gives it a name of your own ("Fatima's computer", "Gaming"). The name exists only in the app; nothing changes on the laptop.

The timers are silent unless you turn on **Warn them first**, which shows a notice on the laptop when the timer starts and again one minute before the end.

### Logging someone in from the phone

Open the account in the app and tap **Log in without the password**; the phone asks for your fingerprint. Then, on the laptop, they click their name at the login screen and are let in (if it is already asking for the password, they press Enter). The approval works once and only for two minutes. If the account is already logged in and locked, its screen simply unlocks.

Nothing is stored on the phone and no password travels over the network.

**The keyring.** Ubuntu keeps an account's saved passwords (browsers and the like) in a keyring that is locked with the login password. So that a login from the phone does not end in a question about the keyring, the laptop remembers each account's login password the next time it is typed at the login screen, in a file only the administrator can read (`/var/lib/curfew/passwords.json`), and uses it to unlock the keyring after a login from the phone. This means:

- log in to each account **once by typing its password** after installing Curfew; from then on the phone can do it without any question
- if you change an account's password, type the new one once at the login screen
- the file is deleted when Curfew is removed

This needs the standard Ubuntu (GNOME) login screen.

### Websites they have visited

On an account's page, under **Websites**, you see each browser the person has used — Firefox, Google Chrome, Chromium. Tap one to see the pages it has open right now and every page it has visited in the last 7 days, newest first. Tap any page to open it in your phone's own browser and see what it was. Searches show the words they typed ("Searched 'how to beat the ender dragon'"), and the search box at the top lets you look for a word across everything — type "netflix" or "minecraft" and only the matching pages stay.

This reads the browser's own history on the laptop, so it works even when the person is not logged in at that moment, as long as the laptop is on and at home. Clearing the browser history clears it here too.

What it cannot show: **private or incognito windows**. Browsers write nothing to disk for those, so there is nothing to read — the honest way to handle them is to switch private windows off, which can be added later. Open tabs are read exactly for Firefox; for Chrome they are shown as "the last few minutes", because Chrome does not let anything read its open tabs. Other browsers (Brave, Edge, Tor) are not read.

The list travels over the home Wi-Fi signed but **not encrypted**, the same as everything else Curfew sends, so treat it as private to the home network.

### Internet for one account

On an account's page, **Internet** turns the internet off for that account only: now, or after a countdown. The laptop itself stays connected (so the app keeps working) and other accounts are not affected. It stays off across restarts until you turn it back on. Admin accounts can never be turned off. Wi-Fi or cable makes no difference.

What the status line means:

| It says | Meaning |
|---|---|
| *Name* is using it | That person is logged in and in front of it |
| On, nobody logged in | The laptop is at the login screen |
| Off | Shut down, asleep, or not connected to the Wi-Fi |
| This phone is not on Wi-Fi / not on the home Wi-Fi | Curfew only works at home, on the same Wi-Fi as the laptops |
| On, but Curfew is not running on it | Restart the laptop; if it stays, run `sudo apt install --reinstall curfew` |
| Needs pairing again | The laptop was reinstalled. Remove it in the app and pair it again |

## Removing things

**Remove a laptop from your phone:** open the laptop in the app, scroll down, tap **Remove this computer**. It disappears at once. You do not need to be at home; the phone tells the laptop quietly the next time it can reach it.

**Remove a phone from a laptop** (a lost or replaced phone): on the laptop type

```
sudo curfew phones
```

to see the list, then

```
sudo curfew unpair 2
```

using the number (or the name) from the list. That phone can no longer control the laptop, and the laptop disappears from that phone's app the next time it is opened at home.

## Changing the Wi-Fi later

After installing, only an admin account can change the network. In the other accounts the network switches and settings simply do nothing; they do not ask for a password, so your password is never typed in a child's account. To join a new Wi-Fi, log in to your admin account and connect as usual; the network is then available in every account. To give everyone that freedom back: `sudo rm /etc/polkit-1/rules.d/10-curfew-network.rules`.

## After reinstalling Ubuntu on a laptop

Install the service again (step 1) and pair again (step 3). In the app the old entry shows **Needs pairing again**; remove it.

## Uninstalling

On the laptop:

```
sudo apt purge curfew
```

It removes the service and everything it set up or stored, and the laptop is as it was before: the login screen asks for passwords as usual, anyone may change the Wi-Fi again, and no account has its internet blocked. On the phone, remove the laptop in the app, or simply uninstall the app.

## If something does not work

- **The laptop shows Off but it is on.** Is the laptop on the Wi-Fi in the account that is logged in? See the Wi-Fi warning in step 1. Is your phone on the same Wi-Fi (not mobile data, not a guest network)?
- **Check the service on the laptop:** `sudo curfew status` shows whether it is running, the paired phones, the timer and the accounts.
- **The scanner says it is not ready.** The scanner is a small part of Google Play services that downloads the first time. Wait a minute with internet on and try again, or type the address and code.

## What Curfew cannot stop

It is a remote control, not a lock. A determined child can still:

- turn the laptop back on after you shut it down (lock or log out their account, or set the timer again)
- cut the laptop off from the network with airplane mode, or by pulling the cable on a desktop; the app then shows **Off** and you cannot reach it, including for a timer you have not yet set (a timer that is already running still fires, it lives on the laptop). They can no longer join another Wi-Fi, type a new Wi-Fi password, forget a network, change the address or DNS, or turn Wi-Fi off: after installing, only an admin account can do any of that
- start the laptop from a USB stick or another system if the firmware (BIOS) allows it
- learn the admin password; anyone with it can remove Curfew
- become administrator without any password by starting the laptop in recovery mode (hold Shift or press Esc while it starts). Only a password on the boot menu (GRUB) stops this

With only the password of their own standard account they cannot stop, change or remove Curfew, read its keys, or turn their internet back on.

---

## For whoever maintains this

| Path | What |
|---|---|
| `agent/curfew.py` | the whole laptop side: daemon and `curfew` command, Python standard library only |
| `agent/debian/`, `agent/build-deb.sh`, `agent/VERSION` | the package: what it sets up after installing (`postinst`) and undoes when removed (`postrm`), and the script that builds it |
| `agent/install.sh`, `agent/uninstall.sh`, `agent/curfew.service` | build-and-install from the folder, remove, systemd unit |
| `tools/release.sh` | publishes a new version of the laptop side (see below) |
| `agent/test_agent.py` | end-to-end tests against a real agent in dry-run mode: `python3 agent/test_agent.py` |
| `android/` | the app (Kotlin, Jetpack Compose). Build with `android/build.sh` after `tools/setup-toolchain.sh` |
| `API.md` | the small network protocol, for a second client later |
| `tools/` | toolchain setup, emulator helpers, demo data |

**Dry-run mode** runs the agent as a normal user on port 8787 and logs "would power off" instead of doing it
(the internet block and the login approval are logged the same way):

```
python3 agent/curfew.py --dry-run serve      # in one terminal
python3 agent/curfew.py --dry-run pair       # in another: shows the QR code
```

**Releasing a new version of the laptop side:** raise the number in `agent/VERSION`, commit, then run `tools/release.sh`. It builds the package, publishes the signed apt folder to the `gh-pages` branch (served at `https://sharjeelmazhar.github.io/Curfew/apt`, which is what `sudo apt update` reads on the laptops) and creates a GitHub release with the package and the app. `tools/release.sh build` only builds into `dist/`.

The apt folder is signed with the key in `.apt-key/`, which is not in git. Keep a copy of that folder: without it no update can be published that the laptops will accept, and anyone who has it can publish software that the laptops install as administrator.

The app's signing key (`android/curfew-release.jks`) is created by the first build and is not in git. Keep it: an APK signed with a different key cannot update the installed app.
