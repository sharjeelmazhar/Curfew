#!/usr/bin/env python3
"""Curfew agent: lets a paired phone see and control this computer over the LAN.

One file, standard library only, Python 3.10+. It is both the root daemon
("curfew serve", started by systemd) and the admin command ("sudo curfew pair",
"sudo curfew phones", "sudo curfew unpair NAME", "curfew status"), and the
helper that the login screen asks about a login approved from a phone
("curfew pam-login", see debian/postinst).

It performs only the fixed actions in OPS below; there is no way to make it run
an arbitrary command. The wire protocol is described in API.md.
"""
import contextlib
import fcntl
import glob
import grp
import hashlib
import hmac
import json
import os
import pwd
import re
import secrets
import shutil
import socket
import sqlite3
import struct
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PROTO = 1
PORT = 787                      # below 1024: a standard user cannot bind it
STATE_DIR = "/var/lib/curfew"
RUN_DIR = "/run/curfew"         # gone after a restart: approved logins
DRY = False                     # dry-run: log "would ..." instead of acting
PAIR_SECONDS = 120
PAIR_MAX_FAILS = 5
NONCE_SECONDS = 60
NONCES_PER_ADDRESS = 32         # so one address cannot push out another's nonces
CONNECTIONS_PER_ADDRESS = 8
LOGIN_SECONDS = 120             # how long an approved login waits to be used
PAM_FILE = "/etc/pam.d/gdm-password"
FW_CHAIN = "CURFEW"
MAX_BODY = 16384
CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"   # no 0/O/1/I
TOKEN_RE = re.compile(r"^[A-Za-z0-9]{8,64}$")
MAC_RE = re.compile(r"^[0-9a-f]{64}$")


def log(msg):
    print(("[dry-run] " if DRY else "") + msg, file=sys.stderr, flush=True)


# ---------------------------------------------------------------- state file

@contextlib.contextmanager
def state(write=False):
    """Load the state under an exclusive lock; save it on exit if write=True."""
    os.makedirs(STATE_DIR, mode=0o700, exist_ok=True)
    with open(os.path.join(STATE_DIR, "lock"), "a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        path = os.path.join(STATE_DIR, "state.json")
        try:
            with open(path) as f:
                st = json.load(f)
        except (OSError, ValueError):
            st, write = {}, True
        if "id" not in st:
            st["id"], write = secrets.token_hex(8), True
        for k in ("phones", "removed", "net"):
            st.setdefault(k, {})
        for k in ("pairing", "timer"):
            st.setdefault(k, None)
        yield st
        if write:
            tmp = path + ".tmp"
            fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, "w") as f:
                json.dump(st, f, indent=1)
                f.flush()
                os.fsync(f.fileno())
            os.replace(tmp, path)


# -------------------------------------------------------------------- crypto

def mac(key, *parts):
    """HMAC-SHA256 over the parts joined by newlines, as lowercase hex."""
    return hmac.new(key, "\n".join(parts).encode(), hashlib.sha256).hexdigest()


def derive_key(code, snonce, cnonce, phone_id):
    msg = "\n".join(("key", snonce, cnonce, phone_id)).encode()
    return hmac.new(code.encode(), msg, hashlib.sha256).digest()


def clean_code(code):
    return re.sub(r"[^A-Z0-9]", "", str(code).upper())


class Nonces:
    """One-time numbers handed out by /v1/hello. Each is valid once, briefly."""

    def __init__(self):
        self.live, self.lock = {}, threading.Lock()

    def new(self, who=""):
        n, now = secrets.token_hex(16), time.monotonic()
        with self.lock:
            for k in [k for k, (exp, _) in self.live.items() if exp < now]:
                del self.live[k]
            mine = [k for k, (_, w) in self.live.items() if w == who]
            for k in mine[:max(0, len(mine) - NONCES_PER_ADDRESS + 1)]:
                del self.live[k]            # oldest first: dicts keep insertion order
            while len(self.live) >= 4096:
                del self.live[next(iter(self.live))]
            self.live[n] = (now + NONCE_SECONDS, who)
        return n

    def use(self, n):
        with self.lock:
            exp, _ = self.live.pop(n, (None, None))
        return exp is not None and exp >= time.monotonic()


NONCES = Nonces()


# ------------------------------------------------------------ users/sessions

def run(argv, timeout=8):
    try:
        return subprocess.run(argv, capture_output=True, text=True, timeout=timeout).stdout
    except (OSError, subprocess.SubprocessError):
        return ""


def act(what, argv):
    """Perform one of the fixed actions (or only log it in dry-run mode)."""
    if DRY:
        log("would %s  (%s)" % (what, " ".join(argv)))
        return True
    log(what)
    try:
        return subprocess.run(argv, capture_output=True, timeout=15).returncode == 0
    except (OSError, subprocess.SubprocessError):
        return False


def human_users():
    admins = set()
    for g in ("sudo", "admin"):
        with contextlib.suppress(KeyError):
            gr = grp.getgrnam(g)
            admins.update(gr.gr_mem)
            admins.update(p.pw_name for p in pwd.getpwall() if p.pw_gid == gr.gr_gid)
    out = []
    for p in pwd.getpwall():
        if 1000 <= p.pw_uid < 60000 and not p.pw_shell.endswith(("nologin", "false")):
            out.append({"name": p.pw_name, "uid": p.pw_uid, "home": p.pw_dir,
                        "full": p.pw_gecos.split(",")[0], "admin": p.pw_name in admins})
    return sorted(out, key=lambda u: u["uid"])


def sessions():
    """Real user sessions from logind. The login screen's own session (class
    'greeter') and systemd's per-user manager sessions are left out."""
    ids = [ln.split()[0] for ln in run(["loginctl", "list-sessions", "--no-legend"]).splitlines() if ln.split()]
    if not ids:
        return []
    out = run(["loginctl", "show-session", "-p", "Id", "-p", "Name", "-p", "Class", "-p", "Type",
               "-p", "Active", "-p", "State", "-p", "LockedHint", "-p", "Remote"] + ids)
    res = []
    for block in out.split("\n\n"):
        s = dict(ln.split("=", 1) for ln in block.splitlines() if "=" in ln)
        if s.get("Class") != "user" or s.get("State") == "closing" or not s.get("Name"):
            continue
        res.append({"id": s.get("Id", ""), "user": s["Name"],
                    "graphical": s.get("Type") in ("x11", "wayland", "mir"),
                    "active": s.get("Active") == "yes" and s.get("Remote") != "yes",
                    "locked": s.get("LockedHint") == "yes"})
    return res


RANK = {"none": 0, "logged_in": 1, "locked": 2, "active": 3}


def user_states():
    states = {}
    for s in sessions():
        st = "logged_in"
        if s["active"]:
            st = "locked" if s["locked"] else "active"
        if RANK[st] > RANK[states.get(s["user"], "none")]:
            states[s["user"]] = st
    return states


def os_name():
    with contextlib.suppress(OSError):
        with open("/etc/os-release") as f:
            for ln in f:
                if ln.startswith("PRETTY_NAME="):
                    return ln.split("=", 1)[1].strip().strip('"')
    return "Linux"


# ---------------------------------------------------------------- open apps

SHELLS = {"bash", "zsh", "sh", "dash", "fish", "ksh", "tcsh", "csh", "nu", "xonsh", "login",
          "tmux", "tmux: server", "tmux: client", "screen", "script", "sudo", "su", "ssh-agent", "gpg-agent",
          "dbus-launch", "dbus-daemon", "wl-copy", "wl-paste", "xclip", "less", "man", "pager"}
WRAPPERS = {"env", "nohup", "setsid", "sudo", "time", "nice", "ionice", "exec", "prime-run",
            "gamemoderun", "mangohud", "stdbuf", "torsocks", "firejail"}
INTERPRETERS = {"python": "Python", "python3": "Python", "node": "Node.js", "ruby": "Ruby", "perl": "Perl",
                "bash": "script", "sh": "script", "wine": "Wine", "wine64": "Wine", "mono": "Mono"}
NOISE = re.compile(r"^(org\.freedesktop\.|org\.gnome\.Shell|org\.gnome\.SettingsDaemon|org\.gnome\.Evolution|"
                   r"org\.gnome\.OnlineAccounts|org\.gnome\.ScreenSaver|org\.gnome\.Software|org\.gnome\.Tecla|"
                   r"xdg-desktop-portal|snapd-desktop-integration|snap-store|update-notifier|update-manager|"
                   r"im-launch|ibus|gnome-initial-setup|ubuntu-report|org\.gtk\.|ca\.desrt\.dconf|"
                   r"org\.gnome\.Identity|org\.gnome\.keyring|org\.a11y|gnome-keyring|tracker|localsearch|"
                   r"org\.gnome\.Nautilus\.Previewer|org\.gnome\.NautilusPreviewer|firmware-notifier|"
                   r"io\.snapcraft\.|desktop-security-center|prompting-client|geoclue|org\.gnome\.Characters\.|"
                   r"org\.gnome\.Calculator\.SearchProvider|org\.gnome\.Calendar$|org\.gnome\.Contacts\.SearchProvider)",
                   re.I)
TERM_NOISE = re.compile(r"^(gitstatusd|ssh-agent|gpg-agent|dbus-|at-spi|fzf|starship|direnv|zoxide|atuin)")
# small helpers that scripts and prompts run all the time; never what someone "has open"
HELPERS = {"sleep", "tail", "head", "cat", "tee", "grep", "sed", "awk", "wc", "sort", "cut", "tr", "xargs",
           "timeout", "true", "false", "echo", "printf", "date", "env", "ls", "ps", "find", "which", "watch"}
MINECRAFT = re.compile(r"minecraft|tlauncher|launchwrapper|multimc|prismlauncher|lunarclient|badlion", re.I)
TERM_SCOPE = re.compile(r"^(vte-spawn-|ptyxis-spawn-|tmux-spawn-|app-ghostty-surface-|foot|kgx-)")
_desktop_cache = {}


def desktop_index(home):
    """Map desktop-file ids and program names to (friendly name, hidden)."""
    hit = _desktop_cache.get(home)
    if hit and hit[0] > time.monotonic():
        return hit[1]
    by_id, by_exec = {}, {}
    autostart = {os.path.basename(p)[:-8].lower() for d in ("/etc/xdg/autostart", home + "/.config/autostart")
                 for p in glob.glob(d + "/*.desktop")}
    dirs = ["/usr/share/applications", "/usr/local/share/applications", "/var/lib/snapd/desktop/applications",
            "/var/lib/flatpak/exports/share/applications", home + "/.local/share/flatpak/exports/share/applications",
            home + "/.local/share/applications"]
    for d in dirs:
        for path in glob.glob(d + "/*.desktop"):
            name = exe = None
            hidden = False
            try:
                if not os.path.isfile(path) or os.path.getsize(path) > 1 << 20:
                    continue        # the user's own folder: no pipes, devices or huge files
                with open(path, errors="replace") as f:
                    section = ""
                    for ln in f:
                        ln = ln.strip()
                        if ln.startswith("["):
                            section = ln
                        elif section == "[Desktop Entry]":
                            if ln.startswith("Name=") and name is None:
                                name = ln[5:]
                            elif ln.startswith("Exec=") and exe is None:
                                exe = ln[5:]
                            elif ln in ("NoDisplay=true", "Hidden=true"):
                                hidden = True
            except OSError:
                continue
            if not name:
                continue
            by_id[os.path.basename(path)[:-8].lower()] = (name, hidden)
            if exe and not hidden:
                words = [w for w in exe.split() if "=" not in w and os.path.basename(w) not in WRAPPERS]
                if words:
                    by_exec.setdefault(os.path.basename(words[0].strip('"')), name)
    idx = (by_id, by_exec, autostart)
    _desktop_cache[home] = (time.monotonic() + 60, idx)
    return idx


def unit_app_ids(cgroup):
    """Candidate application ids for a systemd cgroup path, best guess first."""
    parts = cgroup.strip("/").split("/")
    unit = parts[-1]
    un = lambda s: re.sub(r"\\x([0-9a-f]{2})", lambda m: chr(int(m.group(1), 16)), s)
    m = re.match(r"^snap\.([^.]+)\.(.+?)[-.][0-9a-f]{8}-[0-9a-f-]{27}\.scope$", unit)
    if m:
        return [m.group(1) + "_" + m.group(2), m.group(1)]
    m = (re.match(r"^app-(.+?)-(\d+|[0-9a-f]{32})\.scope$", unit) or re.match(r"^app-(.+?)@[^@]*\.service$", unit)
         or re.match(r"^app-([^@]+)\.service$", unit))          # an app that runs as its own service (Ghostty)
    if m:
        if "autostart" in unit:
            return []
        raw = m.group(1)
        return [un(raw)] + ([un(raw.split("-", 1)[1])] if "-" in raw else [])
    m = re.match(r"^dbus-:\d+\.\d+-(.+?)@\d+\.service$", unit)
    if m:
        return [un(m.group(1))]
    if unit.endswith(".service"):
        for p in parts[:-1]:
            m = re.match(r"^app-(.+)\.slice$", p)
            if m:
                return [un(m.group(1))]
    return []


def friendly_command(argv, by_exec):
    """A readable (name, detail) for a program started from a terminal."""
    args = list(argv)
    while len(args) > 1 and (os.path.basename(args[0]) in WRAPPERS or "=" in args[0].split("/")[0]):
        args.pop(0)
        while len(args) > 1 and args[0].startswith("-"):
            args.pop(0)
    base = os.path.basename(args[0]).lstrip("-") or args[0]
    detail = " ".join(args)
    detail = detail if len(detail) <= 140 else detail[:139] + "…"
    rest = args[1:]
    if base == "java" or base.startswith("java"):
        target = None
        for i, a in enumerate(rest):
            if a == "-jar" and i + 1 < len(rest):
                target = os.path.basename(rest[i + 1])
                break
            if a in ("-cp", "-classpath", "-p", "--module-path"):
                rest[i + 1:i + 2] = ["-"]
            elif not a.startswith("-"):
                target = a.split(".")[-1]
                break
        return (target + " (Java)" if target else "Java program"), detail
    stem = re.sub(r"[\d.]+$", "", base)
    if stem in INTERPRETERS or base in INTERPRETERS:
        kind = INTERPRETERS.get(base) or INTERPRETERS[stem]
        script = next((a for a in rest if not a.startswith("-")), None)
        if script and rest[:1] != ["-c"] and rest[:1] != ["-m"]:
            return "%s (%s)" % (os.path.basename(script), kind), detail
        return (kind if kind != "script" else base), detail
    return by_exec.get(base, base), detail


def open_apps(user):
    """What this user has open: desktop apps plus programs started from a
    terminal, newest first, without background and system processes."""
    uid, home = user["uid"], user["home"]
    by_id, by_exec, autostart = desktop_index(home)
    tick = os.sysconf("SC_CLK_TCK")
    with open("/proc/uptime") as f:
        uptime = float(f.read().split()[0])
    procs = {}
    for name in os.listdir("/proc"):
        if not name.isdigit():
            continue
        d = "/proc/" + name
        try:
            if os.stat(d).st_uid != uid:
                continue
            with open(d + "/cmdline", "rb") as f:
                argv = [a.decode(errors="replace") for a in f.read().split(b"\0") if a]
            if not argv:
                continue
            with open(d + "/stat") as f:
                st = f.read()
            with open(d + "/cgroup") as f:
                cg = next((ln.strip().split("::", 1)[1] for ln in f if ln.startswith("0::")), "")
        except (OSError, IndexError):
            continue
        comm = st[st.index("(") + 1:st.rindex(")")]
        rest = st[st.rindex(")") + 2:].split()
        tty = int(rest[4])
        procs[int(name)] = {"argv": argv, "comm": comm, "ppid": int(rest[1]), "cg": cg,
                            "pts": 136 <= ((tty >> 8) & 0xfff) <= 143,
                            "age": max(0, int(uptime - int(rest[19]) / tick))}
    kids = {}
    for pid, p in procs.items():
        kids.setdefault(p["ppid"], []).append(pid)

    def subtree_text(pid, depth=0):
        text = " ".join(procs[pid]["argv"])
        if depth < 4:
            for k in kids.get(pid, [])[:20]:
                text += " " + subtree_text(k, depth + 1)
        return text

    apps = {}

    def add(name, detail, age, terminal):
        old = apps.get(name)
        if old is None or age > old["age"]:
            apps[name] = {"name": name, "detail": detail, "age": age, "terminal": terminal}

    # a desktop service may hold a terminal device without anyone having typed a command
    # (the file manager's connection to a phone runs ssh that way)
    service = lambda p: "/session.slice/" in p["cg"] or "/background.slice/" in p["cg"]
    in_term = lambda p: not service(p) and (p["pts"] or bool(TERM_SCOPE.match(p["cg"].rsplit("/", 1)[-1])))
    plain = lambda p: os.path.basename(p["argv"][0]).lstrip("-")
    cand = {pid for pid, p in procs.items()
            if in_term(p) and p["comm"] not in SHELLS and not TERM_NOISE.match(p["comm"])
            and plain(p) not in SHELLS and plain(p) not in HELPERS}
    for pid in cand:
        p = procs[pid]
        if p["ppid"] in cand:
            continue
        name, detail = friendly_command(p["argv"], by_exec)
        if MINECRAFT.search(subtree_text(pid)):
            name = "Minecraft"
        add(name, detail, p["age"], True)
    login_age = max((p["age"] for p in procs.values()), default=0)
    for p in procs.values():
        if in_term(p):
            continue
        ids = unit_app_ids(p["cg"])
        if not ids or NOISE.match(ids[0]) or NOISE.match(ids[-1]):
            continue
        if login_age - p["age"] < 90 and any(i.lower() in autostart for i in ids):
            continue            # started by itself at login, not by the user
        entry = next((by_id[i.lower()] for i in ids if i.lower() in by_id), None)
        if entry is None:
            if not p["cg"].endswith(".scope") or any(i.lower() in autostart for i in ids):
                continue        # a background service or start-up helper without a launcher entry
            # No launcher under that id (an app built on a browser engine names its group after
            # the engine): go by the program that runs in it.
            mates = [q for q in procs.values() if q["cg"] == p["cg"]]
            known = next((by_exec[plain(q)] for q in sorted(mates, key=lambda q: -q["age"]) if plain(q) in by_exec), None)
            entry = (known or ids[-1].split(".")[-1].replace("_", " ").replace("-", " ").title(), False)
        if entry[1]:
            continue
        add(entry[0], "", p["age"], False)
    return sorted(apps.values(), key=lambda a: a["age"])[:15]


# ----------------------------------------------------------- browser activity
#
# What sites each account has looked at, read as root straight from the
# browser's own files. History (the sites visited, newest first) and the tabs
# open right now. Private/incognito windows write nothing to disk, so they
# never show up here. See API.md for the shape sent to the phone.

HISTORY_DAYS = 7                # how far back the history goes
HISTORY_MAX = 1000              # a safety cap on rows per browser, so a huge history cannot flood the phone
OPEN_WINDOW = 90                # Chrome has no readable tab list; "open now" is the last 90 seconds of history
SEARCH_HOSTS = ("google.", "bing.", "duckduckgo.", "search.brave.", "ecosia.", "startpage.")

# Each browser: where its profiles live under the account's home, and how to read them.
# A snap and a .deb of the same browser keep their files in different places; we look in all.
BROWSERS = [
    {"id": "firefox", "name": "Firefox", "kind": "firefox", "db": "places.sqlite",
     "roots": ["snap/firefox/common/.mozilla/firefox", ".mozilla/firefox",
               "snap/firefox/common/.cache/mozilla/firefox"]},
    {"id": "chrome", "name": "Google Chrome", "kind": "chromium", "db": "History",
     "roots": [".config/google-chrome"]},
    {"id": "chromium", "name": "Chromium", "kind": "chromium", "db": "History",
     "roots": ["snap/chromium/common/chromium", ".config/chromium",
               ".var/app/org.chromium.Chromium/config/chromium"]},
]


def _host(url):
    """The site part of a URL, without the www., for showing and filtering."""
    m = re.match(r"^[a-z][a-z0-9+.-]*://([^/?#]+)", url or "", re.I)
    host = (m.group(1) if m else "").lower().split("@")[-1].split(":")[0]
    return host[4:] if host.startswith("www.") else host


def _search_term(url, host):
    """If this is a search page, the words searched for; otherwise ''."""
    if not any(host.startswith(s) or ("." + host).find("." + s) >= 0 for s in SEARCH_HOSTS):
        return ""
    try:
        q = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
    except ValueError:
        return ""
    for key in ("q", "query", "p"):
        if q.get(key):
            return q[key][0][:200]
    return ""


def _lz4_block(src):
    """Decompress one raw LZ4 block (what Firefox wraps in its mozLz4 files)."""
    out = bytearray()
    i, n = 0, len(src)
    while i < n:
        token = src[i]; i += 1
        length = token >> 4
        if length == 15:
            while i < n:
                b = src[i]; i += 1; length += b
                if b != 255:
                    break
        out += src[i:i + length]; i += length
        if i >= n:
            break
        off = src[i] | (src[i + 1] << 8); i += 2
        if off == 0:
            break
        match = (token & 15) + 4
        if (token & 15) == 15:
            while i < n:
                b = src[i]; i += 1; match += b
                if b != 255:
                    break
        start = len(out) - off
        for j in range(match):
            out.append(out[start + j])
    return bytes(out)


def _mozlz4(path):
    """The JSON inside a Firefox .jsonlz4 file, or None if it cannot be read."""
    try:
        with open(path, "rb") as f:
            data = f.read(16 << 20)
    except OSError:
        return None
    if data[:8] != b"mozLz40\0":
        return None
    try:
        return json.loads(_lz4_block(data[12:]))
    except (ValueError, IndexError):
        return None


def _snapshot_rows(db, sql, params=()):
    """Run a read-only query against a copy of a browser database, so it works
    even while the browser holds the original open. Returns [] on any trouble."""
    tmp = tempfile.mkdtemp(prefix="curfew-br-")
    try:
        base = os.path.join(tmp, "db")
        got = False
        for suffix in ("", "-wal", "-shm"):
            if os.path.exists(db + suffix):
                try:
                    shutil.copyfile(db + suffix, base + suffix)
                    got = got or suffix == ""
                except OSError:
                    pass
        if not got:
            return []
        conn = sqlite3.connect(base, timeout=3)
        try:
            return conn.execute(sql, params).fetchall()
        finally:
            conn.close()
    except sqlite3.Error:
        return []
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def _entry(url, title, when, search=""):
    host = _host(url)
    return {"url": url, "title": title or "", "host": host, "when": int(when),
            "search": search or _search_term(url, host)}


def _newest_profile(home, spec):
    """The profile folder this account browses in: the one whose database was
    touched most recently, across every place this browser might keep it."""
    best = None
    for root in spec["roots"]:
        for db in glob.glob(os.path.join(home, root, "*", spec["db"])):
            try:
                mtime = os.path.getmtime(db)
            except OSError:
                continue
            if best is None or mtime > best[0]:
                best = (mtime, os.path.dirname(db), db)
    return best and best[1:]


def _firefox(profile, db):
    since = int((time.time() - HISTORY_DAYS * 86400) * 1_000_000)   # microseconds since the epoch
    rows = _snapshot_rows(db, "select url, title, last_visit_date from moz_places "
                              "where last_visit_date is not null and hidden = 0 and last_visit_date >= ? "
                              "order by last_visit_date desc limit ?", (since, HISTORY_MAX))
    recent = [_entry(u, t, (d or 0) / 1_000_000) for u, t, d in rows if _host(u)]
    tabs = []
    for name in ("sessionstore-backups/recovery.jsonlz4", "sessionstore-backups/recovery.baklz4",
                 "sessionstore-backups/previous.jsonlz4", "sessionstore.jsonlz4"):
        data = _mozlz4(os.path.join(profile, name))
        if not isinstance(data, dict):
            continue
        for win in data.get("windows", []):
            for tab in win.get("tabs", []):
                entries = tab.get("entries", [])
                idx = tab.get("index", len(entries))
                if entries and 1 <= idx <= len(entries):
                    e = entries[idx - 1]
                    url = e.get("url", "")
                    if _host(url) and not url.startswith("about:"):
                        tabs.append(_entry(url, e.get("title", ""), (tab.get("lastAccessed") or 0) / 1000))
        break
    return tabs, recent


def _chromium(profile, db):
    since = int((time.time() - HISTORY_DAYS * 86400 + 11644473600) * 1_000_000)   # Chrome counts microseconds from 1601
    rows = _snapshot_rows(db, "select u.url, u.title, u.last_visit_time, k.term "
                              "from urls u left join keyword_search_terms k on k.url_id = u.id "
                              "where u.last_visit_time >= ? order by u.last_visit_time desc limit ?", (since, HISTORY_MAX))
    recent = [_entry(u, t, v / 1_000_000 - 11644473600, s or "") for u, t, v, s in rows if _host(u)]
    now = time.time()
    tabs = [e for e in recent if now - e["when"] < OPEN_WINDOW]     # no readable tab list; the last minute or two stand in
    return tabs, recent


def _dedupe(entries):
    """Newest kept, same address not repeated."""
    seen, out = set(), []
    for e in entries:
        if e["url"] in seen:
            continue
        seen.add(e["url"])
        out.append(e)
    return out


def browser_activity(user):
    """Per browser this account has used: the tabs open now and the recent history."""
    home = user["home"]
    out = []
    for spec in BROWSERS:
        found = _newest_profile(home, spec)
        if not found:
            continue
        profile, db = found
        reader = _firefox if spec["kind"] == "firefox" else _chromium
        try:
            tabs, recent = reader(profile, db)
        except Exception as e:
            log("browser %s read failed: %r" % (spec["id"], e))
            tabs, recent = [], []
        if not tabs and not recent:
            continue
        out.append({"id": spec["id"], "name": spec["name"],
                    "open": _dedupe(tabs)[:60], "recent": _dedupe(recent)[:HISTORY_MAX]})
    return out


# ------------------------------------------------------------------- actions

def power(kind):
    if kind == "reboot":
        act("restart the computer", ["systemctl", "reboot", "-i"])
    else:
        act("power off", ["systemctl", "poweroff", "-i"])


def human(seconds):
    """'30 seconds', '15 minutes', '1 hour 30 minutes'."""
    if seconds < 60:
        return "%d seconds" % seconds
    h, m = divmod(round(seconds / 60), 60)
    parts = ["%d %s%s" % (n, word, "" if n == 1 else "s") for n, word in ((h, "hour"), (m, "minute")) if n]
    return " ".join(parts)


def notify(text, only=None):
    """Show a notice to everyone with a desktop session, or to one user."""
    for s in sessions():
        if s["graphical"] and only in (None, s["user"]):
            with contextlib.suppress(KeyError):
                uid = pwd.getpwnam(s["user"]).pw_uid
                act("notify %s: %s" % (s["user"], text),
                    ["runuser", "-u", s["user"], "--", "env",
                     "DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/%d/bus" % uid,
                     "notify-send", "-u", "critical", "-a", "Curfew", "Curfew", text])


# ------------------------------------------------------ internet per account

def fw_rules(uid):
    """Nothing leaves the computer for this account, and no name lookups either
    (those go through a local resolver, which would otherwise stay reachable)."""
    who = ["-m", "owner", "--uid-owner", str(uid)]
    return [who + ["-p", "udp", "--dport", "53", "-j", "REJECT"],
            who + ["-p", "tcp", "--dport", "53", "-j", "REJECT", "--reject-with", "tcp-reset"],
            who + ["!", "-o", "lo", "-p", "tcp", "-j", "REJECT", "--reject-with", "tcp-reset"],
            who + ["!", "-o", "lo", "-j", "REJECT"]]


def net_supported():
    return DRY or bool(shutil.which("iptables"))


def net_sync():
    """Make the firewall match the state file. Admin accounts are never blocked.
    A computer where this was never used keeps an untouched firewall."""
    with state() as st:
        pass
    off = {n for n, e in st["net"].items() if e.get("blocked")}
    users = [u for u in human_users() if u["name"] in off and not u["admin"]]
    if DRY:
        log("would block the internet for: " + (", ".join(u["name"] for u in users) or "nobody"))
        return True
    ok = True
    try:
        for tool in ("iptables", "ip6tables"):
            if not shutil.which(tool):
                ok = ok and tool != "iptables"
                continue
            ipt = lambda *a: subprocess.run([tool, "-w", "5", *a], capture_output=True, text=True, timeout=15)
            have = ipt("-S", FW_CHAIN)
            if have.returncode != 0 and not users:
                continue
            want = sorted(str(u["uid"]) for u in users for _ in fw_rules(u["uid"]))
            first = [ln for ln in ipt("-S", "OUTPUT").stdout.splitlines() if ln.startswith("-A ")][:1]
            if (have.returncode == 0 and first == ["-A OUTPUT -j " + FW_CHAIN]
                    and sorted(re.findall(r"--uid-owner (\d+)", have.stdout)) == want):
                continue
            log("updating %s: internet off for %s" % (tool, ", ".join(u["name"] for u in users) or "nobody"))
            ipt("-N", FW_CHAIN)
            ipt("-F", FW_CHAIN)
            done = [ipt("-A", FW_CHAIN, *r).returncode == 0 for u in users for r in fw_rules(u["uid"])]
            while ipt("-D", "OUTPUT", "-j", FW_CHAIN).returncode == 0:
                pass
            done.append(ipt("-I", "OUTPUT", "1", "-j", FW_CHAIN).returncode == 0)   # ahead of any other rule
            ok = ok and all(done)
    except (OSError, subprocess.SubprocessError) as e:
        log("firewall error: %r" % e)
        return False
    return ok


# -------------------------------------------------------------------- timers

TIMER_WAKE = threading.Event()


def due(e, now):
    """What a countdown needs right now: 'fire', 'warn' or None."""
    if not e or e.get("deadline") is None:
        return None
    left = e["deadline"] - now
    if left <= 0:
        return "fire"
    return "warn" if e.get("warn") and not e.get("warned") and left <= 60 else None


def timer_loop():
    """Owns the shut-down countdown and the internet countdowns. The deadlines are
    wall-clock times in the state file, so they survive a restart of the agent
    and a reboot. Also puts the firewall back if something else cleared it."""
    checked = None
    while True:
        with state() as st:
            pass
        now = time.time()
        entries = [st["timer"]] + list(st["net"].values())
        if any(due(e, now) for e in entries):
            todo = []
            with state(write=True) as st:       # decide again under the lock: it may just have been cancelled
                what = due(st["timer"], now)
                if what == "fire":
                    st["timer"] = None
                    todo.append(("power", None))
                elif what:
                    st["timer"]["warned"] = True
                    todo.append(("notify", None, "This computer will shut down in 1 minute."))
                for name, e in st["net"].items():
                    what = due(e, now)
                    if what == "fire":
                        e.update(blocked=True, deadline=None)
                        todo.append(("net", name, e.get("warn")))
                    elif what:
                        e["warned"] = True
                        todo.append(("notify", name, "The internet will turn off in 1 minute."))
            if any(t[0] == "net" for t in todo):
                net_sync()
            for t in todo:
                if t[0] == "notify" or (t[0] == "net" and t[2]):
                    notify(t[2] if t[0] == "notify" else "The internet has been turned off.", t[1])
            if any(t[0] == "power" for t in todo):
                log("timer reached zero")
                power("poweroff")
            continue
        blocking = any(e.get("blocked") for e in st["net"].values())
        if checked is None or (blocking and time.monotonic() - checked >= 30):
            net_sync()
            checked = time.monotonic()
        lefts = [e["deadline"] - now for e in entries if e and e.get("deadline") is not None]
        TIMER_WAKE.wait(min(lefts + [5.0]) if lefts else 30.0)   # short ticks also catch waking from suspend
        TIMER_WAKE.clear()


def demo():
    """Dry-run only: made-up users and apps from the JSON file named in $CURFEW_FAKE,
    for trying out the phone app without a house full of laptops."""
    if DRY and os.environ.get("CURFEW_FAKE"):
        with contextlib.suppress(OSError, ValueError):
            with open(os.environ["CURFEW_FAKE"]) as f:
                return json.load(f)
    return None


def host_name():
    return (demo() or {}).get("name") or socket.gethostname()


def login_hook():
    """Is the login screen set up (by the package's postinst) to ask us about approved logins?"""
    if DRY:
        return True
    with contextlib.suppress(OSError):
        with open(PAM_FILE) as f:
            return "curfew.py pam-login" in f.read()
    return False


def all_users():
    """Every account with its state, as the phone sees it."""
    if demo():
        return [dict(u) for u in demo()["users"]]
    states = user_states()
    return [{"name": u["name"], "full": u["full"], "admin": u["admin"],
             "state": states.get(u["name"], "none")} for u in human_users()]


def op_status(req, st):
    now, users = time.time(), all_users()
    for u in users:
        e = st["net"].get(u["name"]) or {}
        u["net"] = "off" if e.get("blocked") and not u["admin"] else "on"
        if e.get("deadline") is not None and not u["admin"]:
            u["net_timer"] = {"remaining": max(0, int(e["deadline"] - now)), "warn": bool(e.get("warn"))}
    t = st["timer"]
    timer = {"remaining": max(0, int(t["deadline"] - now)), "warn": bool(t.get("warn"))} if t else None
    caps = ["seconds", "browsers"] + (["net"] if net_supported() else []) + (["login"] if login_hook() else [])
    return {"ok": True, "id": st["id"], "name": host_name(), "os": os_name(),
            "users": users, "timer": timer, "caps": caps, "dry": DRY}


def find_user(req):
    return next((u for u in human_users() if u["name"] == req.get("user")), None)


def op_apps(req, st):
    d = demo()
    if d:
        ok = any(u["name"] == req.get("user") for u in d["users"])
        return {"ok": True, "apps": d.get("apps", {}).get(req.get("user"), [])} if ok else {"ok": False, "error": "bad_user"}
    u = find_user(req)
    return {"ok": True, "apps": open_apps(u)} if u else {"ok": False, "error": "bad_user"}


def op_browsers(req, st):
    d = demo()
    if d:
        ok = any(u["name"] == req.get("user") for u in d["users"])
        return {"ok": True, "browsers": d.get("browsers", {}).get(req.get("user"), [])} if ok else {"ok": False, "error": "bad_user"}
    u = find_user(req)
    if not u:
        return {"ok": False, "error": "bad_user"}
    try:
        return {"ok": True, "browsers": browser_activity(u)}
    except Exception as e:
        log("browser activity failed: %r" % e)
        return {"ok": True, "browsers": []}


def countdown(req, low=10):
    """The length asked for, in seconds, or None if it is not acceptable.
    'seconds' is preferred; 'minutes' is what older phones send."""
    n = req.get("seconds")
    if n is None and isinstance(req.get("minutes"), int) and not isinstance(req.get("minutes"), bool):
        n = req["minutes"] * 60
    if not isinstance(n, int) or isinstance(n, bool) or not low <= n <= 86400:
        return None
    return n


def op_power(req, st):
    threading.Timer(1.0, power, [req["op"]]).start()     # after the reply is sent
    return {"ok": True}


def op_timer_set(req, st):
    seconds, warn = countdown(req), bool(req.get("warn"))
    if seconds is None:
        return {"ok": False, "error": "bad_args"}
    with state(write=True) as s:
        s["timer"] = {"deadline": time.time() + seconds, "warn": warn, "warned": seconds <= 60}
    log("timer set: shut down in %s" % human(seconds))
    TIMER_WAKE.set()
    if warn:
        threading.Thread(target=notify, daemon=True, args=(
            "This computer will shut down in %s." % human(seconds),)).start()
    return {"ok": True, "timer": {"remaining": seconds, "warn": warn}}


def op_timer_cancel(req, st):
    with state(write=True) as s:
        s["timer"] = None
    log("timer cancelled")
    TIMER_WAKE.set()
    return {"ok": True, "timer": None}


def op_demo_action(req):
    u = next((u for u in demo()["users"] if u["name"] == req.get("user")), None)
    if not u:
        return {"ok": False, "error": "bad_user"}
    if u["state"] == "none":
        return {"ok": False, "error": "not_logged_in"}
    log("would %s %s" % (req["op"], u["name"]))
    return {"ok": True}


def op_lock(req, st):
    if demo():
        return op_demo_action(req)
    u = find_user(req)
    if not u:
        return {"ok": False, "error": "bad_user"}
    revoke_login(u["name"])
    ids = [s["id"] for s in sessions() if s["user"] == u["name"] and s["graphical"]]
    ok = bool(ids) and all([act("lock the screen of " + u["name"], ["loginctl", "lock-session", i]) for i in ids])
    return {"ok": True} if ok else {"ok": False, "error": "not_logged_in" if not ids else "failed"}


def op_logout(req, st):
    if demo():
        return op_demo_action(req)
    u = find_user(req)
    if not u:
        return {"ok": False, "error": "bad_user"}
    revoke_login(u["name"])
    if not any(s["user"] == u["name"] for s in sessions()):
        return {"ok": False, "error": "not_logged_in"}
    ok = act("log out " + u["name"], ["loginctl", "terminate-user", u["name"]])
    return {"ok": True} if ok else {"ok": False, "error": "failed"}


def login_path(name):
    return os.path.join(RUN_DIR, "login", name)


def revoke_login(name):
    with contextlib.suppress(OSError):
        os.unlink(login_path(name))


def op_login(req, st):
    """Let an account in without its password being typed. A session that is
    already open is brought to the screen and unlocked. Otherwise the login
    screen is told to accept this account once, within LOGIN_SECONDS (it asks
    'curfew pam-login', below)."""
    u = next((u for u in all_users() if u["name"] == req.get("user")), None)
    if not u:
        return {"ok": False, "error": "bad_user"}
    name = u["name"]
    if demo():
        log("would log in " + name)
        return {"ok": True, "how": "approved" if u["state"] == "none" else "unlocked", "seconds": LOGIN_SECONDS}
    live = sessions()
    mine = [s["id"] for s in live if s["user"] == name and s["graphical"]]
    hook = login_hook()
    if not mine and not hook:
        return {"ok": False, "error": "unsupported"}
    if hook:
        os.makedirs(os.path.dirname(login_path(name)), mode=0o700, exist_ok=True)
        with open(login_path(name), "w") as f:
            f.write(str(time.time() + LOGIN_SECONDS))
        log("login approved for %s by phone %r" % (name, st["phones"].get(req["_phone"], {}).get("name")))
    real = find_user(req)
    threading.Thread(target=unlock_keyring, daemon=True, args=(real, LOGIN_SECONDS + 30 if not mine else 15)).start()
    if not mine:
        return {"ok": True, "how": "approved", "seconds": LOGIN_SECONDS}
    for s in live:                      # nobody else's open session is left behind unlocked
        if s["graphical"] and s["user"] != name:
            act("lock the screen of " + s["user"], ["loginctl", "lock-session", s["id"]])
    ok = (act("bring the session of %s to the screen" % name, ["loginctl", "activate", mine[0]])
          and act("unlock the screen of " + name, ["loginctl", "unlock-session", mine[0]]))
    return {"ok": True, "how": "unlocked", "seconds": LOGIN_SECONDS} if ok else {"ok": False, "error": "failed"}


def passwords(change=None):
    """The login passwords remembered for unlocking keyrings (see cmd_pam_remember),
    in a file only root can read. change(dict) edits them."""
    path = os.path.join(STATE_DIR, "passwords.json")
    with state():                       # same lock as the state file
        try:
            with open(path) as f:
                known = json.load(f)
        except (OSError, ValueError):
            known = {}
        if change:
            change(known)
            fd = os.open(path + ".tmp", os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, "w") as f:
                json.dump(known, f)
            os.replace(path + ".tmp", path)
    return known


def cmd_pam_remember():
    """Run by the login screen after a password was typed and accepted, with the
    password on standard input. It is kept so that a later login from the phone
    can unlock the account's keyring (saved passwords of browsers and the like),
    which is locked with this password; without it every such login would end in
    a question about the keyring."""
    name = os.environ.get("PAM_USER", "")
    if os.environ.get("PAM_TYPE") != "auth" or not find_user({"user": name}):
        return 1
    secret = sys.stdin.buffer.read(4096).split(b"\0")[0].decode(errors="surrogateescape")
    if secret:
        passwords(lambda known: known.update({name: secret}))
    return 0


def keyring_control(path, secret):
    """Ask a running gnome-keyring to unlock the login keyring (or to create it, for an
    account that has none), the way its own login module does. 0 means unlocked."""
    data = secret.encode(errors="surrogateescape")
    with socket.socket(socket.AF_UNIX) as s:
        s.settimeout(5)
        s.connect(path)
        s.sendall(b"\0" + struct.pack(">III", 12 + len(data), 1, len(data)) + data)
        answer = b""
        while len(answer) < 8:
            chunk = s.recv(8 - len(answer))
            if not chunk:
                return -1
            answer += chunk
    return struct.unpack(">II", answer)[1]


def cmd_keyring(path):
    """Helper the agent runs as the account itself: the keyring only listens to its owner."""
    try:
        return 0 if keyring_control(path, sys.stdin.buffer.read(4096).decode(errors="surrogateescape")) == 0 else 1
    except OSError:
        return 2


def unlock_keyring(u, wait):
    """After a login from the phone: unlock the keyring with the remembered password as
    soon as the session has one, so nothing asks for it. Gives up after `wait` seconds
    (the approval was not used) or if there is nothing remembered."""
    secret = passwords().get(u["name"])
    if not secret:
        return log("no password remembered for %s yet; its keyring stays locked" % u["name"])
    path = "/run/user/%d/keyring/control" % u["uid"]
    if DRY:
        return log("would unlock the keyring of " + u["name"])
    end = time.monotonic() + wait
    while time.monotonic() < end:
        if os.path.exists(path):
            try:
                gid = pwd.getpwnam(u["name"]).pw_gid
                r = subprocess.run([sys.executable, os.path.abspath(__file__), "keyring", path], input=secret.encode(
                    errors="surrogateescape"), user=u["uid"], group=gid, extra_groups=[], timeout=10, capture_output=True)
            except (OSError, KeyError, subprocess.SubprocessError) as e:
                return log("keyring of %s: %r" % (u["name"], e))
            if r.returncode != 2:       # 2: not listening yet, try again
                return log("keyring of %s %s" % (u["name"], "unlocked" if r.returncode == 0 else "did not accept the remembered password"))
        time.sleep(0.5)


def cmd_pam_login():
    """Run by the login screen for the account in $PAM_USER before it asks for the
    password. Exit status 0 means: a phone approved this login, let them in.
    Each approval works once."""
    name = os.environ.get("PAM_USER", "")
    if os.environ.get("PAM_TYPE") != "auth" or not find_user({"user": name}):
        return 1
    try:
        with open(login_path(name)) as f:
            expires = float(f.read())
        os.unlink(login_path(name))
    except (OSError, ValueError):
        return 1
    if expires < time.time():
        return 1
    log("let %s in without a password (approved from a phone)" % name)
    return 0


def op_net_set(req, st):
    """Turn the internet off for one account: now (seconds 0) or after a countdown."""
    u = next((u for u in all_users() if u["name"] == req.get("user")), None)
    seconds, warn = countdown(req, low=0), bool(req.get("warn"))
    if not u:
        return {"ok": False, "error": "bad_user"}
    if u["admin"]:
        return {"ok": False, "error": "is_admin"}
    if seconds is None or 0 < seconds < 10:
        return {"ok": False, "error": "bad_args"}
    if not net_supported():
        return {"ok": False, "error": "unsupported"}
    with state(write=True) as s:
        old = s["net"].get(u["name"])
        s["net"][u["name"]] = {"blocked": seconds == 0, "deadline": time.time() + seconds if seconds else None,
                               "warn": warn, "warned": seconds <= 60}
    if not net_sync():
        with state(write=True) as s:    # say so, and do not pretend it is off
            if old is None:
                s["net"].pop(u["name"], None)
            else:
                s["net"][u["name"]] = old
        net_sync()
        return {"ok": False, "error": "failed"}
    log("internet for %s: off %s" % (u["name"], "in " + human(seconds) if seconds else "now"))
    TIMER_WAKE.set()
    if warn:
        text = "The internet will turn off in %s." % human(seconds) if seconds else "The internet has been turned off."
        threading.Thread(target=notify, daemon=True, args=(text, u["name"])).start()
    return {"ok": True}


def op_net_clear(req, st):
    """Internet back on for one account, and any countdown for it cancelled."""
    if not any(u["name"] == req.get("user") for u in all_users()):
        return {"ok": False, "error": "bad_user"}
    with state(write=True) as s:
        s["net"].pop(req["user"], None)
    ok = net_sync()
    log("internet for %s: on" % req["user"])
    TIMER_WAKE.set()
    return {"ok": True} if ok else {"ok": False, "error": "failed"}


def op_forget(req, st):
    with state(write=True) as s:
        gone = s["phones"].pop(req["_phone"], None)
    log("phone %r removed itself" % (gone or {}).get("name"))
    return {"ok": True}


OPS = {"status": op_status, "apps": op_apps, "browsers": op_browsers, "poweroff": op_power, "reboot": op_power,
       "timer_set": op_timer_set, "timer_cancel": op_timer_cancel, "lock": op_lock,
       "logout": op_logout, "login": op_login, "net_set": op_net_set, "net_clear": op_net_clear,
       "forget": op_forget}


# ---------------------------------------------------------------------- HTTP

def handle_pair(req):
    s, c, payload, m = (req.get(k) for k in ("snonce", "cnonce", "payload", "mac"))
    if not (isinstance(payload, str) and all(isinstance(x, str) and TOKEN_RE.match(x) for x in (s, c))
            and isinstance(m, str) and MAC_RE.match(m)):
        return 400, {"error": "bad_request"}
    if not NONCES.use(s):
        return 409, {"error": "bad_nonce"}
    with state(write=True) as st:
        p = st["pairing"]
        if not p or p["expires"] < time.time():
            st["pairing"] = None
            return 403, {"error": "no_pairing"}
        if not hmac.compare_digest(mac(p["code"].encode(), "pair", s, c, payload), m):
            p["fails"] = p.get("fails", 0) + 1
            if p["fails"] >= PAIR_MAX_FAILS:
                st["pairing"] = None
            return 403, {"error": "bad_code"}
        try:
            name = str(json.loads(payload).get("name", ""))
        except (ValueError, AttributeError):
            return 400, {"error": "bad_request"}
        name = re.sub(r"[^\w .'()+-]", "", name).strip()[:40] or "Phone"
        taken = {ph["name"] for ph in st["phones"].values()}
        base, n = name, 2
        while name in taken:
            name, n = "%s %d" % (base, n), n + 1
        pid = secrets.token_hex(8)
        key = derive_key(p["code"], s, c, pid)
        st["phones"][pid] = {"name": name, "key": key.hex(), "added": int(time.time())}
        st["pairing"] = None           # the code works once, for one phone
        st["last_paired"] = {"name": name, "when": time.time()}
        out = json.dumps({"ok": True, "id": st["id"], "name": host_name(), "phone_name": name})
    log("paired phone %r" % name)
    return 200, {"phone": pid, "payload": out, "mac": mac(key, "res", pid, s, c, out)}


def handle_call(req):
    pid, s, c, payload, m = (req.get(k) for k in ("phone", "snonce", "cnonce", "payload", "mac"))
    if not (isinstance(payload, str) and all(isinstance(x, str) and TOKEN_RE.match(x) for x in (pid, s, c))
            and isinstance(m, str) and MAC_RE.match(m)):
        return 400, {"error": "bad_request"}
    with state() as st:
        pass
    phone, gone = st["phones"].get(pid), st["removed"].get(pid)
    if not phone and not gone:
        return 403, {"error": "unknown_phone"}
    key = bytes.fromhex((phone or gone)["key"])
    if not hmac.compare_digest(mac(key, "req", pid, s, c, payload), m):
        return 403, {"error": "bad_mac"}
    if not NONCES.use(s):               # replayed or stale request
        return 409, {"error": "bad_nonce"}
    if not phone:
        # Unpaired on this side. Answer signed with the old key so the phone can
        # trust it; the old key can do nothing else.
        result = {"ok": False, "error": "unpaired"}
    else:
        try:
            body = json.loads(payload)
            fn = OPS.get(body.get("op"))
            body["_phone"] = pid
            result = fn(body, st) if fn else {"ok": False, "error": "bad_op"}
        except (ValueError, AttributeError, TypeError):
            result = {"ok": False, "error": "bad_request"}
        except Exception as e:           # never let one request kill the reply
            log("error in request: %r" % e)
            result = {"ok": False, "error": "failed"}
    out = json.dumps(result)
    return 200, {"payload": out, "mac": mac(key, "res", pid, s, c, out)}


class Handler(BaseHTTPRequestHandler):
    server_version, sys_version, timeout = "Curfew", "", 10

    def log_message(self, *a):
        pass

    def reply(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path != "/v1/hello":
            return self.reply(404, {"error": "not_found"})
        with state() as st:
            pass
        self.reply(200, {"app": "curfew", "proto": PROTO, "id": st["id"],
                         "name": host_name(), "nonce": NONCES.new(self.client_address[0])})

    def do_POST(self):
        fn = {"/v1/pair": handle_pair, "/v1/call": handle_call}.get(self.path)
        if not fn:
            return self.reply(404, {"error": "not_found"})
        try:
            n = int(self.headers.get("Content-Length", ""))
            if not 0 < n <= MAX_BODY:
                raise ValueError
            req = json.loads(self.rfile.read(n))
            if not isinstance(req, dict):
                raise ValueError
        except (ValueError, OSError):
            return self.reply(400, {"error": "bad_request"})
        self.reply(*fn(req))


class Server(ThreadingHTTPServer):
    """Serves a few connections per address at a time, so that someone flooding it
    from one place (an account on this computer, say) cannot keep the phone out."""
    daemon_threads, allow_reuse_address, request_queue_size = True, True, 32
    busy, busy_lock = {}, threading.Lock()

    def verify_request(self, request, client_address):
        ip = client_address[0]
        with self.busy_lock:
            if self.busy.get(ip, 0) >= CONNECTIONS_PER_ADDRESS:
                return False
            self.busy[ip] = self.busy.get(ip, 0) + 1
        return True

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            with self.busy_lock:
                left = self.busy.get(client_address[0], 1) - 1
                if left > 0:
                    self.busy[client_address[0]] = left
                else:
                    self.busy.pop(client_address[0], None)


def serve():
    srv = Server(("0.0.0.0", PORT), Handler)
    with state() as st:
        log("Curfew agent %s listening on port %d" % (st["id"], PORT))
    threading.Thread(target=timer_loop, daemon=True).start()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


# ------------------------------------------------------------------- QR code

def qr_matrix(text):
    """Minimal QR encoder: byte mode, error correction L, versions 1-5."""
    data = text.encode()
    caps = {1: (19, 7), 2: (34, 10), 3: (55, 15), 4: (80, 20), 5: (108, 26)}
    ver = next((v for v, (n, _) in caps.items() if len(data) + 2 <= n), None)
    if ver is None:
        raise ValueError("text too long for QR")
    ndata, nec = caps[ver]
    bits = "0100" + format(len(data), "08b") + "".join(format(b, "08b") for b in data)
    bits += "0" * min(4, ndata * 8 - len(bits))
    bits += "0" * (-len(bits) % 8)
    cw = [int(bits[i:i + 8], 2) for i in range(0, len(bits), 8)]
    cw += [(0xEC, 0x11)[i % 2] for i in range(ndata - len(cw))]
    exp, lg, x = [0] * 512, [0] * 256, 1
    for i in range(255):
        exp[i], lg[x] = x, i
        x = (x << 1) ^ (0x11D if x & 0x80 else 0)
    for i in range(255, 512):
        exp[i] = exp[i - 255]
    mul = lambda a, b: exp[lg[a] + lg[b]] if a and b else 0
    gen = [1]
    for i in range(nec):
        gen = [mul(g, exp[i]) ^ h for g, h in zip(gen + [0], [0] + gen)]
    gen = gen[::-1]                     # highest degree first, monic
    rem = [0] * nec
    for b in cw:
        f = b ^ rem[0]
        rem = [r ^ mul(g, f) for r, g in zip(rem[1:] + [0], gen[1:])]
    cw += rem
    n = 17 + 4 * ver
    m = [[False] * n for _ in range(n)]
    fn = [[False] * n for _ in range(n)]

    def put(x, y, v):
        if 0 <= x < n and 0 <= y < n:
            m[y][x], fn[y][x] = v, True

    for cx, cy in ((3, 3), (n - 4, 3), (3, n - 4)):
        for dy in range(-4, 5):
            for dx in range(-4, 5):
                put(cx + dx, cy + dy, max(abs(dx), abs(dy)) in (0, 1, 3))
    if ver > 1:
        for dy in range(-2, 3):
            for dx in range(-2, 3):
                put(n - 7 + dx, n - 7 + dy, max(abs(dx), abs(dy)) != 1)
    for i in range(8, n - 8):
        put(i, 6, i % 2 == 0)
        put(6, i, i % 2 == 0)

    def put_format(mask):
        d = (1 << 3) | mask             # level L
        r = d
        for _ in range(10):
            r = (r << 1) ^ ((r >> 9) * 0x537)
        b = ((d << 10) | r) ^ 0x5412
        bit = lambda i: bool((b >> i) & 1)
        for i in range(6):
            put(8, i, bit(i))
        put(8, 7, bit(6)); put(8, 8, bit(7)); put(7, 8, bit(8))
        for i in range(9, 15):
            put(14 - i, 8, bit(i))
        for i in range(8):
            put(n - 1 - i, 8, bit(i))
        for i in range(8, 15):
            put(8, n - 15 + i, bit(i))
        put(8, n - 8, True)

    put_format(0)                       # reserve the format areas
    i, total = 0, len(cw) * 8
    for right in range(n - 1, 0, -2):
        if right <= 6:
            right -= 1
        for vert in range(n):
            for j in range(2):
                x = right - j
                y = n - 1 - vert if ((right + 1) & 2) == 0 else vert
                if not fn[y][x] and i < total:
                    m[y][x] = bool((cw[i >> 3] >> (7 - (i & 7))) & 1)
                    i += 1
    masks = [lambda x, y: (x + y) % 2 == 0, lambda x, y: y % 2 == 0, lambda x, y: x % 3 == 0,
             lambda x, y: (x + y) % 3 == 0, lambda x, y: (x // 3 + y // 2) % 2 == 0,
             lambda x, y: x * y % 2 + x * y % 3 == 0, lambda x, y: (x * y % 2 + x * y % 3) % 2 == 0,
             lambda x, y: ((x + y) % 2 + x * y % 3) % 2 == 0]
    base, best = [row[:] for row in m], None
    for k, f in enumerate(masks):
        for y in range(n):
            for x in range(n):
                m[y][x] = base[y][x] ^ (f(x, y) and not fn[y][x])
        put_format(k)
        score = 0
        for rows in (m, list(zip(*m))):                 # long runs of one colour
            for row in rows:
                runlen = 1
                for a, b in zip(row, row[1:]):
                    runlen = runlen + 1 if a == b else 1
                    score += 3 if runlen == 5 else 1 if runlen > 5 else 0
        for y in range(n - 1):                          # 2x2 blocks
            for x in range(n - 1):
                score += 3 * (m[y][x] == m[y][x + 1] == m[y + 1][x] == m[y + 1][x + 1])
        dark = sum(map(sum, m)) * 100 // (n * n)
        score += abs(dark - 50) // 5 * 10
        if best is None or score < best[0]:
            best = (score, [row[:] for row in m])
    return best[1]


def qr_terminal(text, quiet=3):
    m = qr_matrix(text)
    n = len(m) + 2 * quiet
    g = [[False] * n for _ in range(n + 1)]
    for y, row in enumerate(m):
        for x, v in enumerate(row):
            g[y + quiet][x + quiet] = v
    lines = []
    for y in range(0, n, 2):
        s = "".join(" ▄▀█"[(2 if g[y][x] else 0) + (1 if g[y + 1][x] else 0)] for x in range(n))
        lines.append("\033[38;5;16;48;5;231m" + s + "\033[0m")   # black on white
    return "\n".join(lines)


# ----------------------------------------------------------------------- CLI

def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.0.2.1", 9))
        return s.getsockname()[0]
    except OSError:
        return None
    finally:
        s.close()


def agent_running():
    try:
        with socket.create_connection(("127.0.0.1", PORT), timeout=2):
            return True
    except OSError:
        return False


def cmd_pair():
    if not agent_running():
        print("The Curfew service is not running. Try: sudo systemctl restart curfew")
        return 1
    ip = lan_ip()
    if not ip:
        print("This computer is not connected to a network. Connect to the home Wi-Fi first.")
        return 1
    code = "".join(secrets.choice(CODE_ALPHABET) for _ in range(16))
    started = time.time()
    with state(write=True) as st:
        st["pairing"] = {"code": code, "expires": started + PAIR_SECONDS, "fails": 0}
        agent_id = st["id"]
    addr = ip if PORT == 787 else "%s:%d" % (ip, PORT)
    print("\nIn the Curfew app tap  Add computer  and scan this code:\n")
    print(qr_terminal("curfew://pair?a=%s:%d&i=%s&c=%s" % (ip, PORT, agent_id, code)))
    print("\nOr type it in by hand:")
    print("    Address:  " + addr)
    print("    Code:     " + "-".join(code[i:i + 4] for i in range(0, 16, 4)))
    print("\nThe code works once and expires in %d minutes. Waiting for the phone... (Ctrl+C to cancel)"
          % (PAIR_SECONDS // 60))
    try:
        while True:
            time.sleep(1)
            with state() as st:
                p, last = st["pairing"], st.get("last_paired")
            if last and last["when"] >= started and not (p and p["code"] == code):
                print("\nPaired with \"%s\". Done." % last["name"])
                return 0
            if not p or p["code"] != code:
                print("\nToo many wrong attempts; the code was cancelled. Run the command again.")
                return 1
            if time.time() > p["expires"]:
                print("\nThe code expired. Run the command again.")
                break
    except KeyboardInterrupt:
        print("\nCancelled.")
    with state(write=True) as st:
        if st["pairing"] and st["pairing"]["code"] == code:
            st["pairing"] = None
    return 1


def cmd_phones():
    with state() as st:
        phones = sorted(st["phones"].values(), key=lambda p: p["added"])
    if not phones:
        print("No phones are paired. Pair one with: sudo curfew pair")
    for i, p in enumerate(phones, 1):
        print("%2d. %-30s paired %s" % (i, p["name"], time.strftime("%d %b %Y", time.localtime(p["added"]))))
    return 0


def cmd_unpair(who):
    with state(write=True) as st:
        order = sorted(st["phones"].items(), key=lambda kv: kv[1]["added"])
        match = [pid for i, (pid, p) in enumerate(order, 1) if who == str(i) or p["name"].lower() == who.lower()]
        if len(match) != 1:
            print("No phone called \"%s\". See the list with: sudo curfew phones" % who)
            return 1
        p = st["phones"].pop(match[0])
        # keep the key only so this phone can be told, verifiably, that it was removed
        st["removed"][match[0]] = {"key": p["key"], "name": p["name"], "when": int(time.time())}
        for old in sorted(st["removed"], key=lambda k: st["removed"][k]["when"])[:-50]:
            del st["removed"][old]
    print("Removed \"%s\". It can no longer control this computer." % p["name"])
    return 0


def cmd_status():
    with state() as st:
        pass
    s = op_status({}, st)
    print("Curfew agent %s on %s (%s), port %d, %s" % (
        st["id"], s["name"], s["os"], PORT, "running" if agent_running() else "NOT running"))
    print("Paired phones: %d" % len(st["phones"]))
    t = s["timer"]
    print("Shut-down timer: " + ("%d min %02d s left" % divmod(t["remaining"], 60) if t else "none"))
    print("Login from the phone: " + ("ready" if login_hook() else "not set up (needs the GNOME login screen)"))
    locked = any(os.path.exists(p) for p in ("/etc/polkit-1/rules.d/10-curfew-network.rules",
                                             "/etc/polkit-1/localauthority/50-local.d/curfew-network.pkla"))
    print("Changing the network: " + ("administrators only" if locked else "anyone"))
    for u in s["users"]:
        nt = u.get("net_timer")
        net = "internet off" if u["net"] == "off" else "internet off in %d min %02d s" % divmod(nt["remaining"], 60) if nt else ""
        print("  %-20s %-9s %-10s %s" % (u["name"], "admin" if u["admin"] else "standard", u["state"].replace("_", " "), net))
    return 0


USAGE = """Curfew - control this computer from a paired phone.

  sudo curfew pair            show a QR code to pair a phone
  sudo curfew phones          list paired phones
  sudo curfew unpair NAME     remove a phone (name or number from the list)
  sudo curfew status          show the agent, timer and users
"""


def main(argv):
    global DRY, PORT, STATE_DIR, RUN_DIR
    if argv[:1] == ["keyring"] and len(argv) == 2:      # runs as the account, not as root
        return cmd_keyring(argv[1])
    args = [a for a in argv if a != "--dry-run"]
    if len(args) != len(argv) or os.environ.get("CURFEW_DRY") == "1":
        DRY, PORT = True, int(os.environ.get("CURFEW_PORT", "8787"))
        STATE_DIR = os.environ.get("CURFEW_STATE") or os.path.expanduser("~/.local/state/curfew-dryrun")
        RUN_DIR = os.path.join(STATE_DIR, "run")
    os.umask(0o077)
    cmd = args[0] if args else "help"
    if cmd in ("help", "-h", "--help"):
        print(USAGE)
        return 0
    if not DRY and os.geteuid() != 0:
        print("This needs administrator rights. Run it as: sudo curfew " + " ".join(args))
        return 1
    if cmd == "serve":
        return serve()
    if cmd == "pam-login":
        return cmd_pam_login()
    if cmd == "pam-remember":
        return cmd_pam_remember()
    if cmd == "id":
        with state() as st:
            print(st["id"])
        return 0
    if cmd == "pair":
        return cmd_pair()
    if cmd == "phones":
        return cmd_phones()
    if cmd == "unpair" and len(args) >= 2:
        return cmd_unpair(" ".join(args[1:]))
    if cmd == "status":
        return cmd_status()
    if cmd == "apps" and len(args) == 2:       # debugging aid: what the phone would see
        u = find_user({"user": args[1]})
        print(json.dumps(open_apps(u), indent=1) if u else "no such user")
        return 0
    if cmd == "browsers" and len(args) == 2:   # debugging aid: the browser activity the phone would see
        u = find_user({"user": args[1]})
        print(json.dumps(browser_activity(u), indent=1) if u else "no such user")
        return 0
    print(USAGE)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
