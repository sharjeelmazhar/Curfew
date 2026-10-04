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
| `status` | | `id`, `name`, `os`, `caps`, `users: [{name, full, admin, state, since?, today, net, net_timer?}]`, `timer: null \| {remaining, warn}` |
| `apps` | `user` | `apps: [{name, detail, age, terminal}]`, newest first, at most 15 |
| `browsers` | `user` | `browsers: [{id, name, open, recent}]`; `open` and `recent` are `[{url, title, host, when, search}]`, newest first |
| `usage` | | `now`, `boot`, `users: [{name, state, boot: {used, on}, days: [{date, used, on}], logins: [{start, end}]}]` |
| `poweroff`, `reboot` | | |
| `timer_set` | `seconds` (10-86400) or `minutes` (1-1440), `warn` (bool) | `timer` |
| `timer_cancel` | | `timer: null` |
| `lock`, `logout` | `user` | |
| `web_set` | `user`? (that account's own rules, on top of the computer's; `status` lists them under `web.users`), `sites`? (list, replaces the blocked websites; each is reduced to its site, `www.` dropped), `private`? (true: children get no private/incognito windows) | `web`: `{sites, private}`. Error `bad_site`. Applies to accounts that are not administrators: the firewall refuses those sites for them (TLS server name / HTTP Host, QUIC refused), and Firefox/Chrome policies (website filter, no private windows, no DNS over HTTPS) are in place while no administrator is the active session |
| `login` | `user` | `how`: `unlocked` (an open session was brought to the screen and unlocked) or `approved` (the login screen lets this account in once, within `seconds`) |
| `net_set` | `user`, `seconds` (0 = now, or 10-86400), `warn` | internet off for that account, now or after the countdown |
| `net_clear` | `user` | internet back on, countdown cancelled |
| `forget` | | removes the calling phone |
| `watch` | `after` (int, -1 for "from now"), `epoch` (string or absent), `wait` (0-60) | `epoch`, `seq`, `events`, `bye`, `status` (see below) |
| `limit_set` | `user`, `minutes` (1-1440), `action` (`poweroff` or `logout`), `warn`, `tell` (bools) | `limit` |
| `limit_clear` | `user` | `limit: null` |
| `limit_extra` | `user`, `minutes` (1-720) | `limit`: that many more minutes today |
| `limit_elsewhere` | `date` (`2026-10-03`), `users: {name: seconds}` | `applied` (false when `date` is not today there) |

`caps` lists what this agent can do: `seconds` (timers in seconds), `net` (iptables is present),
`login` (the login screen is set up to ask the agent), `browsers` (the agent can read browser
history), `usage` (the agent keeps screen time), `watch` (the `watch` op and events), `limits`
(screen-time limits), `web` (blocked websites, private windows; `status` then has `web`: `{sites, private}`). An agent without `caps` is the first version. `status` also has `date`, the
computer's today (`2026-10-03`), and for an account with a limit, `limit` as below.

Watching: a phone keeps one `watch` call open per computer. It is answered as soon as there are
events numbered above `after`, or when the computer goes away, or after `wait` seconds, and then
the phone asks again with `after` set to the `seq` it got. Events are numbered within an `epoch`
(a string that changes only when the computer's list of events was lost); with another `epoch`, or
`after` -1, the answer comes at once with no events and the `seq` to continue from. A newer
`watch` from the same phone answers the older one at once. `status` is the full `status` answer
(left out when `bye` is set). `bye` is null, or why the computer is going away right now:
`shutdown`, `reboot`, `sleep` (the system-sleep hook) or `restart` (only the Curfew service
restarts, as in an update). Events, oldest first, at most 100, kept for 3 days:

- `{"seq", "time", "type": "login", "user", "start", "how"?}`: an account logged in. `how` (when the login screen said so): `password` or `phone` (then `by`: the name of the phone that let them in); `unlocked` when a phone unlocked a session that was already open (`start` is then that moment). The same `how` is in `status` next to `since`.
- `{"seq", "time", "type": "tamper", "user", "what"}`: someone tried to cut the network. `user`
  is null at the login screen. `what` is `airplane` (undone at once), `wifi_off`, `network_off`,
  `disconnect`, `wifi_settings` (a password or other setting), `forget_network`,
  `other_network` or `network`; all but `airplane` were refused. The same try by the same
  account is told once a minute at most.
- `{"seq", "time", "type": "limit", "user", "minutes", "used", "action", "tell", "again"}`: the
  account's screen time ran out while it was on the screen; `action` follows in a minute. `again`
  is true when it already ran out earlier that day. `tell` is the limit's own setting: whether the
  parent wants a notification for it.

Screen-time limits: `limit` is `{minutes, action, warn, tell, extra, elsewhere, used, left}`.
Time counts as in screen time (`used`: on the screen and unlocked), plus `elsewhere`, the seconds
the phone says the same child spent today on other accounts (`limit_elsewhere`; for one limit
shared by several accounts or computers), against `minutes` plus `extra` (`limit_extra`, today
only). When `left` reaches 0 while the account is on the screen, it gets a notice and a minute
later the computer shuts down (`poweroff`), or the account is logged out (`logout`, and also for
`poweroff` when someone else is on the screen by then). With `warn`, notices come at 5 minutes and
at 1 minute left. Admin accounts cannot have a limit (`is_admin`); `limit_extra` without a limit is
`no_limit`.

Screen time: `used` is seconds with the account on the screen and unlocked (`state` was `active`),
`on` is seconds logged in at all. In `status`, `since` is when the account's current login began
(unix seconds, only while logged in) and `today` is its `used` seconds today. In `usage`, `days` is
today then yesterday (`date` as `2026-10-02`, in the computer's time zone, zeros when nothing
happened), `boot` holds the totals since the computer was turned on, `logins` are the logins of those
two days, newest first, with `end: null` while still logged in, and `now` and `boot` are the
computer's clock and the time it was turned on. The agent looks every 15 seconds and on every
`status` or `usage` call, and does not count time the computer was asleep.

For `browsers`: `id` is `firefox`, `chrome` or `chromium`; `open` is the tabs open now (read
exactly for Firefox, approximated as the last few minutes of history for Chrome/Chromium, which
do not expose their open tabs); `recent` is the history of the last 7 days, newest first (capped at 1000 rows); `when` is
unix seconds and `search` holds the words typed into a search engine when the page was a search,
else `""`. A browser only appears if the account has used it. Private/incognito windows are not on
disk and never appear. The reply is signed but, like everything on this protocol, not encrypted.
`net` is `on` or `off`; `net_timer` is `{remaining, warn}` while a countdown runs. Admin accounts are
always `on`.

`state` is `none`, `logged_in`, `locked` or `active`. `remaining` and `age` are seconds, so
clients never compare clocks. Failures are authenticated too: `{"ok": false, "error": ...}` with
`bad_op`, `bad_user`, `bad_args`, `not_logged_in`, `is_admin`, `no_limit`, `unsupported`, `failed`, or **`unpaired`**: the phone was
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
