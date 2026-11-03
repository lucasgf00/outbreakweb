# Second region: North America (and Asia)

A plan, no infrastructure implemented (2026-10-24). Based on [measurements.md](measurements.md) (RTT by country), [protocol.md](protocol.md) (kk, SERVER SELECT/FREE MODE), and `deploy/README.md`. Tags: **[FACT]** = in the code, logs or measurements; **[HYPOTHESIS]** = reasoned, untested; **[ESTIMATE]** = general knowledge, providers not consulted.

## 1. Why it is needed and how much it helps

- **[FACT]** The VPS is in Brazil. Mean RTT to the gateway: BR 26 ms, AR 50, CL 141, US 169, CA 171, MX 189, ES 195, JP 304, ID 372, MY 391, PH 394, TH 414. North America (US+MX+CA) was about 5,800 sessions in 26 h, as many as BR+AR.
- **[FACT]** All game traffic (0x0F/0x10) goes through the kk relay (`KkService`). The one-way delay from A to B is about `(rtt_A + rtt_B) / 2`, where each RTT is that player's RTT to **their** gateway; the gateway ↔ kk hop is loopback (0.1 ms in the gateway, ~0.5 ms in the relay; see §8).
- **[FACT, since 24/10]** Per-game measurement is now in place: `game X stats after …: <name> rtt N ms (max, jitter, samples); one-way est. worst pair N ms` and the `game_lag` metrics event (see network.md §14). With a few days of that we will know how many games are single-continent, which decides whether a region is worthwhile.
- **Consequence (triangle inequality):** for a same-continent pair, a nearby relay cuts the delay a lot; for a mixed pair (BR + US), the delay is nearly the same with the relay on either side, because it still has to cross the continent. Examples:

| Pair | Relay in BR (today) | Relay in the eastern US [ESTIMATE] |
|---|---|---|
| US + US | (169 + 169)/2 ≈ 170 ms one-way | (40-70 + 40-70)/2 ≈ 40-70 ms |
| MX + US | (189 + 169)/2 ≈ 180 ms | (60-80 + 40-70)/2 ≈ 50-75 ms |
| BR + US | (26 + 169)/2 ≈ 100 ms | (130-150 + 40-70)/2 ≈ 85-110 ms |
| BR + BR | 26 ms | (130-150)·2/2 ≈ 140 ms (they should not play there) |

RTTs to Virginia/New York from the US depend on the coast (east 20-40 ms, west 60-80 ms) and go through Cloudflare: **[ESTIMATE]**.

## 2. What runs on the node

Options:

1. **Only the gateway in the US, kk in Brazil.** The gateway's UDP flow would go to Brazil over the internet. **Not useful**: the relay stays in Brazil and a US+US pair still crosses twice. It would only swap TCP+Cloudflare for UDP on the long leg (less head-of-line blocking), not the distance. [HYPOTHESIS: improves jitter, not the average.]
2. **Gateway + local kk with the central lobby in Brazil (split control and data).** The game client talks to **a single** kk address and one session (tag, per-receiver reliable seq/ack, `relSeq`), so the relay cannot be moved to a separate process without reimplementing the session state in the gateway. A lot of work for something fragile. Discarded for now.
3. **A full server per region (recommended).** The US node runs the same thing as Brazil, minus what is not needed:
   - `reo-gateway` (same config as `deploy/files/gateway.json`; the allowlist points to the local ports).
   - `reo-server` (the same jar) with `reo.games=` empty (no JP lobbies and no MariaDB, like the local test runs) and only what the US client uses: app01 TCP 10127, regweb 8080, kk UDP 9090/9092 and the API on 127.0.0.1:8088. **[FACT]** the server starts this way without MariaDB.
   - `caddy` with only `/eth` (and `/api/health` for the health check and for the page's latency measurement, §5). The web, the chunked disc images, chat and `/admin` stay in Brazil behind Cloudflare (Cloudflare's cache already distributes the disc chunks).
   - Each region has its own lobby: rooms, areas and sessions are local. There is no shared game state.

## 3. How rooms are split

- **[FACT]** SERVER SELECT has two fixed entries in the ELF (DOWN TOWN / SCENARIO MODE and UP TOWN / FREE MODE, texts at `0x610AC8`); the patch forces FREE MODE. Both use the same kk address and the same session: **they cannot be used to choose a region**.
- **[FACT]** The "AREA 01…" names are set by the client by index; the server only offers `obmfNN` objects (up to 99). Reserving areas per region in a single lobby does not change where the data flows, so it does not help either.
- **So the region is chosen by the page before the game starts** (the gateway URL), and inside the game everything is the same. To avoid splitting the community:
  - The page's `/api/rooms` (room list) queries both regions and tags each room with its region ("BR" / "NA").
  - Joining a room in the other region = reloading the game with that region (the page already supports `?gateway=`). It can be offered as a "play in NA" button next to the room.

## 4. Accounts

- **[FACT]** The page registers `{name, credential}` with `POST /api/kk/register`; credential = `hex(SHA-256("reo-web:"+name+":"+token))[0:12]`, deterministic for the same browser. The server stores `name=credential` in `kk-accounts.properties` (0600) and uses it to encrypt the login's 0x2D.
- Proposal: **Brazil is the source of truth.** On a login for a name it does not know, the NA server asks Brazil through an internal endpoint (`GET /api/internal/kk/account?name=` with a server-to-server token, over HTTPS only) and stores the response. Registration from the page keeps going only to Brazil (as today), so a name cannot be taken twice.
  - Cost: the first login of each name in NA waits for one query (~150 ms), once.
  - Moderation (`moderation.properties`, blocked names) through the same endpoint, refreshed every minute.
  - If Brazil does not respond, NA accepts only the names it already has (degrades without going down).
- Simpler alternative to start: an `rsync` every minute of `kk-accounts.properties` from Brazil to NA, with KkService re-reading it if it changed. It has a one-minute window in which a newly registered name cannot enter NA. [HYPOTHESIS: acceptable for a trial.]

## 5. How the page chooses the region

- **By measured latency, with the country as the initial value.** On page open: 3 `fetch`es to `/api/health` of each region (in parallel, uncached), take the median; the faster region wins if it beats the other by more than 30 ms; otherwise it stays on the country's region (`CF-IPCountry`, which the API can already return). It is saved in `localStorage` and can be changed by hand in Settings ("Region: automatic / South America / North America").
- Going by country alone is simpler but fails at the borders (Mexico, Caribbean, Central America, western Canada): the measurement is cheap and already goes through the same Cloudflare that the WebSocket uses.
- To play with a friend, the room list shows each room's region (§3) and the notice "your friend is in NA".

## 6. Changes in `deploy/`

- `deploy.sh --region na` (and `status.sh`, `admin.sh` with the same option): host and name in `lib.sh` (`REO_HOST_NA`), same package; `--region na` omits `web/` and `admin/` from the package.
- `files/Caddyfile.edge`: the `na.` host block with the origin TLS config, `/eth` → 127.0.0.1:8765 and `/api/health`; nothing else.
- `files/server-edge.properties`: `reo.games=`, `regweb`/app01/kk as in Brazil, `metrics.enabled=true` (own metrics), `us.kk.accounts_upstream=<Brazil origin>` + token in the server's private config.
- `bootstrap.sh`: no MariaDB in edge mode; the rest the same (firewall allowing only Cloudflare on 443, fail2ban, journald, units).
- `remote/health.sh`: in edge mode it does not check `/`, `/file1/`, `/file2/` or `/admin`; it does check `/eth` (101, and 403 with another Origin), kk UDP on loopback and the account query to Brazil.
- Cloudflare: a proxied `na` record (the origin certificate already covers the wildcard); the page's CSP (`connect-src`) adds the NA `wss://` and `https://` origins.
- `/admin` in Brazil: add NA's metrics by querying its `/api/admin/metrics` (or show them separately).

## 7. Cost [ESTIMATE, providers not consulted]

- What the node needs: 1-2 vCPU, 2 GB RAM (Java + Go), 20 GB disk. Small game traffic (≈ 1-2 KB/s per player according to `game X stats`; 100 players ≈ 0.5 TB/month in total counting the WebSocket), no discs (Cloudflare serves them from Brazil).
- A small VPS in Virginia (Ashburn) or New York/New Jersey: **US$ 5-12/month** depending on provider (1-2 vCPU, 2 GB plans at the usual VPS providers with Virginia locations). Check the included traffic (some US plans include 1-2 TB).
- Location: the east (Virginia/New York) is better for the eastern US, eastern Canada and the Caribbean; Dallas or Chicago is a middle point for the western US and Mexico [HYPOTHESIS: Dallas may be the best single location for US+MX; worth comparing against per-state measurements if Cloudflare provides them].

## 8. Avoidable delays reviewed (gateway and relay)

- **[FACT, measured 24/10]** Gateway in loopback (WebSocket → gVisor → UDP → echo → UDP → WebSocket): p50 0.12 ms, p99 0.6-2 ms (`TestUDPRelayDelay`, gateway commit `9f9e76a`). No queues or timers on the path: each frame is written on its own, with no Nagle (Go sets TCP_NODELAY by default on the WebSocket socket and Caddy is also Go).
- **[FACT, measured 24/10]** Local kk relay (Python client A → server → B, 0x0F): p50 0.6 ms, p99 6-7 ms including Python (a local test script). `relay()` sends on the same thread on receipt; `tick()` runs after each datagram, but walks only the sessions with pending reliables.
- No avoidable buffering, batching or timers were found: no changes were made. The delay is almost all distance (and Cloudflare).
- [HYPOTHESIS] What does add up and is not visible on the server: (a) TCP head-of-line blocking of the WebSocket: the p90 of the per-session **maximum** RTT is 434 ms even in BR (with a 26 ms mean), and a TCP retransmission stalls every message behind it. An unreliable transport (WebTransport over QUIC or an unordered WebRTC DataChannel) would fix it, but Cloudflare does not proxy those the way it does a WebSocket: a big change. (b) The browser delivers WebSocket messages in its event loop; if the thread is busy with a frame, they wait up to ~16 ms.

## 9. Asia (Singapore), briefer

- **[FACT]** PH 394 ms, TH 414, ID 372, MY 391, JP 304 mean RTT to Brazil, with spikes of several seconds (p90 of the max: PH 13.5 s, MY 7 s, TH 4.4 s). About 4,200 sessions from PH+ID+TH+MY+JP in 26 h.
- Same design as NA (full server per region, accounts from Brazil, region chosen by latency). Singapore suits Southeast Asia: **[ESTIMATE]** RTT 10-30 ms MY/ID, 30-50 TH, 40-70 PH, 70-90 JP. For Japan, Tokyo would be better (≈ 10-30 ms), but a single Singapore node already cuts ~300 to ~80 ms.
- The multi-second spikes from PH/ID/MY look like last-mile (mobile, Wi-Fi) [HYPOTHESIS]: a nearby node lowers the average, not those stalls.
- Cost **[ESTIMATE]**: small VPS in Singapore US$ 6-15/month (Asia tends to cost a bit more and include less traffic than the US).

## 10. Suggested order

1. Gather a few days of `game_lag` (already implemented) and count US+US / MX+US / mixed games.
2. Internal accounts endpoint in the server + `us.kk.accounts_upstream` (tested with two local stacks).
3. Edge mode in `deploy/` (Caddyfile, properties, health) tested with the deploy test scripts.
4. Region selector on the page and region in `/api/rooms`.
5. A US VPS, with the owner's approval (monthly cost).
