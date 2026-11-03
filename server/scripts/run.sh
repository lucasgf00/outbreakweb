#!/usr/bin/env bash
# Run the reo-web bioserver (foreground).
#
#   scripts/run.sh                    both games, config/server.properties
#   scripts/run.sh --games file1      only File #1
#   PACKETLOG=hex scripts/run.sh      enable packet log (logs/packets.log)
#   extra JVM props: JAVA_OPTS="-Dreo.public_ip=192.168.1.10" scripts/run.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JDBC_JAR="${JDBC_JAR:-/usr/share/java/mariadb-java-client.jar}"
JAR="$ROOT/build/reo-bioserver.jar"
cd "$ROOT"

[ -f "$JAR" ] || "$ROOT/scripts/setup.sh" build
if ! mariadb-admin ping >/dev/null 2>&1 && ! (echo > /dev/tcp/127.0.0.1/3306) 2>/dev/null; then
    echo "MariaDB is not running. Start it with: sudo systemctl start mariadb  (or: wsl -u root -e systemctl start mariadb)"
    exit 1
fi
[ -f "$ROOT/config/local.properties" ] || { echo "config/local.properties missing: run scripts/setup.sh db"; exit 1; }

opts=(${JAVA_OPTS:-})
[ -n "${PACKETLOG:-}" ] && opts+=("-Dreo.packetlog.mode=$PACKETLOG")
mkdir -p "$ROOT/logs"
exec java "${opts[@]}" -cp "$JAR:$JDBC_JAR" reo.Main "$ROOT/config/server.properties" "$@"
