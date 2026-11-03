# Outbreak Web

**Resident Evil Outbreak File #1 and File #2 (US, PS2) playable in the browser, online included.**
This is the code behind [outbreakweb.com](https://outbreakweb.com): a static recompiler that turns the PS2 game into WebAssembly, the web page around it, a fan lobby server, and a WebSocket-to-Ethernet gateway that lets the browser game reach that server.

This is an unofficial fan project. It is not affiliated with or endorsed by Capcom or Sony. Resident Evil and Outbreak are trademarks of Capcom; PlayStation is a trademark of Sony Interactive Entertainment.

## What is (and is not) in this repository

This repository contains **only original code and third-party code under its own license**. It contains **no game code or data**, and never will. Not included, on purpose:

| Not included | Why / where it comes from |
|---|---|
| Disc images (ISO), the game's executable (ELF), overlays and any file from the disc | Capcom's copyrighted game. Use a disc image of a game you own. |
| Generated C++ (`generated/`) and the built `.wasm` / `.data` | They are a translation of the game's executable, so they belong to the game's owners. Build them on your own machine and do not publish them. |
| Game art: screenshots, videos, item icons, portraits, map names | The page's status tracker decodes icons and names from **your own disc** in your browser at run time. |
| Production secrets and private config | Admin tokens, database passwords, TLS keys, webhook URLs, the server address. They live only on the server and the operator's PC. Templates (`*.example`) show the format. |
| Internal notes, logs and packet captures | Operational history and data from real players. The technical findings are in `docs/` instead. |

Our own code patches (`ps2-web-recomp/scripts/patches/`) contain only addresses and instructions we wrote, never bytes copied from the game.

## Layout

| Path | What | License |
|---|---|---|
| `ps2-web-recomp/` | PS2 static recompiler (EE → C++ → WebAssembly), runtime (IOP, GS on WebGPU, SPU2, CDVD, DEV9/network), the web page (`web/shell.html`), port and build scripts | MIT (fork of [snuri00/ps2-web-recomp](https://github.com/snuri00/ps2-web-recomp)) |
| `server/` | Java lobby server for both games: the original JP lobby protocol (from Bioserver), the US `app01` / `kkClient` lobby (UDP 9090), and an HTTP API for the site (rooms, chat, moderation, metrics, friends, reports) | **AGPL-3.0** (derived from BioServer 1/2 by obsrv.org) |
| `net/gateway/` | Go gateway: Ethernet frames over WebSocket from the emulated network adapter, DHCP/DNS, and a strict allow list of upstream services | MIT |
| `deploy/` | Scripts to install and update a production server (Ubuntu VPS behind Cloudflare, Caddy, systemd), backups and alerts | MIT |
| `run-local.sh` | Runs server + gateway + web page on one machine | MIT |
| `docs/` | Technical notes: protocol, network, runtime, web, tracker, performance | CC BY 4.0 |

## Build and play locally

Linux or WSL2 (Ubuntu 24.04) on x86-64. You need your own disc images of **Resident Evil Outbreak (SLUS-20765)** and/or **File #2 (SLUS-20984)**.

```bash
mkdir -p ~/reo-web && cd ~/reo-web
git clone https://github.com/lucasgf00/outbreakweb.git .     # this repository, cloned into ~/reo-web

# 1. Translate and build the game (first run installs the Emscripten SDK; ~300 MB per game)
cd ps2-web-recomp
scripts/port.sh outbreak1 "/path/to/Resident Evil Outbreak.iso"
scripts/port.sh outbreak2 "/path/to/Resident Evil Outbreak File 2.iso"

# 2. Lobby server (Java 21 + MariaDB)
cd ../server && scripts/setup.sh

# 3. Gateway (Go 1.22+)
cd ../net/gateway && go build ./cmd/reo-gateway

# 4. Everything together: http://localhost:8620/
cd ~/reo-web && ./run-local.sh
```

Then open `http://localhost:8620/` in Chrome or Edge (WebGPU is required). Two players on one PC: a normal window and an incognito window, each with a different player name. See [docs/playing-online.md](docs/playing-online.md) and [ps2-web-recomp/README.md](ps2-web-recomp/README.md) for the recompiler itself.

Tests: `server/tests/run-tests.sh`, `cd net/gateway && go test ./...`, `deploy/tests/run-tests.sh`.

## Running your own public server

`deploy/` holds the scripts used for outbreakweb.com; see [deploy/README.md](deploy/README.md). Put your server's address in `~/.config/reo/deploy.env` (`REO_HOST=...`), never in the repository. If you run a modified server for others, the AGPL requires you to offer them its source.

## Documentation

Start with [docs/protocol.md](docs/protocol.md) (lobby and in-game protocol), [docs/network.md](docs/network.md) (client side network stack and gateway), [docs/runtime.md](docs/runtime.md) and [docs/web.md](docs/web.md). The notes separate verified facts from hypotheses; addresses refer to the US discs.

## Contributing

Issues and pull requests are welcome. Please:
- never attach or commit game files, generated code, builds, screenshots of the game, or bytes from the game;
- describe findings with addresses and evidence (logs, steps to reproduce);
- keep server changes under the AGPL.

Security problems: please report them privately (GitHub's "Report a vulnerability"), not in a public issue.

## Credits

- [ps2-web-recomp](https://github.com/snuri00/ps2-web-recomp) by Samir Nuri: the recompiler and runtime this project is built on.
- BioServer 1/2 by obsrv.org (ghostline / gh0stl1ne): the fan lobby server for the JP games that `server/` started from. The US online services the site uses were written for this project; see [server/README.md](server/README.md). This project is not affiliated with obsrv.org.
- The status tracker is based on the [Outbreak Tracker](https://github.com/madgamer98/OutbreakTracker) by Fothsid, killme, phoe-nix and madgamer98; web adaptation by a community member.
- PCSX2 and Play! were references for hardware behavior (no code copied; see `ps2-web-recomp/runtime/src/ipu/NOTICE` for the adapted MPEG tables).
