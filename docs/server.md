# Own server (route B): reo-bioserver

> **Update 2026-10-18:** this document describes the 2026-10-15 baseline (the JP Bioserver protocol, ports 8300/8200). The US client **does not use that protocol**: it uses app01 (TCP 10127, `reo.us.UsAppService`) and kkClient over UDP 9090 (`reo.us.KkService`), plus the API (`/api/rooms`, `/api/kk/register`). See [protocol.md](protocol.md) §6.1–§6.7 and [playing-online.md](playing-online.md). To bring everything up locally use `run-local.sh`. What follows is still valid for the repo structure, the DB and the JP codec.

Baseline last updated: 2026-10-15. Local git repo in `server/` (branch `main`, no remote). License **AGPL-3.0**, isolated from the runtime and the site (MIT): they communicate only over the network.

## Origin
- Base: **Bioserver1** (`gitlab.com/gh0stl1ne/Bioserver1` @ `a29e7ee`) and **Bioserver2** (`gitlab.com/gh0stl1ne/Bioserver2` @ `a2b5d7f`), Copyright (C) 2013-2019 obsrv.org. The repo's first commit (`33b7cb0`) is a verbatim import of both. `git diff 33b7cb0` shows all changes.
- Not imported: `www/` (PHP login), the PDF, and `patch/`. `patch_101.raw` is the File #1 1.01 patch, derived from the game, and is not committed.
- `corbin-zip/bioserver` (2023) was reviewed. Its functional changes are the `java.sql` imports and a longer timeout on 0x61A0. The imports are covered; the timeout was left as upstream.

## Decision: one base, two packages, one process
- **[FACT]** The differences between File #1 and File #2 are game data and rules: `RuleSet` (247 diff lines), `Packet6881` (197), areas (2 vs 10), difficulty 0x6914 (16 vs 32 bytes), patch flow (File #1 only) and East Town autostart (File #1 only). There are also about 236 diff lines in `PacketHandler`. The core (NIO networking, header, encryption, login, relay) is the same.
- Chosen: keep **both upstream trees almost intact**, renaming package `bioserver` to `bioserver1`/`bioserver2`, and add new shared code under `common/src/reo/`. A single JVM (`reo.Main`) starts both games, and the HTTP API sees both. Merging the two `PacketHandler`s by hand would be more work and more risk, with no benefit until the US protocol is understood.
- If needed, one process per game can be run with `--games file1` or `--games file2`.

## Layout
```
server/
  LICENSE.txt  README.md  .gitignore
  common/src/reo/Main.java                 entry point: games + API + sniffers
  common/src/reo/common/ServerConfig.java  config/server.properties + local.properties + -Dreo.*
  common/src/reo/common/GameConfig.java    ports, bind, GSINFO, DB, protocol profile
  common/src/reo/common/PacketLog.java     hex log + parsed headers
  common/src/reo/common/RawSniffer.java    capture-only TCP listeners
  common/src/reo/common/HttpApi.java       /api/rooms /api/dns /api/health /api/dev/login
  common/src/reo/common/SessionStore.java  replacement for the PHP login
  file1/src/bioserver1/*.java              Bioserver1 (+ StatusView.java)
  file2/src/bioserver2/*.java              Bioserver2 (+ StatusView.java)
  config/server.properties                 committed config; local.properties (DB password) is not
  database/schema.sql, testdata.sql        tables + test users test1/test1, test2/test2 + MOTD
  database/bioserver{1,2}.sql              original upstream SQL (reference)
  scripts/setup.sh, run.sh, patch_upstream.py
  tools/testclient.py
```

## How to bring it up
From the repo root (Linux or WSL):
```
sudo server/scripts/setup.sh deps db      # apt + MariaDB + schema + users
server/scripts/setup.sh build             # build/reo-bioserver.jar
server/scripts/run.sh                     # foreground
cd server && python3 tools/testclient.py              # File #1 test
cd server && python3 tools/testclient.py --game file2
curl http://127.0.0.1:8088/api/rooms
```
- Dependencies installed: `openjdk-21-jdk-headless` 21.0.12, `mariadb-server` 11.8.6, `libmariadb-java` 2.7.6 (`/usr/share/java/mariadb-java-client.jar`). Built with plain `javac`, no Maven or Gradle or downloads.
- DB: `bioserver1` and `bioserver2` (same schema). The user is `bioserver@localhost`, with a random password in `config/local.properties` (mode 600, in `.gitignore`).
- Ports: File #1 uses lobby 8300 and game 8690; File #2 uses lobby 8200 and game 8590 (the upstream JP ones). The API is on `127.0.0.1:8088`.
- Packet log: `PACKETLOG=hex scripts/run.sh` (or `header`) writes to `logs/packets.log`.
- Overrides without touching files: `JAVA_OPTS="-Dreo.public_ip=192.168.1.10 -Dreo.file1.lobby_port=1234" scripts/run.sh`.

## Architecture
- Per game (upstream code): `ServerThread` (NIO, lobby) → `PacketHandler` (state: `ClientList`, `Areas`, `Rooms`, `Slots`; dispatches by who/qsw/cmd) → queue → `send`. `GameServerThread`/`GameServerPacketHandler` is the relay: `GSLOGIN` 0x1031, then it forwards every frame to the clients with the same `gamenumber`. `HeartBeatThread` sends 0x6202 every 30 s and 0x6001 every 60 s (disconnects on no reply), plus autostart and room cleanup.
- The session lives in MariaDB (`sessions.sessid`, 8 digits). It is created by the web login (formerly PHP, now `SessionStore` via `/api/dev/login`), and the lobby and gameserver validate it.
- Changes over upstream (details in the repo's `README.md`):
  - External config. The GSINFO port is no longer hardcoded.
  - All queries use prepared statements (upstream built SQL with `String.format` and client data, e.g. the nickname).
  - `ClientList` uses `CopyOnWriteArrayList`.
  - No crash when `patch.raw` is absent.
- **HTTP API** (`HttpApi`, `com.sun.net.httpserver`, no dependencies, CORS `*`):
  - `GET /api/rooms[?game=file1]`: per game returns `players_lobby`, `players_in_game` and `areas[]`. Each area carries `{id,name,description,active,players[],rooms[]}`, and each room `{id,name,active,players[],slots[]}`. Only slots that are non-free or have players are listed: `{id,name,status,scenario,password,max_players,game_number,players[]}`. It also includes `after_game_lobby[]` and `games_in_progress[]`. For each player it exposes `handle`, `nickname` and `character`. The login userid and the session are **never** exposed.
  - `GET /api/dns`: returns `hosts` (`lobby`→`app01…`, `snap`→`snap01…`, `regweb`→`regweb…yav4.com`), an `overrides` map fqdn→`public_ip` for the gateway, and the ports.
  - `GET /api/health`.
  - `POST /api/dev/login` (form `game,user,password` → `{"sessid"}`). Works only with `api.dev_login=true` and from loopback. It is a stopgap replacement for the web login (`regweb…/reweb/index.jsp` on US, KDDI's `CRS-top.jsp` on JP).
- **Extension point for the US client:**
  - `hosts.*`, `fileN.lobby_port`/`game_port`/`gs_ip`/`gs_port` and `public_ip` are defined in `config/server.properties`.
  - `fileN.protocol=jp` is the profile. Only `jp` exists today; `us` is the planned place for an alternative handler.
  - `PacketLog` records every raw `read()`/`send()` and, separately, tries to parse the 12-byte JP header of each frame. If it does not match, it marks it `[unparsed] … do not match the JP 12-byte header`, which is exactly the signal to look for with the US client.
  - `sniff.ports=…` opens capture-only listeners (they do not reply) for unknown ports. Tested with a simulated HTTP GET on port 9999: it is logged as `[unparsed]` with a hexdump.

## What the test client validates (`tools/testclient.py`)
Result of 2026-10-15: **23/23 checks on File #1 and 23/23 on File #2**. The client is Python stdlib and only talks to the given host (127.0.0.1 by default).

| Step | What is validated | Evidence |
|---|---|---|
| Header | `who qsw cmd16 len16 pid16 err ff ff ff` big-endian, both directions. TELL replies reuse the QUERY's `pid` | all frames parse and match by pid |
| LOGIN 0x6101 | The server opens with a QUERY (payload `28 37`). The client replies TELL with `00 0a` + 10 ASCII digits = (half1 + seed, half2 + seed) with 5 digits each, where seed = the TELL's pid. Valid session → `CHECKVERSION` 0x6103. Nonexistent session → the server closes the socket | checks "session accepted" + "unknown session is rejected" |
| CHECKVERSION 0x6103 | TELL with 3 bytes + encrypted string. The server only logs it; with an old version it would send the patch, which is disabled | → IDHNPAIRS |
| IDHNPAIRS 0x6131 | BROADCAST `count, {len16 handle, len16 nick, 00 00}*`. Parsed, and on the 2nd run the handle created in the 1st appears | `existing handles: [('<handle>','<nick>')]` |
| UNKN61A0 | Replies 8 bytes `0000070800000258` (1800 s / 600 s) | ok |
| **XOR encryption** | CHECKRND 0x600E: the server decrypts our string with `calc_shift(i, pid&0xff)` and returns the first character. Tested with 3 pids. HNSELECT: the encrypted nickname (all indices) comes back decrypted and correct in `/api/rooms`. CHARSELECT: 0xD0 encrypted bytes; byte 0xC8 (index ≥128, Java's signed-byte branch) comes back as the correct `character` | 3 checks |
| HNSELECT 0x6132 | `******` → the server creates a random handle, stores it with the nickname and replies `00 06 <handle>`, then BROADCAST 0x6104 (end of login). With an existing handle it reuses it | ok on both branches |
| MOTD 0x614C, CHARSELECT 0x6190 | replies received; MOTD comes from the `motd` table | ok |
| Areas | AREACOUNT 0x6203, and per area AREANAME 0x6204, AREASTATUS 0x6206, AREAPLAYERCNT 0x6205 and AREADESCRIPT 0x620A. Then AREASELECT 0x6207 | File #1: 2 areas (East/West Town). File #2: 10 (Free Area…SECRET Area) |
| Rooms | ROOMSCOUNT 0x6301 (10), ROOMNAME 0x6302, ROOMSTATUS 0x6304, ENTERROOM 0x6305, SLOTCOUNT 0x6401 (20), SLOTSTATUS 0x6404 | ok |
| API | `/api/rooms` shows the player in area 1, room 1, with correct handle, nickname and character | ok |
| Gameserver | Two sessions (test1 and test2) receive `GSLOGIN` 0x1031 QUERY (who=0x28). They reply TELL who=0x82 with 10 digits without prefix. An opaque frame `[len=12]RELAYTEST!!` from A arrives identical at B. This implies both sessions were accepted, since without a registered client the relay throws an NPE | ok |
| LOGOUT 0x6006 | answered | ok |

**Not validated yet:** slot creation and rules (0x6407/0x6509/0x660x), join (0x6406), chat (0x6701), game start (0x6508 → 0x6910…0x6917, GSINFO), post-game lobby (0x621x), buddies/PM, the patch 0x6121–0x6125 and the string checksum. The server does not verify the checksum; we send `sum(bytes)`. **[HYPOTHESIS]**: the real algorithm is unknown, and the 2 leading payload bytes of LOGIN (`00 0a`) and the 3 of CHECKVERSION are also unknown.

Note: all this validates that **we understood the JP protocol as Bioserver implements it**. Whether the US client speaks the same remains a **[HYPOTHESIS]** (see `protocol.md` §3.2).

## Known limitations (inherited from upstream)
- Passwords are stored in plain text in `users`, as upstream does. They must be hashed before exposing the server.
- There are race conditions between the NIO thread and the heartbeat over state. The parsers assume well-formed payloads: a malicious client can throw exceptions in the lobby thread, which are caught per iteration and do not kill the process.
- Strings with length ≥128 are parsed as signed in some `Packet` getters (upstream bug; it has not mattered so far).
- AGPL: if the server is exposed publicly, its source code must be offered to users.

## Next steps (as of the baseline)
1. With DEV9/SMAP and the gateway: DNS override of `*.yav4.com` → our IP (what `/api/dns` returns), start with `PACKETLOG=hex` and `sniff.ports` if the US client asks for unexpected ports, and capture.
2. Serve the US web login (`http://regweb.reo.capcom.sf.yav4.com/reweb/index.jsp`): see what the game's browser expects. On JP it was a CSV embedded in an HTML comment (`startsession.php`: `<!--<CSV>"OK","<sessid>","<url start>","<url exit>",</CSV>-->`).
3. If the US protocol differs, implement the `protocol=us` profile (another handler or a framing adapter) without touching `jp`.
4. Extend the test client to slot→game (0x6407…0x6916) with 2 players, and to GSINFO pointing at `public_ip`.
5. Before any public deploy: hash the passwords, turn off `api.dev_login`, decide on hosting, and publish the code (AGPL).
