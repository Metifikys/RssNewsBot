#!/usr/bin/env bash
# Rebuild the shadow jar from the repo checkout and restart the systemd service.
# Usage (on the server):  deploy/restart.sh
#
# Before anything is deployed, the NEW jar checks the prod config (`--check-config`): load +
# validation, unknown keys, prompt files. On failure the jar is not installed and the service is
# not restarted, so the old version keeps running.
#
# Env overrides:
#   RSSBOT_HOME        install dir that holds RssNewsBot.jar + config.yaml (default /opt/rssbot)
#   RSSBOT_REPO        repo checkout dir (default: the parent of this script)
#   RSSBOT_SKIP_CHECK  set to 1 to deploy without the config check (emergencies only)
#   JAVA               java binary for the check (default: java on PATH)
set -euo pipefail

APP_DIR="${RSSBOT_HOME:-/opt/rssbot}"
REPO_DIR="${RSSBOT_REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
JAR="RssNewsBot-1.0-SNAPSHOT.jar"   # keep in sync with build.gradle version

echo "[restart] building shadow jar in $REPO_DIR ..."
cd "$REPO_DIR"
./gradlew --no-daemon clean shadowJar

if [ "${RSSBOT_SKIP_CHECK:-0}" != "1" ]; then
  echo "[restart] checking $APP_DIR/config.yaml with the new jar ..."
  # Same working directory (prompt paths are relative to it) and secrets as the service.
  if ! (
    cd "$APP_DIR"
    if [ -r rssbot.env ]; then set -a; . ./rssbot.env; set +a; fi
    "${JAVA:-java}" -jar "$REPO_DIR/build/libs/$JAR" --check-config "$APP_DIR/config.yaml"
  ); then
    echo "[restart] config check FAILED: jar not deployed, service not restarted (the old version keeps running)." >&2
    exit 1
  fi
fi

echo "[restart] deploying jar to $APP_DIR/RssNewsBot.jar ..."
install -m 0644 "build/libs/$JAR" "$APP_DIR/RssNewsBot.jar"

echo "[restart] restarting rssbot.service ..."
sudo systemctl restart rssbot.service
sleep 2
sudo systemctl --no-pager status rssbot.service | head -n 15
