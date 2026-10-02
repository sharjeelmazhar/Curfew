#!/usr/bin/env python3
"""Curfew agent: lets a paired phone see and control this computer over the LAN.

One file, standard library only, Python 3.10+. It is both the root daemon
("curfew serve", started by systemd) and the admin command ("sudo curfew pair",
"sudo curfew phones", "sudo curfew unpair NAME", "curfew status").

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
import socket
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PROTO = 1
PORT = 787                      # below 1024: a standard user cannot bind it
STATE_DIR = "/var/lib/curfew"
DRY = False                     # dry-run: log "would ..." instead of acting
PAIR_SECONDS = 120
PAIR_MAX_FAILS = 5
NONCE_SECONDS = 60
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
        for k in ("phones", "removed"):
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

    def new(self):
        n, now = secrets.token_hex(16), time.monotonic()
        with self.lock:
            for k in [k for k, exp in self.live.items() if exp < now]:
                del self.live[k]
            while len(self.live) >= 1024:
                del self.live[next(iter(self.live))]
            self.live[n] = now + NONCE_SECONDS
        return n

    def use(self, n):
        with self.lock:
            exp = self.live.pop(n, None)
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
MINECRAFT = re.compile(r"minecraft|tlauncher|launchwrapper|multimc|prismlauncher|lunarclient|badlion", re.I)
TERM_SCOPE = re.compile(r"^(vte-spawn-|ptyxis-spawn-|tmux-spawn-|foot|kgx-)")
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
    m = re.match(r"^app-(.+?)-(\d+|[0-9a-f]{32})\.scope$", unit) or re.match(r"^app-(.+?)@[^@]*\.service$", unit)
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

    in_term = lambda p: p["pts"] or bool(TERM_SCOPE.match(p["cg"].rsplit("/", 1)[-1]))
    cand = {pid for pid, p in procs.items()
            if in_term(p) and p["comm"] not in SHELLS and not TERM_NOISE.match(p["comm"]) and os.path.basename(p["argv"][0]).lstrip("-") not in SHELLS}
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
            entry = (ids[-1].split(".")[-1].replace("_", " ").replace("-", " ").title(), False)
        if entry[1]:
            continue
        add(entry[0], "", p["age"], False)
    return sorted(apps.values(), key=lambda a: a["age"])[:15]


# ------------------------------------------------------------------- actions

def power(kind):
    if kind == "reboot":
        act("restart the computer", ["systemctl", "reboot", "-i"])
    else:
        act("power off", ["systemctl", "poweroff", "-i"])


def notify_all(text):
    for s in sessions():
        if s["graphical"]:
            with contextlib.suppress(KeyError):
                uid = pwd.getpwnam(s["user"]).pw_uid
                act("notify %s: %s" % (s["user"], text),
                    ["runuser", "-u", s["user"], "--", "env",
                     "DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/%d/bus" % uid,
                     "notify-send", "-u", "critical", "-a", "Curfew", "Curfew", text])


TIMER_WAKE = threading.Event()


def timer_loop():
    """Owns the power-off countdown. The deadline is a wall-clock time in the
    state file, so it survives a restart of the agent and a reboot."""
    while True:
        fire = warn = False
        with state() as st:
            t = st["timer"]
        wait = 30.0
        if t:
            left = t["deadline"] - time.time()
            if left <= 0:
                fire = True
            else:
                warn = t.get("warn") and not t.get("warned") and left <= 60
                wait = min(left, 5.0)       # short ticks also catch waking from suspend
        if fire or warn:
            with state(write=True) as st:
                if st["timer"] and fire:
                    st["timer"] = None
                elif st["timer"]:
                    st["timer"]["warned"] = True
            if fire:
                log("timer reached zero")
                power("poweroff")
            else:
                notify_all("This computer will switch off in 1 minute.")
            continue
        TIMER_WAKE.wait(wait)
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


def op_status(req, st):
    states = user_states()
    users = [{"name": u["name"], "full": u["full"], "admin": u["admin"],
              "state": states.get(u["name"], "none")} for u in human_users()]
    if demo():
        users = demo()["users"]
    t = st["timer"]
    timer = {"remaining": max(0, int(t["deadline"] - time.time())), "warn": bool(t.get("warn"))} if t else None
    return {"ok": True, "id": st["id"], "name": host_name(), "os": os_name(),
            "users": users, "timer": timer, "dry": DRY}


def find_user(req):
    return next((u for u in human_users() if u["name"] == req.get("user")), None)


def op_apps(req, st):
    d = demo()
    if d:
        ok = any(u["name"] == req.get("user") for u in d["users"])
        return {"ok": True, "apps": d.get("apps", {}).get(req.get("user"), [])} if ok else {"ok": False, "error": "bad_user"}
    u = find_user(req)
    return {"ok": True, "apps": open_apps(u)} if u else {"ok": False, "error": "bad_user"}


def op_power(req, st):
    threading.Timer(1.0, power, [req["op"]]).start()     # after the reply is sent
    return {"ok": True}


def op_timer_set(req, st):
    minutes, warn = req.get("minutes"), bool(req.get("warn"))
    if not isinstance(minutes, int) or isinstance(minutes, bool) or not 1 <= minutes <= 1440:
        return {"ok": False, "error": "bad_args"}
    with state(write=True) as s:
        s["timer"] = {"deadline": time.time() + minutes * 60, "warn": warn, "warned": minutes <= 1}
    log("timer set: power off in %d min" % minutes)
    TIMER_WAKE.set()
    if warn:
        threading.Thread(target=notify_all, daemon=True, args=(
            "This computer will switch off in %d minute%s." % (minutes, "" if minutes == 1 else "s"),)).start()
    return {"ok": True, "timer": {"remaining": minutes * 60, "warn": warn}}


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
    ids = [s["id"] for s in sessions() if s["user"] == u["name"] and s["graphical"]]
    ok = bool(ids) and all([act("lock the screen of " + u["name"], ["loginctl", "lock-session", i]) for i in ids])
    return {"ok": True} if ok else {"ok": False, "error": "not_logged_in" if not ids else "failed"}


def op_logout(req, st):
    if demo():
        return op_demo_action(req)
    u = find_user(req)
    if not u:
        return {"ok": False, "error": "bad_user"}
    if not any(s["user"] == u["name"] for s in sessions()):
        return {"ok": False, "error": "not_logged_in"}
    ok = act("log out " + u["name"], ["loginctl", "terminate-user", u["name"]])
    return {"ok": True} if ok else {"ok": False, "error": "failed"}


def op_forget(req, st):
    with state(write=True) as s:
        gone = s["phones"].pop(req["_phone"], None)
    log("phone %r removed itself" % (gone or {}).get("name"))
    return {"ok": True}


OPS = {"status": op_status, "apps": op_apps, "poweroff": op_power, "reboot": op_power,
       "timer_set": op_timer_set, "timer_cancel": op_timer_cancel, "lock": op_lock,
       "logout": op_logout, "forget": op_forget}


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
                         "name": host_name(), "nonce": NONCES.new()})

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
    daemon_threads, allow_reuse_address, request_queue_size = True, True, 32


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
    print("Power-off timer: " + ("%d min %02d s left" % divmod(t["remaining"], 60) if t else "none"))
    for u in s["users"]:
        print("  %-20s %-9s %s" % (u["name"], "admin" if u["admin"] else "standard", u["state"].replace("_", " ")))
    return 0


USAGE = """Curfew - control this computer from a paired phone.

  sudo curfew pair            show a QR code to pair a phone
  sudo curfew phones          list paired phones
  sudo curfew unpair NAME     remove a phone (name or number from the list)
  sudo curfew status          show the agent, timer and users
"""


def main(argv):
    global DRY, PORT, STATE_DIR
    args = [a for a in argv if a != "--dry-run"]
    if len(args) != len(argv) or os.environ.get("CURFEW_DRY") == "1":
        DRY, PORT = True, int(os.environ.get("CURFEW_PORT", "8787"))
        STATE_DIR = os.environ.get("CURFEW_STATE") or os.path.expanduser("~/.local/state/curfew-dryrun")
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
    print(USAGE)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
