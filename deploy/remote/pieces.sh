#!/bin/bash
# shellcheck disable=SC2012  # piece names are ours (NNN.bin), ls is fine
# reo-pieces: cuts the shared ISOs into 64 MiB pieces (<site>/disc/NNN.bin) that Cloudflare can cache.
# Idempotent: a site whose pieces already match the ISO (count, sizes and the file stamp) is left alone.
set -euo pipefail
ISO="${REO_ISO:-/opt/reo/shared/iso}"
PIECE=67108864

for site in file1 file2; do
    src="$ISO/$site/disc.iso"
    dir="$ISO/$site/disc"
    [ -f "$src" ] || { echo "reo-pieces: no $src, skipped"; continue; }
    size=$(stat -c %s "$src")
    stamp="$size-$(stat -c %Y "$src")"
    count=$(( (size + PIECE - 1) / PIECE ))
    if [ -f "$dir/.stamp" ] && [ "$(cat "$dir/.stamp")" = "$stamp" ] && [ "$(ls "$dir"/*.bin 2>/dev/null | wc -l)" = "$count" ]; then
        echo "reo-pieces: $site up to date ($count pieces)"
        continue
    fi
    tmp="$ISO/$site/.disc-new"
    rm -rf "$tmp"
    mkdir -p "$tmp"
    split -b "$PIECE" -d -a 3 --additional-suffix=.bin "$src" "$tmp/"
    [ "$(ls "$tmp"/*.bin | wc -l)" = "$count" ] || { echo "reo-pieces: wrong piece count for $site" >&2; exit 1; }
    # verification: the pieces glued back must have the ISO's size and hash
    a=$(cat "$tmp"/*.bin | sha256sum | cut -d' ' -f1)
    b=$(sha256sum "$src" | cut -d' ' -f1)
    [ "$a" = "$b" ] || { echo "reo-pieces: hash mismatch for $site" >&2; exit 1; }
    echo "$stamp" > "$tmp/.stamp"
    chmod 0755 "$tmp"; chmod 0644 "$tmp"/*.bin "$tmp/.stamp"
    rm -rf "$dir"
    mv "$tmp" "$dir"
    echo "reo-pieces: $site ready ($count pieces)"
done
