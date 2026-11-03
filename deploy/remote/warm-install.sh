#!/bin/bash
set -e
cat > /usr/local/sbin/reo-warm <<'EOF'
#!/bin/bash
# Pide cada pieza del ISO por la URL publica (de a una) para mantenerlas en la cache de Cloudflare.
exec 9>/run/reo-warm.lock
flock -n 9 || exit 0
DOM=${REO_DOMAIN:-outbreakweb.com}
bad=0; hit=0; miss=0
for f in file1:70 file2:69; do
  g=${f%%:*}; n=${f##*:}
  for i in $(seq 0 "$n"); do
    p=$(printf '%03d' "$i")
    st=$(curl -sS -o /dev/null -D /tmp/reo-warm.h -w '%{http_code}' --max-time 300 --limit-rate 25M "https://$DOM/$g/disc/$p.bin" || echo 000)
    if [ "$st" != 200 ]; then bad=$((bad+1)); echo "$(date -Is) $g/$p http $st"; continue; fi
    if grep -qi '^cf-cache-status: HIT' /tmp/reo-warm.h; then hit=$((hit+1)); else miss=$((miss+1)); fi
    sleep 1
  done
done
echo "$(date -Is) warm done: hit=$hit miss=$miss bad=$bad"
EOF
chmod 755 /usr/local/sbin/reo-warm
cat > /etc/systemd/system/reo-warm.service <<'EOF'
[Unit]
Description=Mantener calientes las piezas del ISO en Cloudflare
[Service]
Type=oneshot
Nice=15
IOSchedulingClass=idle
ExecStart=/usr/local/sbin/reo-warm
EOF
cat > /etc/systemd/system/reo-warm.timer <<'EOF'
[Unit]
Description=Recalentar piezas del ISO cada 3 horas
[Timer]
OnBootSec=10min
OnUnitActiveSec=3h
RandomizedDelaySec=10min
[Install]
WantedBy=timers.target
EOF
systemctl daemon-reload
systemctl enable --now reo-warm.timer
systemctl list-timers reo-warm.timer --no-pager | head -3
