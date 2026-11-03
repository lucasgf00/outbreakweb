#!/usr/bin/env bash
# reo-backup (installed on the VPS as /usr/local/sbin/reo-backup; reo-backup.timer runs it daily).
# One file per run: /opt/reo/backups/reo-<UTC date>.tar.gz (0600 root) with
#   mariadb.sql   dump of bioserver1 and bioserver2
#   opt/reo/data/server/   kk accounts, moderation, blocked names, metrics
#   etc/reo/  etc/caddy/   config and secrets (admin token, DB password, basic auth, TLS origin key)
# Keeps 14 days. To copy them to the PC: deploy.sh fetch-backup (README).
# The file contains credentials: keep the downloaded copy as private as C:\reo-secrets.
set -euo pipefail
umask 077
DEST=/opt/reo/backups
KEEP_DAYS=14
ts="$(date -u +%Y%m%d-%H%M%S)"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
mkdir -p "$DEST"

mariadb-dump --single-transaction --quick --routines --databases bioserver1 bioserver2 > "$tmp/mariadb.sql"
# The server keeps writing (metrics, chat, accounts) while tar reads: "file changed as we read it" makes tar exit
# with 1, and with set -e every backup stayed .part (none finished on 23/10). The archive is complete in that case
# (checked with gzip -t and its listing); only a status above 1 is a real failure.
rc=0
tar --warning=no-file-changed -czf "$DEST/reo-$ts.tar.gz.part" \
    -C "$tmp" mariadb.sql \
    -C / opt/reo/data/server etc/reo etc/caddy || rc=$?
[ "$rc" -le 1 ] || exit "$rc"
gzip -t "$DEST/reo-$ts.tar.gz.part"
mv "$DEST/reo-$ts.tar.gz.part" "$DEST/reo-$ts.tar.gz"
find "$DEST" -maxdepth 1 -name 'reo-*.tar.gz' -mtime +$((KEEP_DAYS - 1)) -delete
find "$DEST" -maxdepth 1 -name '*.part' -mmin +120 -delete
echo "backup: $DEST/reo-$ts.tar.gz ($(du -h "$DEST/reo-$ts.tar.gz" | cut -f1))"
