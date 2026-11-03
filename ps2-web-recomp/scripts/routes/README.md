# Exploring routes

`scripts/port.sh` finds the code its static pass missed by running the game headless and recording every
address that had no translation (`--misses`). It can only find code the run reaches, so the default input
(start, then cross every 300 frames) stops at the title screens. A route is an input script that takes a run
further: into a level, through doors, into scripted events.

```
scripts/port.sh outbreak1 ~/reo-web/isos/file1.iso --route scripts/routes/outbreak1-jsbar-bathroom.txt
```

`--route` can be given several times; every exploring round plays each route once and merges what they find.
Untranslated code runs in the interpreter (`runtime/src/interp.cpp`), so a run no longer stops at the first
miss and one route collects every miss along its path.

## Format

```
# comment
frames 13000            # length of the run (optional, defaults to --frames)
300:start               # frame:button[+button][:frames held], frames held defaults to 6
7515:up:95
12305:cross:8
```

Buttons: `select l3 r3 start up right down left l2 r2 l1 r1 triangle circle cross square`.
Frames count from power-on, and the run uses a new memory card.

### Sync points

Loading times change when the runtime changes, and a route with only absolute frames then drifts. A sync
point ties the route to something on screen:

```
4402:sync:black         # the screen went black at frame 4402 when the route was recorded
5591:sync:visible       # the screen came back from black at frame 5591
```

The runner watches the screen from the end of the press before the sync point. When the change comes early,
the rest of the route moves earlier with it; when it comes late, the route waits (no later press starts).
The log says what happened: `[route] sync visible (route frame 5591) at frame 5600, shift +9`. "Black" is a
mean brightness of 2 or less (0-255), "visible" is 8 or more after having been black; dark game scenes
sit around 10-25, the NOW LOADING screen counts as black. To set the frame of a new sync point, put any
guess, run the route once and copy the frame the log reports (then the shift is +0 for this build).
Save states do not keep the shift: when recording from a state, the presses are in power-on frames of the
run the state came from, which equal route frames only while the shift is +0.

## Recording a route

Runs are deterministic, so a route can be built a piece at a time from save states:

1. Boot with the inputs so far and save a state: `ps2run ... --press ... --save-state 7500:a.state --frames 7600`.
2. Try the next inputs from it, with frames written often: `ps2run ... --load-state a.state --press 7510:right:30
   --frames 7800 --frames-dir out --frame-every 40`. Presses keep absolute frame numbers.
3. When a try works, save a new state inside it and go on from there.
4. Put the pieces together, cutting each press at the frame where the next piece's state was saved (a press
   still held at a save point is not part of the state), and check the whole route from power-on.

In Outbreak the controls are tank-style: `left`/`right` turn (about 20 frames for a quarter turn), `up` walks
forward, `cross` checks or opens what Kevin faces. A run that goes black for good has hit something; look
for `[ee] interpreting untranslated code` and `[ee] halted` in the log.

## Routes

| File | Game | Covers |
|---|---|---|
| `outbreak2-wildthings.txt` | SLUS-20984 | Boot, scenario "wild things" with Kevin, Mark and Jim on NORMAL, the intro FMV skipped, the zoo entrance cutscene, then Kevin walks to the truck and opens the inventory (frame 9300). Two sync points: NOW LOADING and the zoo appearing. |
| `outbreak1-jsbar-bathroom.txt` | SLUS-20765 | Boot, scenario "Outbreak", J's Bar, the scripted attack and its reload, the kitchen behind the bar, the men's bathroom door (room load at ~12800). **Out of sync since the net merge**: after the reload Kevin is no longer where the kitchen walk expects him (not a load-time drift), so the post-reload part needs recording again. |
| `scenario_route.py` (generator) | both | `scenario_route.py <game> <index> <seed> [frames]` prints a route: boot with a new card, the n-th Scenario Select entry, the character screens, `sync:black` on NOW LOADING, the intro skipped, then a random walk (turns, walking with cross, aim and fire, inventory, map/pause). New cards open File #1 "Outbreak" (index 0) and File #2 training ground, wild things, underbelly, flashback and desperate times (0-4). |

Lines `frame:poke:addr=hex` (or `first-last:poke:addr=hex`, every frame of the range) write EE RAM through
`PS2_POKE`, for state that would otherwise need a long play (port.sh and the native runner pass them on).
