#!/usr/bin/env bash
# status.sh (PC): state of the VPS over SSH.
#   status.sh                       services, health checks, players, disk/RAM, bans, backups, last errors
#   status.sh logs [unit] [lines]   recent log (reo-server | reo-gateway | caddy | mariadb | fail2ban)
#   status.sh follow [unit]         live log (Ctrl+C to stop)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
. "$HERE/lib.sh"

case "${1:-}" in
    "") rsh reo-status ;;
    logs)
        u="${2:-}"; n="${3:-100}"
        [[ "$u" =~ ^(|reo-server|reo-gateway|caddy|mariadb|fail2ban)$ ]] || die "unit: reo-server | reo-gateway | caddy | mariadb | fail2ban"
        [[ "$n" =~ ^[0-9]+$ ]] || die "lines must be a number"
        rsh "reo-status logs ${u:-\"\"} $n"
        ;;
    follow)
        u="${2:-reo-server}"
        [[ "$u" =~ ^(reo-server|reo-gateway|caddy|mariadb|fail2ban)$ ]] || die "unit: reo-server | reo-gateway | caddy | mariadb | fail2ban"
        rsh_tty "journalctl -f -u $u"
        ;;
    *) sed -n '2,5p' "$0"; exit 2 ;;
esac
