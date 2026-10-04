#!/usr/bin/env python3
"""End-to-end tests: starts the agent in dry-run mode on a spare port with a
throw-away state folder and talks to it over real HTTP.  Run: python3 test_agent.py"""
import hashlib
import hmac
import json
import os
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
PORT = 18787
TMP = tempfile.mkdtemp(prefix="curfew-test-")
ENV = dict(os.environ, CURFEW_DRY="1", CURFEW_PORT=str(PORT), CURFEW_STATE=TMP)
BASE = "http://127.0.0.1:%d" % PORT
LOG = open(os.path.join(TMP, "agent.log"), "w")
passed = 0


def check(name, cond):
    global passed
    if not cond:
        print("FAIL  " + name)
        sys.exit(1)
    passed += 1
    print("ok    " + name)


def start(fake=None):
    if fake and not fake.startswith("/"):
        fake = HERE + "/../tools/demo/%s.json" % fake
    env = dict(ENV, CURFEW_FAKE=fake) if fake else ENV
    p = subprocess.Popen([sys.executable, HERE + "/curfew.py", "serve"], env=env, stderr=LOG)
    for _ in range(50):
        try:
            http("GET", "/v1/hello")
            return p
        except OSError:
            time.sleep(0.1)
    raise SystemExit("agent did not start")


def http(method, path, body=None, raw=None, timeout=5):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(BASE + path, data=data, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def mac(key, *parts):
    return hmac.new(key, "\n".join(parts).encode(), hashlib.sha256).hexdigest()


def nonce():
    return http("GET", "/v1/hello")[1]["nonce"]


def cli(*args, **env):
    return subprocess.run([sys.executable, HERE + "/curfew.py", *args], env=dict(ENV, **env), capture_output=True, text=True)


def elsewhere():
    """A connection to the agent from another address than the tests use."""
    s = socket.socket()
    s.bind(("127.0.0.2", 0))
    s.settimeout(5)
    s.connect(("127.0.0.1", PORT))
    return s


def state():
    with open(TMP + "/state.json") as f:
        return json.load(f)


def set_pairing(code, expires_in=120):
    st = state()
    st["pairing"] = {"code": code, "expires": time.time() + expires_in, "fails": 0}
    with open(TMP + "/state.json", "w") as f:
        json.dump(st, f)


def pair(code, name, use_code=None):
    s, c = nonce(), secrets.token_hex(16)
    payload = json.dumps({"name": name})
    status, r = http("POST", "/v1/pair", {"snonce": s, "cnonce": c, "payload": payload,
                                          "mac": mac((use_code or code).encode(), "pair", s, c, payload)})
    if status != 200:
        return status, r
    key = hmac.new(code.encode(), "\n".join(("key", s, c, r["phone"])).encode(), hashlib.sha256).digest()
    assert r["mac"] == mac(key, "res", r["phone"], s, c, r["payload"]), "pair response not authentic"
    return 200, {"phone": r["phone"], "key": key, "info": json.loads(r["payload"])}


def call(ph, op, envelope_only=False, timeout=5, **args):
    s, c = nonce(), secrets.token_hex(16)
    payload = json.dumps(dict(args, op=op))
    env = {"phone": ph["phone"], "snonce": s, "cnonce": c, "payload": payload,
           "mac": mac(ph["key"], "req", ph["phone"], s, c, payload)}
    if envelope_only:
        return env
    status, r = http("POST", "/v1/call", env, timeout=timeout)
    if status != 200:
        return status, r
    assert r["mac"] == mac(ph["key"], "res", ph["phone"], s, c, r["payload"]), "response not authentic"
    return 200, json.loads(r["payload"])


sys.path.insert(0, HERE)
import curfew  # noqa: E402
fc = lambda *argv: curfew.friendly_command(list(argv), {"firefox": "Firefox"})[0]
check("java -jar is named after the jar", fc("java", "-Xmx2G", "-jar", "/home/k/TLauncher-2.9.jar") == "TLauncher-2.9.jar (Java)")
check("java main class is named", fc("/usr/bin/java", "-cp", "a.jar:b.jar", "net.minecraft.client.main.Main", "--x") == "Main (Java)")
check("python script is named", fc("python3", "-u", "game.py") == "game.py (Python)")
check("wrappers are skipped", fc("env", "FOO=1", "nohup", "firefox", "--new-window") == "Firefox")
check("unknown program falls back to its command", fc("/opt/x/weird-game", "--fast") == "weird-game")
ids = curfew.unit_app_ids
check("gnome app scope parsed", ids("/user.slice/u/app.slice/app-gnome-org.gnome.Nautilus-123.scope")[-1] == "org.gnome.Nautilus")
check("escaped id parsed", "google-chrome" in ids("/a/app-gnome-google\\x2dchrome-99.scope"))
check("snap scope parsed", ids("/a/snap.firefox.firefox-0b6d2f0e-1111-2222-3333-444455556666.scope")[0] == "firefox_firefox")
check("an app running as its own service is named (Ghostty)", ids("/u/app.slice/app-com.mitchellh.ghostty.service") == ["com.mitchellh.ghostty"])
check("autostart service ignored", ids("/a/app-gnome-foo\\x2dautostart@autostart.service") == [])
check("QR too long is rejected cleanly", not hasattr(curfew, "x") and len(curfew.qr_matrix("a" * 106)) == 37)

# --- screen time, one look at a time
day = lambda t: time.strftime("%Y-%m-%d", time.localtime(t))
noon = time.mktime((2026, 10, 2, 12, 0, 0, 0, 0, -1))
kid = lambda state, **k: dict({"name": "kid", "state": state}, **k)
d = {"users": {}}
curfew.usage_step(d, [kid("active", since=noon - 300), {"name": "dad", "state": "none"}], noon, 0)
check("a login is noted with the time logind gives", d["users"]["kid"]["logins"] == [[int(noon - 300), None]] and "dad" not in d["users"])
curfew.usage_step(d, [kid("active")], noon + 15, 15)
curfew.usage_step(d, [kid("locked")], noon + 30, 15)
curfew.usage_step(d, [kid("logged_in")], noon + 45, 15)
check("only time on the screen, unlocked, counts as use", d["users"]["kid"]["days"][day(noon)] == {"used": 15, "on": 45} and d["users"]["kid"]["boot"] == {"used": 15, "on": 45})
curfew.usage_step(d, [kid("none")], noon + 60, 15)
check("a logout ends the login", d["users"]["kid"]["logins"] == [[int(noon - 300), int(noon + 60)]])
curfew.usage_step(d, [kid("active")], noon + 120, 15)
curfew.usage_step(d, [kid("active")], noon + 135, 15)
check("logging in again adds to the same day", d["users"]["kid"]["days"][day(noon)]["used"] == 45 and len(d["users"]["kid"]["logins"]) == 2)
curfew.usage_step(d, [kid("active")], noon + 86400, 15)
days = d["users"]["kid"]["days"]
check("a new day starts from zero and keeps yesterday", days[day(noon + 86400)]["used"] == 15 and days[day(noon)]["used"] == 45)
curfew.usage_step(d, [kid("active")], noon + 7 * 86400, 15)
e = d["users"]["kid"]
check("older days and their logins are forgotten", sorted(e["days"]) == [day(noon + 86400), day(noon + 7 * 86400)] and e["logins"] == [[int(noon + 120), None]])
check("logind's time is read", curfew.stamp("Fri 2026-10-02 12:00:00 PKT") == int(noon) and curfew.stamp("") is None)

# --- the Wi-Fi stays on (a made-up /sys/class/rfkill; nothing real is touched)
rf = tempfile.mkdtemp(prefix="curfew-rfkill-")
KID = __import__("pwd").getpwuid(os.getuid()).pw_name       # must exist: its session bus is looked up
def radio(n, kind, soft):
    os.makedirs("%s/rfkill%d" % (rf, n), exist_ok=True)
    for name, text in (("type", kind), ("soft", soft)):
        with open("%s/rfkill%d/%s" % (rf, n, name), "w") as f:
            f.write(text + "\n")
soft = lambda n: open("%s/rfkill%d/soft" % (rf, n)).read().strip()
real = (curfew.RFKILL_DIR, curfew.sessions, curfew.human_users, curfew.act, curfew.notify, curfew.DRY, curfew.event_add)
did, told, events = [], [], []
curfew.event_add = lambda kind, **f: events.append((kind, f))
curfew.RFKILL_DIR, curfew.DRY = rf, False
curfew.human_users = lambda: [{"name": "dad", "admin": True}, {"name": KID, "admin": False}]
curfew.act = lambda what, argv: did.append(argv) or True
curfew.notify = lambda text, only=None: told.append(only)
front = lambda user: [{"id": "7", "user": user, "graphical": True, "active": True, "locked": False, "since": None}]
radio(0, "bluetooth", "1"); radio(1, "wlan", "0")
curfew.sessions = lambda: front(KID)
check("Wi-Fi that is on is left alone, and so is Bluetooth", curfew.keep_wifi_on([], 0) is False and soft(0) == "1" and did == [])
radio(1, "wlan", "1")
curfew.sessions = lambda: front("dad")
check("an administrator may use airplane mode", curfew.keep_wifi_on([], 0) is False and soft(1) == "1")
curfew.sessions = lambda: front(KID)
strikes = []
check("airplane mode in a standard account is undone", curfew.keep_wifi_on(strikes, 10) is True and soft(1) == "0" and soft(0) == "1" and told == [KID])
check("the first time only turns the Wi-Fi back on", not any("lock-session" in a for a in did))
radio(1, "wlan", "1"); curfew.keep_wifi_on(strikes, 20)
radio(1, "wlan", "1"); curfew.keep_wifi_on(strikes, 30)
check("switching it off again and again locks the screen", ["loginctl", "lock-session", "7"] in did and strikes == [] and soft(1) == "0")
check("the account's own 'never lock' setting is put back before locking",
      any(a[-4:] == ["set", "org.gnome.desktop.lockdown", "disable-lock-screen", "false"] and a[:3] == ["runuser", "-u", KID] for a in did))
del did[:]
curfew._reported[(KID, "airplane")] -= 120         # as if a few minutes had gone by
radio(1, "wlan", "1"); curfew.keep_wifi_on(strikes, 400); radio(1, "wlan", "1"); curfew.keep_wifi_on(strikes, 500); radio(1, "wlan", "1"); curfew.keep_wifi_on(strikes, 600)
check("now and then over a long time does not lock", not any("lock-session" in a for a in did))
radio(1, "wlan", "1")
curfew.sessions = lambda: []
check("airplane mode at the login screen is undone too", curfew.keep_wifi_on([], 0) is True and soft(1) == "0")
check("airplane mode is reported to the phones, the same one at most once a minute",
      [e for e in events if e[0] == "tamper"] == [("tamper", {"user": KID, "what": "airplane"}), ("tamper", {"user": KID, "what": "airplane"}),
                                                   ("tamper", {"user": None, "what": "airplane"})])

# --- a child trying to change the network, as NetworkManager writes it down
del events[:]
real_pw = curfew.pwd.getpwuid
curfew.pwd.getpwuid = lambda uid: type("P", (), {"pw_name": {1001: KID, 1000: "dad"}[uid]})
line = lambda op, uid, result="fail", reason="org.freedesktop.NetworkManager.network-control request failed: not authorized": (
    '<info>  [1790933143.4851] audit: op="%s" interface="wlp2s0" ifindex=2 pid=25404 uid=%d result="%s"' % (op, uid, result)
    + (' reason="%s"' % reason if reason else ""))
check("a refused disconnect by a child is noticed", curfew.nm_refused(line("device-disconnect", 1001)) == (KID, "disconnect"))
check("a refused new Wi-Fi password is noticed", curfew.nm_refused(line("connection-update", 1001, reason="Insufficient privileges")) == (KID, "wifi_settings"))
check("turning the Wi-Fi off is noticed", curfew.nm_refused(line("radio-control", 1001)) == (KID, "wifi_off"))
check("anything else refused counts as changing the network", curfew.nm_refused(line("checkpoint-create", 1001)) == (KID, "network"))
check("what an administrator does is not reported", curfew.nm_refused(line("device-disconnect", 1000)) is None)
check("what went through is not reported", curfew.nm_refused(line("device-disconnect", 1001, "success", None)) is None)
check("a failure for another reason is not reported", curfew.nm_refused(line("connection-activate", 1001, reason="no secrets")) is None)
check("other lines are ignored", curfew.nm_refused("<info> device (wlp2s0): state change: activated") is None)
check("tries are reported once a minute at most", curfew.report(KID, "disconnect") and not curfew.report(KID, "disconnect")
      and curfew.report(KID, "wifi_settings") and len(events) == 2)
curfew.pwd.getpwuid = real_pw
curfew.RFKILL_DIR, curfew.sessions, curfew.human_users, curfew.act, curfew.notify, curfew.DRY, curfew.event_add = real
shutil.rmtree(rf, ignore_errors=True)

# --- a limit whose ending fails (loginctl timed out, say) is tried again, and only until it works
real_dir, curfew.STATE_DIR = curfew.STATE_DIR, tempfile.mkdtemp(prefix="curfew-limit-")
with curfew.state(write=True) as st:
    st["limits"]["kid"] = {"minutes": 1, "action": "logout", "warn": False, "tell": True}
seen = {"users": {"kid": {"days": {curfew.kept_days(time.time())[0]: {"used": 100, "on": 100}}, "logins": []}}}
on_screen = [{"name": "kid", "admin": False, "state": "active"}]
real2, tries = (curfew.act, curfew.notify, curfew.event_add, curfew.LIMIT_GRACE), []
curfew.act = lambda what, argv: tries.append(argv) or len(tries) > 1
curfew.notify, curfew.event_add, curfew.LIMIT_GRACE = (lambda *a, **k: None), (lambda *a, **k: None), 0
for _ in range(5):
    curfew.limits_check(seen, on_screen, time.time())
check("a failed logout at the end of the time is tried again, then left alone",
      tries == [["loginctl", "terminate-user", "kid"]] * 2)
curfew.act, curfew.notify, curfew.event_add, curfew.LIMIT_GRACE = real2
shutil.rmtree(curfew.STATE_DIR, ignore_errors=True)
curfew.STATE_DIR = real_dir
curfew.LIMIT_RUN.clear()

agent = start()
try:
    st, hello = http("GET", "/v1/hello")
    check("hello gives id and nonce", st == 200 and hello["app"] == "curfew" and len(hello["nonce"]) == 32)
    check("state file is private", oct(os.stat(TMP + "/state.json").st_mode & 0o777) == "0o600")

    # --- pairing
    check("pair with no code pending is refused", pair("AAAABBBBCCCCDDDD", "x")[0] == 403)
    set_pairing("AAAABBBBCCCCDDDD")
    check("wrong code is refused", pair("AAAABBBBCCCCDDDD", "x", use_code="ZZZZZZZZZZZZZZZZ")[1] == {"error": "bad_code"})
    st, a = pair("AAAABBBBCCCCDDDD", "Dad's Galaxy")
    check("correct code pairs", st == 200 and a["info"]["phone_name"] == "Dad's Galaxy")
    check("used code cannot be used again", pair("AAAABBBBCCCCDDDD", "kid")[0] == 403)
    set_pairing("EEEEFFFFGGGGHHHH", expires_in=-1)
    check("expired code is refused", pair("EEEEFFFFGGGGHHHH", "kid")[1] == {"error": "no_pairing"})
    set_pairing("JJJJKKKKLLLLMMMM")
    for _ in range(5):
        pair("JJJJKKKKLLLLMMMM", "kid", use_code="WRONGWRONGWRONGW")
    check("code is cancelled after 5 wrong tries", pair("JJJJKKKKLLLLMMMM", "kid")[1] == {"error": "no_pairing"})
    set_pairing("NNNNPPPPQQQQRRRR")
    st, b = pair("NNNNPPPPQQQQRRRR", "Dad's Galaxy")
    check("second phone pairs, name made unique", st == 200 and b["info"]["phone_name"] == "Dad's Galaxy 2")
    check("phones have different keys", a["key"] != b["key"])

    # --- authenticated calls
    st, s = call(a, "status")
    check("status works", st == 200 and s["ok"] and s["id"] == hello["id"] and s["dry"] is True)
    check("status lists users with states", all(u["state"] in ("none", "logged_in", "locked", "active") for u in s["users"]) and s["users"])
    check("greeter is not listed", all(u["name"] not in ("gdm", "gdm-greeter") for u in s["users"]))
    me = next(u["name"] for u in s["users"])
    st, r = call(a, "apps", user=me)
    check("apps list works", st == 200 and r["ok"] and isinstance(r["apps"], list))
    check("unknown user refused", call(a, "apps", user="root")[1] == {"ok": False, "error": "bad_user"})
    check("status advertises the browsers capability", "browsers" in s["caps"])
    st, r = call(a, "browsers", user=me)
    check("browsers op works", st == 200 and r["ok"] and isinstance(r["browsers"], list))
    for br in r["browsers"]:
        shape_ok = {"id", "name", "open", "recent"} <= set(br) and isinstance(br["open"], list) and isinstance(br["recent"], list)
        entries_ok = all({"url", "title", "host", "when", "search"} <= set(e) for e in br["open"] + br["recent"])
        check("browser %s has the right shape" % br.get("id"), shape_ok and entries_ok)
    check("browsers for an unknown user refused", call(a, "browsers", user="root")[1] == {"ok": False, "error": "bad_user"})
    check("unknown op refused", call(a, "run", cmd="id")[1] == {"ok": False, "error": "bad_op"})

    # --- bad key / replay / malformed
    bad = dict(a, key=b"\x00" * 32)
    check("bad key is refused", http("POST", "/v1/call", call(bad, "status", envelope_only=True)) == (403, {"error": "bad_mac"}))
    env = call(a, "poweroff", envelope_only=True)
    check("first use of a request works", http("POST", "/v1/call", env)[0] == 200)
    check("replayed request is refused", http("POST", "/v1/call", env) == (409, {"error": "bad_nonce"}))
    env = call(a, "status", envelope_only=True)
    env["payload"] = json.dumps({"op": "poweroff"})
    check("tampered request is refused", http("POST", "/v1/call", env)[0] == 403)
    check("unknown phone is refused", http("POST", "/v1/call", call(dict(a, phone="deadbeefdeadbeef"), "status", envelope_only=True)) == (403, {"error": "unknown_phone"}))
    check("malformed JSON is refused", http("POST", "/v1/call", raw=b"{not json")[0] == 400)
    check("missing fields are refused", http("POST", "/v1/call", {"phone": "x"})[0] == 400)
    check("non-object body is refused", http("POST", "/v1/call", [1, 2])[0] == 400)
    check("oversized body is refused", http("POST", "/v1/call", raw=b"x" * 20000)[0] == 400)
    check("unknown path is 404", http("GET", "/etc/passwd")[0] == 404)
    check("agent survived the bad requests", call(a, "status")[0] == 200)

    # --- someone flooding the agent from another address does not keep the phone out
    mine = nonce()
    for _ in range(60):
        with elsewhere() as sock:
            sock.sendall(b"GET /v1/hello HTTP/1.0\r\n\r\n")
            while sock.recv(4096):
                pass
    env = call(a, "status", envelope_only=True)
    env["snonce"] = mine
    env["mac"] = mac(a["key"], "req", a["phone"], mine, env["cnonce"], env["payload"])
    check("a flood of hellos from elsewhere does not push out our nonce", http("POST", "/v1/call", env)[0] == 200)
    idle = [elsewhere() for _ in range(30)]
    time.sleep(0.3)
    check("idle connections from elsewhere do not block the phone", call(a, "status")[0] == 200)
    for sock in idle:
        sock.close()

    # --- timer
    check("status says what the agent can do", {"seconds", "net", "login"} <= set(s["caps"]))
    check("bad timer value refused", call(a, "timer_set", minutes=0)[1]["ok"] is False)
    check("too short a timer refused", call(a, "timer_set", seconds=5)[1] == {"ok": False, "error": "bad_args"})
    check("timer in seconds", call(a, "timer_set", seconds=15)[1]["timer"]["remaining"] == 15)
    check("notices say the length in words", curfew.human(30) == "30 seconds" and curfew.human(60) == "1 minute"
          and curfew.human(5400) == "1 hour 30 minutes" and curfew.human(7200) == "2 hours")
    check("timer set", call(a, "timer_set", minutes=30, warn=True)[1]["timer"]["remaining"] == 1800)
    time.sleep(1.2)
    rem = call(a, "status")[1]["timer"]["remaining"]
    check("timer counts down", 1795 <= rem < 1800)
    agent.terminate(); agent.wait()
    agent = start()
    rem2 = call(a, "status")[1]["timer"]["remaining"]
    check("timer survives an agent restart", 1790 <= rem2 <= rem)
    check("timer cancel", call(a, "timer_cancel")[1] == {"ok": True, "timer": None} and call(a, "status")[1]["timer"] is None)
    agent.terminate(); agent.wait()
    stt = state(); stt["timer"] = {"deadline": time.time() + 2, "warn": False, "warned": True}
    json.dump(stt, open(TMP + "/state.json", "w"))
    agent = start()                      # like a reboot shortly before the deadline
    time.sleep(3.5)
    LOG.flush()
    check("timer fires after restart ('would power off')", "would power off" in open(TMP + "/agent.log").read().split("timer reached zero")[-1])
    check("fired timer is cleared", state()["timer"] is None)

    # --- lock / logout (dry-run only logs)
    r = call(a, "lock", user=me)[1]
    check("lock answers", r == {"ok": True} or r["error"] == "not_logged_in")
    r = call(a, "logout", user=me)[1]
    check("logout answers", r == {"ok": True} or r["error"] == "not_logged_in")

    # --- log in from the phone: the login screen asks 'curfew pam-login'
    token = TMP + "/run/login/" + me
    pam = lambda user=me, kind="auth": cli("pam-login", PAM_USER=user, PAM_TYPE=kind).returncode
    check("no approval, no login", pam() == 1)
    r = call(a, "login", user=me)[1]
    check("login approved", r["ok"] and r["how"] in ("approved", "unlocked") and os.path.exists(token))
    check("approval is private", oct(os.stat(token).st_mode & 0o777) == "0o600")
    check("approval is for that account only", pam("root") == 1 and pam("nobody") == 1 and pam("../" + me) == 1)
    check("approval is not used outside a login", pam(kind="account") == 1 and os.path.exists(token))
    check("the approval names the phone", open(token).read().partition("\n")[2] != "")
    check("approved login is let in", pam() == 0)
    how = open(TMP + "/run/how/" + me).read()
    check("the login is noted as from that phone", how.startswith("phone\n") and len(how) > 6
          and curfew.how_of.__doc__ is not None)
    check("approval works once", pam() == 1 and not os.path.exists(token))
    call(a, "login", user=me)
    open(token, "w").write(str(time.time() - 1))
    check("expired approval is refused and removed", pam() == 1 and not os.path.exists(token))
    call(a, "login", user=me)
    call(a, "lock", user=me)
    check("locking takes the approval back", not os.path.exists(token))
    check("login for unknown account refused", call(a, "login", user="root")[1] == {"ok": False, "error": "bad_user"})

    # --- the typed login password is remembered, to unlock the keyring after a login from the phone
    keep = lambda user, text, kind="auth": subprocess.run([sys.executable, HERE + "/curfew.py", "pam-remember"], input=text,
                                                         env=dict(ENV, PAM_USER=user, PAM_TYPE=kind)).returncode
    kept = lambda: json.load(open(TMP + "/passwords.json")) if os.path.exists(TMP + "/passwords.json") else {}
    check("a password for an unknown account is not kept", keep("root", b"x\0") == 1 and kept() == {})
    check("nothing is kept outside a login", keep(me, b"x\0", "account") == 1 and kept() == {})
    check("a typed password is kept", keep(me, "pässword one\0".encode()) == 0 and kept() == {me: "pässword one"})
    check("a newer one replaces it", keep(me, b"two") == 0 and kept() == {me: "two"})
    check("kept passwords are private", oct(os.stat(TMP + "/passwords.json").st_mode & 0o777) == "0o600")
    call(a, "login", user=me)
    time.sleep(0.5)
    LOG.flush()
    check("a login from the phone unlocks the keyring", "would unlock the keyring of " + me in open(TMP + "/agent.log").read())
    if shutil.which("gnome-keyring-daemon") and shutil.which("dbus-run-session") and shutil.which("gdbus"):
        # a real keyring service in a throw-away home: locked at start, as after a login without a password
        kr = tempfile.mkdtemp(prefix="k", dir="/tmp")       # short: socket paths have a length limit
        script = """
            q() { gdbus call --session --dest org.freedesktop.secrets --object-path /org/freedesktop/secrets/collection/login \\
                  --method org.freedesktop.DBus.Properties.Get org.freedesktop.Secret.Collection Locked 2>/dev/null | tr -d '\\n'; }
            gnome-keyring-daemon --foreground --components=secrets --control-directory=$XDG_RUNTIME_DIR/keyring >/dev/null 2>&1 & D=$!
            sleep 1.5
            printf 'first' | %(py)s %(here)s/curfew.py keyring $XDG_RUNTIME_DIR/keyring/control; echo "new:$? $(q)"
            kill $D; wait $D 2>/dev/null
            gnome-keyring-daemon --foreground --components=secrets --control-directory=$XDG_RUNTIME_DIR/keyring >/dev/null 2>&1 & D=$!
            sleep 1.5; echo "start:$(q)"
            printf 'wrong' | %(py)s %(here)s/curfew.py keyring $XDG_RUNTIME_DIR/keyring/control; echo "wrong:$? $(q)"
            printf 'first' | %(py)s %(here)s/curfew.py keyring $XDG_RUNTIME_DIR/keyring/control; echo "right:$? $(q)"
            kill $D; wait $D 2>/dev/null
        """ % {"py": sys.executable, "here": HERE}
        os.makedirs(kr + "/run", mode=0o700); os.makedirs(kr + "/home")
        out = subprocess.run(["dbus-run-session", "--", "sh", "-c", script], capture_output=True, text=True, timeout=60,
                             env={"PATH": os.environ["PATH"], "HOME": kr + "/home", "XDG_RUNTIME_DIR": kr + "/run"}).stdout
        shutil.rmtree(kr, ignore_errors=True)
        check("keyring of a new account is created unlocked", "new:0 (<false>,)" in out)
        check("keyring is locked after a login without the password", "start:(<true>,)" in out)
        check("a wrong remembered password leaves it locked", "wrong:1 (<true>,)" in out)
        check("the remembered password unlocks it", "right:0 (<false>,)" in out)

    # --- internet per account (made-up accounts: dad is admin, gaming and classes are not)
    agent.terminate(); agent.wait()
    agent = start(fake="kids")
    users = lambda: {u["name"]: u for u in call(a, "status")[1]["users"]}
    # --- screen time (classes has its screen locked, gaming is in use, dad is not logged in)
    began = int(time.time())
    check("status says the agent keeps screen time", "usage" in call(a, "status")[1]["caps"])
    u = users()
    check("status says since when an account is logged in", abs(u["gaming"]["since"] - began) <= 5 and "since" not in u["dad"] and u["dad"]["today"] == 0)
    time.sleep(2.2)
    check("time in use shows in status", users()["gaming"]["today"] >= 2 and users()["classes"]["today"] == 0)
    r = call(a, "usage")[1]
    use = {x["name"]: x for x in r["users"]}
    check("usage lists today and yesterday for every account", r["ok"] and set(use) == {"dad", "classes", "gaming"}
          and [x["date"] for x in use["dad"]["days"]] == curfew.kept_days(time.time()) and abs(r["now"] - time.time()) <= 5 and r["boot"] > 0)
    check("usage counts the account in use", use["gaming"]["days"][0]["used"] >= 2 and use["gaming"]["boot"]["used"] >= 2 and use["gaming"]["logins"][0]["end"] is None)
    check("a locked screen is logged in but not in use", use["classes"]["days"][0] == dict(use["classes"]["days"][0], used=0) and use["classes"]["days"][0]["on"] >= 2)
    check("an account that never logged in has nothing", use["dad"]["logins"] == [] and use["dad"]["days"][0]["used"] == 0)
    agent.terminate(); agent.wait()
    saved = json.load(open(TMP + "/usage.json"))
    check("screen time is written when the agent stops", saved["users"]["gaming"]["boot"]["used"] >= 2 and oct(os.stat(TMP + "/usage.json").st_mode & 0o777) == "0o600")
    saved["boot"] = "an earlier start of the computer"
    json.dump(saved, open(TMP + "/usage.json", "w"))
    agent = start(fake="kids")
    use = {x["name"]: x for x in call(a, "usage")[1]["users"]}
    check("after a restart of the computer the day's total stays and the rest starts again",
          use["gaming"]["days"][0]["used"] >= 2 and use["gaming"]["boot"]["used"] < 2 and len(use["gaming"]["logins"]) == 2 and use["gaming"]["logins"][1]["end"] is not None)

    check("internet is on to begin with", all(u["net"] == "on" and "net_timer" not in u for u in users().values()))
    check("internet is never turned off for an admin", call(a, "net_set", user="dad", seconds=0)[1] == {"ok": False, "error": "is_admin"})
    check("internet for unknown account refused", call(a, "net_set", user="x", seconds=0)[1] == {"ok": False, "error": "bad_user"})
    check("bad internet countdown refused", call(a, "net_set", user="gaming", seconds=3)[1] == {"ok": False, "error": "bad_args"})
    check("internet off now", call(a, "net_set", user="gaming", seconds=0)[1] == {"ok": True})
    u = users()
    check("only that account is off", u["gaming"]["net"] == "off" and u["classes"]["net"] == "on" and u["dad"]["net"] == "on")
    check("internet back on", call(a, "net_clear", user="gaming")[1] == {"ok": True} and users()["gaming"]["net"] == "on")
    check("internet countdown set", call(a, "net_set", user="classes", seconds=12, warn=True)[1] == {"ok": True})
    u = users()["classes"]
    check("internet still on while counting down", u["net"] == "on" and 9 <= u["net_timer"]["remaining"] <= 12 and u["net_timer"]["warn"])
    agent.terminate(); agent.wait()
    stt = state(); stt["net"]["classes"]["deadline"] = time.time() + 2
    json.dump(stt, open(TMP + "/state.json", "w"))
    agent = start(fake="kids")           # like a reboot shortly before the deadline
    time.sleep(3.5)
    u = users()["classes"]
    check("internet countdown fires after restart", u["net"] == "off" and "net_timer" not in u)
    check("a new countdown gives the internet back until it ends", call(a, "net_set", user="classes", seconds=600)[1]["ok"] and users()["classes"]["net"] == "on")
    check("cancelling the countdown leaves it on", call(a, "net_clear", user="classes")[1]["ok"] and state()["net"] == {})
    r = call(a, "login", user="dad")[1]
    check("login for an account that is not logged in waits at the login screen", r["how"] == "approved" and r["seconds"] == 120)
    w0 = call(a, "watch", after=-1, wait=1)[1]
    before = w0["seq"]
    check("login for an open session unlocks it", call(a, "login", user="classes")[1]["how"] == "unlocked")
    ev = call(a, "watch", after=before, epoch=w0["epoch"], wait=1)[1]["events"]
    check("the other phones hear that a phone unlocked it", any(e["type"] == "login" and e.get("how") == "unlocked" and e["user"] == "classes" for e in ev))
    # --- watching: the phone keeps a call open and hears of what happens within a second
    agent.terminate(); agent.wait()
    kids = TMP + "/kids.json"
    shutil.copy(HERE + "/../tools/demo/kids.json", kids)
    def kids_set(**states):
        d = json.load(open(kids))
        for u in d["users"]:
            u["state"] = states.get(u["name"], u["state"])
        json.dump(d, open(kids + ".tmp", "w")); os.replace(kids + ".tmp", kids)
    agent = start(fake=kids)
    import threading
    def watch_in_background(**args):
        box = {}
        def go():
            t = time.monotonic()
            box["r"] = call(a, "watch", timeout=40, **args)[1]
            box["took"] = time.monotonic() - t
        th = threading.Thread(target=go); th.start()
        time.sleep(0.5)
        return box, th
    s = call(a, "status")[1]
    check("status says it can be watched and keeps limits, and which day it is", {"watch", "limits"} <= set(s["caps"])
          and s["date"] == curfew.kept_days(time.time())[0])
    w = call(a, "watch", after=-1, wait=30)[1]
    check("a first watch answers at once with where the numbering stands", w["ok"] and w["events"] == [] and w["bye"] is None
          and isinstance(w["epoch"], str) and w["seq"] >= 0 and w["status"]["ok"])
    check("bad watch arguments are refused", call(a, "watch", after=0, wait=61)[1] == {"ok": False, "error": "bad_args"}
          and call(a, "watch", after="x", wait=1)[1] == {"ok": False, "error": "bad_args"})
    ep, seq = w["epoch"], w["seq"]
    t = time.monotonic()
    w = call(a, "watch", after=seq, epoch=ep, wait=2, timeout=10)[1]
    check("with nothing happening a watch answers after its wait", w["events"] == [] and 1.8 <= time.monotonic() - t < 4
          and w["status"]["users"])
    box, th = watch_in_background(after=seq, epoch=ep, wait=30)
    kids_set(dad="active", gaming="logged_in")
    th.join(35)
    got = box["r"]["events"]
    check("a login is told within seconds", box["took"] < 5 and [(e["type"], e["user"]) for e in got] == [("login", "dad")]
          and abs(got[0]["start"] - time.time()) < 10 and got[0]["seq"] == seq + 1)
    check("the watch answer carries the new status", {u["name"]: u["state"] for u in box["r"]["status"]["users"]}["dad"] == "active")
    seq = box["r"]["seq"]
    box, th = watch_in_background(after=seq, epoch=ep, wait=30)
    w = call(a, "watch", after=seq, epoch=ep, wait=0)[1]
    th.join(5)
    check("a newer watch from the same phone ends the older one", not th.is_alive() and box["took"] < 3 and box["r"]["events"] == [])
    box, th = watch_in_background(after=seq, epoch=ep, wait=30)
    cli("fake", "tamper", "gaming", "wifi_off")
    th.join(10)
    check("a try to change the network is told", [(e["type"], e["user"], e["what"]) for e in box["r"]["events"]] == [("tamper", "gaming", "wifi_off")]
          and box["took"] < 3)
    seq = box["r"]["seq"]
    agent.terminate(); agent.wait()
    check("events are kept on disk, privately", oct(os.stat(TMP + "/events.json").st_mode & 0o777) == "0o600")
    agent = start(fake=kids)
    w = call(a, "watch", after=seq - 2, epoch=ep, wait=5)[1]
    check("a phone that was away gets what it missed", [e["type"] for e in w["events"]] == ["login", "tamper"] and w["seq"] == seq)
    w = call(a, "watch", after=0, epoch="someotherepoch", wait=5)[1]
    check("a phone from another numbering starts from now", w["events"] == [] and w["seq"] == seq)
    box, th = watch_in_background(after=seq, epoch=ep, wait=30)
    cli("fake", "bye", "sleep")
    th.join(10)
    check("going to sleep is told at once", box["r"]["bye"] == "sleep" and box["took"] < 3 and "status" not in box["r"])
    check("while asleep a watch answers at once", call(a, "watch", after=seq, epoch=ep, wait=20)[1]["bye"] == "sleep")
    cli("fake", "bye", "back")
    time.sleep(1)
    check("after waking up it waits again", call(a, "watch", after=seq, epoch=ep, wait=1, timeout=10)[1]["bye"] is None)
    box, th = watch_in_background(after=seq, epoch=ep, wait=30)
    agent.terminate(); agent.wait()
    th.join(5)
    check("when the agent stops, the phone is told before it goes", box["r"]["bye"] == "restart" and box["took"] < 3)

    # --- screen-time limits
    agent = start(fake=kids)
    kids_set(dad="none", gaming="active")
    time.sleep(2.5)
    # blocked websites and private windows
    check("a typed address becomes its site", [curfew.site_of(x) for x in ("https://www.YouTube.com/watch?v=1", "discord.gg", "youtube.com:443/a", "bad", "http://x")]
          == ["youtube.com", "discord.gg", "youtube.com", None, None])
    ff, ch = curfew.web_policies(["youtube.com"], True)
    check("browsers are told the sites and no private windows", ff["WebsiteFilter"]["Block"] == ["*://*.youtube.com/*"] and ff["DisablePrivateBrowsing"]
          and ch["URLBlocklist"] == ["youtube.com"] and ch["IncognitoModeAvailability"] == 1)
    check("the firewall refuses a blocked site for the account", any("youtube.com" in r for r in curfew.fw_sites(1001, ["youtube.com"])[1]) and curfew.fw_sites(1001, []) == [])
    r = call(a, "web_set", sites=["https://www.YouTube.com/x", "discord.com", "discord.com"], private=True)[1]
    check("sites are kept by their name, once", r == {"ok": True, "web": {"sites": ["discord.com", "youtube.com"], "private": True, "users": {}}})
    r = call(a, "web_set", user="gaming", sites=["tiktok.com"])[1]
    check("an account gets its own sites on top", r["ok"] and r["web"]["users"]["gaming"] == {"sites": ["tiktok.com"], "private": False}
          and curfew.web_for(r["web"], "gaming") == (["discord.com", "tiktok.com", "youtube.com"], True)
          and curfew.web_for(r["web"], "classes") == (["discord.com", "youtube.com"], True))
    check("no own sites for an admin", call(a, "web_set", user="dad", sites=["x.com"])[1] == {"ok": False, "error": "is_admin"})
    check("an emptied account list is forgotten", call(a, "web_set", user="gaming", sites=[])[1]["web"]["users"] == {})
    check("a bad site is refused", call(a, "web_set", sites=["not a site"])[1] == {"ok": False, "error": "bad_site"})
    check("status tells the sites", call(a, "status")[1]["web"]["sites"] == ["discord.com", "youtube.com"] and "web" in call(a, "status")[1]["caps"])
    # Curfew's own copy of a child's history
    saved_dir, curfew.STATE_DIR = curfew.STATE_DIR, tempfile.mkdtemp()
    now = int(time.time())
    page = lambda u, w: {"url": u, "title": "", "host": "", "when": w}
    curfew.history_kept("kid", [{"id": "firefox", "name": "Firefox", "recent": [page("https://a.com/", now - 60), page("https://old.com/", now - 9 * 86400)]}])
    k = curfew.history_kept("kid", [{"id": "firefox", "name": "Firefox", "recent": []}])      # the child cleared the history
    check("cleared history stays in Curfew's copy", [e["url"] for e in k["firefox"]["recent"]] == ["https://a.com/"])
    k = curfew.history_kept("kid", [{"id": "firefox", "name": "Firefox", "recent": [page("https://b.com/", now), page("https://a.com/", now - 60)]}])
    check("new pages are added once, newest first", [e["url"] for e in k["firefox"]["recent"]] == ["https://b.com/", "https://a.com/"])
    check("the copy is readable by root only", oct(os.stat(os.path.join(curfew.STATE_DIR, "history.json")).st_mode & 0o777) == "0o600")
    shutil.rmtree(curfew.STATE_DIR, ignore_errors=True)
    curfew.STATE_DIR = saved_dir
    check("no limit for an admin", call(a, "limit_set", user="dad", minutes=60)[1] == {"ok": False, "error": "is_admin"})
    check("no limit for an unknown account", call(a, "limit_set", user="x", minutes=60)[1] == {"ok": False, "error": "bad_user"})
    check("bad limits are refused", all(call(a, "limit_set", user="gaming", **k)[1] == {"ok": False, "error": "bad_args"} for k in
          ({"minutes": 0}, {"minutes": 1441}, {"minutes": "60"}, {"minutes": True}, {"minutes": 60, "action": "explode"})))
    r = call(a, "limit_set", user="gaming", minutes=60, action="logout", warn=True, tell=False)[1]
    lim = r["limit"]
    check("a limit is set", r["ok"] and lim["minutes"] == 60 and lim["action"] == "logout" and lim["warn"] and not lim["tell"]
          and 3500 < lim["left"] <= 3600 and lim["extra"] == 0 and lim["elsewhere"] == 0)
    u = users()
    check("status shows the limit, and none where there is none", u["gaming"]["limit"]["minutes"] == 60 and "limit" not in u["classes"])
    left = u["gaming"]["limit"]["left"]
    r = call(a, "limit_extra", user="gaming", minutes=15)[1]
    check("extra time today adds to what is left", r["ok"] and r["limit"]["extra"] == 900 and abs(r["limit"]["left"] - left - 900) < 5)
    check("extra time needs a limit", call(a, "limit_extra", user="classes", minutes=15)[1] == {"ok": False, "error": "no_limit"})
    check("bad extra time is refused", call(a, "limit_extra", user="gaming", minutes=0)[1] == {"ok": False, "error": "bad_args"})
    today = curfew.kept_days(time.time())[0]
    r = call(a, "limit_elsewhere", date=today, users={"gaming": 600, "classes": 50})[1]
    u = users()["gaming"]["limit"]
    check("time on other accounts counts against a shared limit", r == {"ok": True, "applied": True} and u["elsewhere"] == 600
          and abs(u["left"] - (left + 900 - 600)) < 5 and "elsewhere" not in state()["limits"].get("classes", {}))
    r = call(a, "limit_elsewhere", date="2020-01-01", users={"gaming": 6000})[1]
    check("time elsewhere for another day is not counted", r == {"ok": True, "applied": False} and users()["gaming"]["limit"]["elsewhere"] == 600)
    check("bad time elsewhere is refused", call(a, "limit_elsewhere", date=today, users={"gaming": -1})[1]["ok"] is False
          and call(a, "limit_elsewhere", date=today, users=[1])[1]["ok"] is False)
    check("a limit is cleared", call(a, "limit_clear", user="gaming")[1] == {"ok": True, "limit": None} and "limit" not in users()["gaming"]
          and state()["limits"] == {})

    # running out: 50 seconds used of a 1-minute limit, with warnings; the grace is 2 s in this test
    agent.terminate(); agent.wait()
    use = json.load(open(TMP + "/usage.json"))
    use["users"]["gaming"]["days"][today]["used"] = 50
    json.dump(use, open(TMP + "/usage.json", "w"))
    agent.terminate(); agent.wait()
    ENV["CURFEW_LIMIT_GRACE"] = "2"
    agent = start(fake=kids)
    w = call(a, "watch", after=-1, wait=0)[1]
    ep, seq = w["epoch"], w["seq"]
    mark = os.path.getsize(TMP + "/agent.log")
    since = lambda: (LOG.flush(), open(TMP + "/agent.log").read()[mark:])[1]
    call(a, "limit_set", user="gaming", minutes=1, action="poweroff", warn=True, tell=True)
    time.sleep(3)
    check("they are warned a minute before", "would notify gaming: 1 minute of screen time is left for today." in since())
    w = call(a, "watch", after=seq, epoch=ep, wait=25, timeout=30)[1]
    ev = [e for e in w["events"] if e["type"] == "limit"]
    check("when it runs out the phone is told", len(ev) == 1 and ev[0]["user"] == "gaming" and ev[0]["minutes"] == 1
          and ev[0]["used"] >= 60 and ev[0]["action"] == "poweroff" and ev[0]["tell"] and not ev[0]["again"])
    check("and they are told it is ending", "would notify gaming: Your screen time for today is used up. The computer shuts down in 1 minute." in since())
    time.sleep(3.5)
    check("after the grace the computer shuts down", "would power off" in since() and since().count("would power off") == 1)
    check("status shows nothing left", users()["gaming"]["limit"]["left"] == 0)
    seq = w["seq"]
    kids_set(gaming="none"); time.sleep(2.5)
    mark = os.path.getsize(TMP + "/agent.log")
    kids_set(gaming="active", classes="active")      # back again the same day, and someone else on the screen too
    w = call(a, "watch", after=seq, epoch=ep, wait=15, timeout=20)[1]
    while not any(e["type"] == "limit" for e in w["events"]):
        w = call(a, "watch", after=w["seq"], epoch=ep, wait=15, timeout=20)[1]
    check("logging in again the same day ends again, and says so", [e for e in w["events"] if e["type"] == "limit"][0]["again"])
    time.sleep(3.5)
    check("with someone else on the screen only that account is logged out",
          "would log out gaming: screen time used up" in since() and "would power off" not in since())
    kids_set(gaming="none", classes="locked"); time.sleep(2.5)
    kids_set(gaming="active"); time.sleep(2.5)
    mark = os.path.getsize(TMP + "/agent.log")
    r = call(a, "limit_extra", user="gaming", minutes=10)[1]
    time.sleep(3.5)
    check("more time given while it is ending stops it", r["limit"]["left"] > 500 and "would power off" not in since()
          and "would log out" not in since() and "would notify gaming: Your parent gave you 10 minutes more today." in since())
    call(a, "limit_clear", user="gaming")
    del ENV["CURFEW_LIMIT_GRACE"]
    agent.terminate(); agent.wait()
    agent = start()

    # --- unpair from the laptop side, forget from the phone side
    out = cli("phones").stdout
    check("'curfew phones' lists both", "Dad's Galaxy 2" in out and " 1. " in out)
    out = cli("unpair", "Dad's Galaxy 2").stdout
    check("'curfew unpair' removes one", "Removed" in out)
    check("removed phone gets a signed 'unpaired' answer", call(b, "poweroff") == (200, {"ok": False, "error": "unpaired"}))
    check("other phone still works", call(a, "status")[1]["ok"])
    check("forget removes the phone", call(a, "forget")[1] == {"ok": True})
    check("forgotten phone is unknown afterwards", http("POST", "/v1/call", call(a, "status", envelope_only=True)) == (403, {"error": "unknown_phone"}))
    check("no phones left", state()["phones"] == {})
    print("\nAll %d checks passed." % passed)
finally:
    agent.terminate()
    LOG.close()
    shutil.rmtree(TMP, ignore_errors=True) if not os.environ.get("KEEP") else print(TMP)
