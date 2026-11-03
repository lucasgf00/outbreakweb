#!/usr/bin/env python3
"""Turns native runs traced with PS2_LOADLOG=2 into the page's disc warm-up list (web/shell.html GAMES[..].warm).

usage: disc_warm.py <log> [<log> ...]

Each log line "[disc] read <sector> +<count>" is a read the game made. The output is the 256 KiB chunks (the web
reader's unit, host/web.cpp kChunk) in the order the game first needs them, written as ranges ("12,40-44,1023").
Chunks of long sequential runs (a movie or a music stream: more than 8 chunks one after the other) are left out:
the reader's own read-ahead keeps up with those, and the player may skip the movie. The list holds positions on the
disc, never its bytes.
"""
import re
import sys

CHUNK = 256 << 10
SECTOR = 2048
RUN = 8

order, seen = [], set()
for path in sys.argv[1:]:
    for line in open(path, errors='replace'):
        m = re.match(r'\[disc\] read (\d+) \+(\d+)', line)
        if not m:
            continue
        sector, count = int(m.group(1)), int(m.group(2))
        if not count:
            continue
        for k in range(sector * SECTOR // CHUNK, ((sector + count) * SECTOR - 1) // CHUNK + 1):
            if k not in seen:
                seen.add(k)
                order.append(k)

# Drop long sequential runs (in first-use order).
keep, i = [], 0
while i < len(order):
    j = i
    while j + 1 < len(order) and order[j + 1] == order[j] + 1:
        j += 1
    if j - i + 1 <= RUN:
        keep.extend(order[i:j + 1])
    i = j + 1

parts, i = [], 0
while i < len(keep):
    j = i
    while j + 1 < len(keep) and keep[j + 1] == keep[j] + 1:
        j += 1
    parts.append(str(keep[i]) if i == j else '%d-%d' % (keep[i], keep[j]))
    i = j + 1
sys.stderr.write('%d chunks read, %d kept (%.1f MB)\n' % (len(order), len(keep), len(keep) * CHUNK / 1048576))
print(','.join(parts))
