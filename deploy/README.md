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

`restart.sh` first runs the new jar's config check against `/opt/rssbot/config.yaml`; if the
prod config (or a prompt file it points at) would not start with the new code, nothing is
deployed and the old version keeps running. `RSSBOT_SKIP_CHECK=1` bypasses it.

The unit runs the JVM with `-Djava.awt.headless=true` (the weekly barometer draws its chart with
java.awt; `Main` sets it too). After updating `deploy/rssbot.service`, copy it to
`/etc/systemd/system/` and `sudo systemctl daemon-reload`. A JRE without the AWT native bits
(`libfreetype6` / `fontconfig` on a minimal image) makes the chart fail; the roundup then
carries the text sparkline instead — check the `[Barometer]` log line after the first run.

## Checking the prod config by hand

```bash
cd /opt/rssbot
sudo -u rssbot bash -c 'set -a; . ./rssbot.env; set +a; java -jar RssNewsBot.jar --check-config config.yaml --against src/config.example.yaml'
```

Prints `ERROR` lines (unknown or misspelled keys with the keys accepted there, config
validation, broken prompt files), `WARN` / `INFO` prompt findings, registered options newer than
the file (`new option since … not set: …`) and, with `--against`, the key paths each file has
and the other lacks. Paths only, never values. Exit code 0 = would start, 1 = would not.

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

`logs/rssbot.json.log` carries the same lines as JSON with the run's `runId` /
`category` / `phase`, kept for two days (today + two daily archives, 1 GB cap).
One category run, start to batch callback:

```bash
cd /opt/rssbot
zcat -f logs/rssbot*.json.log* | jq -c 'select(.mdc.runId=="tech-digest-20260923-071856-3fa2") | [(.timestamp/1000|todate),.level,.message]'
```

The runId is in the text log after the thread name (`[digest-tech] [tech-digest-…]`), in the
`[Cycle:tech] … started` line, and in the reply to `/run`.
