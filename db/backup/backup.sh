#!/usr/bin/env bash
set -euo pipefail
timestamp=$(date +%Y%m%d_%H%M%S)
docker exec adforecast-db-1 pg_dump -U adforecast -d adforecast > "adforecast_${timestamp}.sql"
echo "Резервная копия: adforecast_${timestamp}.sql"
