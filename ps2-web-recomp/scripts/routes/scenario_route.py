#!/usr/bin/env python3
"""Writes an exploring route for one Outbreak scenario: boot, Scenario Select (n-th entry), the
character screens, NOW LOADING (sync point), the intro skipped, then a long random walk.

usage: scenario_route.py <game> <index> <seed> [frames] > route.txt
  game   outbreak1 (SLUS-20765) or outbreak2 (SLUS-20984)
  index  entry of Scenario Select, 0 = the first (File #1: 0 Outbreak; File #2: 0 training ground,
         1 wild things, 2 underbelly, 3 flashback, 4 desperate times). Only the entries a new
         memory card unlocks can be chosen.
  seed   random walk seed (a route is the same for the same arguments)

The walk is tank-style: turn left/right, walk up with cross every 25 frames (check, open, pick up),
now and then aim and fire (r1+cross), the inventory (start, a few moves, circle) and the map/pause
(select, circle). It does not follow a plan; it is cheap coverage: many runs with different seeds
reach cutscenes, doors, items and enemies the static analysis may have missed (port.sh --route).
"""
import random
import sys

# Boot to Scenario Select with a new memory card; frames as recorded in outbreak2-wildthings.txt and
# outbreak1-jsbar-bathroom.txt (the menu opens at ~3100 in File #2, ~3300 in File #1).
BOOT = {
    'outbreak2': ['300:start'] + ['%d:%s' % (f, 'start' if (f // 150) % 2 == 0 else 'cross') for f in range(600, 3001, 150)],
    'outbreak1': ['300:start'] + ['%d:cross' % f for f in range(600, 1801, 300)] + ['2400:start', '2500:start', '2700:cross', '3000:cross'],
}
MENU = {'outbreak2': 3110, 'outbreak1': 3400}


def walk(r, f, end):
    out = []
    while f < end - 300:
        roll = r.random()
        if roll < 0.06:   # inventory: open, move the cursor, close
            out += ['%d:start' % f, '%d:%s' % (f + 40, r.choice(['right', 'down', 'left', 'up'])), '%d:circle' % (f + 80), '%d:circle' % (f + 120)]
            f += 160
            continue
        if roll < 0.09:   # map / pause and back
            out += ['%d:select' % f, '%d:circle' % (f + 60), '%d:select' % (f + 100)]
            f += 140
            continue
        if roll < 0.20:   # aim and fire
            out.append('%d:r1+cross:%d' % (f, r.randint(10, 40)))
            out.append('%d:r1:%d' % (f + 45, r.randint(20, 60)))
            f += 110
            continue
        t = r.randint(0, 40)
        if t > 4:
            out.append('%d:%s:%d' % (f, r.choice(['left', 'right']), t))
            f += t + 2
        w = r.randint(30, 220)
        out.append('%d:up:%d' % (f, w))
        out += ['%d:cross:4' % c for c in range(f + 20, f + w, 25)]
        f += w + 4
    return out


def main():
    game, index, seed = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
    frames = int(sys.argv[4]) if len(sys.argv) > 4 else 30000
    r = random.Random(seed * 1000 + index)
    out = ['frames %d' % frames] + BOOT[game]
    f = MENU[game]
    for _ in range(index):
        out.append('%d:right' % f)
        f += 100
    out.append('%d:cross' % (f - 10 if index else f + 90))
    f += 190
    # Character, partners and difficulty: start/cross alternated (start confirms where cross would
    # change a partner slot). NOW LOADING is black; from there the presses follow the load.
    for k in range(8):
        out.append('%d:%s' % (f + 150 * k, 'start' if k % 2 == 0 else 'cross'))
    f += 150 * 8
    out.append('%d:sync:black' % (f - 140))
    # Intro FMV and the opening cutscene: start skips the movie, cross the dialogue.
    for k in range(20):
        out.append('%d:%s' % (f + 100 + 150 * k, 'start' if k < 4 and k % 2 == 0 else 'cross'))
    f += 100 + 150 * 20
    out += walk(r, f, frames)
    print('\n'.join(['# %s scenario %d, walk seed %d (scripts/routes/scenario_route.py)' % (game, index, seed)] + out))


if __name__ == '__main__':
    main()
