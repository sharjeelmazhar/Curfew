# Curfew

A parent controls the kids' Ubuntu computers from an Android phone on the home Wi-Fi. Two parts:
a root service on each computer (the "agent") and an Android app. Public repo:
https://github.com/sharjeelmazhar/Curfew. User-facing instructions are in `README.md`, the wire
protocol in `API.md`. Read both before changing behaviour.

## Layout

| Path | What |
|---|---|
| `agent/curfew.py` | the whole laptop side: daemon, `curfew` CLI, PAM helpers. One file, Python stdlib only, 3.10+ |
| `agent/debian/` | package control file and maintainer scripts. `postinst` is where all system setup lives |
| `agent/build-deb.sh`, `agent/VERSION` | builds `curfew_<version>_all.deb` |
| `agent/install.sh`, `agent/uninstall.sh` | offline wrappers: build the same .deb and install it / purge it |
| `agent/test_agent.py` | end-to-end tests against a dry-run agent (99 checks) |
| `android/` | the app: Kotlin, Jetpack Compose, minSdk 26. `Models.kt` and `Proto.kt` are plain JVM and unit-tested |
| `tools/release.sh` | publishes the laptop side (apt folder to `gh-pages`, GitHub release) |
| `tools/emu.sh`, `tools/demo/` | emulator helpers and dry-run demo agents with made-up users |
| `Curfew.apk` | the built app, committed and attached to each release |

Not in git, and must not be lost: `.apt-key/` (GPG key that signs the apt folder) and
`android/curfew-release.jks` (app signing key). `.toolchain/` (JDK, Android SDK, emulator), `dist/`,
`logs/`, `.dry/` and `Bugs or misc/` (the user's screenshots) are also ignored.

## Commands

```
python3 agent/test_agent.py              # agent tests, no root needed
android/build.sh                         # unit tests + release APK -> Curfew.apk (first run: tools/setup-toolchain.sh)
android/build.sh quick                   # APK only
tools/release.sh build                   # package + signed apt folder into dist/, publishes nothing
tools/release.sh                         # publish: needs a bumped agent/VERSION and the commit pushed
tools/emu.sh start|install|shot NAME|tap X Y|stop      # emulator (tools/setup-toolchain.sh emulator)
tools/demo/agent.sh start a              # dry-run agent on :8787 with users from tools/demo/kids.json
python3 agent/curfew.py --dry-run apps <user>          # what the phone would see as open apps
```

`android/build.sh` takes over 10 minutes on a cold cache: run it in the background.

## How it works

- **Protocol:** plain HTTP on port 787, every message HMAC-signed with a per-phone key from
  QR pairing, one-time nonces against replay. Nothing is encrypted, so no secret may travel over
  it. Status carries `caps` (`seconds`, `net`, `login`, `browsers`) so the app hides what an agent cannot do.
- **Timers:** wall-clock deadlines in `/var/lib/curfew/state.json`; `timer_loop` owns the
  shut-down countdown and the per-account internet countdowns.
- **Internet per account:** iptables/ip6tables chain `CURFEW`, rules by uid, never for an admin
  account, re-applied at start and every 30 s while anyone is blocked.
- **Login from the phone:** the `login` op writes a one-time approval to `/run/curfew/login/<user>`
  (120 s). `/etc/pam.d/gdm-password` gets `auth sufficient pam_exec.so ... curfew.py pam-login`
  before `@include common-auth`, which lets that account in without a password. An account with an
  open session is instead activated and unlocked with `loginctl`.
- **Keyring:** a second PAM line after `common-auth` (`pam_exec.so expose_authtok ... pam-remember`)
  stores a typed, accepted password in `/var/lib/curfew/passwords.json` (root, 0600). After a phone
  login the agent unlocks the account's keyring through gnome-keyring's control socket
  (`keyring_control`), running as that user. `gnome-keyring-daemon --unlock` does not work for this
  on gnome-keyring 50; the control protocol does.
- **Network lock:** polkit rule `/etc/polkit-1/rules.d/10-curfew-network.rules` returns `NO` for
  every `org.freedesktop.NetworkManager.*` action except `wifi.scan` for non-admins. It is `NO` and
  not `AUTH_ADMIN` on purpose: with a prompt, GNOME Shell's own menu freezes the whole screen for
  25 s (it waits synchronously for an answer only its own prompt can give).
- **Browser activity:** `browsers` op reads each account's Firefox (`places.sqlite`, open tabs from
  `sessionstore-backups/recovery.jsonlz4`, decoded by a pure-Python LZ4) and Chrome/Chromium
  (`History`, `keyword_search_terms`) by copying the database first, so it works while the browser
  is open. Chrome's open tabs cannot be read; "open now" there is the last 90 s of history. "Open now"
  is shown only while the browser process is actually running (`_running`), so a closed browser's
  saved session is never shown as open. History is
  the last `HISTORY_DAYS` (7) days, capped at `HISTORY_MAX` rows. In the app each page is tappable
  and opens in the phone's browser; the browser screen polls every 2 s so a new tab shows quickly.
  Private/incognito windows leave nothing on disk and are not shown.
- **App:** locked behind `BiometricPrompt` (`Lock.kt`) every time it comes to the front; fingerprint
  only when one is enrolled, screen lock otherwise. Login from the phone asks for the fingerprint
  again,
  and so does removing a computer. Computer and account names can be aliased; aliases live only on the phone.
- **Package:** installs `/usr/lib/curfew/curfew.py`, `/usr/bin/curfew`, the systemd unit, and its
  own apt source (`https://sharjeelmazhar.github.io/Curfew/apt`, flat repo on the `gh-pages` branch)
  plus keyring, so `apt upgrade` delivers new versions. `postrm` removes only what `postinst` added;
  the PAM file comes back byte for byte.

## State

- Released: **1.1.7** (laptop side and app); `main` equals the release. App versionName 1.1.
  The app shows bundled logos for common apps (`Logos.kt`, `res/drawable-nodpi/logo_*.png`).
  Each account's page has a "Shut down the computer" box above "Internet" (same timer layout).
- A sister-in-law's Galaxy Note 8 (Android 9) runs a separate APK, never from GitHub: Samsung's
  Android 9 fingerprint dialog needs an AppCompat theme, and the scanner module is fetched urgently.
  Source on the local branch `note8-local`, APK in `logs/note8/`. The user chose not to ship these
  in the generic app.
- `TIMER_MIN_SECONDS` in `Models.kt` is **15, a testing value**. The user will ask for the final
  build: set it to `5 * 60` (max stays 6 h), rebuild, release the APK. Do not change it before then.
- Installed and tested by the user on their own Ubuntu 26.04 desktop (wired) with a Redmi Note 11.
  Target machines are the family's Ubuntu 24.04 laptops and a Samsung S24 Ultra.

## Not tested, or known limits

- Ubuntu 22.04 path of the network rule (`.pkla` file) has never run.
- Wi-Fi behaviour has not been seen on a real laptop from here (the dev machine is wired).
- Open apps: Files (Nautilus) is not listed because Ubuntu keeps it running from login for desktop
  icons; the default Terminal app was reported missing and not yet diagnosed.
- A keyring whose password differs from the login password keeps asking.
- Out of reach by design: GRUB recovery mode gives root without a password, airplane mode cuts the
  phone off, booting from USB bypasses everything. A GRUB password in the installer was offered and
  not decided.
- macOS: the app would work unchanged, the agent would be a new program, and unlocking without the
  password is not possible there.

## Working here

- **No sudo in this session.** Anything needing root is tested in a sandbox or handed to the user as
  one copy-paste block. Techniques that worked without root:
  - firewall rules: `unshare --map-root-user --map-auto -n` with a dummy interface
  - PAM stack: `unshare --map-root-user -m`, bind-mount an edited `/etc/pam.d` and a fake
    `/etc/shadow`, drive libpam with ctypes
  - package install/upgrade/purge: ubuntu-base 24.04 tarball as a rootless chroot, apt with
    `-o APT::Sandbox::User=root` (overlayfs in a user namespace is refused on this machine)
  - keyring: `dbus-run-session` with a throw-away `HOME` and a **short** `XDG_RUNTIME_DIR`
    (socket paths over ~100 characters are silently truncated)
- The Curfew package is installed on this dev machine with a `testkid` standard account for testing.
  `journalctl -u curfew` and the system journal are readable here without sudo.
- App changes are checked in the emulator against a dry-run agent; `adb emu finger touch 1` after
  enrolling a fingerprint exercises the lock. Protected prompts show as black in screenshots.
- Flow the user expects: build locally, they test on real hardware, then publish. Releases go out
  with `tools/release.sh`; work happens on a branch with a pull request. The user wants `main` to
  always show the latest, and asked for the last PR to be merged for them.
- Releasing a changed app means rebuilding `Curfew.apk` with the existing `.jks`; a build with a
  different key cannot update an installed app.
- Wording in the app and README is for non-technical parents: "shut down" (never "switch off" or
  "power off"), plain sentences, no jargon.
