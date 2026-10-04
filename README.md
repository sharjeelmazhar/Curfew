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
- see how long each account was used today and yesterday, and when it logged in and out

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

- **Home screen:** one card per laptop. A glowing green power light means it is on; a dim one means off or out of reach. The line below says who is using it. For a laptop that is off it says when the phone last saw it. If a countdown is running you see it here.
- **Tap a laptop:** Shut down, Restart, the shut-down timer, and the list of accounts on that laptop with what each is doing (not logged in, logged in in the background, in use right now, screen locked). After "Switch user", both accounts are logged in and both are shown: the one on the screen has a green dot, the one left in the background an orange dot. Under each name you see since when it is logged in and how long it was used today. **Screen time** at the bottom opens the times for every account.
- **Tap an account:** its daily limit, Log in without the password, Lock screen, Log out, a shut-down timer for the computer, the internet for that account, and what that person has open at the moment.
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

What it cannot show: **private or incognito windows**. Browsers write nothing to disk for those, so there is nothing to read; switch them off with **Block private windows** (below). Curfew keeps its own copy of a child's history for 7 days, so clearing the history in the browser does not hide it from the phone. Open tabs are read exactly for Firefox; for Chrome they are shown as "the last few minutes", because Chrome does not let anything read its open tabs. Other browsers (Brave, Edge, Tor) are not read.

The list travels over the home Wi-Fi signed but **not encrypted**, the same as everything else Curfew sends, so treat it as private to the home network.

### Screen time

On a laptop's page, **Screen time** shows every account: how long it was used today, how long yesterday, how long since the laptop was turned on, and each login with its date and time ("2 Oct 2026, 8:55 PM") and when it logged out. The same box is on each account's own page. Logging out and back in adds to the same day's total; the day starts again at midnight, and only today and yesterday are kept.

Time counts while the account is on the screen and unlocked. A locked screen does not count, and neither does an account that was left logged in while someone else used the laptop after "Switch user". The laptop only counts while Curfew is installed, so the first day starts from the moment you install this version.

The phone keeps a copy of the last screen time it saw. When a laptop is shut down or away from home, its page still has **Screen time**, showing that copy and when it was saved ("Last seen 8:55 PM"). As soon as the laptop is back, the numbers are up to date again: the laptop keeps counting even when it has no internet, and the phone picks it all up next time.

### Notifications

The phone tells you, within a few seconds, when:

- someone logs in on a laptop ("Ali logged in · On kids-laptop at 8:55 PM"), admin accounts included. A login you allowed from the phone with **Log in without the password** is not announced
- someone tries to cut the laptop off the network: airplane mode, turning the Wi-Fi off, disconnecting, a new Wi-Fi password, another network ("Ali tried to turn off the Wi-Fi · It was not allowed")
- an account's daily screen time runs out (see **Daily limit** below), if you chose to be told

This works with the app closed too: Curfew stays in touch with the laptops and the laptop tells the phone the moment something happens, the way a chat app gets its messages. Android then shows a quiet notice, **Watching 1 computer**, which you can hide by holding it. The first time, tap **Allow notifications** and **Allow** (to run in the background) on the home screen. On Xiaomi, Redmi and POCO phones also turn on **Autostart** for Curfew: the button is under the bell, **If notifications come late**.

The **bell** at the top of the home screen keeps every notification of the last 30 days, also the ones you swiped away or missed, with a number for the new ones. Tap one to go to that account. **Clear all** empties the list. Below the list you choose what you want to be told about.

If your phone was away from home, or the laptop had no Wi-Fi, you are told when both are home again; the notification shows when it happened, not when you were told. When a laptop shuts down, restarts or goes to sleep, the app shows that at once ("Shut down", "Asleep").

### Daily limit

On an account's page, **No daily limit** opens the limit: how long that account may be used each day (30 minutes to 12 hours), and what happens when the time is up: **Shut down the computer**, or **Log them out**. Time counts the same way as screen time: on the screen and unlocked. With **Warn them first** they get a notice 5 minutes and 1 minute before; when the time is up they get a notice and one more minute to save their work. If someone else is on the screen by then (after "Switch user"), only their account is logged out instead of shutting down. **Tell me when the time is up** sends you a notification.

**Count together with** joins the same child's accounts, on one laptop or several ("Fatima Class" and "Fatima Gaming"): the time on all of them adds up to one limit, and whichever one they are on when it runs out is the one that ends.

The limit lives on the laptop, so it works with your phone away. Only for a limit counted together does the laptop need the phone at home now and then, to hear how long the other accounts were used. Logging in again the same day ends again after a minute (and you are told). The account's page shows how much is left, and **More time today** adds 15 minutes, 30 minutes or an hour, for today only. Admin accounts cannot have a limit.

### Internet for one account

On an account's page, **Internet** turns the internet off for that account only: now, or after a countdown. The laptop itself stays connected (so the app keeps working) and other accounts are not affected. It stays off across restarts until you turn it back on. Admin accounts can never be turned off. Wi-Fi or cable makes no difference.

What the status line means:

| It says | Meaning |
|---|---|
| *Name* is using it | That person is logged in and in front of it |
| On, nobody logged in | The laptop is at the login screen |
| Off | Shut down, asleep, or not connected to the Wi-Fi |
| Shut down / Asleep / Restarting… | The laptop said so as it went |
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
- cut the laptop off from the network with a Wi-Fi switch on the laptop's case, or by pulling the cable on a desktop; the app then shows **Off** and you cannot reach it, including for a timer you have not yet set (a timer that is already running still fires, it lives on the laptop). **Airplane mode no longer works for this:** in a standard account its button is gone from the menu at the top right (from the next login after installing), and if it is switched on any other way (the Settings app, a key on the keyboard, the login screen) the laptop turns its Wi-Fi back on within a moment, tells them so and tells your phone; after three tries in two minutes it locks the screen. In an admin account airplane mode works as usual. They can also no longer join another Wi-Fi, type a new Wi-Fi password, forget a network, change the address or DNS, or turn Wi-Fi off: after installing, only an admin account can do any of that
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

## Blocked websites and private windows

On a computer's page, **Blocked websites** lists the sites children cannot open. Type a site (for
example `youtube.com`), or long-press a page in a browser's history and choose **Block**. A site is
blocked with everything under it (`youtube.com` also blocks `www.youtube.com` and `m.youtube.com`). Each
child's page has its own **Blocked websites** too, for sites only that account may not open.
Some services use several addresses (YouTube also uses `youtu.be`); add each one. The switch
**Block private windows** turns off private and incognito windows for children, so every page they
open stays in the history. Administrators are never limited. Airplane mode is hidden in children's accounts (top-right menu and Settings) and on the login screen; children cannot switch Bluetooth on or off either, because it is the same switch. Children's accounts also reach the
internet over Wi-Fi only, not through a network cable.

## Which phones and computers

- Phone: Android 8.0 or newer (one app for every phone, Samsung's older ones included). No iPhone.
- Computer: Ubuntu 24.04 or newer with the standard desktop. 22.04 may work but is not tested.
- The phone and the computers must be on the same home Wi-Fi (a guest Wi-Fi usually keeps them apart).
