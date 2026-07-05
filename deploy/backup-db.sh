#!/usr/bin/env bash
# Daily online backup of news.db (SQLite .backup is safe on a live DB — no need to stop the bot).
# Keeps the last $RETAIN local gz copies and optionally mirrors the newest off-box.
#
# Cron (as the rssbot user):
#   0 4 * * *  /opt/rssbot/deploy/backup-db.sh >> /opt/rssbot/logs/backup.log 2>&1
#
# Env overrides:
#   RSSBOT_DB          path to news.db (default /opt/rssbot/news.db)
#   RSSBOT_BACKUP_DIR  local backup dir, ideally on the external USB disk
#   RSSBOT_OFFSITE_SCP    scp target, e.g. user@dev-pc:/backups/rssbot/   (optional)
#   RSSBOT_OFFSITE_RCLONE rclone remote, e.g. gdrive:rssbot-backups        (optional)
set -euo pipefail

DB="${RSSBOT_DB:-/opt/rssbot/news.db}"
BACKUP_DIR="${RSSBOT_BACKUP_DIR:-/mnt/usb/rssbot-backups}"
RETAIN=7
OFFSITE_SCP="${RSSBOT_OFFSITE_SCP:-}"
OFFSITE_RCLONE="${RSSBOT_OFFSITE_RCLONE:-}"

stamp="$(date +%F)"
mkdir -p "$BACKUP_DIR"
dest="$BACKUP_DIR/news-$stamp.db"

echo "[backup] $(date -Is) $DB -> $dest.gz"
sqlite3 "$DB" ".backup '$dest'"
gzip -f "$dest"
dest="$dest.gz"

# Rotate: keep the newest $RETAIN gz backups, delete the rest.
ls -1t "$BACKUP_DIR"/news-*.db.gz 2>/dev/null | tail -n +$((RETAIN + 1)) | xargs -r rm -f

# Off-box mirror (best-effort — a failing mirror must not fail the local backup).
[ -n "$OFFSITE_SCP" ]    && { scp "$dest" "$OFFSITE_SCP" || echo "[backup] scp mirror failed"; }
[ -n "$OFFSITE_RCLONE" ] && { rclone copy "$dest" "$OFFSITE_RCLONE" || echo "[backup] rclone mirror failed"; }

echo "[backup] done. Local copies:"
ls -1t "$BACKUP_DIR"/news-*.db.gz | head -n "$RETAIN"
