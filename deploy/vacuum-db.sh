#!/usr/bin/env bash
# One-shot compaction for the oversized news.db (was 700+ MB before cycle pruning landed).
# Backs up, runs VACUUM INTO a fresh file, then swaps it in.
#
#   STOP THE BOT FIRST so nothing writes mid-swap:
#     sudo systemctl stop rssbot
#     deploy/vacuum-db.sh
#     sudo systemctl start rssbot
#
# Env: RSSBOT_DB path to news.db (default /opt/rssbot/news.db)
set -euo pipefail

DB="${RSSBOT_DB:-/opt/rssbot/news.db}"
ts="$(date +%Y%m%d-%H%M%S)"
backup="$DB.bak-$ts"
tmp="$DB.vacuumed"

echo "[vacuum] backing up $DB -> $backup"
cp -v "$DB" "$backup"

echo "[vacuum] VACUUM INTO $tmp ..."
sqlite3 "$DB" "VACUUM INTO '$tmp';"

echo "[vacuum] swapping compacted file in"
mv -v "$tmp" "$DB"

echo "[vacuum] done. Sizes:"
ls -la "$DB" "$backup"
echo "[vacuum] verify the bot starts cleanly, then remove $backup"
