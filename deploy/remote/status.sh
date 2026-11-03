#!/usr/bin/env bash
# reo-status: summary of the VPS (services, health, disk, RAM, recent errors). Run as root.
#   reo-status            summary
#   reo-status logs [unit] [lines]   recent log of reo-server | reo-gateway | caddy (default: all three, 100 lines)
set -uo pipefail
if [ "${1:-}" = logs ]; then
    u="${2:-}"; n="${3:-100}"
    [[ "$n" =~ ^[0-9]+$ ]] || n=100
    case "$u" in
        reo-server|reo-gateway|caddy|mariadb|fail2ban) exec journalctl -u "$u" -n "$n" --no-pager ;;
        "") exec journalctl -u reo-server -u reo-gateway -u caddy -n "$n" --no-pager ;;
        *) echo "unit: reo-server | reo-gateway | caddy | mariadb | fail2ban"; exit 2 ;;
    esac
fi

echo "== $(hostname) $(date -u '+%F %T UTC'), up $(uptime -p | sed 's/^up //')"
echo "release: $(basename "$(readlink /opt/reo/current 2>/dev/null)" 2>/dev/null || echo none)"
echo
echo "== services"
for u in reo-server reo-gateway caddy mariadb fail2ban reo-backup.timer reo-cf-ips.timer reo-alert.timer; do
    printf '  %-18s %s (since %s)\n' "$u" "$(systemctl is-active "$u")" "$(systemctl show -p ActiveEnterTimestamp --value "$u" | cut -c5-23)"
done
printf '  restarts: reo-server %s, reo-gateway %s\n' "$(systemctl show -p NRestarts --value reo-server)" "$(systemctl show -p NRestarts --value reo-gateway)"
echo
echo "== health"
reo-health | sed 's/^/  /'
echo
echo "== players"
curl -s --max-time 3 http://127.0.0.1:8088/api/rooms | python3 -c 'import json,sys
try:
    d=json.load(sys.stdin)
except Exception:
    print("  (no /api/rooms)"); sys.exit()
print("  " + json.dumps(d)[:300])' 2>/dev/null
curl -s --max-time 3 http://127.0.0.1:8765/healthz | sed 's/^/  gateway: /'
echo
echo "== http, last hour (Caddy access log, no IPs)"
python3 - /var/log/caddy/access.log <<'PY' 2>/dev/null | sed 's/^/  /'
import collections, json, sys, time
since = time.time() - 3600
total, errors, slow = 0, collections.Counter(), []
try:
    log = open(sys.argv[1], encoding='utf-8', errors='replace')
except OSError:
    print('(no access log yet)')
    sys.exit()
for line in log:
    try:
        e = json.loads(line)
    except ValueError:
        continue
    if e.get('ts', 0) < since:
        continue
    total += 1
    status, uri = e.get('status', 0), (e.get('request') or {}).get('uri', '?')
    if status >= 500:
        errors[(status, uri)] += 1
    slow.append((e.get('duration', 0), status, uri, e.get('size', 0)))
print('requests: %d, 5xx: %d' % (total, sum(errors.values())))
for (status, uri), n in errors.most_common(10):
    print('  %5d x %d %s' % (n, status, uri))
for d, status, uri, size in sorted(slow, reverse=True)[:3]:
    print('  slowest: %.1f s %d %s (%d bytes)' % (d, status, uri, size))
PY
echo
echo "== resources"
df -h / /opt/reo | sed 's/^/  /' | uniq
free -m | sed 's/^/  /'
printf '  memory of services: reo-server %s MB, reo-gateway %s MB\n' \
    "$(( $(systemctl show -p MemoryCurrent --value reo-server 2>/dev/null | grep -E '^[0-9]+$' || echo 0) / 1048576 ))" \
    "$(( $(systemctl show -p MemoryCurrent --value reo-gateway 2>/dev/null | grep -E '^[0-9]+$' || echo 0) / 1048576 ))"
echo
echo "== security"
fail2ban-client status sshd 2>/dev/null | grep -E 'Currently banned|Total banned' | sed 's/^[ |`-]*/  /'
ufw status 2>/dev/null | head -1 | sed 's/^/  ufw: /'
printf '  rate limited (24 h): %s\n' "$(journalctl -u reo-server -u reo-gateway --since -24h --no-pager 2>/dev/null | grep -ci 'rate limit\|too many\|429')"
echo
echo "== backups (last 3)"
printf '  last download to the PC: %s\n' "$(stat -c %y /opt/reo/backups/.offsite 2>/dev/null | cut -c1-16 | grep . || echo never)"
find /opt/reo/backups -name 'reo-*.tar.gz' -printf '  %TY-%Tm-%Td %TH:%TM  %s bytes  %f\n' 2>/dev/null | sort | tail -3
echo
echo "== last errors (24 h)"
journalctl -u reo-server -u reo-gateway -u caddy -p warning --since -24h -n 15 --no-pager 2>/dev/null | sed 's/^/  /'
