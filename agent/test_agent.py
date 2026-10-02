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
    env = dict(ENV, CURFEW_FAKE=HERE + "/../tools/demo/%s.json" % fake) if fake else ENV
    p = subprocess.Popen([sys.executable, HERE + "/curfew.py", "serve"], env=env, stderr=LOG)
    for _ in range(50):
        try:
            http("GET", "/v1/hello")
            return p
        except OSError:
            time.sleep(0.1)
    raise SystemExit("agent did not start")


def http(method, path, body=None, raw=None):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(BASE + path, data=data, method=method)
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
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


def call(ph, op, envelope_only=False, **args):
    s, c = nonce(), secrets.token_hex(16)
    payload = json.dumps(dict(args, op=op))
    env = {"phone": ph["phone"], "snonce": s, "cnonce": c, "payload": payload,
           "mac": mac(ph["key"], "req", ph["phone"], s, c, payload)}
    if envelope_only:
        return env
    status, r = http("POST", "/v1/call", env)
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
curfew.usage_step(d, [kid("active")], noon + 2 * 86400, 15)
e = d["users"]["kid"]
check("older days and their logins are forgotten", sorted(e["days"]) == [day(noon + 86400), day(noon + 2 * 86400)] and e["logins"] == [[int(noon + 120), None]])
check("logind's time is read", curfew.stamp("Fri 2026-10-02 12:00:00 PKT") == int(noon) and curfew.stamp("") is None)

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
    check("approved login is let in", pam() == 0)
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
    check("login for an open session unlocks it", call(a, "login", user="classes")[1]["how"] == "unlocked")
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
    shutil.rmtree(TMP, ignore_errors=True)
