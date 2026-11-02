# Curfew guide

Everything the app can do, and what to do if something goes wrong. To install Curfew, see the [README](../README.md).

## Using the app

- **Home screen:** one card per laptop. A glowing green power light means it is on; a dim one means off or out of reach. The line below says who is using it. For a laptop that is off it says when the phone last saw it. If a countdown is running you see it here.
- **Tap a laptop:** the list of accounts, children first and administrators below them, with what each is doing (not logged in, logged in in the background, in use, or locked). Under that: the shut-down timer, Restart, Shut down, Screen time and Blocked websites.
- **Tap an account:** three tabs. **Activity** shows screen time, what is open, websites and screenshots. **Rules** has the daily limit, bedtime, open hours, blocked apps and websites and the switches (an administrator has no rules, so no tab). **Controls** has Lock screen, Log out, the internet for that account and the shut-down timer. **Log in without the password** stays at the top whenever that account is not on the screen.
- **The pencil** at the top of a laptop or an account gives it a name of your own ("Emma's computer", "Gaming"). The name exists only in the app; nothing changes on the laptop.

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

**Logos.** Common apps and websites show their own logo. For other websites the logo comes from the child's own browser, which already saved it: nothing outside your home is asked, so no other company learns which sites were opened. A site with no saved logo shows its first letter.

### Screen time

On a laptop's page, **Screen time** shows every account: how long it was used today, how long yesterday, how long since the laptop was turned on, and each login with its date and time ("2 Oct 2026, 8:55 PM") and when it logged out. The same box is on each account's own page. Logging out and back in adds to the same day's total; the day starts again at midnight, and only today and yesterday are kept.

Time counts while the account is on the screen and unlocked. A locked screen does not count, and neither does an account that was left logged in while someone else used the laptop after "Switch user". The laptop only counts while Curfew is installed, so the first day starts from the moment you install this version.

The phone keeps a copy of the last screen time it saw. When a laptop is shut down or away from home, its page still has **Screen time**, showing that copy and when it was saved ("Last seen 8:55 PM"). As soon as the laptop is back, the numbers are up to date again: the laptop keeps counting even when it has no internet, and the phone picks it all up next time.

### Notifications

The phone tells you, within a few seconds, when:

- someone logs in on a laptop ("James logged in · On family-pc at 8:55 PM"), admin accounts included. A login you allowed from the phone with **Log in without the password** is not announced
- someone tries to cut the laptop off the network: airplane mode, turning the Wi-Fi off, disconnecting, a new Wi-Fi password, another network ("James tried to turn off the Wi-Fi · It was not allowed")
- an account's daily screen time runs out (see **Daily limit** below), if you chose to be told

This works with the app closed too: Curfew stays in touch with the laptops and the laptop tells the phone the moment something happens, the way a chat app gets its messages. Android then shows a quiet notice, **Watching 1 computer**, which you can hide by holding it. The first time, tap **Allow notifications** and **Allow** (to run in the background) on the home screen. On Xiaomi, Redmi and POCO phones also turn on **Autostart** for Curfew: the button is under the bell, **If notifications come late**.

The **bell** at the top of the home screen keeps every notification of the last 30 days, also the ones you swiped away or missed, with a number for the new ones. Tap one to go to that account. **Clear all** empties the list. Below the list you choose what you want to be told about.

If your phone was away from home, or the laptop had no Wi-Fi, you are told when both are home again; the notification shows when it happened, not when you were told. When a laptop shuts down, restarts or goes to sleep, the app shows that at once ("Shut down", "Asleep").

### Screenshots

On a child's page, **Screenshots** takes a picture of their screen when you ask, or by itself every 15 seconds, or every 1, 5 or 15 minutes. You can have it take them only while Discord or Minecraft is open. It works only for children's accounts, never for an administrator's.

Your child is not told and sees nothing when one is taken. These are only pictures Curfew takes; Curfew never reads screenshots your child takes.

Screenshots stay on the computer for 7 days. Your phone keeps a copy too, encrypted and never in your photos, so you can still look at them while the computer is off. **How much to keep** in the app's Settings chooses the size on the phone (Off, 100 MB, 300 MB or 1 GB); the oldest are removed first.

### Notices on the child's screen

**Show notices on the screen** on a child's page is off unless you turn it on. When it is on, your child sees a notice when a blocked app is closed, when Wi-Fi is turned back on, and for similar things Curfew does by itself. Warnings before a shut-down, or before the internet or screen time ends, have their own **Warn them first** choice.

### Daily limit

On an account's page, **No daily limit** opens the limit: how long that account may be used each day (30 minutes to 12 hours), and what happens when the time is up: **Shut down the computer**, or **Log them out**. Time counts the same way as screen time: on the screen and unlocked. With **Warn them first** they get a notice 5 minutes and 1 minute before; when the time is up they get a notice and one more minute to save their work. If someone else is on the screen by then (after "Switch user"), only their account is logged out instead of shutting down. **Tell me when the time is up** sends you a notification.

**Count together with** joins the same child's accounts, on one laptop or several ("Priya Class" and "Priya Gaming"): the time on all of them adds up to one limit, and whichever one they are on when it runs out is the one that ends.

The limit lives on the laptop, so it works with your phone away. Only for a limit counted together does the laptop need the phone at home now and then, to hear how long the other accounts were used. Logging in again the same day ends again after a minute (and you are told). The account's page shows how much is left, and **More time today** adds 15 minutes, 30 minutes or an hour, for today only. Admin accounts cannot have a limit.

### Bedtime

On an account's page, **Bedtime** sets the hours each night that account may not use the computer. At bedtime it logs the account out or shuts the computer down, and again if they log back in. It works with your phone away. **Let them stay tonight** gives one exception.

### Blocked apps

On an account's page, **Blocked apps** lists apps that child may not open, such as Minecraft or a game launcher. Choose from the apps seen on that account or common ones, or type a name. If they open one, it closes within a few seconds and you get a notification (at most once a minute per app). They see why only if **Show notices on the screen** is on for that account. This works with your phone away and after a restart. Admin accounts cannot be limited, and the same app stays usable on your own account.

It goes by the program's name. **If someone renames a program or copies it under another name, it gets past this.** It never closes Windows, the desktop or Curfew, and it closes a browser only if you choose that browser; to keep one site away use blocked websites. The app ends suddenly, so anything not saved in it is lost. Checking every 3 seconds uses too little of the computer to notice.

### Open hours

For when you are out: on an account's page, **Open hours** sets times of the week, Monday to Sunday, when that child can start the computer, click their name and be let in with no password. On Windows the login screen shows it as **name (open now)**; the plain name beside it still asks for the password.

When the time ends the account is logged out or the computer shuts down, whenever they began. If they never logged in, the computer is left as it is. If the computer is off when you set the times, the change waits on your phone and arrives when it comes back on, as long as your phone can still reach the internet or the home Wi-Fi by then. Anyone at the computer during those times can click the name, so choose times when that is fine.

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

## Using it away from home

Nothing to set up: once a phone has reached a computer at home, it reaches it from anywhere too, over mobile data or another Wi-Fi. Each computer keeps a small connection to Curfew's relay, and everything on it is sealed with the key from pairing, so the relay can pass messages on but cannot read or change them. At home the phone still talks to the computer directly, and that keeps working if the relay is down. Away from home the app asks a little less often, about every 15 seconds. Notifications arrive within seconds and the phone keeps no connection of its own open, which saves battery; this needs Google Play on the phone (without it, the app stays connected on its own as before).

### Using Tailscale instead

You don't need Tailscale. If you already use it, it keeps working: install it on the computer and the phone, then remove the computer in the app, run `sudo curfew pair`, and add it again with **type the address**, using its `100.` address.
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
- move the clock forward in the BIOS while the computer has no internet and your phone cannot reach it. Curfew goes by the real time, not the computer's clock: it asks a large website (Google, or Cloudflare if Google does not answer) every few minutes, and sends nothing about you or the computer, and otherwise your phone tells it the time whenever it talks to the computer. If the clock is wrong by over 5 minutes you get a notice. Moving the clock back does not help them even with no internet, because Curfew remembers the last real time it saw

With only the password of their own standard account they cannot stop, change or remove Curfew, read its keys, or turn their internet back on.

---

## Blocked websites and private windows

On a computer's page, **Blocked websites** lists the sites children cannot open. Type a site (for
example `youtube.com`), or long-press a page in a browser's history and choose **Block**. A site is
blocked with everything under it (`youtube.com` also blocks `www.youtube.com` and `m.youtube.com`). Each
child's page has its own **Blocked websites** too, for sites only that account may not open.
Some services use several addresses (YouTube also uses `youtu.be`); add each one. The switch
**Block private windows** turns off private and incognito windows for children, so every page they
open stays in the history. Administrators are never limited. Airplane mode is hidden in children's accounts (top-right menu and Settings) and on the login screen; children cannot switch Bluetooth on or off either, because it is the same switch. On a computer with Wi-Fi, children's accounts also reach the
internet over Wi-Fi only, not through a network cable. A computer without Wi-Fi keeps using its cable.

**On Windows website blocking is thinner.** It tells Windows itself to find nothing for a blocked site and every address under it, and adds the settings of Edge, Chrome, Brave and Firefox. That works for programs that ask Windows where a site is, but only while a child is on the screen, not per account. A browser or app that looks up addresses on its own (private DNS, a built-in VPN, Tor) gets past it. Firefox, Chrome and Edge private windows are blocked (Brave gets the same settings, not yet seen working); a browser Curfew does not know about is not.

## Which phones and computers

- Phone: Android 8.0 or newer (one app for every phone, Samsung's older ones included). No iPhone.
- Computer: Ubuntu 20.04 or newer, Debian 12 or 13, Fedora, Arch, Windows 10 or 11.
- To pair, the phone and the computer must be on the same home Wi-Fi (a guest Wi-Fi usually keeps them apart). After that the phone reaches the computer from anywhere.
