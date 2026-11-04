#!/bin/sh
# Copies encrypted backups to storage at another provider, prunes local copies and checks that a
# recent copy exists offsite (docs/PRODUCTION_READINESS.md §8). Runs after every backup and hourly,
# so a failed upload is retried within the hour.
#
# Environment:
#   BACKUP_REMOTE            REQUIRED rclone destination, e.g. offsite:clash-backups/game
#   RCLONE_CONFIG_OFFSITE_*  the "offsite" remote, e.g. TYPE=b2, ACCOUNT=<key id>, KEY=<application key>
#   BACKUP_DIR               local directory (default /backups)
#   BACKUP_RETENTION_DAYS    local copies older than this are removed once confirmed offsite (default 30)
#   BACKUP_MAX_AGE_HOURS     alert when the newest offsite copy is older (default 26)
#   BACKUP_HEARTBEAT_URL     optional heartbeat monitor (healthchecks.io, Better Stack, ...): pinged when
#                            the offsite copy is fresh, "<url>/fail" when it is not
set -eu

: "${BACKUP_REMOTE:?set BACKUP_REMOTE}"
BACKUP_DIR="${BACKUP_DIR:-/backups}"
BACKUP_RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-30}"
BACKUP_MAX_AGE_HOURS="${BACKUP_MAX_AGE_HOURS:-26}"
pattern='strategy-game-*.dump.gpg'

heartbeat() {
	if [ -n "${BACKUP_HEARTBEAT_URL:-}" ]; then
		curl -fsS -m 10 --retry 3 -o /dev/null "$BACKUP_HEARTBEAT_URL$1" || echo "Heartbeat ping failed" >&2
	fi
}

# 1. Upload every copy not yet confirmed offsite. A copy counts as offsite only after rclone has
#    compared size and checksum of the stored object with the local file. An object that already
#    exists is never replaced (the storage key cannot delete either); if it differs, the check fails.
failed=0
for file in $(ls -1 "$BACKUP_DIR"/$pattern 2>/dev/null | sort); do
	[ -f "$file.offsite" ] && continue
	name="$(basename "$file")"
	if rclone copy --ignore-existing --include "$name" "$BACKUP_DIR" "$BACKUP_REMOTE" \
		&& rclone check --one-way --include "$name" "$BACKUP_DIR" "$BACKUP_REMOTE"; then
		touch "$file.offsite"
		echo "Offsite: $name"
	else
		echo "Upload of $name failed; retried on the next run" >&2
		failed=1
	fi
done

# 2. Remove local copies older than the retention period, but only when confirmed offsite, and never
#    the newest. Offsite copies expire by the bucket's lifecycle rule, not by this script.
newest="$(ls -1 "$BACKUP_DIR"/$pattern 2>/dev/null | sort | tail -n 1)"
find "$BACKUP_DIR" -maxdepth 1 -name "$pattern" -mtime +"$BACKUP_RETENTION_DAYS" | while read -r old; do
	if [ "$old" != "$newest" ] && [ -f "$old.offsite" ]; then
		rm -f "$old" "$old.sha256" "$old.offsite"
		echo "Removed local copy: $(basename "$old")"
	fi
done

# 3. Alert unless the newest offsite copy is recent. This catches a failed backup as well as a failed
#    upload. rclone keeps the file's modification time, which is when the backup was made.
latest="$(rclone lsf --files-only --format t --include "$pattern" "$BACKUP_REMOTE" | sort | tail -n 1)"
if [ -z "$latest" ]; then
	echo "ALERT: no backup offsite at $BACKUP_REMOTE" >&2
	heartbeat /fail
	exit 1
fi
age_hours=$(( ($(date -u +%s) - $(date -u -d "$latest" +%s)) / 3600 ))
if [ "$age_hours" -ge "$BACKUP_MAX_AGE_HOURS" ]; then
	echo "ALERT: newest offsite backup is $age_hours hours old ($latest UTC)" >&2
	heartbeat /fail
	exit 1
fi
echo "Newest offsite backup: $latest UTC ($age_hours h old)"
heartbeat ""
exit "$failed"
