# Social: mute, report and friends (2026-11-03)

Server `common/src/reo/common/Social.java` (tests `tests/SocialTest.java`) and the web page `web/shell.html`, `web/admin.html`. Local, **not deployed** at the time of writing.

## Design (decisions)
- **Mute** is browser-only: `localStorage['reo-muted']` (JSON array of names, 500 max, everything inside try/catch). It hides messages in Community and My game and leaves a discreet line: "N messages hidden from muted users". The list to undo is in Settings -> Online -> "Muted users". The server does not know.
- **User menu**: tapping a name in chat (except your own) or in the Rooms list opens a dialog with three options: Mute/Unmute, Report and Add to friends (or "Already your friend" / "Request sent").
- **Report**: reason (`insult|spam|cheat|name|other`), optional detail (300 characters), "Mute them too" and, attached, the last 10 messages from them that the page received (`seenBy`, kept even if muted). In addition, the server adds that person's messages it still has stored (`ChatService.messagesBy`). In /admin they are shown separately: "From the server" (verified) and "Saw" (what the reporter's browser sends, which can be forged).
- **Friends: request and simple acceptance**, not one-way following. With following anyone could follow anyone and see their status; with mutual acceptance, status is visible only to people the other side accepted. The implementation is "both added each other": accepting is adding back, and there are no separate states.
  - Status sharing is **off by default** (opt-in "Show my friends if I am online and where"). The conservative choice: a friend without opt-in appears as "Status hidden".
  - Status: online in game (File #1/#2, area, room, in game or in the lobby, from the kk sessions), "On the page" (chat poll within the last 90 s) or "Offline".
  - Rejecting deletes the other's request and puts them in the rejecter's `ignore` list (300 max): if they ask again, it is not shown. Removing deletes only my side. There are no private messages.
- **Persistence: file, not MariaDB.** US accounts (`data/kk-accounts.properties`) are a file; MariaDB is used only by Bioserver's JP lobbies. Friends go in `data/social/friends.json` (mode 0600, rewritten atomically with tmp + move on every change, loaded at startup). "Migration" = if the file does not exist, start empty; if it is broken, log it and start empty **without overwriting it** until the next change (accepted hypothesis: it would be lost at that change; back it up if it happens).
- **Released name** (`reo-admin release` / Moderation "Release name", `KkService.release/releaseName`): `Social.forget` deletes its friends and requests in both directions, because someone else may register that name. When transferring the name with the code (same name and credential in another browser) the friends remain.

## API
- `POST /api/social/friends {name, credential, op, target?, share?}`, `op` = `list|add|remove|reject|share` -> `{ok, share, max:100, friends:[{name, since, status|hidden}], sent:[..], incoming:[..<=50], incoming_total}`. Errors: 401 credential, 403 banned, 400 `bad target|self|bad op`, 404 `unknown name` (not registered), 409 `full`, 429.
- `POST /api/social/report {name, credential, target, reason, note?, game?, seen:[{t,text}]}` -> `{ok, id}`; 429 `already reported|rate limited` + `retry_after`.
- `GET /api/admin/player-reports?limit=` (admin token) -> `{total, open, reports:[{id,t,target,reason,note,reporter,ip_ref,game,server[],seen[],done,target_24h}]}`; `POST {id, done}` marks reviewed (stored as a `done_of` line in the jsonl).
- Discord webhook: environment variable **`REO_REPORT_WEBHOOK`** (the server reads it from the environment, or else from the line in the alerts env file named by `api.alerts_env_file`, which is the server's private config). If empty or missing, nothing is posted. It only accepts `https://discord(app).com/api/webhooks/...` URLs (or `http://127.0.0.1` for tests). It sends `{"content", "allowed_mentions":{"parse":[]}}` with: report number, reported name, reason, a 120-character extract without markdown, and `api.public_url + /admin` if configured. **Never** the reporter's name, IPs or the note. It does not use `REO_ALERT_WEBHOOK`.
- New config (optional): `api.public_url` (for the link; without it there is no link), `api.social_file`, `api.player_reports_file` (`data/social/player-reports.jsonl`, 5000 max), `api.preports_gap_s`, `api.alerts_env_file`.

## Limits
- Reports require a registered account. The server applies rate limits (per account, per IP, per reported player and globally) and the webhook is capped too (the rest stays only in /admin); exact tuning is not published.
- Friends: 100 added names per account, with rate limits on additions and requests. The page polls every 20 s with the tab open and every 2 min otherwise (for the request counter).
- `/api/social/report` accepts a 16 KiB body, same as `/api/report`. MiniJson takes per-call limits (the metrics ones are unchanged).
- IPs are not stored in clear: only `ip_ref` (HMAC), as in bug reports.

## Stored data
- `data/social/friends.json`: per account, the name as written, `share`, `add` {name: ms} and `ignore`.
- `data/social/player-reports.jsonl`: the full report (includes the reporter, to detect abuse; visible only in /admin).
- In the browser: `reo-muted`. Seen messages are kept only in page memory.

## DONE (verified 11-03)
- `tests/run-tests.sh`: all OK, including `SocialTest` (31 checks): request -> friendship, hidden <-> share, "On the page", reject and hidden request, remove, file with 40 users loaded, `forget`, reports (401/400/404, duplicate, interval, 10-line x 200 cuts, clean note, no IP), local webhook read from the alerts env file (without the reporter, mentions off, link to /admin), admin queue and "reviewed".
- run-local.sh plus two headless Chrome via CDP (A in ES, B in EN), 19/19 OK with no JS errors: B sees A's message, mutes it (hidden + `reo-muted`), sees it in Settings and undoes it, reports with 1 attached line, a second report gives "already reported", adds A from the menu, A accepts in Friends (badge), hidden status -> B shares -> A sees "On the page", nonexistent name -> message, remove. /admin shows the report with "From the server" and "Saw". (Screenshots are local evidence, not published.)
- `check_web.mjs` OK on shell.html and admin.html.

## HYPOTHESES / untested
- The "in game" status (File/area/room) comes from `KkService.players()`, like the moderation panel; not tested with a running game.
- The real Discord webhook was not tested (no third-party traffic): the format is the one the alerts kit specifies (204).
- Names in the Rooms list as buttons: the rendering was touched but not tested with real rooms.
- Texts exist in ES/EN; PT/FR/DE/IT/FIL fall back to English.
