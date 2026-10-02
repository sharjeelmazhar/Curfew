# Curfew

See and control the kids' Ubuntu laptops from your Android phone, while you are at home on the same Wi-Fi.

From the phone you can:

- see whether each laptop is on, and who is using it right now
- power it off or restart it, now or after a countdown (15, 30, 45, 60 minutes, or your own time)
- lock someone's screen or log them out
- see what each person has open, including programs started from a terminal

It works whoever is logged in on the laptop, and also when nobody is logged in yet.

There are two parts: a small service that you install once on each laptop, and an app on your phone.

---

## 1. Install the service on a laptop

Do this once per laptop, from **your admin account**.

1. Copy the `agent` folder onto the laptop (a USB stick is fine).
2. Open the folder in the file manager, right-click an empty area and choose **Open in Terminal**.
3. Type this and press Enter, then type your password when asked:

   ```
   sudo ./install.sh
   ```

4. It prints `Curfew is running` and then asks **Pair a phone now?** Press Enter to say yes and continue with step 3 below, or type `n` to do it later.

If it prints a **WARNING about the Wi-Fi network**, the Wi-Fi password is saved only for your own account, so the laptop has no network in the kids' accounts or at the login screen, and the phone cannot reach it. The warning shows one command that fixes this; copy it into the terminal and press Enter.

Running `sudo ./install.sh` again later is harmless. It refreshes the files and keeps the phones you already paired.

## 2. Put the app on your phone

1. Get the file `Curfew.apk` onto the phone: send it to yourself (email, WhatsApp, Google Drive) or copy it over with a USB cable.
2. Tap the file on the phone. Android asks whether to allow installing from this source: choose **Settings**, switch on **Allow from this source**, go back and tap **Install**.
3. If Google Play Protect says it does not recognise the app, tap **More details**, then **Install anyway**. This appears because the app is not from the Play Store.

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
- **Tap a laptop:** Power off, Restart, the switch-off timer, and the list of accounts on that laptop with what each is doing (not logged in, logged in, in use right now, screen locked).
- **Tap an account:** Lock screen, Log out, and what that person has open at the moment.

The timer is silent unless you switch on **Warn them first**, which shows a notice on the laptop when the timer starts and again one minute before the end.

What the status line means:

| It says | Meaning |
|---|---|
| *Name* is using it | That person is logged in and in front of it |
| On, nobody logged in | The laptop is at the login screen |
| Off | Switched off, asleep, or not connected to the Wi-Fi |
| This phone is not on Wi-Fi / not on the home Wi-Fi | Curfew only works at home, on the same Wi-Fi as the laptops |
| On, but Curfew is not running on it | Restart the laptop; if it stays, run `sudo ./install.sh` again |
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

## After reinstalling Ubuntu on a laptop

Install the service again (step 1) and pair again (step 3). In the app the old entry shows **Needs pairing again**; remove it.

## Uninstalling

On the laptop, open a terminal in the `agent` folder and type:

```
sudo ./uninstall.sh
```

It removes the service and everything it stored. On the phone, remove the laptop in the app, or simply uninstall the app.

## If something does not work

- **The laptop shows Off but it is on.** Is the laptop on the Wi-Fi in the account that is logged in? See the Wi-Fi warning in step 1. Is your phone on the same Wi-Fi (not mobile data, not a guest network)?
- **Check the service on the laptop:** `sudo curfew status` shows whether it is running, the paired phones, the timer and the accounts.
- **The scanner says it is not ready.** The scanner is a small part of Google Play services that downloads the first time. Wait a minute with internet on and try again, or type the address and code.

## What Curfew cannot stop

It is a remote control, not a lock. A determined child can still:

- switch the laptop back on after you power it off (lock or log out their account, or set the timer again)
- turn the laptop's Wi-Fi off or pull it off the network; the app then shows **Off** and you cannot reach it, including for a timer you have not yet set (a timer that is already running still fires, it lives on the laptop)
- start the laptop from a USB stick or another system if the firmware (BIOS) allows it
- learn the admin password; anyone with it can remove Curfew

---

## For whoever maintains this

| Path | What |
|---|---|
| `agent/curfew.py` | the whole laptop side: daemon and `curfew` command, Python standard library only |
| `agent/install.sh`, `agent/uninstall.sh`, `agent/curfew.service` | installer, uninstaller, systemd unit |
| `agent/test_agent.py` | end-to-end tests against a real agent in dry-run mode: `python3 agent/test_agent.py` |
| `android/` | the app (Kotlin, Jetpack Compose). Build with `android/build.sh` after `tools/setup-toolchain.sh` |
| `API.md` | the small network protocol, for a second client later |
| `tools/` | toolchain setup, emulator helpers, demo data |

**Dry-run mode** runs the agent as a normal user on port 8787 and logs "would power off" instead of doing it:

```
python3 agent/curfew.py --dry-run serve      # in one terminal
python3 agent/curfew.py --dry-run pair       # in another: shows the QR code
```

The app's signing key (`android/curfew-release.jks`) is created by the first build and is not in git. Keep it: an APK signed with a different key cannot update the installed app.
