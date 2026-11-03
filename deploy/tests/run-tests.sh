#!/usr/bin/env bash
# Local checks of the kit (no VPS involved): shellcheck, caddy validate, gateway config, systemd units.
# Needs: shellcheck, caddy, systemd-analyze, the gateway binary (~/reo-web/net/gateway/bin/reo-gateway).
set -euo pipefail
K="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GW="${REO_SRC:-$HOME/reo-web}/net/gateway/bin/reo-gateway"
fail=0
ok()  { printf 'ok   %s\n' "$*"; }
bad() { printf 'FAIL %s\n' "$*"; fail=1; }
t="$(mktemp -d)"; trap 'rm -rf "$t"' EXIT

# 1. shellcheck
mapfile -t scripts < <(cd "$K" && ls ./*.sh remote/*.sh tests/*.sh)
if (cd "$K" && shellcheck -x "${scripts[@]}"); then ok "shellcheck (${#scripts[@]} scripts)"; else bad shellcheck; fi
for s in "${scripts[@]}"; do bash -n "$K/$s" || bad "bash -n $s"; done

# 2. caddy validate (self-signed stand-in for the origin certificate, test snippets)
mkdir -p "$t/caddy/tls" "$t/rel/web" "$t/rel/admin" "$t/iso/file1" "$t/iso/file2"
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 2 -subj /CN=outbreakweb.com \
    -keyout "$t/caddy/tls/origin.key" -out "$t/caddy/tls/origin.pem" 2>/dev/null
echo 'trusted_proxies static 173.245.48.0/20 2400:cb00::/32' > "$t/caddy/reo-cloudflare.caddy"
echo "owner $(caddy hash-password --plaintext test-password)" > "$t/caddy/reo-admin-users.caddy"
touch "$t/iso/file1/disc.iso" "$t/iso/file2/disc.iso"
mkdir -p "$t/log"
if REO_CADDY_DIR="$t/caddy" REO_WEB="$t/rel" REO_ISO="$t/iso" REO_CADDY_LOG="$t/log" caddy validate --config "$K/files/Caddyfile" --adapter caddyfile >"$t/cv.log" 2>&1; then
    ok "caddy validate"
else bad "caddy validate"; tail -5 "$t/cv.log"; fi
if caddy fmt "$K/files/Caddyfile" | diff -q - "$K/files/Caddyfile" >/dev/null; then ok "caddy fmt"; else bad "caddy fmt (run: caddy fmt --overwrite files/Caddyfile)"; fi

# 3. gateway config
if [ -x "$GW" ]; then
    if "$GW" -config "$K/files/gateway.json" -print-config > "$t/gw.json" 2>"$t/gw.err"; then
        ok "gateway accepts files/gateway.json"
        grep -q '"pcap_dir": ""' "$t/gw.json" || grep -q '"pcap_dir":""' "$t/gw.json" || bad "gateway pcap_dir not empty"
    else bad "gateway config: $(cat "$t/gw.err")"; fi
else echo "skip gateway config (no $GW)"; fi

# 4. server properties: production switches
p="$K/files/server.properties"
for kv in api.dev_login=false packetlog.mode=off bind=127.0.0.1 api.bind=127.0.0.1 regweb.bind=127.0.0.1 \
          us.kk.password= sniff.ports= regweb.dev_user= api.cors_origin=https://outbreakweb.com \
          metrics.tz=UTC api.real_ip_header=X-Real-IP; do
    grep -qx "$kv" "$p" || bad "server.properties lacks $kv"
done
ok "server.properties production switches"

# 5. systemd units (ExecStart paths replaced by /bin/true: the real ones exist only on the VPS)
mkdir -p "$t/units"
for u in "$K"/files/*.service "$K"/files/*.timer; do
    sed -E 's#^ExecStart=.*#ExecStart=/bin/true#; s#^(EnvironmentFile)=-?#\1=-#' "$u" > "$t/units/$(basename "$u")"
done
if (cd "$t/units" && SYSTEMD_LOG_LEVEL=warning systemd-analyze verify --man=no ./*.service ./*.timer) >"$t/sv.log" 2>&1 \
   && ! grep -Ev 'mariadb.service|Failed to prepare filename' "$t/sv.log" | grep -q .; then
    ok "systemd-analyze verify"
else bad "systemd-analyze verify"; cat "$t/sv.log"; fi
for u in reo-server reo-gateway; do
    for k in User=reo Restart=always NoNewPrivileges=yes ProtectSystem=strict ReadWritePaths=/opt/reo/data MemoryMax=; do
        grep -q "^$k" "$K/files/$u.service" || bad "$u.service lacks $k"
    done
done
ok "units hardening keys"

# 6. reo-alert and fetch-backup (fake webhook, forced failures)
if "$K/tests/alert-test.sh" > "$t/alert.log" 2>&1; then ok "reo-alert + fetch-backup (tests/alert-test.sh)"; else bad "tests/alert-test.sh"; grep -v "^ok" "$t/alert.log"; fi

[ $fail = 0 ] && echo "ALL OK" || echo "SOME CHECKS FAILED"
exit $fail
