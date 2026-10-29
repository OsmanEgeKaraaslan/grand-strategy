#!/bin/sh
# Logical backup of the game schema (docs/PRODUCTION_READINESS.md §8). Supabase's own backups are
# the first line; this is an independent copy the operator controls.
#
# Requires pg_dump of the server's major version (17), e.g. run inside postgres:17-alpine:
#   docker run --rm -e BACKUP_DATABASE_URL -v "$PWD/backups:/backups" -v "$PWD/backup:/scripts:ro" \
#     postgres:17-alpine sh /scripts/backup.sh
#
# Environment:
#   BACKUP_DATABASE_URL  REQUIRED libpq URL, ideally a read-only role, e.g.
#                        postgresql://backup_ro:<password>@db.<ref>.supabase.co:5432/postgres?sslmode=require
#   BACKUP_DIR           target directory (default /backups)
#   BACKUP_KEEP          number of dumps to keep (default 14)
set -eu

: "${BACKUP_DATABASE_URL:?set BACKUP_DATABASE_URL}"
BACKUP_DIR="${BACKUP_DIR:-/backups}"
BACKUP_KEEP="${BACKUP_KEEP:-14}"

mkdir -p "$BACKUP_DIR"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
file="$BACKUP_DIR/strategy-game-$stamp.dump"

# Custom format (compressed, selectively restorable); schema "game" only, which includes Flyway's
# history table. Ownership and grants are left out so the dump restores into any role setup.
pg_dump --format=custom --schema=game --no-owner --no-privileges --file="$file.partial" "$BACKUP_DATABASE_URL"

# A dump that pg_restore cannot list is unusable: fail now rather than at restore time.
pg_restore --list "$file.partial" > /dev/null
mv "$file.partial" "$file"
sha256sum "$file" > "$file.sha256"
echo "Backup written: $file ($(wc -c < "$file") bytes)"

# Keep the newest $BACKUP_KEEP dumps.
ls -1t "$BACKUP_DIR"/strategy-game-*.dump 2>/dev/null | tail -n +"$((BACKUP_KEEP + 1))" | while read -r old; do
	rm -f "$old" "$old.sha256"
	echo "Removed old backup: $old"
done
