#!/usr/bin/env bash
# admin.sh (PC): moderation of the site's chat and lobby with the admin token, over SSH.
# The token stays on the VPS (/etc/reo/admin.env); this only passes validated words.
#   admin.sh list
#   admin.sh ban    name Fulano [minutes] [reason...]   (0 = permanent; also deletes Fulano's chat messages)
#   admin.sh mute   name Fulano 60 spam
#   admin.sh ban    ip 203.0.113.9 1440 flood
#   admin.sh unban|unmute name|ip <who>
#   admin.sh purge  name Fulano                         (delete the messages without banning)
#   admin.sh release name Fulano                        (free the name: anyone can register it again; kicks it if online)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
. "$HERE/lib.sh"

action="${1:-}"
case "$action" in
    list) rsh "reo-admin list" ;;
    ban|unban|mute|unmute|purge|release)
        kind="${2:-}"; who="${3:-}"; minutes="${4:-0}"
        reason="${*:5}"
        [[ "$kind" =~ ^(name|ip)$ ]] || die "usage: admin.sh $action name|ip <who> [minutes] [reason]"
        if [ "$kind" = name ]; then [[ "$who" =~ ^[A-Za-z0-9]{1,15}$ ]] || die "bad name"
        else [[ "$who" =~ ^[0-9A-Fa-f:.]{2,45}$ ]] || die "bad ip"; fi
        [[ "$minutes" =~ ^[0-9]{1,7}$ ]] || die "minutes must be a number (0 = permanent)"
        reason="$(printf '%s' "$reason" | tr -cd 'A-Za-z0-9_.,:-' | cut -c1-80)"   # one word on the wire
        rsh "reo-admin $action $kind $who $minutes $reason"
        ;;
    *) sed -n '2,11p' "$0"; exit 2 ;;
esac
