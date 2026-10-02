# Curfew agent API

Plain HTTP with JSON on the LAN, port **787** (dry-run: 8787). Nothing is encrypted; every
message is authenticated with HMAC-SHA256 and a per-phone 32-byte key. Three endpoints.

`mac(key, a, b, ...)` below means: HMAC-SHA256 with `key` over the parts joined by `\n` (UTF-8),
written as lowercase hex.

## Finding the agent

mDNS service `_curfew._tcp` (IPv4), TXT record `id=<agent id>`. Clients remember the last
address and prefer a discovered one whose `id` matches.

## GET /v1/hello

No authentication. Returns the identity and a fresh one-time number.

```json
{"app": "curfew", "proto": 1, "id": "0b825f00961665a4", "name": "kids-laptop", "nonce": "<32 hex>"}
```

A nonce is valid once and for 60 seconds. Every POST needs a fresh one (this is what stops
replays without relying on clocks).

## POST /v1/pair

`sudo curfew pair` creates a 16-character code (80 bits, valid 2 minutes, single use, cancelled
after 5 wrong attempts). The code is shown in a QR code as
`curfew://pair?a=<ip>:<port>&i=<agent id>&c=<code>` and never travels over the network.

Request (`payload` is a JSON **string**, `cnonce` is 8-64 letters/digits chosen by the client):

```json
{"snonce": "<nonce from hello>", "cnonce": "...", "payload": "{\"name\": \"Dad's phone\"}",
 "mac": mac(code, "pair", snonce, cnonce, payload)}
```

Success (HTTP 200):

```json
{"phone": "<phone id>", "payload": "{\"ok\": true, \"id\": ..., \"name\": ..., \"phone_name\": ...}",
 "mac": mac(key, "res", phone, snonce, cnonce, payload)}
```

Both sides compute `key = HMAC-SHA256(code, "key\n" + snonce + "\n" + cnonce + "\n" + phone)` (raw 32 bytes).
The client must verify `mac`: only an agent that knows the code can produce it.
Errors (HTTP 403/409): `{"error": "bad_code" | "no_pairing" | "bad_nonce"}`.

## POST /v1/call

```json
{"phone": "<phone id>", "snonce": "...", "cnonce": "...", "payload": "{\"op\": \"status\"}",
 "mac": mac(key, "req", phone, snonce, cnonce, payload)}
```

Authenticated answer (HTTP 200); verify `mac` before trusting it:

```json
{"payload": "{\"ok\": true, ...}", "mac": mac(key, "res", phone, snonce, cnonce, payload)}
```

Unauthenticated refusals (HTTP 4xx, treat as hints only): `unknown_phone`, `bad_mac`,
`bad_nonce` (get a new nonce and retry once), `bad_request`.

### Operations (the `op` field of the payload)

| op | extra fields | answer |
|---|---|---|
| `status` | | `id`, `name`, `os`, `caps`, `users: [{name, full, admin, state, net, net_timer?}]`, `timer: null \| {remaining, warn}` |
| `apps` | `user` | `apps: [{name, detail, age, terminal}]`, newest first, at most 15 |
| `browsers` | `user` | `browsers: [{id, name, open, recent}]`; `open` and `recent` are `[{url, title, host, when, search}]`, newest first |
| `poweroff`, `reboot` | | |
| `timer_set` | `seconds` (10-86400) or `minutes` (1-1440), `warn` (bool) | `timer` |
| `timer_cancel` | | `timer: null` |
| `lock`, `logout` | `user` | |
| `login` | `user` | `how`: `unlocked` (an open session was brought to the screen and unlocked) or `approved` (the login screen lets this account in once, within `seconds`) |
| `net_set` | `user`, `seconds` (0 = now, or 10-86400), `warn` | internet off for that account, now or after the countdown |
| `net_clear` | `user` | internet back on, countdown cancelled |
| `forget` | | removes the calling phone |

`caps` lists what this agent can do: `seconds` (timers in seconds), `net` (iptables is present),
`login` (the login screen is set up to ask the agent), `browsers` (the agent can read browser
history). An agent without `caps` is the first version.

For `browsers`: `id` is `firefox`, `chrome` or `chromium`; `open` is the tabs open now (read
exactly for Firefox, approximated as the last few minutes of history for Chrome/Chromium, which
do not expose their open tabs); `recent` is the last 15 pages of history, newest first; `when` is
unix seconds and `search` holds the words typed into a search engine when the page was a search,
else `""`. A browser only appears if the account has used it. Private/incognito windows are not on
disk and never appear. The reply is signed but, like everything on this protocol, not encrypted.
`net` is `on` or `off`; `net_timer` is `{remaining, warn}` while a countdown runs. Admin accounts are
always `on`.

`state` is `none`, `logged_in`, `locked` or `active`. `remaining` and `age` are seconds, so
clients never compare clocks. Failures are authenticated too: `{"ok": false, "error": ...}` with
`bad_op`, `bad_user`, `bad_args`, `not_logged_in`, `is_admin`, `unsupported`, `failed`, or **`unpaired`**: the phone was
removed with `sudo curfew unpair`; it is signed with the old key so the client can trust it and
drop the computer.

## Flood limits

The agent serves at most 8 connections per source address at a time and keeps at most 32 unused
nonces per source address, so a flood from one place (an account on the same computer, say) cannot
lock a phone out.

## Notes for a second client (web page for an iPhone)

- A page served over plain HTTP has no `crypto.subtle`, so it needs a small JavaScript
  HMAC-SHA256 (about 100 lines) and must keep its key in `localStorage`.
- Pairing would be the typed code, or the QR link opened in the browser.
- The agent would serve the page from a fourth, static endpoint; nothing above changes.
