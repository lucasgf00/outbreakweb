#!/usr/bin/env bash
# One-time setup of the production VPS (Ubuntu 24.04), run as root ON THE VPS.
# deploy.sh bootstrap copies this kit to /root/reo-kit and runs it; it is safe to run again
# (every step checks what is already there and secrets are never regenerated unless asked).
#
#   bash /root/reo-kit/bootstrap.sh                 full setup
#   bash /root/reo-kit/bootstrap.sh rotate admin-token | basic-auth | db
#
# What it does: user `reo`, SSH with keys only, ufw (22 open with rate limit; 80/443 only from
# Cloudflare, refreshed weekly), unattended-upgrades, fail2ban (sshd), 2 GB swap, UTC + NTP,
# journald limits (500 MB, 14 days), Caddy (official repo), Java 21, MariaDB (DB user with a random
# password), /opt/reo and /etc/reo, secrets (admin token, basic auth for /admin), systemd units and
# timers (Cloudflare ranges, daily backup). It never installs or asks for game discs.
set -euo pipefail
umask 022

KIT="$(cd "$(dirname "${BASH_SOURCE[0]:-.}")" && pwd)"
DOMAIN="${REO_DOMAIN:-outbreakweb.com}"
export DEBIAN_FRONTEND=noninteractive

say()  { printf '\033[1m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[33m!!  %s\033[0m\n' "$*" >&2; }
die()  { printf '\033[31mxx  %s\033[0m\n' "$*" >&2; exit 1; }

[ "$(id -u)" = 0 ] || die "run as root"
IN_CONTAINER=0
if systemd-detect-virt --container --quiet 2>/dev/null; then IN_CONTAINER=1; fi
SHOW=()      # secrets created in this run, printed once at the end

randhex() { head -c "$1" /dev/urandom | od -An -tx1 | tr -d ' \n'; }
randpw()  { head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c "$1"; }

# ---------------------------------------------------------------------------------------------
step_packages() {
    say "packages"
    apt-get update -qq
    apt-get install -y -qq ca-certificates curl gnupg debian-keyring debian-archive-keyring apt-transport-https \
        ufw fail2ban unattended-upgrades mariadb-server openjdk-21-jre-headless libmariadb-java \
        python3 iproute2 tar gzip >/dev/null
    if ! command -v caddy >/dev/null; then
        say "caddy (official repository)"
        curl -1sLf https://dl.cloudsmith.io/public/caddy/stable/gpg.key | gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
        curl -1sLf https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt > /etc/apt/sources.list.d/caddy-stable.list
        chmod 0644 /usr/share/keyrings/caddy-stable-archive-keyring.gpg /etc/apt/sources.list.d/caddy-stable.list
        apt-get update -qq
        apt-get install -y -qq caddy >/dev/null
    fi
    cat > /etc/apt/apt.conf.d/20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
APT::Periodic::AutocleanInterval "7";
EOF
}

step_system() {
    say "time zone UTC, NTP, journald, swap"
    timedatectl set-timezone UTC 2>/dev/null || ln -sf /usr/share/zoneinfo/UTC /etc/localtime
    timedatectl set-ntp true 2>/dev/null || warn "could not enable NTP (container?)"
    install -d -m 0755 /etc/systemd/journald.conf.d
    install -m 0644 "$KIT/files/journald-reo.conf" /etc/systemd/journald.conf.d/reo.conf
    systemctl restart systemd-journald || true
    if [ "$IN_CONTAINER" = 1 ]; then
        warn "container: swap skipped"
    elif ! swapon --show --noheadings | grep -q .; then
        fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile >/dev/null && swapon /swapfile
        grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
        echo 'vm.swappiness=10' > /etc/sysctl.d/90-reo-swap.conf
        sysctl -q -p /etc/sysctl.d/90-reo-swap.conf || true
    fi
}

step_user() {
    say "user reo and directories"
    if ! id reo >/dev/null 2>&1; then
        useradd --system --create-home --home-dir /home/reo --shell /bin/bash reo
    fi
    # Same authorized key as root (no sudo, no password: login with the key only).
    if [ -s /root/.ssh/authorized_keys ]; then
        install -d -o reo -g reo -m 0700 /home/reo/.ssh
        install -o reo -g reo -m 0600 /root/.ssh/authorized_keys /home/reo/.ssh/authorized_keys
    fi
    passwd -l reo >/dev/null 2>&1 || true
    install -d -m 0755 /opt/reo /opt/reo/releases /opt/reo/shared /opt/reo/shared/iso /opt/reo/shared/iso/file1 /opt/reo/shared/iso/file2
    install -d -o reo -g reo -m 0750 /opt/reo/data
    install -d -o reo -g reo -m 0700 /opt/reo/data/server /opt/reo/data/server/logs
    install -d -m 0700 /opt/reo/backups
    install -d -o root -g reo -m 0750 /etc/reo
    for d in web admin server gateway; do
        [ -e "/opt/reo/$d" ] || ln -s "current/$d" "/opt/reo/$d"
    done
    install -d -o root -g caddy -m 0750 /etc/caddy/tls
}

step_ssh() {
    say "ssh: keys only"
    if [ ! -s /root/.ssh/authorized_keys ]; then
        warn "root has no authorized_keys: password login NOT disabled (you would be locked out)"
        return
    fi
    install -m 0644 "$KIT/files/sshd-reo.conf" /etc/ssh/sshd_config.d/00-reo.conf
    if sshd -t; then
        # reload keeps the current sessions open
        systemctl reload ssh 2>/dev/null || systemctl reload sshd 2>/dev/null || warn "could not reload sshd"
    else
        rm -f /etc/ssh/sshd_config.d/00-reo.conf
        die "sshd -t failed with the new settings; they were removed and sshd was not touched"
    fi
}

step_tls() {
    say "TLS (Cloudflare origin certificate)"
    if [ -f "$KIT/tls/origin.pem" ] && [ -f "$KIT/tls/origin.key" ]; then
        install -o root -g caddy -m 0644 "$KIT/tls/origin.pem" /etc/caddy/tls/origin.pem
        install -o root -g caddy -m 0640 "$KIT/tls/origin.key" /etc/caddy/tls/origin.key
        rm -f "$KIT/tls/origin.pem" "$KIT/tls/origin.key"
    fi
    [ -s /etc/caddy/tls/origin.pem ] && [ -s /etc/caddy/tls/origin.key ] || die "missing /etc/caddy/tls/origin.{pem,key} (deploy.sh bootstrap uploads them)"
    openssl x509 -in /etc/caddy/tls/origin.pem -noout -subject -enddate | sed 's/^/    /'
}

step_db() {
    say "MariaDB (127.0.0.1 only)"
    systemctl enable --now mariadb >/dev/null 2>&1 || true
    printf '[mysqld]\nbind-address = 127.0.0.1\nskip-name-resolve\n' > /etc/mysql/mariadb.conf.d/90-reo.cnf
    systemctl restart mariadb
    if ! grep -qs '^db.password=' /etc/reo/local.properties; then
        set_db_password
    fi
}

set_db_password() {
    local pw; pw="$(randpw 32)"
    mariadb <<SQL
CREATE DATABASE IF NOT EXISTS bioserver1 DEFAULT CHARACTER SET latin1 COLLATE latin1_swedish_ci;
CREATE DATABASE IF NOT EXISTS bioserver2 DEFAULT CHARACTER SET latin1 COLLATE latin1_swedish_ci;
-- JDBC connects over TCP to 127.0.0.1 and skip-name-resolve does not map it to 'localhost': both hosts.
CREATE USER IF NOT EXISTS 'bioserver'@'localhost' IDENTIFIED BY '$pw';
ALTER USER 'bioserver'@'localhost' IDENTIFIED BY '$pw';
CREATE USER IF NOT EXISTS 'bioserver'@'127.0.0.1' IDENTIFIED BY '$pw';
ALTER USER 'bioserver'@'127.0.0.1' IDENTIFIED BY '$pw';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP ON bioserver1.* TO 'bioserver'@'localhost', 'bioserver'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP ON bioserver2.* TO 'bioserver'@'localhost', 'bioserver'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL
    # The server reads config/local.properties next to server.properties: /etc/reo/local.properties.
    (umask 077; printf '# generated by bootstrap.sh; DB password of the bioserver user\ndb.password=%s\n' "$pw" > /etc/reo/local.properties.tmp)
    chown reo:reo /etc/reo/local.properties.tmp
    mv -f /etc/reo/local.properties.tmp /etc/reo/local.properties
}

step_secrets() {
    say "secrets"
    if ! grep -qs '^REO_ADMIN_TOKEN=' /etc/reo/admin.env; then set_admin_token; fi
    if [ ! -s /etc/caddy/reo-admin-users.caddy ]; then set_basic_auth; fi
}

set_admin_token() {
    local tok; tok="$(randhex 24)"
    (umask 077; printf 'REO_ADMIN_TOKEN=%s\n' "$tok" > /etc/reo/admin.env)
    SHOW+=("Token de /admin y de admin.sh (REO_ADMIN_TOKEN): $tok")
}

set_basic_auth() {
    local pw hash; pw="$(randpw 24)"
    hash="$(caddy hash-password --plaintext "$pw")"
    (umask 077; printf 'usuario: owner\ncontraseña: %s\n' "$pw" > /etc/reo/admin-basic-auth.txt)
    (umask 027; printf 'owner %s\n' "$hash" > /etc/caddy/reo-admin-users.caddy)
    chgrp caddy /etc/caddy/reo-admin-users.caddy
    SHOW+=("Basic auth de https://$DOMAIN/admin: usuario owner, contraseña $pw")
}

step_kit() {
    say "scripts, units and timers"
    install -m 0755 "$KIT/remote/activate.sh" /usr/local/sbin/reo-activate
    install -m 0755 "$KIT/remote/health.sh"   /usr/local/sbin/reo-health
    install -m 0755 "$KIT/remote/cf-ips.sh"   /usr/local/sbin/reo-cf-ips
    install -m 0755 "$KIT/remote/status.sh"   /usr/local/sbin/reo-status
    install -m 0755 "$KIT/remote/admin.sh"    /usr/local/sbin/reo-admin
    install -m 0755 "$KIT/backup.sh"          /usr/local/sbin/reo-backup
    install -m 0755 "$KIT/remote/alert.sh"    /usr/local/sbin/reo-alert
    [ -e /etc/reo/alerts.env ] || install -o root -g root -m 0600 "$KIT/files/alerts.env.example" /etc/reo/alerts.env
    for u in "$KIT"/files/*.service "$KIT"/files/*.timer; do install -m 0644 "$u" /etc/systemd/system/; done
    install -m 0644 "$KIT/files/fail2ban-reo.local" /etc/fail2ban/jail.d/reo.local
    systemctl daemon-reload
    systemctl enable --now fail2ban >/dev/null 2>&1 || warn "fail2ban did not start"
    systemctl enable reo-server.service reo-gateway.service >/dev/null
    systemctl enable --now reo-backup.timer reo-cf-ips.timer reo-alert.timer >/dev/null
}

step_firewall() {
    say "firewall: 22 (rate limited), 80/443 only from Cloudflare"
    /usr/local/sbin/reo-cf-ips --no-firewall --no-reload    # ranges + Caddy's trusted_proxies snippet
    if [ "$IN_CONTAINER" = 1 ]; then
        warn "container: ufw skipped (it would change the host's firewall)"
        return
    fi
    ufw default deny incoming >/dev/null
    ufw default allow outgoing >/dev/null
    if [ -n "${SSH_ALLOW_FROM:-}" ]; then
        ufw allow proto tcp from "$SSH_ALLOW_FROM" to any port 22 comment ssh >/dev/null
    else
        ufw limit 22/tcp comment ssh >/dev/null
    fi
    /usr/local/sbin/reo-cf-ips --no-reload                  # adds the 80/443 rules (comment reo-cf)
    ufw --force enable >/dev/null
    ufw status verbose | sed -n '1,6p'
}

step_caddy() {
    say "Caddy (placeholder site until the first deploy)"
    # Until deploy.sh installs a release, Caddy runs with the kit's Caddyfile (the web root is empty).
    install -m 0644 "$KIT/files/Caddyfile" /etc/caddy/Caddyfile
    # Access log (Caddyfile `log`): `caddy validate` opens the file, so create it owned by caddy first
    # (as root it would be created root-owned and the caddy service could not write it).
    install -d -o caddy -g caddy -m 0750 /var/log/caddy
    [ -e /var/log/caddy/access.log ] || install -o caddy -g caddy -m 0640 /dev/null /var/log/caddy/access.log
    caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null 2>&1 || caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
    systemctl enable caddy >/dev/null 2>&1
    systemctl restart caddy
}

show_secrets() {
    [ ${#SHOW[@]} -gt 0 ] || return 0
    printf '\n\033[1m================ GUARDALO AHORA (se muestra una sola vez) ================\033[0m\n'
    for s in "${SHOW[@]}"; do printf '  %s\n' "$s"; done
    printf '  (copias en el server, solo root: /etc/reo/admin.env, /etc/reo/admin-basic-auth.txt)\n'
    printf '\033[1m==========================================================================\033[0m\n\n'
}

rotate() {
    case "${1:-}" in
        admin-token) set_admin_token; systemctl restart reo-server ;;
        basic-auth)  set_basic_auth; systemctl reload caddy ;;
        db)          set_db_password; systemctl restart reo-server ;;
        *) die "rotate admin-token | basic-auth | db" ;;
    esac
    show_secrets
    say "rotated $1"
}

if [ "${1:-}" = rotate ]; then rotate "${2:-}"; exit 0; fi

step_packages
step_system
step_user
step_ssh
step_tls
step_db
step_secrets
step_kit
step_firewall
step_caddy
show_secrets
say "bootstrap done. Next: deploy.sh (from the PC)"
