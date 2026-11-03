#!/usr/bin/env bash
# reo-admin: moderation through the server's /api/admin with the admin token (run as root on the VPS;
# the token never leaves /etc/reo/admin.env).
#   reo-admin list
#   reo-admin ban|unban|mute|unmute|purge|release name <Name> [minutes] [reason...]
#   reo-admin ban|unban|mute|unmute ip <address> [minutes] [reason...]
# minutes: 0 = permanent (default). ban of a name also deletes its chat messages.
set -euo pipefail
# shellcheck disable=SC1091
. /etc/reo/admin.env
[ -n "${REO_ADMIN_TOKEN:-}" ] || { echo "no REO_ADMIN_TOKEN in /etc/reo/admin.env"; exit 1; }

action="${1:-}"; kind="${2:-}"; who="${3:-}"; minutes="${4:-0}"
reason="${*:5}"
case "$action" in
    list) body='{"action":"list"}' ;;
    ban|unban|mute|unmute|purge|release)
        case "$kind" in
            name) [[ "$who" =~ ^[A-Za-z0-9]{1,15}$ ]] || { echo "bad name"; exit 2; } ;;
            ip)   [[ "$who" =~ ^[0-9A-Fa-f:.]{2,45}$ ]] || { echo "bad ip"; exit 2; }
                  [[ ! "$action" =~ ^(purge|release)$ ]] || { echo "$action works by name"; exit 2; } ;;
            *) echo "usage: reo-admin $action name|ip <who> [minutes] [reason]"; exit 2 ;;
        esac
        [[ "$minutes" =~ ^[0-9]{1,7}$ ]] || { echo "minutes must be a number (0 = permanent)"; exit 2; }
        reason="$(printf '%s' "$reason" | tr -cd 'A-Za-z0-9 _.,:-' | cut -c1-80)"
        body="$(printf '{"action":"%s","%s":"%s","minutes":%s,"reason":"%s"}' "$action" "$kind" "$who" "$minutes" "$reason")"
        ;;
    *) sed -n '2,8p' "$0"; exit 2 ;;
esac
curl -s --max-time 10 -X POST http://127.0.0.1:8088/api/admin \
    -H "Authorization: Bearer $REO_ADMIN_TOKEN" -H 'Content-Type: application/json' --data-binary "$body"
echo
