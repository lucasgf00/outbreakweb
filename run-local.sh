#!/usr/bin/env bash
# Resident Evil Outbreak File #1 (SLUS-20765) and File #2 (SLUS-20984) online, all on this machine, one command:
#   1. the US "app01" server (TCP 10127): the Java server in ~/reo-web/server (also regweb on 8080);
#      PYAPP=1 uses the Python prototype ~/reo-web/server/tools/usapp.py instead,
#   2. the Ethernet-over-WebSocket gateway (~/reo-web/net/gateway, ws://localhost:8765/eth),
#   3. the web build (scripts/serve.py, http://localhost:8620).
#   The Java server also runs the US lobby (kkClient login + rooms, UDP 9090) and the HTTP API
#   (127.0.0.1:8088); serve.py forwards the page's /api/* to it (the pre-menu registers the
#   player name with POST /api/kk/register before starting; the site chat is /api/chat/poll and /api/chat/send,
#   and the room list on the page reads /api/rooms).
#   Owner's metrics panel: http://localhost:WEB_PORT/admin, with the local admin token printed at start
#   (REO_ADMIN_TOKEN, or a random one kept in LOGS/admin-token). Metrics data: ~/reo-web/server/data/metrics/.
# Ctrl+C stops all three.
#
# Two players on one PC: one normal Chrome window and one incognito window (each has its own
# token), a different player name in each pre-menu. See docs/playing-online.md.
#
# Usage (inside WSL):        ~/reo-web/run-local.sh
# From Windows PowerShell:   wsl -e bash -lc ~/reo-web/run-local.sh
# Then open http://localhost:8620/ in Chrome or Edge (picker; games at /file1/ and /file2/), start the game and pick
# NETWORK PLAY. The page talks to the gateway by itself and the memory card gets
# the "REO gateway (DHCP)" network configuration on first use.
#
# Native instead of the browser (same gateway and server):
#   cd ~/reo-web/ps2-web-recomp && build/native-outbreak1/ps2run games/outbreak1/disc/SLUS_207.65 \
#     --disc games/outbreak1/disc.iso --memcard games/outbreak1/online.ps2 --net ws://127.0.0.1:8765/eth --window
#
# Settings (environment): GW_PORT=8765 WEB_PORT=8620 APP_PORT=10127 GAME=outbreak1 METRICS_TZ=UTC
#   SERVER_CMD="..." runs another server instead (it must listen on APP_PORT). PYAPP=1: usapp.py.
#   Logs and per-session pcaps go to ~/reo-web/net/runs/local/.
# If GW_PORT is not 8765, open http://localhost:WEB_PORT/?gateway=ws://localhost:GW_PORT/eth
set -euo pipefail
HERE="$HOME/reo-web"
GW_PORT="${GW_PORT:-8765}"; WEB_PORT="${WEB_PORT:-8620}"; APP_PORT="${APP_PORT:-10127}"; GAME="${GAME:-outbreak1}"
API_PORT="${API_PORT:-8088}"; KK_PORT="${KK_PORT:-9090}"; KK2_PORT="${KK2_PORT:-9092}"
RECOMP="$HERE/ps2-web-recomp"; GATEWAY="$HERE/net/gateway/bin/reo-gateway"
LOGS="${LOGS:-$HERE/net/runs/local}"; mkdir -p "$LOGS/pcap"

[ -x "$GATEWAY" ] || { echo "no gateway binary: cd ~/reo-web/net/gateway && go build -o bin/reo-gateway ./cmd/reo-gateway"; exit 1; }
[ -f "$RECOMP/build/web-$GAME/ps2web.html" ] || { echo "no web build: cd $RECOMP && scripts/build_web.sh $GAME"; exit 1; }
[ -e "$RECOMP/games/$GAME/disc.iso" ] || { echo "no disc image at $RECOMP/games/$GAME/disc.iso (run scripts/port.sh first)"; exit 1; }
listening() { ss -Hltn "sport = :$1" 2>/dev/null | grep -q .; }
udp_listening() { ss -Hlun "sport = :$1" 2>/dev/null | grep -q .; }
for p in "$GW_PORT" "$WEB_PORT" "$APP_PORT" "$API_PORT"; do
    if listening "$p"; then echo "port $p is already in use; stop what uses it or pick another (GW_PORT/WEB_PORT/APP_PORT/API_PORT)"; exit 1; fi
done
if udp_listening "$KK_PORT"; then echo "UDP port $KK_PORT is already in use (another lobby server?)"; exit 1; fi
# The Java server needs MariaDB (the File #1/#2 JP lobbies use it).
if [ -z "${SERVER_CMD:-}" ] && [ "${PYAPP:-0}" != 1 ] && ! (echo > /dev/tcp/127.0.0.1/3306) 2>/dev/null; then
    echo "starting MariaDB (sudo)..."
    sudo -n service mariadb start >/dev/null 2>&1 || sudo -n systemctl start mariadb 2>/dev/null || {
        echo "MariaDB is not running. From Windows: wsl -u root -e service mariadb start"; exit 1; }
fi

# Gateway limits are generous here: every local window (up to 4 players and their reloads) comes from 127.0.0.1.
# A public deployment uses net/gateway/config.production.example.json (12 sessions per address, see security.md).
# Gateway: only the game's own services are reachable (allowlist), DNS answers
# *.reo.capcom.sf.yav4.com with 198.18.0.10 (app01, File #1 lobby), *.capcom.client.sf.yav4.com with 198.18.0.11
# (File #2 lobby: the second kk instance on KK2_PORT), and only the web page's origin may connect.
cat > "$LOGS/gateway.json" <<EOF
{
 "listen": "127.0.0.1:$GW_PORT",
 "path": "/eth",
 "origin_patterns": ["localhost:$WEB_PORT", "127.0.0.1:$WEB_PORT"],
 "network": {"gateway": "10.0.2.2", "client": "10.0.2.15", "prefix_len": 24, "gateway_mac": "52:55:0a:00:02:02", "lease_secs": 86400, "domain_name": "reo.local"},
 "dns": {"overrides": {"*.reo.capcom.sf.yav4.com": "198.18.0.10", "*.capcom.client.sf.yav4.com": "198.18.0.11", "*.dnas.playstation.org": "198.18.0.20"}, "ttl": 60},
 "allow": [
  {"proto": "tcp", "dst": "198.18.0.10:10127", "upstream": "127.0.0.1:$APP_PORT"},
  {"proto": "tcp", "dst": "198.18.0.10:80", "upstream": "127.0.0.1:8080"},
  {"proto": "tcp", "dst": "198.18.0.10:8300", "upstream": "127.0.0.1:8300"},
  {"proto": "tcp", "dst": "198.18.0.10:8690", "upstream": "127.0.0.1:8690"},
  {"proto": "udp", "dst": "198.18.0.10:9090", "upstream": "127.0.0.1:$KK_PORT"},
  {"proto": "udp", "dst": "198.18.0.11:9090", "upstream": "127.0.0.1:$KK2_PORT"}
 ],
 "limits": {"max_sessions": 64, "max_sessions_per_ip": 32, "connects_per_min_per_ip": 600,
            "ip_frames_per_sec": 100000, "ip_bytes_per_sec": 67108864, "frames_per_sec": 3000, "bytes_per_sec": 2097152, "max_flows": 32,
            "idle_timeout": "15m", "udp_idle": "60s", "dial_timeout": "5s"}
}
EOF

# Admin token for /api/admin and the metrics panel (/admin): REO_ADMIN_TOKEN, else one kept in $LOGS/admin-token.
if [ -z "${REO_ADMIN_TOKEN:-}" ]; then
    [ -s "$LOGS/admin-token" ] || (umask 077; head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$LOGS/admin-token")
    REO_ADMIN_TOKEN="$(cat "$LOGS/admin-token")"
fi
export REO_ADMIN_TOKEN
METRICS_TZ="${METRICS_TZ:-UTC}"

pids=(); stopping=""
cleanup() {
    stopping=1
    trap - INT TERM EXIT
    echo; echo "stopping..."
    for p in "${pids[@]}"; do kill "$p" 2>/dev/null || true; done
    wait 2>/dev/null || true
}
trap cleanup INT TERM EXIT

if [ -n "${SERVER_CMD:-}" ]; then
    bash -c "$SERVER_CMD" > "$LOGS/server.log" 2>&1 & pids+=($!)
elif [ "${PYAPP:-0}" != 1 ] && [ -f "$HERE/server/build/reo-bioserver.jar" ]; then
    # Java server (default): US app01 service on APP_PORT (protocol "us"), regweb stand-in on 8080,
    # File #1/#2 lobbies (8300/8690, 8200/8590) and the HTTP API on 8088. Needs MariaDB running.
    # News page of app01: an empty one (size 0) makes the client ask 0x6402 and fail with D901, so a
    # one-line page is served (one X press on the news screen).
    printf "<HTML><BODY>reo-web local server<BR>Welcome to the Raccoon City network.</BODY></HTML>" > "$LOGS/news.htm"
    (cd "$HERE/server" && JAVA_OPTS="-Dreo.api.names_per_ip_hour=100 -Dreo.us.app.info_file=$LOGS/news.htm -Dreo.us.app.port=$APP_PORT -Dreo.api.port=$API_PORT -Dreo.us.kk.port=$KK_PORT -Dreo.us.kk2.port=$KK2_PORT -Dreo.us.kk2.public_port=9090 -Dreo.metrics.tz=$METRICS_TZ ${JAVA_OPTS:-}" exec scripts/run.sh) > "$LOGS/server.log" 2>&1 & pids+=($!)
else
    python3 "$HERE/server/tools/usapp.py" --port "$APP_PORT" --log "$LOGS/server.log" > "$LOGS/server.out" 2>&1 & pids+=($!)
fi
"$GATEWAY" -config "$LOGS/gateway.json" -pcap "$LOGS/pcap" > "$LOGS/gateway.log" 2>&1 & pids+=($!)
# One web server for every built game: /file1/ (outbreak1), /file2/ (outbreak2), game picker at /.
sites=()
for g in outbreak1:file1 outbreak2:file2; do
    if [ -f "$RECOMP/build/web-${g%%:*}/ps2web.wasm" ] && [ -e "$RECOMP/games/${g%%:*}/disc.iso" ]; then
        sites+=("${g#*:}=build/web-${g%%:*},games/${g%%:*}/disc.iso")
    fi
done
(cd "$RECOMP" && exec python3 scripts/serve.py "${sites[@]}" "$WEB_PORT" --api "http://127.0.0.1:$API_PORT") > "$LOGS/web.log" 2>&1 & pids+=($!)

checks=("server:$APP_PORT" "gateway:$GW_PORT" "web:$WEB_PORT")
[ -n "${SERVER_CMD:-}" ] || [ "${PYAPP:-0}" = 1 ] || checks+=("server:$API_PORT")
for name_port in "${checks[@]}"; do
    for _ in $(seq 50); do listening "${name_port#*:}" && break; sleep 0.2; done
    if listening "${name_port#*:}"; then echo "  ${name_port%%:*} listening on ${name_port#*:}"
    else echo "  ${name_port%%:*} did not start, see $LOGS/${name_port%%:*}.log"; exit 1; fi
done
if [ -z "${SERVER_CMD:-}" ] && [ "${PYAPP:-0}" != 1 ]; then
    for _ in $(seq 25); do udp_listening "$KK_PORT" && break; sleep 0.2; done
    udp_listening "$KK_PORT" && echo "  lobby (kk) listening on UDP $KK_PORT" || echo "  warning: no UDP $KK_PORT (see $LOGS/server.log)"
fi
url="http://localhost:$WEB_PORT/"
[ "$GW_PORT" = 8765 ] || url+="?gateway=ws://localhost:$GW_PORT/eth"
cat <<EOF

Ready. Open $url
Two players: a normal Chrome window and an incognito one, a different player name in each,
File #1 -> Iniciar -> Network (steps: docs/playing-online.md). Rooms: http://localhost:$WEB_PORT/api/rooms
Metrics panel (owner): http://localhost:$WEB_PORT/admin  token: $REO_ADMIN_TOKEN
Logs: $LOGS/{server,gateway,web}.log  (pcaps in $LOGS/pcap)
Ctrl+C to stop.
EOF
wait -n "${pids[@]}" || true
[ -n "$stopping" ] || echo "a process exited; see the logs in $LOGS"
