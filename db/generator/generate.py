"""Генератор синтетических данных AdForecast.

Экономика кампаний откалибрована по открытым реальным данным (см. realdata/README.md):
Conjura MMM (93 eCommerce-организации, CC BY 4.0), реальные Facebook Ads анонимной
организации, бенчмарки WordStream/LocaliQ, Triple Whale и click.ru.

Принцип: итоговая окупаемость кампании определяется только теми характеристиками,
которые доступны модели прогнозирования (канал, бюджет и дневной бюджет, длительность,
месяц старта, история самой кампании) плюс небольшой скрытый шум (ниша, креативы,
конкуренция). Это делает задачу прогнозирования содержательной и честной.

Зависимости окупаемости:
- канал: базовая экономика площадки (CPC, CTR, типичный ROAS);
- дневной бюджет: насыщение отдачи (длинный хвост дешёвого трафика заканчивается)
  и штраф малого бюджета (площадка не выходит из обучения);
- длительность: выгорание аудитории на длинных кампаниях;
- месяц старта: спрос по сезонам (пик ноябрь-декабрь, провал сентябрь);
- день недели: внутринедельный ритм спроса;
- шум кампании sigma=0.15 и дневной шум sigma=0.12.
"""

import argparse
import csv
import math
import random
from datetime import date, timedelta
from pathlib import Path

# (название, тип, CTR, CPC в BYN, базовая окупаемость, гамма насыщения по бюджету, выгорание)
#
# CPC и CTR — из открытых источников на октябрь 2026 (см. realdata/README.md):
#   Яндекс Директ: CPC поиска 40,3 ₽ = 1,46 BYN, сети 8,56 ₽ = 0,31 BYN (click.ru, Q2 2026)
#   VK Реклама: 9,15 ₽ = 0,33 BYN, CTR 0,97 % (click.ru); myTarget: 20 ₽ = 0,73 BYN
#   Google Ads: от 0,3 $ = 0,89 BYN в спокойных нишах Беларуси (topsite.by), CTR 6,3 % (MetricNexus)
#   Telegram Ads: CPM ≈ 1 €; TikTok: CPC 0,62 $ = 1,84 BYN, CTR 1,12 % (MetricNexus)
#   Kufar: баннер 12 BYN/1000 показов (belkaads) — при CTR 1,5 % это ≈ 0,80 BYN за клик
# Курсы НБРБ 22.10.2026: 1 USD = 2,9713, 1 EUR = 3,3337, 100 RUB = 3,6254 BYN.
#
# Базовая окупаемость — типичный атрибутированный ROAS канала: ориентиры Triple Whale
# и MetricNexus (Google 3,3–3,8, Meta 1,9–3,2, TikTok 2,1) снижены для малого и среднего
# бизнеса Беларуси, где бюджет и объёмы заказов меньше.
# Последний элемент — минимальный дневной бюджет площадки: ниже него объявления
# не показываются, и такой план в реальности не запустился бы (те же значения, что в
# PlatformCatalog на бэкенде). Кампании дешевле генератор не создаёт.
PLATFORMS = [
    ("Яндекс Директ", "search", 0.055, 1.46, 1.60, 0.20, 0.30, 10.0),
    ("Яндекс Директ — сети", "social", 0.007, 0.31, 1.05, 0.22, 0.35, 10.0),
    ("Google Ads", "search", 0.063, 1.05, 1.70, 0.18, 0.28, 10.0),
    ("VK Реклама", "social", 0.0097, 0.33, 1.20, 0.22, 0.35, 10.0),
    ("myTarget", "social", 0.008, 0.73, 1.10, 0.22, 0.35, 10.0),
    ("Telegram Ads", "social", 0.010, 0.33, 0.95, 0.25, 0.40, 10.0),
    ("TikTok Ads", "social", 0.0112, 1.84, 1.00, 0.24, 0.42, 148.0),
    ("Kufar", "classified", 0.015, 0.80, 1.30, 0.20, 0.30, 10.0),
    ("Email-платформа", "email", 0.030, 0.02, 2.60, 0.15, 0.20, 0.0),
]

MIN_DAY_BUDGET = 7  # индекс минимального дневного бюджета в PLATFORMS

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

# спрос по дням недели (пн..вс) — из Conjura MMM, среднее = 1.0
WEEKDAY_FACTORS = [1.10, 1.04, 1.03, 0.98, 0.94, 0.81, 1.10]

# спрос по месяцам — сглаженная сезонность Conjura MMM (пик Q4, провал сентября)
_MONTH_RAW = [0.85, 0.80, 0.90, 0.95, 1.00, 0.90, 0.85, 0.85, 0.80, 0.95, 1.30, 1.50]
_MONTH_MEAN = sum(_MONTH_RAW) / 12
MONTH_FACTORS = [v / _MONTH_MEAN for v in _MONTH_RAW]

# Кривая отдачи от дневного бюджета: пик в районе 150–300 BYN в день, ниже —
# кампания не выходит из обучения у площадки, выше — начинается насыщение.
REFERENCE_DAY_BUDGET = 300.0
LEARNING_SCALE = 25.0
HISTORY_START = date(2024, 3, 1)
SQL_BATCH = 100


def lognormal(rng: random.Random, sigma: float) -> float:
    return math.exp(rng.gauss(0.0, sigma))


def budget_efficiency(day_budget: float, gamma: float) -> float:
    """Отдача от дневного бюджета: насыщение на больших бюджетах и штраф малого."""
    saturation = (day_budget / REFERENCE_DAY_BUDGET) ** (-gamma)
    learning = 1.0 - math.exp(-day_budget / LEARNING_SCALE)
    return saturation * learning


def fatigue_slope(duration: int, rate: float) -> float:
    """Глубина падения отдачи к концу кампании (доля, 0 — нет выгорания)."""
    ramp = min(1.0, max(0.0, (duration - 20) / 80))
    return min(0.8, rate * ramp / 0.35)


def fatigue_factor(day_index: int, duration: int, slope: float) -> float:
    """Множитель отдачи в день day_index: линейное выгорание после 30 % кампании."""
    if slope <= 0.0:
        return 1.0
    progress = max(0.0, (day_index - 0.3 * duration)) / (0.7 * duration)
    return max(0.35, 1.0 - slope * progress)


def daily_row(rng: random.Random, platform: tuple, day_budget: float, day: date,
              day_index: int, duration: int, campaign_noise: float) -> tuple:
    _, _, ctr, cpc, base_ratio, gamma, fatigue_rate, *_ = platform
    cost_noise = lognormal(rng, 0.03)
    ramp = min(1.0, (day_index + 1) / 3)
    cost = round(day_budget * cost_noise * ramp, 2)
    cost = max(cost, 0.5)
    clicks = max(1, round(cost / cpc * lognormal(rng, 0.05)))
    impressions = max(clicks, round(clicks / ctr * lognormal(rng, 0.05)))
    slope = fatigue_slope(duration, fatigue_rate)
    ratio_day = (
        base_ratio
        * budget_efficiency(day_budget, gamma)
        * MONTH_FACTORS[day.month - 1]
        * WEEKDAY_FACTORS[day.weekday()]
        * fatigue_factor(day_index, duration, slope)
        * campaign_noise
        * lognormal(rng, 0.12)
    )
    revenue = round(cost * ratio_day, 2)
    return impressions, clicks, cost, max(revenue, 0.0)


def campaign_timeline(rng: random.Random, status: str, today: date) -> tuple:
    duration = rng.randint(14, 100)
    if status == "PLANNED":
        return duration, None, None
    if status == "ACTIVE":
        elapsed = rng.randint(7, max(8, duration - 7))
        start = today - timedelta(days=elapsed)
        end = min(start + timedelta(days=duration - 1), today - timedelta(days=1))
    else:
        start = HISTORY_START + timedelta(days=rng.randint(0, (today - timedelta(days=90) - HISTORY_START).days))
        end = start + timedelta(days=duration - 1)
    return duration, start, end


def build_campaign(rng: random.Random, campaign_id: int, account_id: int, status: str,
                   today: date, noise: float) -> dict:
    platform = PLATFORMS[account_id - 1]
    duration, start, end = campaign_timeline(rng, status, today)
    # от 300 BYN — реальный минимум бюджета на систему в Беларуси (ECOM Digital, 2026)
    budget = round(math.exp(rng.uniform(math.log(600), math.log(60000))), 2)
    min_day = platform[MIN_DAY_BUDGET]
    # бюджет должен держать дневной минимум площадки и при длинном прогнозе,
    # иначе модель вернёт «бюджет ниже минимума»
    budget = max(budget, min_day * 120)
    day_budget = budget / duration
    if day_budget < min_day:
        day_budget = min_day
        budget = round(day_budget * duration, 2)
    budget = round(budget, 2)
    campaign_noise = lognormal(rng, noise)
    niche = NICHES[rng.randrange(len(NICHES))]
    name = f"{platform[1]}_{niche}_{start.year if start else today.year}"
    metrics = []
    if start:
        for i in range((end - start).days + 1):
            metrics.append(
                daily_row(rng, platform, day_budget, start + timedelta(days=i),
                          i, duration, campaign_noise)
            )
    return {
        "campaign_id": campaign_id,
        "account_id": account_id,
        "name": name,
        "budget": budget,
        "duration": duration,
        "status": status,
        "start": start,
        "end": end,
        "metrics": metrics,
    }


def cutoff_records(campaign: dict) -> list:
    """Обучающие записи «на отсечках»: состояние кампании в день k -> итог кампании.

    Отсечка 0 соответствует планируемой кампании без истории. Итоговая окупаемость
    считается по всем дням, признаки — только по дням до отсечки.
    """
    metrics = campaign["metrics"]
    if not metrics:
        return []
    duration = campaign["duration"]
    total_cost = sum(m[2] for m in metrics)
    total_revenue = sum(m[3] for m in metrics)
    if total_cost <= 0:
        return []
    target = total_revenue / total_cost
    records = []
    for fraction in (0.0, 0.3, 0.55, 0.8):
        k = int(duration * fraction)
        k = max(0, min(k, len(metrics)))
        prefix = metrics[:k]
        cost = sum(m[2] for m in prefix)
        revenue = sum(m[3] for m in prefix)
        week = prefix[-7:]
        cost7 = sum(m[2] for m in week)
        revenue7 = sum(m[3] for m in week)
        records.append({
            "campaign_id": campaign["campaign_id"],
            "platform": PLATFORMS[campaign["account_id"] - 1][0],
            "budget": campaign["budget"],
            "duration_days": duration,
            "start_month": campaign["start"].month,
            "cutoff_days": k,
            "has_history": 1 if k > 0 else 0,
            "cost_to_date": round(cost, 2),
            "revenue_to_date": round(revenue, 2),
            "cost_last7": round(cost7, 2),
            "revenue_last7": round(revenue7, 2),
            "spent_fraction": round(cost / campaign["budget"], 4),
            "target_ratio": round(target, 4),
        })
    return records


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


def write_cutoffs(path: Path, records: list) -> None:
    columns = ["campaign_id", "platform", "budget", "duration_days", "start_month",
               "cutoff_days", "has_history", "cost_to_date", "revenue_to_date",
               "cost_last7", "revenue_last7", "spent_fraction", "target_ratio"]
    with path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=columns)
        writer.writeheader()
        writer.writerows(records)


def summarize(campaigns: list) -> None:
    by_platform = {}
    for c in campaigns:
        if not c["metrics"]:
            continue
        cost = sum(m[2] for m in c["metrics"])
        revenue = sum(m[3] for m in c["metrics"])
        if cost > 0:
            name = PLATFORMS[c["account_id"] - 1][0]
            by_platform.setdefault(name, []).append(revenue / cost)
    print("Окупаемость по платформам (среднее / доля >= 1 / n):")
    all_ratios = []
    for name, ratios in by_platform.items():
        profitable = sum(1 for r in ratios if r >= 1.0) / len(ratios)
        mean = sum(ratios) / len(ratios)
        all_ratios.extend(ratios)
        print(f"  {name}: {mean:.2f} / {profitable:.0%} / {len(ratios)}")
    all_ratios.sort()
    n = len(all_ratios)
    print(f"Итого: медиана {all_ratios[n // 2]:.2f}, "
          f"p10 {all_ratios[int(0.1 * n)]:.2f}, p90 {all_ratios[min(n - 1, int(0.9 * n))]:.2f}, "
          f"окупаемых {sum(1 for r in all_ratios if r >= 1.0) / n:.0%}")



def main() -> None:
    parser = argparse.ArgumentParser(description="Генерация синтетических данных AdForecast")
    parser.add_argument("--campaigns", type=int, default=800, help="количество завершенных кампаний")
    parser.add_argument("--active", type=int, default=72, help="количество активных кампаний")
    parser.add_argument("--planned", type=int, default=40, help="количество планируемых кампаний")
    parser.add_argument("--exports", type=int, default=6, help="количество CSV-выгрузок рекламных кабинетов")
    parser.add_argument("--noise", type=float, default=0.15, help="скрытый шум окупаемости кампании (sigma)")
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

    # Демо-организация обучения несёт основной пул; у пользователей — свои кампании.
    demo_org = [(2, "Демо-организация (обучение)")]
    demo_hash = "$2a$06$MlVSPvtno61e7qNdSVpOkeDAoCXrr8sfKElJsa.R0uFkkEWuCZ1f2"
    users = [
        (3, 1, "demo", "demo@adforecast.by", "Демо Маркетолог", demo_hash, 2),
        (4, 1, "alena", "alena@adforecast.by", "Алена Семёнова", demo_hash, 2),
        (5, 1, "maxim", "maxim@adforecast.by", "Максим Баранов", demo_hash, 2),
        (6, 1, "olga", "olga@adforecast.by", "Ольга Ковалёва", demo_hash, 2),
        (7, 1, "dmitry", "dmitry@adforecast.by", "Дмитрий Козлов", demo_hash, 2),
        (8, 1, "ekaterina", "ekaterina@adforecast.by", "Екатерина Мороз", demo_hash, 2),
        (9, 1, "sergey", "sergey@adforecast.by", "Сергей Павлов", demo_hash, 2),
        (10, 1, "irina", "irina@adforecast.by", "Ирина Соколова", demo_hash, 2),
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

    # Раздача кампаний: каждому маркетологу небольшой набор, демо-пользователю — основной пул.
    pool = {"FINISHED": [c for c in campaigns if c["status"] == "FINISHED"],
            "ACTIVE": [c for c in campaigns if c["status"] == "ACTIVE"],
            "PLANNED": [c for c in campaigns if c["status"] == "PLANNED"]}
    for key in pool:
        rng.shuffle(pool[key])
    access = []
    aid = 1
    fi = ai = pi = 0
    distributors = [2, 4, 5, 6, 7, 8, 9, 10]  # angejlov + маркетологи
    for uid in distributors:
        fin = pool["FINISHED"][fi:fi + 24]; fi += 24
        act = pool["ACTIVE"][ai:ai + 6]; ai += 6
        plan = pool["PLANNED"][pi:pi + 3]; pi += 3
        for c in fin + act + plan:
            access.append((aid, uid, c["campaign_id"])); aid += 1
    for c in pool["FINISHED"][fi:] + pool["ACTIVE"][ai:] + pool["PLANNED"][pi:]:
        access.append((aid, 3, c["campaign_id"])); aid += 1

    with (outdir / "seed_data.sql").open("w", encoding="utf-8", newline="\n") as out:
        write_batches(out, "organizations",
                      ["organization_id", "organization_name"], demo_org, "organizations_organization_id_seq")
        write_batches(out, "users",
                      ["user_id", "role_id", "login", "email", "password_hash", "full_name", "is_active", "organization_id"],
                      [(u[0], u[1], u[2], u[3], u[5], u[4], True, u[6]) for u in users],
                      "users_user_id_seq")
        write_batches(out, "ad_accounts",
                      ["account_id", "account_name", "platform", "organization_id"],
                      [(a[0], a[1], a[2], 2) for a in accounts], "ad_accounts_account_id_seq")
        campaign_rows = [
            (c["campaign_id"], c["account_id"], c["name"], c["budget"], "BYN", c["status"],
             c["start"] or today, 2)
            for c in campaigns
        ]
        write_batches(out, "campaigns",
                      ["campaign_id", "account_id", "campaign_name", "budget", "currency", "status", "created_at", "organization_id"],
                      campaign_rows, "campaigns_campaign_id_seq")
        write_batches(out, "campaign_access", ["access_id", "user_id", "campaign_id"], access, "campaign_access_access_id_seq")
        write_batches(out, "uploads",
                      ["upload_id", "campaign_id", "file_name", "file_format", "period_start", "period_end", "uploaded_at"],
                      uploads, "uploads_upload_id_seq")
        write_batches(out, "daily_metrics",
                      ["metric_id", "upload_id", "metric_date", "cost", "revenue", "impressions", "clicks"],
                      daily, "daily_metrics_metric_id_seq")

    records = []
    for campaign in campaigns:
        if campaign["status"] != "FINISHED":
            continue
        records.extend(cutoff_records(campaign))
    write_cutoffs(outdir / "cutoff_records.csv", records)

    for i in range(args.exports):
        platform = PLATFORMS[rng.randrange(len(PLATFORMS))]
        duration = rng.randint(21, 60)
        start = today - timedelta(days=rng.randint(30, 120))
        day_budget = math.exp(rng.uniform(math.log(10), math.log(1500)))
        campaign_noise = lognormal(rng, args.noise)
        csv_path = exports_dir / f"sample_upload_{i + 1}.csv"
        with csv_path.open("w", encoding="utf-8", newline="") as f:
            writer = csv.writer(f)
            writer.writerow(["date", "impressions", "clicks", "cost", "revenue"])
            for day_i in range(duration):
                impressions, clicks, cost, revenue = daily_row(
                    rng, platform, day_budget, start + timedelta(days=day_i),
                    day_i, duration, campaign_noise,
                )
                writer.writerow([start + timedelta(days=day_i), impressions, clicks, cost, revenue])

    print(f"Кампании: {len(campaigns)} (завершено {args.campaigns}, активных {args.active}, планируемых {args.planned})")
    print(f"Выгрузки: {len(uploads)}, дневных показателей: {len(daily)}, записей отсечек: {len(records)}")
    print(f"Результат: {outdir / 'seed_data.sql'}, выборки в {exports_dir}, отсечки в cutoff_records.csv")
    summarize(campaigns)


if __name__ == "__main__":
    main()
