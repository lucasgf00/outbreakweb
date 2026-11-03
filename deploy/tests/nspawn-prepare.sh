#!/usr/bin/env bash
# Prepares a local Ubuntu 24.04 container (systemd-nspawn) that plays the VPS, for a rehearsal of
# bootstrap + deploy. Run as root in WSL. Nothing here touches the real VPS.
#   1. debootstrap --include=systemd,systemd-sysv,dbus,ca-certificates,curl,openssh-server,sudo,gnupg,iproute2 \
#        noble /var/lib/machines/reo-noble http://archive.ubuntu.com/ubuntu
#   2. tests/nspawn-prepare.sh /var/lib/machines/reo-noble /mnt/c/Users/<you>/.ssh/id_ed25519.pub
#   3. systemd-nspawn -b -D /var/lib/machines/reo-noble --machine=reo-noble   (shares the host network)
#   4. REO_HOST=127.0.0.1 REO_PORT=2222 REO_SECRETS=<dir with a self-signed origin.pem/key> ./deploy.sh bootstrap
#      REO_HOST=127.0.0.1 REO_PORT=2222 ./deploy.sh --no-build
# The container shares the host's network (ports 80/443/2222/3306/8088/8765/9090 must be free in WSL);
# bootstrap detects the container and skips ufw and swap.
set -euo pipefail
root="${1:?rootfs}"; pub="${2:?public key file}"
[ "$(id -u)" = 0 ] || { echo "run as root"; exit 1; }
cat > "$root/etc/apt/sources.list" <<'EOF'
deb http://archive.ubuntu.com/ubuntu noble main universe
deb http://archive.ubuntu.com/ubuntu noble-updates main universe
deb http://security.ubuntu.com/ubuntu noble-security main universe
EOF
echo reo-vps > "$root/etc/hostname"
install -d -m 0700 "$root/root/.ssh"
install -m 0600 "$pub" "$root/root/.ssh/authorized_keys"
# sshd of the container on 2222 (WSL forwards localhost:2222 to Windows, where ssh.exe runs)
install -d "$root/etc/ssh/sshd_config.d"
printf 'Port 2222\n' > "$root/etc/ssh/sshd_config.d/01-port.conf"
# no ssh.socket activation on 22 inside the container
chroot "$root" systemctl disable ssh.socket >/dev/null 2>&1 || true
chroot "$root" systemctl enable ssh.service >/dev/null 2>&1 || true
echo "prepared $root"
