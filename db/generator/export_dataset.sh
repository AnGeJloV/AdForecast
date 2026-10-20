#!/usr/bin/env bash
set -euo pipefail
query="COPY (
  SELECT a.platform,
         c.budget,
         (u.period_end - u.period_start + 1) AS duration_days,
         EXTRACT(MONTH FROM u.period_start)::int AS start_month,
         ROUND(SUM(m.revenue) / NULLIF(SUM(m.cost), 0), 4) AS payback_ratio
  FROM campaigns c
  JOIN ad_accounts a ON a.account_id = c.account_id
  JOIN uploads u ON u.campaign_id = c.campaign_id
  JOIN daily_metrics m ON m.upload_id = u.upload_id
  WHERE c.status = 'FINISHED'
  GROUP BY c.campaign_id, a.platform, c.budget, u.period_start, u.period_end
) TO STDOUT WITH CSV HEADER"
outdir=$(cd "$(dirname "$0")" && pwd)
docker exec adforecast-db-1 psql -U adforecast -d adforecast -c "$query" > "$outdir/dataset.csv"
echo "Датасет: $outdir/dataset.csv ($(($(wc -l < "$outdir/dataset.csv") - 1)) кампаний)"
