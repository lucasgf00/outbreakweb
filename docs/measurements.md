# Production measurements (since the metrics deploy of 2026-10-23)

## Snapshot 2026-10-24 (~26 h of data)
Computed with local scripts over the gateway/server logs and the metrics event files (`"t":"perf"` events).

### Latency to the gateway (WebSocket RTT, per session; median of avg, p90 of max) [FACT]
| Country | sessions | avg | p90 max |
|---|---|---|---|
| BR | 4012 | 26 ms | 434 |
| US | 3794 | 169 ms | 672 |
| AR | 2057 | 50 ms | 432 |
| MX | 1484 | 189 ms | 861 |
| PH | 1484 | 394 ms | 13 500 |
| ID | 1213 | 372 ms | 2 789 |
| CL | 1147 | 141 ms | 620 |
| ES | 653 | 195 ms | 577 |
| JP | 612 | 304 ms | 1 178 |
| CA / GB / CO | 512 / 436 / 434 | 171 / 180 / 177 ms | |
| TH / MY | 499 / 415 | 414 / 391 ms | 4 444 / 7 034 |
- The VPS is in Brazil: North America (US+MX+CA ≈ 5 800 sessions, as many as BR+AR) sees ~170-190 ms; Asia ~350-410 ms with spikes of several seconds.
- Between two players the delay is the sum of their two legs (the game always talks to the relay): two US players ≈ 170 ms one-way (85+85), US with PH ≈ 280 ms one-way.

### Browser performance (708 005 minutes, 20 939 browsers) [FACT]
- fps p5 = 49, p10 = 57, p50 = 60; 7.3% of minutes under 55 fps.
- **17.1% of players have a median < 55 fps** (slow machine): this is the group that desyncs most (the game runs slower for them).
- By browser (% of minutes < 55 fps): Firefox 2.1%, Opera 5.2%, Edge 5.9%, Chrome 8.2%, Safari 13.6%, Samsung 30.9%.

### Gaps in online reception [FACT, not a very useful metric]
- 4 648 online minutes. Max gap per minute p50 1.1 s, p90 12 s; 96% of minutes have some gap > 250 ms, **the same in BR (19 ms) as in JP**: it does not depend on distance. HYPOTHESIS: the game's own traffic (loads, cutscenes; the game sends little: `game X stats` ≈ 1 msg/s per member), not a network cut. Measuring lag needs another metric (one-way delay per game). **Explained (stutter.md):** File #1 sends its data once per game second.

### Games (`game X stats`, 8 692 games) [FACT]
- 6 233 "back to waiting", 2 458 "closed", duration p50 17 min.
- Pace ratio min/max between members: p10 0.25, p50 0.79; 1 256 games with a member < 40% of the fastest (may be host/guest role, not necessarily lag).

### New lag metric (Oct 24, pending deploy)
- `game X stats` now carries each member's RTT to the gateway and the **estimated one-way delay of the worst pair**; `lag_*` metrics and a `game_lag` event (network.md §14). It replaces the gaps for measuring lag. Region plan: [region-na.md](region-na.md).
