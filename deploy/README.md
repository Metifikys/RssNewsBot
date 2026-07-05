# Deploying RssNewsBot on a server / Raspberry Pi

These files replace the old `nohup + PID-file` setup with a supervised
`systemd` service, add a backup cron, and provide a one-shot DB compaction
script. Paths default to `/opt/rssbot`; override via the env vars documented
in each script.

## Layout on the host

```
/opt/rssbot/
├── RssNewsBot.jar        # shadow jar (deployed by restart.sh)
├── config.yaml           # not in git — your real config
├── rssbot.env            # secrets, chmod 600 (see rssbot.env.example)
├── logs/                 # rotated logback output
├── news.db               # SQLite DB (on the external USB disk ideally)
└── src/                  # this repo checkout (for rebuilds)
```

## First-time setup

```bash
sudo useradd --system --home /opt/rssbot --shell /usr/sbin/nologin rssbot   # if missing
sudo install -d -o rssbot -g rssbot /opt/rssbot /opt/rssbot/logs

# Secrets
sudo -u rssbot cp deploy/rssbot.env.example /opt/rssbot/rssbot.env
sudo -u rssbot chmod 600 /opt/rssbot/rssbot.env
sudo -u rssbot $EDITOR /opt/rssbot/rssbot.env

# Service
sudo cp deploy/rssbot.service /etc/systemd/system/rssbot.service
sudo systemctl daemon-reload
sudo systemctl enable --now rssbot.service
systemctl status rssbot.service
journalctl -u rssbot -f          # follow logs
```

`Restart=always` + `-XX:+ExitOnOutOfMemoryError` means a crash or OOM (or a Pi
reboot) brings the bot back automatically.

## Rebuild & restart after a code change

```bash
cd /opt/rssbot/src && git pull
deploy/restart.sh                # builds the shadow jar and restarts the service
```

## Backups (there were none before)

```bash
sudo cp deploy/backup-db.sh /opt/rssbot/deploy/backup-db.sh   # or run from the checkout
sudo -u rssbot crontab -e
# add:
0 4 * * *  /opt/rssbot/deploy/backup-db.sh >> /opt/rssbot/logs/backup.log 2>&1
```

Keeps 7 daily gz copies on `RSSBOT_BACKUP_DIR` (put this on the USB disk) and,
if you set `RSSBOT_OFFSITE_SCP` / `RSSBOT_OFFSITE_RCLONE`, mirrors the newest
copy off the Pi.

## One-time DB compaction

The DB grew to 700+ MB because per-cycle pruning of `llm_calls` and embeddings
was missing (now fixed in `DigestCycle`). To reclaim the space once:

```bash
sudo systemctl stop rssbot
sudo -u rssbot deploy/vacuum-db.sh
sudo systemctl start rssbot
```

## Logging

Full LLM prompts/responses now log at `DEBUG` (INFO keeps model + lengths), and
logback rotates `logs/rssbot.log` (50 MB × 30, 2 GB cap). systemd sends
stdout/stderr to journald (bounded), so there is no unbounded `rssbot.out`
anymore. Raise a package to DEBUG temporarily by editing `logback.xml`.
