#!/usr/bin/env bash
# deploy.sh: runs on the user's PC, inside WSL (where the builds live). SSH goes through Windows'
# OpenSSH (ssh.exe) so the private key stays in C:\Users\<you>\.ssh. See README.md.
#
#   deploy.sh bootstrap            first time: copy the kit + the Cloudflare origin certificate, run bootstrap.sh
#   deploy.sh                      build -> package -> upload -> /opt/reo/releases/<id> -> current -> restart
#                                  -> health check -> automatic rollback if unhealthy
#   deploy.sh --rollback           back to the previous release
#   deploy.sh releases             list the releases on the VPS
#   deploy.sh package              build + package only (nothing is sent), prints the package path
#   deploy.sh fetch-backup [dir]   download the newest backup (default C:\reo-secrets\backups), keep the newest
#                                  REO_BACKUP_KEEP (30) there, mark the download on the VPS (reo-alert checks it)
#   deploy.sh rotate admin-token|basic-auth|db
#   deploy.sh rotate alerts        asks for the Discord webhooks (stdin) -> /etc/reo/alerts.env, posts a test
# Options for deploy/package:
#   --no-build          package the existing builds
#   --with-game-code    ALSO ship ps2web.wasm/.data (translated game code). Only with the user's
#                       explicit approval (project rule). Without it the site goes up
#                       without the game binaries: the pages, chat, rooms and /admin work, the game does not start.
# Never packaged: ISOs, disc images, games/, generated/, server data, pcaps, logs, memory cards.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
. "$HERE/lib.sh"

RECOMP="$REO_SRC/ps2-web-recomp"
SERVER="$REO_SRC/server"
GATEWAY="$REO_SRC/net/gateway"
OUT="${REO_OUT:-$REO_SRC/deploy-out}"

cmd=deploy; build=1; game_code=0
args=()
for a in "$@"; do
    case "$a" in
        --rollback) cmd=rollback ;;
        --no-build) build=0 ;;
        --with-game-code) game_code=1 ;;
        -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
        *) args+=("$a") ;;
    esac
done
if [ ${#args[@]} -gt 0 ]; then cmd="${args[0]}"; fi

# ---------------------------------------------------------------------------------------------
do_build() {
    say "build: web (outbreak1, outbreak2)"
    (cd "$RECOMP" && scripts/build_web.sh outbreak1 >/dev/null && scripts/build_web.sh outbreak2 >/dev/null)
    say "build: server jar"
    "$SERVER/scripts/setup.sh" build >/dev/null
    say "build: gateway (static linux/amd64)"
    (cd "$GATEWAY" && go test ./... >/dev/null && CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags='-s -w' -o bin/reo-gateway-linux-amd64 ./cmd/reo-gateway)
}

stamp() {
    git -C "$1" rev-parse --short HEAD 2>/dev/null | tr -d '\n'
    if [ -n "$(git -C "$1" status --porcelain --untracked-files=no 2>/dev/null)" ]; then printf '+dirty'; fi
}

do_package() {
    local id="$1" st="$OUT/stage-$1" g site serial title ready games_json="["
    rm -rf "$st"; mkdir -p "$st/web" "$st/admin" "$st/server/database" "$st/gateway" "$st/kit"
    # front page, admin panel
    install -m 0644 "$RECOMP/web/index.html" "$st/web/index.html"
    install -m 0644 "$RECOMP/web/admin.html" "$st/admin/index.html"
    # games: the page (shell) and its loader; the game code only with --with-game-code
    for g in outbreak1:file1 outbreak2:file2; do
        local b="$RECOMP/build/web-${g%%:*}"; site="${g#*:}"
        [ -f "$b/ps2web.html" ] && [ -f "$b/engine.json" ] && [ -f "$b/boot.txt" ] || die "missing web build $b (run without --no-build)"
        mkdir -p "$st/web/$site"
        install -m 0644 "$b/ps2web.html" "$st/web/$site/index.html"
        install -m 0644 "$b/boot.txt" "$st/web/$site/boot.txt"
        # Engine files carry a content hash in the name (ps2web-recomp/scripts/version_web.py writes them and
        # engine.json); Caddy serves them as immutable, the page and engine.json are revalidated.
        local js wasm ljs lcss
        read -r js wasm ljs lcss < <(python3 -c 'import json, sys; d = json.load(open(sys.argv[1])); print(d["js"], d["wasm"], d["loader_js"], d["loader_css"])' "$b/engine.json")
        for f in "$js" "$ljs" "$lcss"; do install -m 0644 "$b/$f" "$b/$f.gz" "$st/web/$site/"; done
        install -m 0644 "$b/engine.json" "$st/web/$site/"
        ready=false
        if [ "$game_code" = 1 ]; then
            install -m 0644 "$b/$wasm" "$b/$wasm.gz" "$b/ps2web.data" "$b/ps2web.data.gz" "$st/web/$site/"
            ready=true
        fi
        serial="$(sed -nE 's/.*S([A-Z]{3})_([0-9]{3})\.([0-9]{2}).*/S\1-\2\3/p' "$b/boot.txt")"
        title="File #1"; [ "$site" = file2 ] && title="File #2"
        [ "$games_json" = "[" ] || games_json+=","
        games_json+="{\"path\":\"$site\",\"serial\":\"$serial\",\"title\":\"$title\",\"ready\":$ready}"
    done
    printf '%s]\n' "$games_json" > "$st/web/games.json"
    # server (AGPL-3: LICENSE travels with the jar) and gateway
    install -m 0644 "$SERVER/build/reo-bioserver.jar" "$st/server/"
    install -m 0644 "$SERVER/database/schema.sql" "$st/server/database/"
    install -m 0644 "$SERVER/LICENSE.txt" "$st/server/"
    install -m 0755 "$GATEWAY/bin/reo-gateway-linux-amd64" "$st/gateway/reo-gateway"
    # the kit itself (configs, units, remote scripts) from this repo's working tree
    (cd "$HERE" && tar -cf - bootstrap.sh backup.sh lib.sh README.md remote files) | tar -xf - -C "$st/kit"
    {
        echo "release=$id"
        echo "built=$(date -u '+%F %T UTC')"
        echo "ps2-web-recomp=$(stamp "$RECOMP")"
        echo "server=$(stamp "$SERVER")"
        echo "gateway=$(stamp "$GATEWAY")"
        echo "deploy=$(stamp "$HERE")"
        echo "game_code=$game_code"
    } > "$st/VERSION"

    # Safety net: nothing derived from the disc or private may be in the package.
    local bad
    bad="$(find "$st" \( -iname '*.iso' -o -iname '*.ps2' -o -iname '*.pcap' -o -iname '*.log' -o -iname '*.elf' -o -iname '*.bin' \
        -o -name games -o -name generated -o -name 'kk-accounts*' -o -name 'disc*' -o -name '*.pem' -o -name '*.key' \) -print)"
    [ -z "$bad" ] || die "forbidden files in the package: $bad"
    if [ "$game_code" = 0 ] && find "$st" \( -name '*.wasm*' -o -name '*.data*' \) | grep -q .; then
        die "game code in a package built without --with-game-code"
    fi
    PKG="$OUT/reo-$id.tar.gz"
    tar -czf "$PKG" -C "$st" --owner=0 --group=0 .
    rm -rf "$st"
    say "package $PKG ($(du -h "$PKG" | cut -f1), game code: $([ "$game_code" = 1 ] && echo YES || echo no))"
}

upload() {   # upload <file> <remote path>: through ssh stdin (no scp path translation), checked with sha256
    local sum rsum
    sum="$(sha256sum "$1" | cut -d' ' -f1)"
    rsh "mkdir -p /root/reo-upload && cat > $2" < "$1"
    rsum="$(rsh "sha256sum $2" | cut -d' ' -f1)"
    [ "$sum" = "$rsum" ] || die "upload corrupted ($sum vs $rsum)"
}

# Optional: purge Cloudflare's copy of the pages after a deploy (the hashed engine files never need it). Only
# with C:\reo-secrets\cloudflare.env on this PC (CF_API_TOKEN with Zone > Cache Purge, CF_ZONE_ID). Purges the
# listed URLs only, never everything (the disc image is not ours to touch).
cf_purge() {
    local env="$REO_SECRETS/cloudflare.env" d="https://$REO_DOMAIN" CF_API_TOKEN="" CF_ZONE_ID=""
    [ -f "$env" ] || return 0
    # shellcheck disable=SC1090
    . "$env"
    [ -n "$CF_API_TOKEN" ] && [ -n "$CF_ZONE_ID" ] || { say "cloudflare purge skipped ($env lacks CF_API_TOKEN / CF_ZONE_ID)"; return 0; }
    local files="[\"$d/\",\"$d/index.html\",\"$d/games.json\",\"$d/file1/\",\"$d/file2/\",\"$d/file1/engine.json\",\"$d/file2/engine.json\"]"
    # the token goes through curl's config on stdin, not the command line
    if printf 'header = "Authorization: Bearer %s"\n' "$CF_API_TOKEN" | curl -fsS -K - -X POST -H 'Content-Type: application/json' \
        --data "{\"files\":$files}" "https://api.cloudflare.com/client/v4/zones/$CF_ZONE_ID/purge_cache" >/dev/null; then
        say "cloudflare: pages purged"
    else
        say "cloudflare purge failed (the deploy itself is fine; purge the pages by hand or wait for revalidation)"
    fi
}

new_id() { printf '%s-%s' "$(date -u +%Y%m%d-%H%M%S)" "$(git -C "$RECOMP" rev-parse --short=7 HEAD 2>/dev/null || echo x)"; }

case "$cmd" in
    bootstrap)
        [ -s "$REO_SECRETS/origin.pem" ] && [ -s "$REO_SECRETS/origin.key" ] || die "missing $REO_SECRETS/origin.pem / origin.key"
        say "connecting to $REO_USER@$REO_HOST"
        rsh true || die "ssh to $REO_HOST failed (key in C:\\Users\\<you>\\.ssh?)"
        mkdir -p "$OUT"; tmpk="$(mktemp -d)"
        (cd "$HERE" && tar -cf - bootstrap.sh backup.sh lib.sh README.md remote files) | tar -xf - -C "$tmpk"
        mkdir -p "$tmpk/tls"
        # the origin key is copied without ever being printed; it lands 0600 in /root/reo-kit/tls
        (umask 077; cp "$REO_SECRETS/origin.pem" "$REO_SECRETS/origin.key" "$tmpk/tls/")
        (umask 077; tar -czf "$OUT/kit.tar.gz" -C "$tmpk" .)
        rm -rf "$tmpk"
        upload "$OUT/kit.tar.gz" /root/reo-upload/kit.tar.gz
        rm -f "$OUT/kit.tar.gz"
        rsh "rm -rf /root/reo-kit && mkdir -m 700 /root/reo-kit && tar -xzf /root/reo-upload/kit.tar.gz -C /root/reo-kit --no-same-owner && rm -f /root/reo-upload/kit.tar.gz"
        rsh "${REO_BOOTSTRAP_ENV:-} bash /root/reo-kit/bootstrap.sh"
        rsh "rm -rf /root/reo-kit/tls"
        say "bootstrap finished. Next: $0"
        ;;
    package)
        mkdir -p "$OUT"
        if [ "$build" = 1 ]; then do_build; fi
        do_package "$(new_id)"
        echo "$PKG"
        ;;
    deploy)
        mkdir -p "$OUT"
        rsh "test -x /usr/local/sbin/reo-activate" || die "the VPS is not bootstrapped: $0 bootstrap"
        arch="$(rsh uname -m | tr -d '\r')"
        [ "$arch" = x86_64 ] || die "VPS architecture is $arch; the gateway is built for x86_64"
        if [ "$build" = 1 ]; then do_build; fi
        id="$(new_id)"
        do_package "$id"
        say "upload"
        upload "$PKG" "/root/reo-upload/reo-$id.tar.gz"
        say "install on the VPS"
        rsh "reo-activate install /root/reo-upload/reo-$id.tar.gz $id"
        rm -f "$PKG"
        # alerts: the reo-activate that ran the install may predate them; the one just installed knows them
        rsh "reo-activate alerts" || warn "reo-alert not installed (run: ssh root@$REO_HOST reo-activate alerts)"
        cf_purge
        say "deployed $id: https://$REO_DOMAIN/"
        ;;
    rollback)
        rsh "reo-activate rollback"
        ;;
    releases)
        rsh "reo-activate list"
        ;;
    fetch-backup)
        # Also run daily by the Windows scheduled task (windows/fetch-backup-task.ps1). stdin is /dev/null on every
        # ssh call: from a scheduled task or a console, ssh.exe fails with an inherited console handle (lib.sh).
        dest="${args[1]:-$REO_SECRETS/backups}"; keep="${REO_BACKUP_KEEP:-30}"
        [[ "$keep" =~ ^[0-9]+$ ]] && [ "$keep" -ge 1 ] || die "REO_BACKUP_KEEP must be a number >= 1"
        mkdir -p "$dest"
        f="$(rsh "ls -1t /opt/reo/backups/reo-*.tar.gz 2>/dev/null | head -1" < /dev/null | tr -d '\r')"
        [[ "$f" =~ ^/opt/reo/backups/reo-[0-9]{8}-[0-9]{6}\.tar\.gz$ ]] || die "no backups on the VPS yet (reo-backup runs daily; now: ssh root@$REO_HOST reo-backup)"
        out="$dest/$(basename "$f")"
        sum="$(rsh "sha256sum $f" < /dev/null | cut -d' ' -f1)"
        if [ -f "$out" ] && [ "$(sha256sum "$out" | cut -d' ' -f1)" = "$sum" ]; then
            say "already here: $out"
        else
            rsh "cat $f" < /dev/null > "$out.part"
            [ "$(sha256sum "$out.part" | cut -d' ' -f1)" = "$sum" ] || { rm -f "$out.part"; die "download corrupted"; }
            mv -f "$out.part" "$out"
            say "saved $out ($(du -h "$out" | cut -f1))"
        fi
        chmod 600 "$out"
        # tell the VPS: reo-alert warns when no backup was downloaded for REO_ALERT_OFFSITE_DAYS
        rsh "touch /opt/reo/backups/.offsite" < /dev/null || warn "could not mark the download on the VPS"
        # rotation: only the files this command names (reo-<date>.tar.gz), newest first by name
        find "$dest" -maxdepth 1 -name 'reo-*.tar.gz' -printf '%f\n' | sort -r | tail -n +$((keep + 1)) | while read -r old; do
            rm -f "${dest:?}/$old"; say "removed old copy $old"
        done
        ;;
    rotate)
        what="${args[1]:-}"
        if [ "$what" = alerts ]; then
            rsh "test -x /usr/local/sbin/reo-alert" < /dev/null || die "reo-alert is not on the VPS yet (next deploy, or: ssh root@$REO_HOST reo-activate alerts)"
            echo "Discord: channel > Edit channel > Integrations > Webhooks > New webhook > Copy webhook URL."
            echo "Paste each URL and press Enter (Enter alone keeps the current one). Nothing is shown or saved on this PC."
            read -rsp "alerts channel webhook (REO_ALERT_WEBHOOK): " a; echo
            read -rsp "reports channel webhook (REO_REPORT_WEBHOOK): " r; echo
            for u in "$a" "$r"; do
                [ -z "$u" ] || [[ "$u" =~ ^https://(discord\.com|discordapp\.com|canary\.discord\.com)/api/webhooks/[0-9]+/[A-Za-z0-9_-]+$ ]] || die "that is not a Discord webhook URL"
            done
            printf 'REO_ALERT_WEBHOOK=%s\nREO_REPORT_WEBHOOK=%s\n' "$a" "$r" | rsh "reo-alert set"
            rsh "reo-alert test" < /dev/null || warn "test post failed (check the URL; reo-alert test)"
            [ -z "$r" ] || say "REO_REPORT_WEBHOOK reaches reo-server on its next restart (approve: systemctl restart reo-server)"
            exit 0
        fi
        case "$what" in admin-token|basic-auth|db) ;; *) die "rotate admin-token | basic-auth | db" ;; esac
        # this PC's bootstrap.sh, sent on stdin (the rotate steps need no other kit file)
        rsh "bash -s rotate $what" < "$HERE/bootstrap.sh"
        ;;
    *) die "unknown command $cmd (see $0 --help)" ;;
esac
