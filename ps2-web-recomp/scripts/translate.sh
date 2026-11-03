#!/usr/bin/env bash
# Translates a ported game's machine code to C++ in generated/<name>, with the
# entry points, IOP modules, code overlays and code patches port.sh collected.
# usage: scripts/translate.sh <name> [--if-patches-changed]
#
# Code patches (eerecomp --patches) come from games/<name>/patches.txt when it
# exists, else from scripts/patches/<serial>.txt (serial from SYSTEM.CNF, e.g.
# SLUS-20765). They only change the translated code, never the bytes the game
# checks. --if-patches-changed skips the translation when generated/<name>
# was already translated with the same patches (build_web.sh uses it).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
name="$1"; only_if_changed="${2:-}"
game="$ROOT/games/$name"
generated="$ROOT/generated/$name"
boot=$(sed -n 's/^BOOT2 *= *//p' "$game/disc/SYSTEM.CNF" | tr -d '\r')
program=${boot#*\\}; program=${program%;*}
elf="$game/disc/$program"
[ -f "$game/core.elf" ] && elf="$game/core.elf"

serial=$(echo "$program" | tr '_' '-' | tr -d '.')
patches=""
if [ -f "$game/patches.txt" ]; then patches="$game/patches.txt"
elif [ -f "$ROOT/scripts/patches/$serial.txt" ]; then patches="$ROOT/scripts/patches/$serial.txt"; fi

stamp="$generated/patches.applied"
if [ "$only_if_changed" = "--if-patches-changed" ]; then
    if { [ -z "$patches" ] && [ ! -s "$stamp" ]; } || { [ -n "$patches" ] && [ -f "$stamp" ] && cmp -s "$patches" "$stamp"; }; then
        exit 0
    fi
    echo "code patches changed since the last translation, translating again"
fi

args=(--entries "$game/entries.txt")
[ -d "$game/modules" ] && args+=(--modules "$game/modules")
compgen -G "$game/overlays/*" > /dev/null && args+=(--overlays "$game/overlays")
[ -n "$patches" ] && { args+=(--patches "$patches"); echo "code patches: ${patches#$ROOT/}"; }
touch "$game/entries.txt"
# Keep the translator in step with the sources (a stale one may lack --patches).
ninja -C "$ROOT/build/tools" eerecomp > /dev/null
mkdir -p "$generated"
out=$("$ROOT/build/tools/eerecomp" "$elf" "${args[@]}" --out "$generated")
grep "patched words" <<< "$out" || true
tail -1 <<< "$out"
if [ -n "$patches" ]; then cp "$patches" "$stamp"; else : > "$stamp"; fi
