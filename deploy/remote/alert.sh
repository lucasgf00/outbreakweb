#!/usr/bin/env bash
# reo-alert: watches the VPS and posts to a Discord webhook (private staff channel) when something goes from
# OK to FAIL, when it recovers, and a reminder every REO_ALERT_REMIND_HOURS while it stays failing.
# reo-alert.timer runs `reo-alert run` every 5 minutes (as root).
#   reo-alert run            one pass: checks, compares with the last state, posts on changes
#   reo-alert check          checks only, prints them (no post, no state change)
#   reo-alert test           posts a test message to the alert webhook (and the report webhook if set)
#   reo-alert send alert|report   posts stdin as a message to that webhook (for scripts; text is cut at 1900 chars)
#   reo-alert set            reads KEY=VALUE lines on stdin (REO_ALERT_WEBHOOK, REO_REPORT_WEBHOOK, REO_ALERT_*)
#                            and merges them into /etc/reo/alerts.env (0600 root); deploy.sh rotate alerts uses it
# Config: /etc/reo/alerts.env (never in the repo; files/alerts.env.example shows the keys). Without a webhook,
# `run` still keeps the state and prints to the journal (journalctl -u reo-alert).
# Everything below can be overridden from the environment (the local tests point it at fake files).
set -uo pipefail
export LC_ALL=C
ENV_FILE="${REO_ALERT_ENV:-/etc/reo/alerts.env}"
if [ -r "$ENV_FILE" ]; then
    # only KEY=VALUE lines with the keys we know; the file is root's, but never `source` it blindly
    while IFS='=' read -r k v; do
        case "$k" in
            REO_ALERT_WEBHOOK|REO_REPORT_WEBHOOK|REO_ALERT_[A-Z0-9_]*)
                v="${v%\"}"; v="${v#\"}"
                if [ -z "${!k:-}" ]; then printf -v "$k" '%s' "$v"; fi ;;
        esac
    done < <(grep -E '^[A-Z0-9_]+=' "$ENV_FILE")
fi
DOMAIN="${REO_DOMAIN:-outbreakweb.com}"
STATE_DIR="${REO_ALERT_STATE_DIR:-/var/lib/reo-alert}"
HEALTH_CMD="${REO_ALERT_HEALTH_CMD:-/usr/local/sbin/reo-health}"
BACKUP_DIR="${REO_ALERT_BACKUP_DIR:-/opt/reo/backups}"
ACCESS_LOG="${REO_ALERT_ACCESS_LOG:-/var/log/caddy/access.log}"
DISK_PATHS="${REO_ALERT_DISK_PATHS:-/ /opt/reo /var/log}"
DISK_MAX="${REO_ALERT_DISK_MAX:-85}"            # % used
MEM_MIN="${REO_ALERT_MEM_MIN:-10}"              # % of RAM available
HTTP5XX_MAX="${REO_ALERT_5XX_MAX:-30}"          # 5xx answers in the last hour
EXC_MAX="${REO_ALERT_EXC_MAX:-3}"               # server exceptions in the last 15 min (client disconnects excluded)
BACKUP_HOURS="${REO_ALERT_BACKUP_HOURS:-26}"
OFFSITE_DAYS="${REO_ALERT_OFFSITE_DAYS:-3}"     # 0 = do not check the PC's download
TLS_DAYS="${REO_ALERT_TLS_DAYS:-14}"
REMIND_HOURS="${REO_ALERT_REMIND_HOURS:-6}"
RECHECK_S="${REO_ALERT_RECHECK_S:-30}"          # a failure must still be there after this many seconds
JOURNAL_CMD="${REO_ALERT_JOURNAL_CMD:-journalctl}"
TLS_CONNECT="${REO_ALERT_TLS_CONNECT:-127.0.0.1:443}"
BUSY_FLAG="${REO_ALERT_BUSY_FLAG:-/run/reo-activate.busy}"              # reo-activate creates it while it restarts things
HOST="$(hostname -s 2>/dev/null || echo vps)"

# ---------------------------------------------------------------------------------------------
# checks: each prints "FAIL <key>|<message>" or nothing. Keys are stable (used for state); messages are short.
checks() {
    local line key msg
    # 1. the deploy health check (services, /api/health, pages, wss, DB, ports). Its "exceptions since start"
    #    line is replaced by the windowed check below (one exception would otherwise alert until the next restart).
    while IFS= read -r line; do
        case "$line" in "FAIL "*) ;; *) continue ;; esac
        msg="${line#FAIL }"
        case "$msg" in *"logged exceptions"*) continue ;; esac
        key="$(printf '%s' "${msg%%:*}" | awk '{n=0; for (i=1;i<=NF && n<3;i++) if ($i !~ /^[0-9]+$/) {printf "%s%s", (n?"_":""), $i; n++}}')"
        printf 'FAIL health_%s|%s\n' "$key" "${msg:0:140}"
    done < <("$HEALTH_CMD" 2>&1)

    # 2. 5xx in the last hour (Caddy access log + the newest rotated file, it rotates every ~40 min at peak)
    local n
    n="$(python3 - "$ACCESS_LOG" <<'PY' 2>/dev/null
import glob, gzip, json, os, sys, time
since, n = time.time() - 3600, 0
cur = sys.argv[1]
files = [cur] + sorted(glob.glob(os.path.join(os.path.dirname(cur), 'access-*.log.gz')), key=os.path.getmtime)[-1:]
for f in files:
    try:
        if os.path.getmtime(f) < since:
            continue
        fh = gzip.open(f, 'rt', errors='replace') if f.endswith('.gz') else open(f, errors='replace')
    except OSError:
        continue
    with fh:
        for line in fh:
            if '"status":5' not in line:
                continue
            try:
                e = json.loads(line)
            except ValueError:
                continue
            if e.get('ts', 0) >= since and e.get('status', 0) >= 500:
                n += 1
print(n)
PY
)"
    if [ "${n:-0}" -gt "$HTTP5XX_MAX" ]; then echo "FAIL http5xx|$n HTTP 5xx in the last hour (limit $HTTP5XX_MAX)"; fi

    # 3. server exceptions in the last 15 min, without client disconnects (java.net.Socket*Exception, EOF)
    local exc severe
    exc="$("$JOURNAL_CMD" -u reo-server --since -15min --no-pager -o cat 2>/dev/null \
        | grep -E 'Exception|OutOfMemoryError' | grep -Ev 'java\.net\.Socket[A-Za-z]*Exception|EOFException|^\s+at ' || true)"
    n="$(printf '%s' "$exc" | grep -c . || true)"
    severe="$(printf '%s' "$exc" | grep -Ec 'SQLException|Exception in thread|OutOfMemoryError' || true)"
    if [ "$n" -gt "$EXC_MAX" ] || [ "$severe" -gt 0 ]; then
        echo "FAIL exceptions|$n server exceptions in 15 min: $(printf '%s' "$exc" | tail -1 | cut -c1-120)"
    fi

    # 4. disk and memory
    local p use
    for p in $DISK_PATHS; do
        [ -e "$p" ] || continue
        use="$(df --output=pcent "$p" 2>/dev/null | tail -1 | tr -dc 0-9)"
        if [ -n "$use" ] && [ "$use" -gt "$DISK_MAX" ]; then echo "FAIL disk_$p|disk $p at $use% (limit $DISK_MAX%)"; fi
    done
    local avail total
    avail="$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo)"; total="$(awk '/^MemTotal:/ {print $2}' /proc/meminfo)"
    if [ -n "$avail" ] && [ -n "$total" ] && [ $((avail * 100 / total)) -lt "$MEM_MIN" ]; then
        echo "FAIL memory|RAM available $((avail / 1024)) MB of $((total / 1024)) MB (limit $MEM_MIN%)"
    fi

    # 5. backups: one finished in the last BACKUP_HOURS; the PC downloaded one in the last OFFSITE_DAYS
    local newest age
    newest="$(find "$BACKUP_DIR" -maxdepth 1 -name 'reo-*.tar.gz' -printf '%T@\n' 2>/dev/null | sort -n | tail -1)"
    if [ -z "$newest" ]; then echo "FAIL backup|no backup in $BACKUP_DIR"
    else
        age=$(( ($(date +%s) - ${newest%.*}) / 3600 ))
        if [ "$age" -ge "$BACKUP_HOURS" ]; then echo "FAIL backup|newest backup is ${age} h old (limit $BACKUP_HOURS h; journalctl -u reo-backup)"; fi
    fi
    if [ "$OFFSITE_DAYS" -gt 0 ]; then
        # deploy.sh fetch-backup touches this file after it downloaded and verified a backup
        if [ ! -f "$BACKUP_DIR/.offsite" ]; then echo "FAIL offsite|no backup was ever downloaded to the PC (deploy.sh fetch-backup)"
        else
            age=$(( ($(date +%s) - $(stat -c %Y "$BACKUP_DIR/.offsite")) / 86400 ))
            if [ "$age" -ge "$OFFSITE_DAYS" ]; then echo "FAIL offsite|last backup downloaded to the PC ${age} days ago (limit $OFFSITE_DAYS)"; fi
        fi
    fi

    # 6. TLS certificate actually served on 443 (what Cloudflare sees from the origin)
    local pem end
    pem="$(timeout 10 openssl s_client -connect "$TLS_CONNECT" -servername "$DOMAIN" </dev/null 2>/dev/null | openssl x509 2>/dev/null)"
    if [ -z "$pem" ]; then echo "FAIL tls|could not read the TLS certificate on $TLS_CONNECT"
    elif ! printf '%s\n' "$pem" | openssl x509 -noout -checkend $((TLS_DAYS * 86400)) >/dev/null; then
        end="$(printf '%s\n' "$pem" | openssl x509 -noout -enddate | cut -d= -f2)"
        echo "FAIL tls|TLS certificate expires $end (less than $TLS_DAYS days)"
    fi
}

ks() { grep . | sort -t'|' -k1,1 -u; }   # sort on the key field only (join needs exactly that order)

# Runs the checks; anything failing is checked again after RECHECK_S and must fail both times (a restart during a
# deploy or a single slow answer does not page anyone).
current() {
    local a b
    a="$(checks | ks)"
    if [ -n "$a" ] && [ "$RECHECK_S" -gt 0 ]; then
        sleep "$RECHECK_S"
        b="$(checks | ks)"
        # keep the keys failing both times, with the newest message
        join -t '|' <(cut -d'|' -f1 <<<"$a" | ks) <(ks <<<"$b") 2>/dev/null
    else
        printf '%s' "$a"
    fi
}

# ---------------------------------------------------------------------------------------------
post() {   # post <webhook> <text>: Discord JSON, no mentions; the URL goes through curl's stdin config, not argv
    local url="$1" text="${2:0:1900}" body
    [ -n "$url" ] || return 1
    body="$(python3 -c 'import json, sys; print(json.dumps({"content": sys.argv[1], "allowed_mentions": {"parse": []}}))' "$text")"
    printf 'url = "%s"\n' "$url" | curl -fsS --max-time 15 --retry 2 -K - -H 'Content-Type: application/json' --data-binary "$body" -o /dev/null
}

run() {
    local now state="$STATE_DIR/state" cur prev new gone still last_remind=0 msg="" ok=1 k
    now="$(date +%s)"
    if [ -f "$BUSY_FLAG" ] && [ $(( now - $(stat -c %Y "$BUSY_FLAG") )) -lt 600 ]; then echo "deploy in progress, skipped"; return 0; fi
    mkdir -p "$STATE_DIR"; chmod 700 "$STATE_DIR"
    cur="$(current)"
    # state file: line 1 "remind <epoch>", then "<key>|<since epoch>|<message>"
    prev=""
    if [ -f "$state" ]; then last_remind="$(sed -n 's/^remind //p' "$state")"; prev="$(grep -v '^remind ' "$state")"; fi
    local curk; curk="$(cut -d'|' -f1 <<<"$cur" | sed 's/^FAIL //' | ks)"
    new="$(join -t '|' -v1 <(cut -d'|' -f1,2 <<<"$cur" | sed 's/^FAIL //' | ks) <(cut -d'|' -f1 <<<"$prev" | ks))"
    gone="$(join -t '|' -v1 <(ks <<<"$prev") <(printf '%s\n' "$curk"))"
    still="$(join -t '|' <(ks <<<"$prev") <(printf '%s\n' "$curk"))"

    if [ -n "$new" ]; then
        msg+="**[$HOST] ALERT / ALERTA** (check failed / falló un chequeo)"$'\n'
        while IFS='|' read -r k m; do msg+="- $m"$'\n'; done <<<"$new"
    fi
    if [ -n "$gone" ]; then
        msg+="**[$HOST] RECOVERED / RECUPERADO**"$'\n'
        while IFS='|' read -r k s m; do msg+="- $m ($(( (now - s + 59) / 60 )) min)"$'\n'; done <<<"$gone"
    fi
    local remind=0
    if [ -z "$new" ] && [ -n "$still" ] && [ $(( now - ${last_remind:-0} )) -ge $(( REMIND_HOURS * 3600 )) ]; then
        remind=1
        msg+="**[$HOST] STILL FAILING / SIGUE FALLANDO**"$'\n'
        while IFS='|' read -r k s m; do msg+="- $m (since / desde $(date -u -d "@$s" '+%d/%m %H:%M UTC'))"$'\n'; done <<<"$still"
    fi
    if [ -n "$msg" ]; then
        if [ -n "$still" ] && [ "$remind" = 0 ]; then msg+="(still failing / sigue fallando: $(cut -d'|' -f1 <<<"$still" | tr '\n' ' '))"$'\n'; fi
        msg+="(details / detalle: reo-status)"
        printf '%s\n' "$msg"
        if [ -n "${REO_ALERT_WEBHOOK:-}" ]; then post "$REO_ALERT_WEBHOOK" "$msg" || { ok=0; echo "webhook post failed; will retry next run" >&2; }
        else echo "(no REO_ALERT_WEBHOOK in $ENV_FILE: not posted)"; fi
    fi
    [ "$ok" = 1 ] || return 1   # state unchanged, so the same change is posted next time
    {
        if [ -n "$new" ] || [ "$remind" = 1 ]; then echo "remind $now"; else echo "remind ${last_remind:-0}"; fi
        # keep the original "since" of the ones still failing (with the newest message), add the new ones
        while IFS='|' read -r k s m; do
            [ -n "$k" ] || continue
            m2="$(grep -F "FAIL $k|" <<<"$cur" | head -1 | cut -d'|' -f2-)"; echo "$k|$s|${m2:-$m}"
        done <<<"$still"
        while IFS='|' read -r k m; do if [ -n "$k" ]; then echo "$k|$now|$m"; fi; done <<<"$new"
    } > "$state.tmp"
    mv -f "$state.tmp" "$state"
    [ -n "$cur" ] && echo "failing: $(cut -d'|' -f1 <<<"$cur" | sed 's/^FAIL //' | tr '\n' ' ')" || echo "all checks OK"
}

set_env() {   # merge KEY=VALUE lines from stdin into the env file (0600 root); empty values are ignored
    local tmp k v
    umask 077
    [ -f "$ENV_FILE" ] || : > "$ENV_FILE"
    tmp="$(mktemp "$ENV_FILE.XXXX")"; cp "$ENV_FILE" "$tmp"
    while IFS='=' read -r k v; do
        v="$(printf '%s' "$v" | tr -d '\r\n"')"
        [[ "$k" =~ ^(REO_ALERT_WEBHOOK|REO_REPORT_WEBHOOK|REO_ALERT_[A-Z0-9_]+)$ ]] || { [ -z "$k" ] || echo "ignored key $k" >&2; continue; }
        [ -n "$v" ] || continue
        case "$k" in *WEBHOOK) [[ "$v" =~ ^https://(discord\.com|discordapp\.com|canary\.discord\.com)/api/webhooks/[0-9]+/[A-Za-z0-9_-]+$ ]] || { echo "not a Discord webhook URL for $k, ignored" >&2; continue; } ;; esac
        grep -v "^$k=" "$tmp" > "$tmp.2" || true; mv -f "$tmp.2" "$tmp"
        printf '%s=%s\n' "$k" "$v" >> "$tmp"
        echo "set $k"
    done
    chmod 600 "$tmp"; chown root:root "$tmp" 2>/dev/null || true
    mv -f "$tmp" "$ENV_FILE"
}

case "${1:-run}" in
    run) run ;;
    check) out="$(checks)"; if [ -n "$out" ]; then printf '%s\n' "$out"; exit 1; else echo "all checks OK"; fi ;;
    test)
        rc=0
        if post "${REO_ALERT_WEBHOOK:-}" "[$HOST] test: alerts work / las alertas funcionan"; then echo "alert webhook: posted"
        else echo "alert webhook: FAILED or not set"; rc=1; fi
        if [ -n "${REO_REPORT_WEBHOOK:-}" ]; then
            if post "$REO_REPORT_WEBHOOK" "[$HOST] test: reports channel / canal de reportes"; then echo "report webhook: posted"
            else echo "report webhook: FAILED"; rc=1; fi
        fi
        exit $rc ;;
    send)
        case "${2:-}" in alert) u="${REO_ALERT_WEBHOOK:-}" ;; report) u="${REO_REPORT_WEBHOOK:-}" ;; *) echo "send alert|report"; exit 2 ;; esac
        post "$u" "$(cat)" ;;
    set) set_env ;;
    *) sed -n '2,13p' "$0"; exit 2 ;;
esac
