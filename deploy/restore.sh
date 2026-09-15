#!/usr/bin/env sh
set -eu

backup_file="${1:?Usage: ./deploy/restore.sh path/to/lab-YYYYMMDD-HHMMSS.db}"
test -f "$backup_file"
mkdir -p ./data

echo "This replaces the live database. Stop now with Ctrl+C if the backup file is incorrect."
sleep 5
docker compose stop app
cp "$backup_file" ./data/lab.db
rm -f ./data/lab.db-wal ./data/lab.db-shm
docker compose start app
echo "Restore completed. Verify login and data before reopening the service."
