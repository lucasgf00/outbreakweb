#!/usr/bin/env bash
# Tests of the HTTP API that need neither MariaDB nor a game: starts HttpApi alone on a random port
# in a temp dir (own data/); uses a throwaway token, never a production one.
#   bash scripts/setup.sh build && bash tests/run-tests.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$ROOT/build/reo-bioserver.jar"
[ -f "$JAR" ] || { echo "build first: scripts/setup.sh build"; exit 1; }
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
PORT=$((20000 + RANDOM % 20000))
printf 'api.bind=127.0.0.1\napi.port=%s\nmetrics.enabled=false\n' "$PORT" > "$T/test.properties"
mkdir -p "$T/data" "$T/cls"
javac -cp "$JAR" -d "$T/cls" "$ROOT/tests/AnnounceTest.java" "$ROOT/tests/ChatReportTest.java" "$ROOT/tests/RuleDifficultyTest.java" "$ROOT/tests/TrkRelayTest.java" "$ROOT/tests/SocialTest.java"
cd "$T"
export REO_ADMIN_TOKEN="test-token-0123456789-abcdefgh"
echo "== AnnounceTest"
java -cp "$JAR:$T/cls" AnnounceTest "$T/test.properties"

# Chat rooms by language, automatic moderation, bug reports: own dir, port, metrics on, fast reloads.
C="$T/chat"; mkdir -p "$C/data"
PORT2=$((20000 + RANDOM % 20000)); [ "$PORT2" = "$PORT" ] && PORT2=$((PORT + 1))
printf 'api.bind=127.0.0.1\napi.port=%s\nmetrics.enabled=true\nmetrics.dir=metrics\napi.badwords_reload_s=1\napi.reports_gap_s=1\n' "$PORT2" > "$C/test.properties"
printf 'alice=aaaaaaaaaaaa\nbob=bbbbbbbbbbbb\ncarl=cccccccccccc\n' > "$C/data/kk-accounts.properties"
: > "$C/data/badwords.txt"
cd "$C"
echo "== ChatReportTest"
java -cp "$JAR:$T/cls" ChatReportTest "$C/test.properties"

# Network difficulty: RULE.DAT (File #1) and the area rule items (File #2) keep the RULE choice (protocol.md ??6.8).
echo "== RuleDifficultyTest"
java -cp "$JAR:$T/cls" reo.us.RuleDifficultyTest

# Tracker data between web pages: REOGW-TRK checked and re-built for the other members (protocol.md 6.18).
echo "== TrkRelayTest"
java -cp "$JAR:$T/cls" reo.us.TrkRelayTest

# Social: friends and player reports (notes/social.md); own dir and port, a local stand-in for the Discord webhook.
S="$T/social"; mkdir -p "$S/data"
PORT3=$((20000 + RANDOM % 20000)); while [ "$PORT3" = "$PORT" ] || [ "$PORT3" = "$PORT2" ]; do PORT3=$((PORT3 + 1)); done
printf 'api.bind=127.0.0.1\napi.port=%s\nmetrics.enabled=false\napi.preports_gap_s=1\napi.alerts_env_file=alerts.env\napi.public_url=https://example.test/\n' "$PORT3" > "$S/test.properties"
printf 'alice=aaaaaaaaaaaa\nbob=bbbbbbbbbbbb\ncarl=cccccccccccc\n' > "$S/data/kk-accounts.properties"
: > "$S/data/badwords.txt"
cd "$S"
echo "== SocialTest"
env -u REO_REPORT_WEBHOOK java -cp "$JAR:$T/cls" SocialTest "$S/test.properties"
