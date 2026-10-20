#!/usr/bin/env bash
set -euo pipefail
outdir=$(cd "$(dirname "$0")" && pwd)
timestamp=$(date +%Y%m%d_%H%M%S)
docker exec adforecast-db-1 pg_dump -U adforecast -d adforecast > "$outdir/adforecast_${timestamp}.sql"
echo "Резервная копия: $outdir/adforecast_${timestamp}.sql"
