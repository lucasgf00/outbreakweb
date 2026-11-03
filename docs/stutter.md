# Teammates that walk in jerks ("take two or three steps and stop")

2026-11-03. Test bench: a local latency harness (two native File #1 instances in network namespaces, private gateway + server, `PLOG=hex`). Analysis script reads `[watch]` and `packets.log`. Character position per frame with `PS2_WATCH=1:4A5C68,4A5C70,...` (X/Z f32 of slots 0-3, struct in `tracker.md`: `0x4A5C30 + 0x10E0*slot`, X `+0x38`, Z `+0x40`). A walks (up 200 frames, right 80, up 250, left 80, up 200) and B watches.

## Short result
**Cause [DONE]: the game itself.** File #1 accumulates what it must send about the own character in a queue and sends it (reliable 0x0F) **only once per second of game time** (float threshold `1.0` in the ELF, `0x235F60`); File #2 every **0.5 s** (`0x2412A0`). The receiver moves the remote character toward the last received position and, on arrival, leaves it still until the next packet: 1-3 records per second = "two or three steps and stop". It happens at **0 ms latency** (run ST1), so it is not the network nor our gateway/relay.
**Fix [DONE, local]:** the runtime sets that threshold to `0.0` at startup (the queue is sent in the same step it fills). With that, in ST2 (0 ms) and ST3 (40/200 ms + 1 % loss) the remote walks continuously. It needs **new wasm** for both games; neither server nor gateway change.

## 1. Measurements

### ST1: 0 ms, original game [DONE]
- `NET="a:0:0:0 b:0:0:0"`. Both natives run at ~29-34 ms per frame (WSL CPU with two instances), i.e. ~15 game steps/s instead of 30: the real times below are stretched x2 relative to a PC at 60 fps.
- **A as seen by A** (truth): segment 1 straight and uniform, 2.4 units per step, frames 29520-29712.
- **A as seen by B** (B frames 29412-29586): speed per 6 frames between **2 and 17 units** (real A: ~7), a stop of **18 frames** mid-segment (8194.6 / 13327.1, where A never stopped), then slow advance, then fast. Second segment the same: 10 -> 2 -> 14 -> 0.5 -> stopped. Jump size per change p50 4.5, p90 7.7 (A: fixed 3.16).
- **What A sends** (`packets.log`, C>S): only reliable `0x0F`, one every **1.55-1.75 s** real (= 30 steps of A at ~17 steps/s), of **42, 84 or 126 bytes** (1, 2 or 3 records of 42 B). Nothing in between, walking or not. B the same (every ~2.1 s, slower) + an unreliable 0x0F of 38 B every ~2.3 s.
- **Server relay**: in -> out of each 0x0F **0.2-0.5 ms** (same `packets.log` marks). Gateway (measured earlier, network.md §14): p50 0.12 ms. Runtime: the DEV9 poll (`kJobDev9Poll`, 250 us emulated) delivers everything available each frame; the game consumes the whole stream every step (runtime.md §10.3).

### Game code [DONE, read in the generated C++ of ELF SLUS-20765]
- `0x1C7510` (per step) calls two "flushes":
  - `0x1C72F0`: `acc(0x3351E8) += dt(0x335064)`; if `acc < *(0x235F60)` (float **1.0**) it does nothing; otherwise it empties queue `0x333BD0` with `0x1CBBC0` = `0x1E5008(ctx, 0x8000, ...)` = **reliable 0x0F**, and sets `acc = 0`.
  - `0x1C7400`: same with `acc 0x3351E0`, threshold `*(0x32AF20)` (BSS = 0: sends as soon as there is something), queue `0x333FF0`, `0x1CBC60` = **unreliable 0x0F** (flags 0). There is almost no data there (38 B every ~2 s).
- The thresholds only change in `0x1F5E30`, handler no. 12 of the game-flow message table (`0x2373F0`): it reads from the stream `timeout (0x235F50, 20.0)`, `reliable interval (0x235F60)`, `unreliable interval (0x32AF20)` and a value at `0x8A6508`; 0 = leave alone. **No code in the ELF builds that message**: something else had to send it (the original server, or a Capcom tool?). [HYPOTHESIS] The original service may have lowered the interval with that message; there is no way to know what value it used. Our server does not send it.
- File #2 (SLUS-20984): same scheme; the equivalent handler (`0x1F9584...`) writes `0x241280` (timeout 31.0), **`0x2412A0` (reliable interval, 0.5 in the ELF)** and `0x35DA80` (unreliable). Capcom had already halved it in File #2.
- **Remote interpolation [DONE by measurement, algorithm HYPOTHESIS]:** B moves the remote a bit each step (changes every 2 frames) toward the last received position, with variable speed (0.3x to 3x the real one) and stops on arrival. It does not extrapolate: if no other record arrives, it stops. With records spaced 1 s apart the pattern is exactly "run, stop, run".

### ST2: 0 ms, threshold at 0.0 (`PS2_POKE=3000-40000:235F60=00000000`) [DONE]
- B sees A's segment 1 as **one continuous movement** (10-17 units every 6 B frames, no stops) and slows only where A slows; segment 2 the same. Jumps per change p50 3.5, p90 4.5, max 5.6 (ST1: p90 7.7, max 8.4). Position changes: 286 of 299 every 2 frames.
- Traffic: reliable 0x0F with data 151 per client over the whole game (ST1: 81); **peak 5 messages/s** per client (~200 B/s). Irrelevant for the gateway (3000 frames/s limit) and the server.

### ST3: 40 ms / 200 ms RTT, 1 % loss per direction, jitter 10/40 ms, runtime with the fix [DONE]
- `NET="a:40:1:10 b:200:1:40"` (profile L1 of protocol.md §9), runtime `420194b` without poke (log `[net] SLUS_207.65: game data flushed every 0.000 s (was 1.0 s)` in both). The natives were slower (~49-50 ms per frame), so times are not comparable at all with ST1/ST2.
- B sees **all three segments** of A as continuous movement: smooth start (4 -> 10 units per 6 frames), cruise 10-17, braking at the end; no mid-segment stop (ST1 had 18-frame stops and speeds 2-17 within the same straight segment). Jumps per change p50 1.9, p90 4.1, max 5.5.
- Traffic as in ST2 (154/152 reliables with data per client). TCP retransmissions from the loss were not noticeable as stops in this segment; with more loss a short pause can appear (normal TCP).

## 2. Hypotheses tested
- **H1 (slow client) [DONE, partial]:** the interval counts game time (steps), so a client at 45 fps sends every 1.33 s real instead of 1 s: slightly worse, but the jerkiness is there at 60 fps too. With the fix, the slow one sends on every own step.
- **H2 (network) [not the cause]:** the jerk is the same at 0 ms. A TCP retransmission on the WebSocket adds at most one more pause; with the fix, no mid-segment stop appeared in ST3 (1 % loss, 40/200 ms). Latency alone only shifts the remote in time (you see it where it was RTT/2 + RTT/2 ago).
- **H3 (our pipeline) [ruled out]:** relay 0.2-0.5 ms, gateway 0.12 ms, runtime delivers per frame. It also explains the old metric "gaps > 250 ms in 96 % of minutes, same in BR as in JP" (measurements.md): the game sends once per second.

## 3. Fix
- **Runtime** (outbreak `420194b`, `runtime/src/system.cpp`, `online_flush_interval()`, called from `online_peer_timeout()` at startup): if the ELF is SLUS-20765 and `0x235F60 == 1.0f`, or SLUS-20984 and `0x2412A0 == 0.5f`, it sets it to `0.0f`. Log `[net] SLUS_207.65: game data flushed every 0.000 s (was 1.0 s)`. `PS2_NET_FLUSH=<s>` picks another value, `PS2_NET_FLUSH=orig` leaves it as the game has it (for comparison).
- **Compatibility:** old and new clients understand each other (only when it is sent changes; the receiver already accepts any rate). A player with the old page cached still looks jerky to others until reload.
- **Deploy:** new wasm for File #1 and File #2. No server or gateway restart.
- **Not done / alternatives:** the server could inject message no. 12 into each client's stream (record format not read); not needed with the runtime change.

## Evidence
Local runs only (not published): per-frame `[watch]` logs for ST1-ST3 on both clients, `packets` logs for ST2/ST3, and the key-press files. The ST1 `packets.log` was overwritten by ST2 (the ST1 timings cited above were read before). The run used `PLOG=hex` and `PS2_WATCH=1:4A5C68,4A5C70,4A6D48,4A6D50,4A7E28,4A7E30,4A8F08,4A8F10` on both clients; ST2 adds `PS2_POKE=3000-40000:235F60=00000000`.

## Open
- Confirm in the browser with two real tabs/players (60 fps) that the remote walks continuously.
- File #2 in a two-client run (only verified that the value is found and changed at startup).
