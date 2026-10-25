#!/usr/bin/env python3
"""Завершить часть активных кампаний с готовым прогнозом.

Прогнозы строятся по активным/планируемым, а вкладка «прогноз против факта»
сравнивает прогноз с фактом у завершённых кампаний. Этот скрипт переводит
часть активных кампаний, по которым уже есть настоящий прогноз, в
завершённые — чтобы сравнение наполнялось реальными данными.

Запускать после `run_forecasts.py`:

  python finish_campaigns.py               # завершить половину доступных
  python finish_campaigns.py --finish 10   # завершить ровно 10
"""

import argparse
import subprocess
import sys

FORECASTED_ACTIVE = """
SELECT count(*) FROM campaigns c
WHERE c.status = 'ACTIVE'
  AND EXISTS (SELECT 1 FROM forecasts f
              WHERE f.campaign_id = c.campaign_id AND f.status = 'COMPLETED');
"""

FINISH_SQL = """
UPDATE campaigns c SET status = 'FINISHED'
WHERE c.campaign_id IN (
    SELECT campaign_id FROM (
        SELECT campaign_id,
               row_number() OVER (ORDER BY campaign_id) AS rn
        FROM campaigns
        WHERE status = 'ACTIVE'
          AND EXISTS (SELECT 1 FROM forecasts f
                      WHERE f.campaign_id = campaigns.campaign_id
                        AND f.status = 'COMPLETED')
    ) t
    WHERE t.rn <= {limit}
);
"""


def psql(container: str, user: str, db: str, sql: str, capture: bool = False) -> str:
    cmd = ["docker", "exec", "-i", container, "psql", "-U", user, "-d", db,
           "-v", "ON_ERROR_STOP=1"] + (["-tA"] if capture else ["-q"])
    proc = subprocess.run(cmd, input=sql, capture_output=True, text=True)
    if proc.returncode != 0:
        print(proc.stderr, file=sys.stderr)
        raise SystemExit(1)
    return proc.stdout


def main() -> None:
    parser = argparse.ArgumentParser(description="Завершить активные кампании с прогнозом")
    parser.add_argument("--finish", type=int, default=None,
                        help="сколько завершить (по умолчанию — половина доступных)")
    parser.add_argument("--container", default="adforecast-db-1")
    parser.add_argument("--user", default="adforecast")
    parser.add_argument("--db", default="adforecast")
    args = parser.parse_args()

    count = int(psql(args.container, args.user, args.db, FORECASTED_ACTIVE, capture=True).strip() or 0)
    limit = args.finish if args.finish is not None else count // 2
    if limit <= 0:
        print("Нет завершаемых кампаний (нет активных с готовым прогнозом).")
        return
    psql(args.container, args.user, args.db, FINISH_SQL.format(limit=limit))
    print(f"Завершено кампаний: {limit} (доступно: {count})")


if __name__ == "__main__":
    main()
