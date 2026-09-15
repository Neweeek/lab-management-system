#!/usr/bin/env sh
set -eu

destination="${1:-./backups}"
stamp="$(date +%Y%m%d-%H%M%S)"
mkdir -p "$destination" ./data/backups

docker compose exec -T app sqlite3 /app/data/lab.db ".backup '/app/data/backups/lab-${stamp}.db'"
docker compose exec -T app sha256sum "/app/data/backups/lab-${stamp}.db" > "${destination}/lab-${stamp}.db.sha256"
cp "./data/backups/lab-${stamp}.db" "${destination}/lab-${stamp}.db"
echo "Backup created: ${destination}/lab-${stamp}.db"
