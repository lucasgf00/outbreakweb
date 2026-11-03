#!/usr/bin/env bash
# shellcheck disable=SC2015,SC2329  # ok/bad never fail; cleanup runs from the EXIT trap
# Local test of remote/alert.sh (no VPS): a fake Discord webhook (HTTP server that saves each POST), fake
# health/journal commands, a local TLS server, and forced failures. Also tests `deploy.sh fetch-backup` against a
# fake ssh that runs the commands locally on a temp "VPS" directory.
set -uo pipefail
K="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
A="$K/remote/alert.sh"
t="$(mktemp -d)"
pids=()
cleanup() { for p in "${pids[@]}"; do kill "$p" 2>/dev/null; done; rm -rf "$t"; }
trap cleanup EXIT
fail=0
ok()  { printf 'ok   %s\n' "$*"; }
bad() { printf 'FAIL %s\n' "$*"; fail=1; }
freeport() { python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])'; }

# fake webhook: prints and saves each JSON body; answers 204 like Discord
WP="$(freeport)"
cat > "$t/hook.py" <<'PY'
import http.server, json, sys
out = sys.argv[2]
class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get('Content-Length', 0)))
        d = json.loads(body)
        assert d.get('allowed_mentions') == {'parse': []}
        with open(out, 'a') as f:
            f.write(json.dumps(d['content']) + '\n')
        self.send_response(204); self.end_headers()
    def log_message(self, *a): pass
http.server.HTTPServer(('127.0.0.1', int(sys.argv[1])), H).serve_forever()
PY
start_hook() { python3 "$t/hook.py" "$WP" "$t/posts" & pids+=($!); HOOKPID=$!; sleep 0.5; }
start_hook

# local TLS server with a 30-day certificate
TP="$(freeport)"
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 30 -subj /CN=outbreakweb.com \
    -keyout "$t/k.pem" -out "$t/c.pem" 2>/dev/null
openssl s_server -accept "$TP" -cert "$t/c.pem" -key "$t/k.pem" -quiet </dev/null >/dev/null 2>&1 & pids+=($!)
sleep 0.5

# fakes
mkdir -p "$t/backups" "$t/log" "$t/state"
printf '#!/bin/sh\ncat "%s/health.out"\n' "$t" > "$t/health"; chmod +x "$t/health"
printf '#!/bin/sh\ncat "%s/journal.out"\n' "$t" > "$t/journal"; chmod +x "$t/journal"
touch "$t/backups/reo-20261103-074725.tar.gz" "$t/backups/.offsite"
: > "$t/journal.out"; : > "$t/log/access.log"
health_ok() { printf 'ok   service reo-server\nok   /api/health\nFAIL reo-server logged exceptions since it started (x)\nHEALTHY\n' > "$t/health.out"; }
health_ok
five() {   # five <n>: n 5xx lines in the access log, now
    : > "$t/log/access.log"
    for _ in $(seq "$1"); do printf '{"ts":%s,"status":502,"request":{"uri":"/api/x"}}\n' "$(date +%s)" >> "$t/log/access.log"; done
}
export REO_ALERT_ENV="$t/alerts.env" REO_ALERT_WEBHOOK="http://127.0.0.1:$WP/hook" REO_ALERT_STATE_DIR="$t/state" \
    REO_ALERT_HEALTH_CMD="$t/health" REO_ALERT_JOURNAL_CMD="$t/journal" REO_ALERT_BACKUP_DIR="$t/backups" \
    REO_ALERT_ACCESS_LOG="$t/log/access.log" REO_ALERT_DISK_PATHS="$t" REO_ALERT_TLS_CONNECT="127.0.0.1:$TP" \
    REO_ALERT_RECHECK_S=0 REO_ALERT_REMIND_HOURS=6
posts() { [ -f "$t/posts" ] && wc -l < "$t/posts" || echo 0; }
last() { tail -1 "$t/posts" 2>/dev/null; }
run() { "$A" run > "$t/run.out" 2>&1; }
expect() {   # expect <posts> <grep in last post or -> <label>
    local n; n="$(posts)"
    if [ "$n" != "$1" ]; then bad "$3: $n posts, expected $1"; cat "$t/run.out"; return; fi
    if [ "$2" != - ] && ! last | grep -q "$2"; then bad "$3: last post lacks '$2': $(last)"; return; fi
    ok "$3"
}

run; expect 0 - "all OK: nothing posted (the 'exceptions since start' health line is ignored)"
printf 'FAIL service reo-server\nFAIL wss /eth gave 502\nUNHEALTHY\n' > "$t/health.out"; five 40
run; expect 1 'ALERT / ALERTA' "server down + 40 5xx: one alert"
last | grep -q 'service reo-server' && last | grep -q '40 HTTP 5xx' && last | grep -q 'wss /eth gave 502' && ok "alert lists the 3 failures" || bad "alert content: $(last)"
run; expect 1 - "same failures again: no repeat"
printf 'FAIL service reo-server\nFAIL wss /eth gave 000\n' > "$t/health.out"
run; expect 1 - "another status code for the same check: same key, no repeat"
REO_ALERT_REMIND_HOURS=0 run; expect 2 'STILL FAILING' "reminder after REMIND_HOURS"
run; expect 2 - "no second reminder right away"
health_ok
run; expect 3 'RECOVERED' "server back: recovery post"
last | grep -q 'service reo-server' && last | grep -q 'still failing.*http5xx' && ok "recovery mentions what still fails" || bad "recovery content: $(last)"
five 5
run; expect 4 'RECOVERED.*' "5xx back under the limit: recovery"
grep -q . "$t/state/state" && ! grep -v '^remind' "$t/state/state" | grep -q . && ok "state empty when all OK" || bad "state: $(cat "$t/state/state")"

# webhook down: exit 1, state kept, posted on the next run
kill "$HOOKPID"; sleep 0.3
printf 'FAIL service caddy\n' > "$t/health.out"
if run; then bad "run with the webhook down should fail"; else ok "webhook down: run fails"; fi
grep -q caddy "$t/state/state" && bad "state changed although not posted" || ok "state unchanged when the post failed"
start_hook
run; expect 5 'service caddy' "webhook back: the pending alert is posted"
health_ok; run; expect 6 RECOVERED "caddy recovered"

# flapping: fails on the first pass only, rechecked after 1 s -> nothing
printf '#!/bin/sh\nif [ -f "%s/flap" ]; then cat "%s/health.out"; else touch "%s/flap"; echo "FAIL service reo-gateway"; fi\n' "$t" "$t" "$t" > "$t/health"
REO_ALERT_RECHECK_S=1 run; expect 6 - "a failure gone on the recheck is not posted"
printf '#!/bin/sh\ncat "%s/health.out"\n' "$t" > "$t/health"

# exceptions: client disconnects do not count, an SQLException does
for _ in $(seq 10); do echo 'us app01 /127.0.0.1:1: java.net.SocketException: Connection reset'; done > "$t/journal.out"
echo 'us app01 /127.0.0.1:2: java.net.SocketTimeoutException: Read timed out' >> "$t/journal.out"
run; expect 6 - "10 SocketException + 1 SocketTimeoutException: no alert"
echo 'bioserver2.Database: java.sql.SQLException: Access denied' >> "$t/journal.out"
run; expect 7 'server exceptions' "one SQLException: alert"
: > "$t/journal.out"; run; expect 8 RECOVERED "exceptions recovered"

# backups, offsite, disk, memory, TLS
touch -d '-30 hours' "$t/backups/reo-20261103-074725.tar.gz"; rm -f "$t/backups/.offsite"
REO_ALERT_DISK_MAX=0 REO_ALERT_MEM_MIN=101 REO_ALERT_TLS_DAYS=60 run
expect 9 ALERT "old backup + never downloaded + disk + memory + TLS: one alert"
for w in 'newest backup is 30 h old' 'ever downloaded' "disk $t at" 'RAM available' 'TLS certificate expires'; do
    last | grep -q "$w" || bad "missing in alert: $w"
done
touch "$t/backups/reo-20261103-074725.tar.gz" "$t/backups/.offsite"
run; expect 10 RECOVERED "all five recovered"
touch -d '-4 days' "$t/backups/.offsite"
run; expect 11 'downloaded to the PC 4 days ago' "no download for 4 days: alert"
REO_ALERT_OFFSITE_DAYS=0 run; expect 12 RECOVERED "offsite check off (0)"
kill "${pids[1]}" 2>/dev/null; sleep 0.2
run; expect 13 'could not read the TLS' "TLS server gone: alert"

# deploy in progress: skipped
touch "$t/busy"; printf 'FAIL service reo-server\n' > "$t/health.out"
REO_ALERT_BUSY_FLAG="$t/busy" run; grep -q "deploy in progress" "$t/run.out" && expect 13 - "deploy in progress: skipped" || bad "busy flag"
health_ok; rm -f "$t/busy"
# send / test / set
echo 'player report: test' | "$A" send alert && expect 14 'player report' "send alert posts stdin" || bad "send alert"
REO_REPORT_WEBHOOK="http://127.0.0.1:$WP/r" "$A" test >/dev/null && expect 16 'reports channel' "test posts to both webhooks" || bad "test"
printf 'REO_ALERT_WEBHOOK=https://discord.com/api/webhooks/123/abc_DEF-1\nREO_REPORT_WEBHOOK=http://evil/x\nFOO=1\nREO_ALERT_DISK_MAX=90\n' \
    | "$A" set >/dev/null 2>&1
if grep -qx 'REO_ALERT_WEBHOOK=https://discord.com/api/webhooks/123/abc_DEF-1' "$t/alerts.env" && ! grep -q evil "$t/alerts.env" \
   && ! grep -q FOO "$t/alerts.env" && grep -qx 'REO_ALERT_DISK_MAX=90' "$t/alerts.env" && [ "$(stat -c %a "$t/alerts.env")" = 600 ]; then
    ok "set: merges valid keys, refuses a non-Discord URL and unknown keys, 0600"
else bad "set: $(cat "$t/alerts.env")"; fi
printf 'REO_ALERT_DISK_MAX=95\n' | "$A" set >/dev/null 2>&1
[ "$(grep -c '^REO_ALERT_DISK_MAX=' "$t/alerts.env")" = 1 ] && grep -qx 'REO_ALERT_DISK_MAX=95' "$t/alerts.env" && ok "set: replaces, keeps the webhook" || bad "set replace"
grep -q 'abc_DEF-1' "$t/alerts.env" || bad "set lost the webhook"
echo "--- sample alert (as posted):"; sed -n 9p "$t/posts" | python3 -c 'import json,sys; print(json.loads(sys.stdin.read()))'

# fetch-backup with a fake ssh: runs the remote command locally, /opt/reo/backups -> a temp dir
mkdir -p "$t/vps/backups" "$t/pc"
for d in 20261101-073217 20261102-074725 20261103-074725; do head -c 2000 /dev/urandom > "$t/vps/backups/reo-$d.tar.gz"; done
for d in 20261001-000000 20261002-000000; do echo old > "$t/pc/reo-$d.tar.gz"; done
echo keep > "$t/pc/notes.txt"
cat > "$t/ssh" <<EOF
#!/bin/bash
# fake ssh: last argument is the remote command; the VPS backup dir is a temp dir here
cmd="\${*: -1}"; cmd="\${cmd//\/opt\/reo\/backups/$t/vps/backups}"
case "\$cmd" in
    ls*) bash -c "\$cmd" | sed "s#$t/vps/backups#/opt/reo/backups#g" ;;
    *) exec bash -c "\$cmd" ;;
esac
EOF
chmod +x "$t/ssh"
if REO_SSH="$t/ssh" REO_BACKUP_KEEP=2 "$K/deploy.sh" fetch-backup "$t/pc" > "$t/fb.out" 2>&1 </dev/null \
   && cmp -s "$t/pc/reo-20261103-074725.tar.gz" "$t/vps/backups/reo-20261103-074725.tar.gz" \
   && [ -f "$t/vps/backups/.offsite" ] && [ "$(stat -c %a "$t/pc/reo-20261103-074725.tar.gz")" = 600 ] \
   && [ "$(find "$t/pc" -name 'reo-*' | wc -l)" = 2 ] && [ -f "$t/pc/notes.txt" ] && [ -f "$t/pc/reo-20261002-000000.tar.gz" ]; then
    ok "fetch-backup: newest downloaded and verified, marked on the VPS, rotation keeps 2, other files untouched"
else bad "fetch-backup"; cat "$t/fb.out"; ls -la "$t/pc"; fi
REO_SSH="$t/ssh" "$K/deploy.sh" fetch-backup "$t/pc" 2>&1 </dev/null | grep -q 'already here' && ok "fetch-backup: second run does not download again" || bad "fetch-backup second run"

[ $fail = 0 ] && echo "ALERT TESTS OK" || echo "SOME ALERT TESTS FAILED"
exit $fail
