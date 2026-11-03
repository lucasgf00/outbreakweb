# reo-bioserver

Fan server for **Resident Evil Outbreak File #1 and File #2** (PS2), part of the
reo-web project. It runs the lobby (login, areas, rooms, game slots, chat) and
the in-game relay for both games in one JVM, and adds a small read-only HTTP API
so a website can show open rooms.

## Origin and license

This is a derivative of **BioServer** by obsrv.org (ghostline / gh0stl1ne):

| Upstream | Commit | Here |
|---|---|---|
| https://gitlab.com/gh0stl1ne/Bioserver1 (File #1, JP) | `a29e7ee` (2023-08-12) | `file1/src/bioserver1/`, `database/bioserver1.sql` |
| https://gitlab.com/gh0stl1ne/Bioserver2 (File #2, JP) | `a2b5d7f` (2019-10-21) | `file2/src/bioserver2/`, `database/bioserver2.sql` |

Copyright (C) 2013-2019 obsrv.org. Modifications (C) 2026 reo-web contributors.

Licensed under the **GNU Affero General Public License v3.0 or later**
(`LICENSE.txt`). If you run a modified version of this server for users over a
network, the AGPL requires you to offer them its complete source code.

Every upstream file carries a modification notice; to see every change, compare
`file1/` and `file2/` with the upstream commits above (`scripts/patch_upstream.py`
applies the mechanical part of the changes). Not imported from upstream: the PHP web login
(`www/`), the PDF manual and `patch/` (the File #1 1.01 patch data is derived from
the game and is never committed here). No game data of any kind belongs in this
repository.

https://github.com/corbin-zip/bioserver (a 2023 merge of both upstreams) was
reviewed; its only functional changes (java.sql imports, a longer timeout value
in 0x61A0) are covered or deliberately left out.

## What comes from BioServer, and what is ours

BioServer was the starting point of this server, and we are grateful for it. In short:

- **From BioServer:** the JP lobby and game servers for File #1 and File #2
  (`file1/`, `file2/`, still included and started), the File #1 area table that the
  US lobby reuses (`bioserver1.Areas`), and the database schema.
- **Written for this project:** everything the US discs actually use online, which BioServer
  never supported: the `app01` service (TCP 10127) and the `kkClient` lobby and in-game
  relay (UDP 9090) in `common/src/reo/us/`, plus the site's HTTP API (rooms, chat,
  moderation, metrics, friends, reports) in `common/src/reo/common/`, the tests and the tools.

This project is independent: it is not affiliated with obsrv.org, does not use or connect to
their servers, and they are not responsible for it. Problems with this server should be
reported here, not to them.

## What changed vs upstream

- Packages renamed `bioserver` -> `bioserver1` / `bioserver2`, so both games run
  in one process (`reo.Main`). Game-specific code (areas, rule sets, 0x6881 data,
  File #1 patch flow) stays separate; nothing was merged by hand.
- Configuration from `config/server.properties` (+ `config/local.properties`,
  not committed): ports, bind address, gameserver address/port sent in GSINFO
  (the port was hardcoded), DB URL/credentials, hostnames for the DNS override.
- Database: `java.sql` + MariaDB Connector/J, URL from config, all queries are
  prepared statements (upstream built SQL with `String.format` from client data).
- `ClientList` uses a `CopyOnWriteArrayList` so the HTTP API can read it safely.
- Missing `patch.raw` no longer crashes File #1 (update to 1.01 is disabled).
- New (`common/src/reo/`): `Main`, `ServerConfig`/`GameConfig`, `PacketLog`
  (hex + parsed 12-byte headers for every socket read/write), `RawSniffer`
  (capture-only listeners for unknown ports), `HttpApi` (`/api/rooms`,
  `/api/dns`, `/api/health`, dev-only `/api/dev/login`), `SessionStore`
  (replaces the PHP login: users table -> 8-digit session id), and
  `StatusView` per game.
- `tools/testclient.py`: protocol test client (see below).

## Quick start (Ubuntu / WSL)

```sh
# packages + databases (root), then build (normal user)
sudo scripts/setup.sh deps db
scripts/setup.sh build
# run both games (File #1 lobby 8300 / game 8690, File #2 lobby 8200 / game 8590, API 127.0.0.1:8088)
scripts/run.sh
# in another shell: protocol test (logs in as test1/test2 over /api/dev/login)
python3 tools/testclient.py              # File #1
python3 tools/testclient.py --game file2 # File #2
curl -s http://127.0.0.1:8088/api/rooms
```

Packet log: `PACKETLOG=hex scripts/run.sh` writes `logs/packets.log`.

## Status

The JP protocol is what this code implements. The US clients (SLUS-20765,
SLUS-20984) have not been captured yet; whether they speak the same lobby
protocol is unknown. See the reo-web docs (`docs/notes/protocol.md`,
`docs/notes/server.md`).
