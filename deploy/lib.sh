# shellcheck shell=bash
# Shared settings for the scripts that run on the user's PC (deploy.sh, status.sh, admin.sh).
# Everything can be overridden from the environment.
#
# SSH: the private key never leaves Windows. Inside WSL the scripts call Windows' own OpenSSH
# (ssh.exe), which reads C:\Users\<you>\.ssh\id_ed25519 and the Windows known_hosts. In Git Bash
# the plain `ssh` already is a Windows program and uses the same files.

# Site values that stay off the repo (VPS address, owner names): ~/.config/reo/deploy.env, e.g. REO_HOST=203.0.113.7
[ -f "$HOME/.config/reo/deploy.env" ] && . "$HOME/.config/reo/deploy.env"
: "${REO_HOST:?set REO_HOST in ~/.config/reo/deploy.env}"
REO_USER="${REO_USER:-root}"
REO_PORT="${REO_PORT:-22}"
REO_DOMAIN="${REO_DOMAIN:-outbreakweb.com}"
REO_SRC="${REO_SRC:-$HOME/reo-web}"                       # where the repos live (WSL)
REO_SECRETS="${REO_SECRETS:-/mnt/c/reo-secrets}"          # origin.pem / origin.key (Cloudflare origin certificate)

if [ -z "${REO_SSH:-}" ]; then
    if [ -n "${WSL_DISTRO_NAME:-}" ] && [ -x /mnt/c/Windows/System32/OpenSSH/ssh.exe ]; then
        REO_SSH=/mnt/c/Windows/System32/OpenSSH/ssh.exe
    else
        REO_SSH=ssh
    fi
fi
REO_SSH_OPTS=(-p "$REO_PORT" -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o ServerAliveInterval=15 -o ConnectTimeout=15)
if [ -n "${REO_SSH_EXTRA:-}" ]; then
    # shellcheck disable=SC2206  # intentional word splitting of extra options
    REO_SSH_OPTS+=($REO_SSH_EXTRA)
fi

say()  { printf '\033[1m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[33m!!  %s\033[0m\n' "$*" >&2; }
die()  { printf '\033[31mxx  %s\033[0m\n' "$*" >&2; exit 1; }

# Runs a command on the VPS. Arguments are joined by ssh with spaces, so callers pass only
# validated, space-free words (or one quoted string they built themselves).
#
# Called as `wsl -e bash -lc "..."` from a PowerShell console, Windows' ssh.exe failed with
# "ssh.exe: Invalid argument": its stdin was the console handed through WSL interop, and its working
# directory a \\wsl.localhost path. So ssh.exe always starts from a Windows directory (/mnt/c), and when
# stdin is a terminal (nothing is piped in) it reads /dev/null instead. Piped input (deploy.sh upload,
# rotate) is passed through unchanged.
reo_win_cwd() { if [[ "$REO_SSH" == /mnt/* || "$REO_SSH" == *.exe ]] && [ -d /mnt/c ]; then cd /mnt/c || true; fi; }
rsh() {
    if [ -t 0 ]; then (reo_win_cwd; "$REO_SSH" "${REO_SSH_OPTS[@]}" "$REO_USER@$REO_HOST" "$@" < /dev/null)
    else (reo_win_cwd; "$REO_SSH" "${REO_SSH_OPTS[@]}" "$REO_USER@$REO_HOST" "$@"); fi
}

# Same, with a terminal (interactive prompts, colors). From PowerShell this one may still fail (it needs the
# console); there, use ssh directly: ssh root@<host> "journalctl -f -u reo-server" (README.md).
rsh_tty() { (reo_win_cwd; "$REO_SSH" "${REO_SSH_OPTS[@]}" -t "$REO_USER@$REO_HOST" "$@"); }
