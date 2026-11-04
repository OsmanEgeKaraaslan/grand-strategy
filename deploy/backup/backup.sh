#!/bin/sh
# Encrypted logical backup of the game schema (docs/PRODUCTION_READINESS.md §8). Supabase's own
# backups are the first line; this is an independent copy the operator controls. Runs nightly in
# the strategy-backup image, followed by offsite.sh.
#
# The dump is encrypted to a GnuPG public key before it reaches the backup directory; the private key
# never is on the server, so neither the server nor the storage provider can read a backup.
#
# Environment:
#   BACKUP_DATABASE_URL     REQUIRED libpq URL of a read-only role (strategy_backup), e.g.
#                           postgresql://strategy_backup:<password>@db.<ref>.supabase.co:5432/postgres?sslmode=require
#   BACKUP_PUBLIC_KEY_FILE  public key to encrypt to (default /keys/backup-public.asc)
#   BACKUP_DIR              target directory (default /backups)
set -eu

: "${BACKUP_DATABASE_URL:?set BACKUP_DATABASE_URL}"
BACKUP_PUBLIC_KEY_FILE="${BACKUP_PUBLIC_KEY_FILE:-/keys/backup-public.asc}"
BACKUP_DIR="${BACKUP_DIR:-/backups}"

# The plaintext dump lives only in the container's own temporary directory, removed on exit.
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
export GNUPGHOME="$work/gnupg"
mkdir -m 700 "$GNUPGHOME"

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
name="strategy-game-$stamp.dump.gpg"
if [ -e "$BACKUP_DIR/$name" ]; then
	echo "Refusing: $name already exists" >&2
	exit 1
fi

# Custom format (compressed, selectively restorable); schema "game" only, which includes Flyway's
# history table. Ownership and grants are left out so the dump restores into any role setup.
pg_dump --format=custom --schema=game --no-owner --no-privileges --file="$work/game.dump" "$BACKUP_DATABASE_URL"

# A dump that pg_restore cannot list is unusable: fail now rather than at restore time.
pg_restore --list "$work/game.dump" > /dev/null

gpg --batch --quiet --trust-model always --recipient-file "$BACKUP_PUBLIC_KEY_FILE" \
	--output "$work/$name" --encrypt "$work/game.dump"

mkdir -p "$BACKUP_DIR"
mv "$work/$name" "$BACKUP_DIR/$name.partial"
(cd "$BACKUP_DIR" && sha256sum "$name.partial" | sed 's/\.partial$//' > "$name.sha256")
mv "$BACKUP_DIR/$name.partial" "$BACKUP_DIR/$name"
echo "Backup written: $BACKUP_DIR/$name ($(wc -c < "$BACKUP_DIR/$name") bytes, encrypted)"
