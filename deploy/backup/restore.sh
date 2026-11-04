#!/bin/sh
# Restores a dump made by backup.sh into a database (docs/PRODUCTION_READINESS.md §8).
# Practise on a scratch database first; restoring over production replaces its game schema.
#
#   docker run --rm -e RESTORE_DATABASE_URL -v "$PWD:/restore:ro" strategy-backup \
#     sh /scripts/restore.sh /restore/strategy-game-<stamp>.dump <database-name>
#
# Backups are encrypted: decrypt the .dump.gpg first, on the machine that holds the private key
# (gpg --output strategy-game-<stamp>.dump --decrypt strategy-game-<stamp>.dump.gpg).
#
# The second argument must equal the target's database name: a guard against restoring into the
# wrong database by a stale environment variable.
#
# Environment:
#   RESTORE_DATABASE_URL  REQUIRED libpq URL of the target, with a role that may create schema objects
set -eu

dump="${1:?usage: restore.sh <dump-file> <expected-database-name>}"
expected="${2:?usage: restore.sh <dump-file> <expected-database-name>}"
: "${RESTORE_DATABASE_URL:?set RESTORE_DATABASE_URL}"

case "$dump" in
*.gpg)
	echo "Refusing: $dump is encrypted. Decrypt it first (see the comment at the top of this script)." >&2
	exit 1 ;;
esac

actual="$(psql "$RESTORE_DATABASE_URL" -Atqc 'SELECT current_database()')"
if [ "$actual" != "$expected" ]; then
	echo "Refusing: target database is '$actual', expected '$expected'." >&2
	exit 1
fi

# The schema is dropped first; pg_restore then runs in one transaction (all objects or none, re-run on failure).
psql "$RESTORE_DATABASE_URL" -v ON_ERROR_STOP=1 -qc 'DROP SCHEMA IF EXISTS game CASCADE'
pg_restore --no-owner --no-privileges --exit-on-error --single-transaction --dbname="$RESTORE_DATABASE_URL" "$dump"

echo "Restored $dump into $actual. Row counts:"
psql "$RESTORE_DATABASE_URL" -Atqc "
	SELECT 'flyway latest version: ' || version FROM game.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1;
	SELECT 'players: ' || count(*) FROM game.player;
	SELECT 'games: ' || count(*) FROM game.game;
	SELECT 'game commands: ' || count(*) FROM game.game_command;"
