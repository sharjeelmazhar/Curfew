#!/usr/bin/env python3
"""End-to-end tests: starts the agent in dry-run mode on a spare port with a
throw-away state folder and talks to it over real HTTP.  Run: python3 test_agent.py"""
import hashlib
import hmac
import json
import os
import secrets
import shutil
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


def start():
    p = subprocess.Popen([sys.executable, HERE + "/curfew.py", "serve"], env=ENV, stderr=LOG)
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
check("autostart service ignored", ids("/a/app-gnome-foo\\x2dautostart@autostart.service") == [])
check("QR too long is rejected cleanly", not hasattr(curfew, "x") and len(curfew.qr_matrix("a" * 106)) == 37)

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

    # --- timer
    check("bad timer value refused", call(a, "timer_set", minutes=0)[1]["ok"] is False)
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

    # --- unpair from the laptop side, forget from the phone side
    out = subprocess.run([sys.executable, HERE + "/curfew.py", "phones"], env=ENV, capture_output=True, text=True).stdout
    check("'curfew phones' lists both", "Dad's Galaxy 2" in out and " 1. " in out)
    out = subprocess.run([sys.executable, HERE + "/curfew.py", "unpair", "Dad's Galaxy 2"], env=ENV, capture_output=True, text=True).stdout
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
