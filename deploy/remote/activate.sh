#!/usr/bin/env bash
# reo-activate: installs a release on the VPS (run as root; deploy.sh calls it).
#   reo-activate install <package.tar.gz> <id>   unpack to /opt/reo/releases/<id>, switch, health check,
#                                                automatic rollback to the previous release if unhealthy
#   reo-activate rollback                        go back to the release before the current one
#   reo-activate list                            releases (* = current)
#   reo-activate alerts                          (re)install reo-alert + its timer from the current release
# A release carries its own kit (configs, units, scripts), so a rollback also restores the configs.
# Secrets (/etc/reo/admin.env, local.properties, Caddy's basic auth, TLS) are never inside a release.
set -euo pipefail
R=/opt/reo/releases
CUR=/opt/reo/current
KEEP=5
SHARED_ISO=/opt/reo/shared/iso

say()  { printf '\033[1m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[33m!!  %s\033[0m\n' "$*" >&2; }
die()  { printf '\033[31mxx  %s\033[0m\n' "$*" >&2; exit 1; }
[ "$(id -u)" = 0 ] || die "run as root"

current_id() { if [ -L "$CUR" ]; then basename "$(readlink "$CUR")"; fi; }

releases() { find "$R" -mindepth 1 -maxdepth 1 -type d -regextype posix-extended -regex '.*/[0-9]{8}-[0-9]{6}(-[a-z0-9]+)?' -printf '%f\n' | sort; }

previous_id() {   # newest release older than the current one
    local cur; cur="$(current_id)"
    releases | awk -v c="$cur" '$0 < c' | tail -1
}

ensure_shared_iso() {
    install -d -m 0755 "$SHARED_ISO/file1" "$SHARED_ISO/file2"
    local cur=""
    [ -L "$CUR" ] && cur="$(readlink "$CUR")"
    for site in file1 file2; do
        local dst="$SHARED_ISO/$site/disc.iso" src=""
        [ -f "$dst" ] && continue
        if [ -n "$cur" ] && [ -f "$cur/web/$site/disc.iso" ]; then src="$cur/web/$site/disc.iso"; fi
        if [ -n "$src" ]; then
            cp -f "$src" "$dst"
            chmod 0644 "$dst"
            say "seeded shared $site/disc.iso from current release"
        else
            warn "shared $site/disc.iso is missing ($dst)"
        fi
    done
}

# reo-alert (timer every 5 min) and /etc/reo/alerts.env. The env file holds the Discord webhooks: it is created
# empty from the example only when missing and never overwritten. deploy.sh also calls `reo-activate alerts` after
# an install, because the reo-activate that runs an install is the previous release's (older ones lack this step).
install_alerts() {
    local k="$1"
    [ -f "$k/remote/alert.sh" ] || return 0
    install -m 0755 "$k/remote/alert.sh" /usr/local/sbin/reo-alert
    for u in reo-alert.service reo-alert.timer; do install -m 0644 "$k/files/$u" /etc/systemd/system/; done
    if [ ! -e /etc/reo/alerts.env ]; then
        install -o root -g root -m 0600 "$k/files/alerts.env.example" /etc/reo/alerts.env
        say "created /etc/reo/alerts.env (empty webhooks: deploy.sh rotate alerts)"
    fi
    systemctl daemon-reload
    systemctl enable --now reo-alert.timer >/dev/null 2>&1 || warn "reo-alert.timer did not start"
}

apply() {   # apply <id>: configs + units from the release's kit, switch the symlink, restart
    local id="$1" rel="$R/$1" k="$R/$1/kit"
    [ -d "$rel" ] && [ -d "$k" ] || die "no release $id"
    say "activating $id"
    ensure_shared_iso
    install -m 0755 "$k/remote/pieces.sh" /usr/local/sbin/reo-pieces
    /usr/local/sbin/reo-pieces || warn "reo-pieces failed: the game keeps reading the whole ISO"
    install -o root -g reo -m 0640 "$k/files/server.properties" /etc/reo/server.properties
    install -o root -g reo -m 0640 "$k/files/gateway.json" /etc/reo/gateway.json
    install -o root -g reo -m 0640 "$k/files/news.htm" /etc/reo/news.htm
    for u in "$k"/files/*.service "$k"/files/*.timer; do install -m 0644 "$u" /etc/systemd/system/; done
    install -m 0755 "$k/remote/activate.sh" /usr/local/sbin/reo-activate
    install -m 0755 "$k/remote/health.sh"   /usr/local/sbin/reo-health
    install -m 0755 "$k/remote/cf-ips.sh"   /usr/local/sbin/reo-cf-ips
    install -m 0755 "$k/remote/status.sh"   /usr/local/sbin/reo-status
    install -m 0755 "$k/remote/admin.sh"    /usr/local/sbin/reo-admin
    install -m 0755 "$k/backup.sh"          /usr/local/sbin/reo-backup
    install_alerts "$k"
    # Access log (Caddyfile `log`): `caddy validate` opens the file, so create it owned by caddy first
    # (as root it would be created root-owned and the caddy service could not write it).
    install -d -o caddy -g caddy -m 0750 /var/log/caddy
    [ -e /var/log/caddy/access.log ] || install -o caddy -g caddy -m 0640 /dev/null /var/log/caddy/access.log
    # Caddy: validate before replacing the live config
    if ! REO_WEB="$rel" caddy validate --config "$k/files/Caddyfile" --adapter caddyfile >/dev/null 2>&1; then
        REO_WEB="$rel" caddy validate --config "$k/files/Caddyfile" --adapter caddyfile || true
        die "Caddyfile of $id does not validate"
    fi
    install -m 0644 "$k/files/Caddyfile" /etc/caddy/Caddyfile
    systemctl daemon-reload
    # DB schema: CREATE TABLE IF NOT EXISTS only, safe to apply every time
    if [ -f "$rel/server/database/schema.sql" ]; then
        for db in bioserver1 bioserver2; do mariadb "$db" < "$rel/server/database/schema.sql"; done
    fi
    ln -sfn "$rel" "$CUR.new" && mv -Tf "$CUR.new" "$CUR"
    systemctl restart reo-server reo-gateway
    if systemctl is-active --quiet caddy; then systemctl reload caddy; else systemctl restart caddy; fi
}

prune() {
    local cur prev; cur="$(current_id)"; prev="$(previous_id)"
    releases | sort -r | tail -n +$((KEEP + 1)) | while read -r old; do
        if [ "$old" != "$cur" ] && [ "$old" != "$prev" ]; then echo "    removing old release $old"; rm -rf "${R:?}/$old"; fi
    done
    find "$R" -mindepth 1 -maxdepth 1 -type d \( -name 'failed-*' -o -name 'rolledback-*' \) -mtime +14 -exec rm -rf {} +
}

# While an install or a rollback restarts things, reo-alert skips its run (the flag expires after 10 min anyway).
case "${1:-}" in install|rollback) touch /run/reo-activate.busy; trap 'rm -f /run/reo-activate.busy' EXIT ;; esac

case "${1:-}" in
    alerts)
        [ -L "$CUR" ] || die "no current release"
        install_alerts "$CUR/kit"
        say "reo-alert installed: $(systemctl is-active reo-alert.timer) (/etc/reo/alerts.env kept)"
        ;;
    install)
        pkg="${2:?package}"; id="${3:?id}"
        [[ "$id" =~ ^[0-9]{8}-[0-9]{6}(-[a-z0-9]+)?$ ]] || die "bad release id $id"
        [ ! -e "$R/$id" ] || die "release $id already exists"
        prev="$(current_id)"
        rm -rf "$R/.tmp-$id"; mkdir -p "$R/.tmp-$id"
        tar -xzf "$pkg" -C "$R/.tmp-$id" --no-same-owner
        # never accept game discs or private data inside a release
        bad="$(find "$R/.tmp-$id" \( -iname '*.iso' -o -iname '*.ps2' -o -iname '*.pcap' -o -iname '*.log' -o -iname '*.elf' \
            -o -iname '*.bin' -o -name 'games' -o -name 'generated' -o -name 'kk-accounts*' -o -name 'disc*' \) -print | head -5)"
        if [ -n "$bad" ]; then rm -rf "$R/.tmp-$id"; die "package contains forbidden files: $bad"; fi
        chmod -R u=rwX,go=rX "$R/.tmp-$id"
        mv "$R/.tmp-$id" "$R/$id"
        rm -f "$pkg"
        apply "$id"
        say "health check"
        if reo-health --wait 90; then
            say "release $id is live"
            prune
        else
            if [ -n "$prev" ] && [ -d "$R/$prev" ]; then
                say "UNHEALTHY: rolling back to $prev"
                apply "$prev"
                reo-health --wait 90 || true
                mv "$R/$id" "$R/failed-$id" 2>/dev/null || true
            fi
            die "deploy of $id failed (journalctl -u reo-server -u reo-gateway -u caddy -n 100)"
        fi
        ;;
    rollback)
        prev="$(previous_id)"
        cur="$(current_id)"
        [ -n "$prev" ] || die "there is no release before ${cur:-none}"
        apply "$prev"
        reo-health --wait 90 || die "the previous release ($prev) is not healthy either"
        mv "$R/$cur" "$R/rolledback-$cur" 2>/dev/null || true
        say "now on $prev (the release $cur was kept as $R/rolledback-$cur)"
        ;;
    list)
        cur="$(current_id)"
        releases | while read -r r; do
            if [ "$r" = "$cur" ]; then echo "* $r"; else echo "  $r"; fi
        done
        ;;
    *) die "usage: reo-activate install <pkg> <id> | rollback | list | alerts" ;;
esac
