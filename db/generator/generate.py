import argparse
import csv
import math
import random
from datetime import date, timedelta
from pathlib import Path

PLATFORMS = [
    ("Яндекс Директ", "search", 0.040, 0.85, 1.00),
    ("Google Ads", "search", 0.035, 0.95, 1.05),
    ("VK Реклама", "social", 0.016, 0.40, 0.37),
    ("myTarget", "social", 0.014, 0.45, 0.40),
    ("Telegram Ads", "social", 0.012, 0.35, 0.27),
    ("Email-платформа", "email", 0.250, 0.06, 0.09),
]

NICHES = [
    "электроника",
    "одежда",
    "косметика",
    "спорт",
    "дом",
    "детские_товары",
    "авто",
    "образование",
]

WEEKDAY_FACTORS = [0.95, 1.05, 1.10, 1.05, 1.00, 0.90, 0.80]
MONTH_FACTORS = [0.85, 0.85, 0.95, 1.00, 1.05, 0.95, 0.90, 0.90, 1.00, 1.10, 1.25, 1.30]

HISTORY_START = date(2024, 3, 1)
SQL_BATCH = 100


def lognormal(rng: random.Random, sigma: float) -> float:
    return math.exp(rng.gauss(0.0, sigma))


def daily_row(rng: random.Random, platform: dict, day_budget: float, day: date, fatigue: float) -> tuple:
    ctr = platform["ctr"] * lognormal(rng, 0.05)
    cpc = platform["cpc"] * lognormal(rng, 0.05)
    clicks = max(1, round(day_budget / cpc))
    impressions = max(clicks, round(clicks / ctr))
    cost = round(clicks * cpc, 2)
    season = WEEKDAY_FACTORS[day.weekday()] * MONTH_FACTORS[day.month - 1] * fatigue
    revenue = round(clicks * platform["rpc"] * season * lognormal(rng, 0.08), 2)
    return impressions, clicks, cost, max(revenue, 0.0)


def campaign_timeline(rng: random.Random, status: str, today: date) -> tuple:
    duration = rng.randint(14, 75)
    if status == "PLANNED":
        return duration, None, None
    if status == "ACTIVE":
        start = today - timedelta(days=rng.randint(duration // 2, max(duration // 2, 40)))
        end = min(start + timedelta(days=duration - 1), today - timedelta(days=1))
    else:
        start = HISTORY_START + timedelta(days=rng.randint(0, (today - timedelta(days=90) - HISTORY_START).days))
        end = start + timedelta(days=duration - 1)
    return duration, start, end


def build_campaign(rng: random.Random, campaign_id: int, account_id: int, status: str, today: date, noise: float) -> dict:
    platform = PLATFORMS[account_id - 1]
    duration, start, end = campaign_timeline(rng, status, today)
    budget = round(rng.uniform(3000, 60000), 2)
    day_budget = budget / duration
    campaign_factor = lognormal(rng, noise)
    niche = NICHES[rng.randrange(len(NICHES))]
    name = f"{platform[1]}_{niche}_{start.year if start else today.year}"
    metrics = []
    if start:
        fatigue = 1.0
        for i in range((end - start).days + 1):
            row = daily_row(
                rng,
                {"ctr": platform[2], "cpc": platform[3], "rpc": platform[4] * campaign_factor},
                day_budget,
                start + timedelta(days=i),
                fatigue,
            )
            metrics.append(row)
            if i > duration // 2:
                fatigue = max(0.7, fatigue - 0.012)
    return {
        "campaign_id": campaign_id,
        "account_id": account_id,
        "name": name,
        "budget": budget,
        "status": status,
        "start": start,
        "end": end,
        "metrics": metrics,
    }


def sql_literal(value) -> str:
    if value is None:
        return "NULL"
    if isinstance(value, str):
        return "'" + value.replace("'", "''") + "'"
    if isinstance(value, date):
        return "'" + value.isoformat() + "'"
    return str(value)


def write_batches(out, table: str, columns: list, rows: list, seq: str) -> None:
    for i in range(0, len(rows), SQL_BATCH):
        chunk = rows[i:i + SQL_BATCH]
        values = ",\n    ".join(
            "(" + ", ".join(sql_literal(v) for v in row) + ")" for row in chunk
        )
        out.write(f"INSERT INTO {table} ({', '.join(columns)}) VALUES\n    {values};\n")
    if rows:
        out.write(f"SELECT setval('{seq}', {max(row[0] for row in rows)});\n")
    out.write("\n")


def main() -> None:
    parser = argparse.ArgumentParser(description="Генерация синтетических данных AdForecast")
    parser.add_argument("--campaigns", type=int, default=800, help="количество завершенных кампаний")
    parser.add_argument("--active", type=int, default=12, help="количество активных кампаний")
    parser.add_argument("--planned", type=int, default=8, help="количество планируемых кампаний")
    parser.add_argument("--exports", type=int, default=6, help="количество CSV-выгрузок рекламных кабинетов")
    parser.add_argument("--noise", type=float, default=0.08, help="уровень шума ROI кампании")
    parser.add_argument("--seed", type=int, default=42, help="зерно генератора")
    parser.add_argument("--outdir", type=Path, default=Path(__file__).parent, help="каталог результатов")
    args = parser.parse_args()

    rng = random.Random(args.seed)
    today = date.today()
    outdir: Path = args.outdir
    exports_dir = outdir / "exports"
    exports_dir.mkdir(parents=True, exist_ok=True)

    accounts = [
        (i + 1, f"{name}_кабинет", name)
        for i, (name, *_rest) in enumerate(PLATFORMS)
    ]

    campaigns = []
    campaign_id = 1
    for _ in range(args.campaigns):
        account_id = rng.randint(1, len(PLATFORMS))
        campaigns.append(build_campaign(rng, campaign_id, account_id, "FINISHED", today, args.noise))
        campaign_id += 1
    for _ in range(args.active):
        account_id = rng.randint(1, len(PLATFORMS))
        campaigns.append(build_campaign(rng, campaign_id, account_id, "ACTIVE", today, args.noise))
        campaign_id += 1
    for _ in range(args.planned):
        account_id = rng.randint(1, len(PLATFORMS))
        campaigns.append(build_campaign(rng, campaign_id, account_id, "PLANNED", today, args.noise))
        campaign_id += 1

    uploads = []
    daily = []
    upload_id = 1
    metric_id = 1
    for campaign in campaigns:
        if not campaign["metrics"]:
            continue
        uploads.append(
            (upload_id, campaign["campaign_id"], f"export_{campaign['name']}.csv", "CSV",
             campaign["start"], campaign["end"], campaign["end"] + timedelta(days=1))
        )
        for i, (impressions, clicks, cost, revenue) in enumerate(campaign["metrics"]):
            daily.append(
                (metric_id, upload_id, campaign["start"] + timedelta(days=i), cost, revenue, impressions, clicks)
            )
            metric_id += 1
        upload_id += 1

    access = [(i, 2, c["campaign_id"]) for i, c in enumerate(campaigns, start=1)]

    with (outdir / "seed_data.sql").open("w", encoding="utf-8", newline="\n") as out:
        write_batches(out, "ad_accounts", ["account_id", "account_name", "platform"], accounts, "ad_accounts_account_id_seq")
        campaign_rows = [
            (c["campaign_id"], c["account_id"], c["name"], c["budget"], "BYN", c["status"],
             c["start"] or today)
            for c in campaigns
        ]
        write_batches(out, "campaigns",
                      ["campaign_id", "account_id", "campaign_name", "budget", "currency", "status", "created_at"],
                      campaign_rows, "campaigns_campaign_id_seq")
        write_batches(out, "campaign_access", ["access_id", "user_id", "campaign_id"], access, "campaign_access_access_id_seq")
        write_batches(out, "uploads",
                      ["upload_id", "campaign_id", "file_name", "file_format", "period_start", "period_end", "uploaded_at"],
                      uploads, "uploads_upload_id_seq")
        write_batches(out, "daily_metrics",
                      ["metric_id", "upload_id", "metric_date", "cost", "revenue", "impressions", "clicks"],
                      daily, "daily_metrics_metric_id_seq")

    for i in range(args.exports):
        platform = PLATFORMS[rng.randrange(len(PLATFORMS))]
        duration = rng.randint(21, 60)
        start = today - timedelta(days=rng.randint(30, 120))
        day_budget = rng.uniform(150, 1200)
        campaign_factor = lognormal(rng, args.noise)
        csv_path = exports_dir / f"sample_upload_{i + 1}.csv"
        with csv_path.open("w", encoding="utf-8", newline="") as f:
            writer = csv.writer(f)
            writer.writerow(["date", "impressions", "clicks", "cost", "revenue"])
            fatigue = 1.0
            for day_i in range(duration):
                impressions, clicks, cost, revenue = daily_row(
                    rng,
                    {"ctr": platform[2], "cpc": platform[3], "rpc": platform[4] * campaign_factor},
                    day_budget,
                    start + timedelta(days=day_i),
                    fatigue,
                )
                writer.writerow([start + timedelta(days=day_i), impressions, clicks, cost, revenue])
                if day_i > duration // 2:
                    fatigue = max(0.7, fatigue - 0.012)

    print(f"Кампании: {len(campaigns)} (завершено {args.campaigns}, активных {args.active}, планируемых {args.planned})")
    print(f"Выгрузки: {len(uploads)}, дневных показателей: {len(daily)}")
    print(f"Результат: {outdir / 'seed_data.sql'}, выборки в {exports_dir}")


if __name__ == "__main__":
    main()
