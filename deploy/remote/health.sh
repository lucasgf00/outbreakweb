#!/usr/bin/env bash
# reo-health: checks the live site from the VPS itself (through Caddy on 127.0.0.1:443, so the
# Cloudflare firewall rules do not matter). Exit 0 = healthy.
#   reo-health            one pass, prints each check
#   reo-health --wait N   retry for up to N seconds (used after a deploy; Java takes a few seconds)
set -uo pipefail
DOMAIN="${REO_DOMAIN:-outbreakweb.com}"
wait_s=0
[ "${1:-}" = --wait ] && wait_s="${2:-60}"

C=(curl -sk --max-time 5 --resolve "$DOMAIN:443:127.0.0.1")
check_all() {
    local fail=0 out code pw
    for u in reo-server reo-gateway caddy mariadb; do
        if systemctl is-active --quiet "$u"; then echo "ok   service $u"; else echo "FAIL service $u"; fail=1; fi
    done
    out="$("${C[@]}" "https://$DOMAIN/api/health")"
    if printf '%s' "$out" | grep -q '"ok" *: *true'; then echo "ok   /api/health"; else echo "FAIL /api/health: ${out:0:120}"; fail=1; fi
    for p in / /file1/ /file2/; do
        out="$("${C[@]}" -D - -o /dev/null "https://$DOMAIN$p")"
        if printf '%s' "$out" | head -1 | grep -q ' 200' && printf '%s' "$out" | grep -qi '^cross-origin-embedder-policy: require-corp'; then
            echo "ok   page $p (200, COOP/COEP)"
        else echo "FAIL page $p: $(printf '%s' "$out" | head -1)"; fail=1; fi
    done
    code="$("${C[@]}" -o /dev/null -w '%{http_code}' "https://$DOMAIN/admin")"
    if [ "$code" = 401 ]; then echo "ok   /admin asks for basic auth (401)"; else echo "FAIL /admin without auth gave $code"; fail=1; fi
    code="$("${C[@]}" -o /dev/null -w '%{http_code}' "https://$DOMAIN/api/dev/login")"
    if [ "$code" = 404 ]; then echo "ok   /api/dev/* closed (404)"; else echo "FAIL /api/dev/login gave $code"; fail=1; fi
    # WebSocket handshake through Caddy; the gateway answers 101 and the session is dropped right away.
    code="$(curl -sk --http1.1 --max-time 3 --resolve "$DOMAIN:443:127.0.0.1" -o /dev/null -w '%{http_code}' \
        -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' \
        -H 'Sec-WebSocket-Key: cmVvLWhlYWx0aC1jaGVjaw==' -H "Origin: https://$DOMAIN" "https://$DOMAIN/eth")"
    if [ "$code" = 101 ]; then echo "ok   wss /eth (101)"; else echo "FAIL wss /eth gave $code"; fail=1; fi
    code="$(curl -sk --http1.1 --max-time 3 --resolve "$DOMAIN:443:127.0.0.1" -o /dev/null -w '%{http_code}' \
        -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' \
        -H 'Sec-WebSocket-Key: cmVvLWhlYWx0aC1jaGVjaw==' -H 'Origin: https://evil.example' "https://$DOMAIN/eth")"
    if [ "$code" = 403 ]; then echo "ok   wss /eth refuses other origins (403)"; else echo "FAIL wss /eth with another origin gave $code"; fail=1; fi
    # the lobby server's DB login, exactly as the server does it (TCP to 127.0.0.1)
    pw="$(sed -n 's/^db.password=//p' /etc/reo/local.properties 2>/dev/null)"
    if MYSQL_PWD="$pw" mariadb -h 127.0.0.1 -u bioserver -N -e 'SELECT 1' bioserver1 >/dev/null 2>&1 \
       && MYSQL_PWD="$pw" mariadb -h 127.0.0.1 -u bioserver -N -e 'SELECT 1' bioserver2 >/dev/null 2>&1; then
        echo "ok   DB login bioserver@127.0.0.1"
    else echo "FAIL DB login bioserver@127.0.0.1 (/etc/reo/local.properties)"; fail=1; fi
    if journalctl _SYSTEMD_INVOCATION_ID="$(systemctl show -p InvocationID --value reo-server)" --no-pager 2>/dev/null \
       | grep -q 'SQLException\|Exception in thread'; then
        echo "FAIL reo-server logged exceptions since it started (status.sh logs reo-server)"; fail=1
    else echo "ok   reo-server log without exceptions"; fi
    if ss -Hlun 'sport = :9090' | grep -q 127.0.0.1; then echo "ok   kk UDP 9090 on loopback"; else echo "FAIL kk UDP 9090"; fail=1; fi
    # none of our internal ports may listen on a public address; anything else besides 22/80/443 is a warning
    local internal=':(3306|8080|8088|8200|8300|8590|8690|8765|9090|9092|10127)$' extra
    out="$(ss -Hltnu 2>/dev/null | awk '$5 !~ /^(127\.|\[::1\]|\[::ffff:127\.)/ {print $1, $5}')"
    if printf '%s\n' "$out" | grep -Eq "$internal"; then
        echo "FAIL internal port on a public address: $(printf '%s\n' "$out" | grep -E "$internal" | tr '\n' ' ')"; fail=1
    else echo "ok   internal ports only on loopback"; fi
    extra="$(printf '%s\n' "$out" | grep -Ev ':(22|80|443)$' | grep . | tr '\n' ' ' || true)"
    if [ -n "$extra" ]; then echo "warn other public listeners: $extra"; fi
    return $fail
}

deadline=$(( $(date +%s) + wait_s ))
while true; do
    res="$(check_all)"; rc=$?
    if [ $rc = 0 ] || [ "$(date +%s)" -ge "$deadline" ]; then
        echo "$res"
        [ $rc = 0 ] && echo "HEALTHY" || echo "UNHEALTHY"
        exit $rc
    fi
    sleep 3
done
