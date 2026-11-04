# Backup tooling for the VPS (docs/PRODUCTION_READINESS.md §8): pg_dump 17 (the server's major
# version), GnuPG for encryption, rclone for the offsite copy, curl for the heartbeat.
#
#   docker build -t strategy-backup deploy/backup
FROM postgres:17-alpine

RUN apk add --no-cache gnupg rclone curl

COPY backup.sh offsite.sh restore.sh /scripts/

# The postgres user (uid 70) owns nothing on the host but the backup directory.
USER postgres
ENV HOME=/tmp
# rclone is configured only through RCLONE_CONFIG_* variables, never a config file.
ENV RCLONE_CONFIG=/dev/null
ENTRYPOINT []
