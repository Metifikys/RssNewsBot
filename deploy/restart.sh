#!/usr/bin/env bash
# Rebuild the shadow jar from the repo checkout and restart the systemd service.
# Usage (on the server):  deploy/restart.sh
#
# Env overrides:
#   RSSBOT_HOME  install dir that holds RssNewsBot.jar + config.yaml (default /opt/rssbot)
#   RSSBOT_REPO  repo checkout dir (default: the parent of this script)
set -euo pipefail

APP_DIR="${RSSBOT_HOME:-/opt/rssbot}"
REPO_DIR="${RSSBOT_REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
JAR="RssNewsBot-1.0-SNAPSHOT.jar"   # keep in sync with build.gradle version

echo "[restart] building shadow jar in $REPO_DIR ..."
cd "$REPO_DIR"
./gradlew --no-daemon clean shadowJar

echo "[restart] deploying jar to $APP_DIR/RssNewsBot.jar ..."
install -m 0644 "build/libs/$JAR" "$APP_DIR/RssNewsBot.jar"

echo "[restart] restarting rssbot.service ..."
sudo systemctl restart rssbot.service
sleep 2
sudo systemctl --no-pager status rssbot.service | head -n 15
