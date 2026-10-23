from __future__ import annotations

import math
from dataclasses import dataclass
from datetime import datetime, timezone

import numpy as np
from catboost import CatBoostRegressor, Pool

FEATURES = [
    "platform",
    "budget",
    "duration_days",
    "start_month",
    "seasonality_index",
    "day_budget_planned",
    "has_history",
    "hist_days",
    "hist_ratio",
    "hist_ratio_last7",
    "hist_spent_fraction",
    "hist_daily_cost",
    "hist_daily_revenue",
]
CATEGORICAL = ["platform"]
TARGET = "target_ratio"
ENSEMBLE_SIZE = 10
HOLDOUT_FRACTION = 0.2
RANDOM_SEED = 42
DAYS_PER_MONTH = 30.44
LAG_FRACTION = 0.5
INTERVAL_COVERAGE = 0.8

# сезонность спроса по месяцам, синхронизирована с генератором (Conjura MMM)
_MONTH_RAW = [0.85, 0.80, 0.90, 0.95, 1.00, 0.90, 0.85, 0.85, 0.80, 0.95, 1.30, 1.50]
_MONTH_MEAN = sum(_MONTH_RAW) / 12
MONTH_FACTORS = [value / _MONTH_MEAN for value in _MONTH_RAW]


@dataclass
class Ensemble:
    version: str
    members: list[CatBoostRegressor]
    meta: dict


def seasonality_index(start_month: int, duration_days: int) -> float:
    months = max(1, math.ceil(duration_days / 30))
    values = [MONTH_FACTORS[(start_month - 1 + offset) % 12] for offset in range(months)]
    return sum(values) / len(values)


def _common(record: dict) -> dict:
    """Приводит обучающую запись и запрос прогноза к единому виду признаков."""
    observed = record.get("observed")
    if observed is not None:
        hist_days = float(observed["days"])
        cost = float(observed["cost_to_date"])
        revenue = float(observed["revenue_to_date"])
        cost7 = float(observed["cost_last7"])
        revenue7 = float(observed["revenue_last7"])
    else:
        hist_days = float(record.get("cutoff_days", 0))
        cost = float(record.get("cost_to_date", 0.0))
        revenue = float(record.get("revenue_to_date", 0.0))
        cost7 = float(record.get("cost_last7", 0.0))
        revenue7 = float(record.get("revenue_last7", 0.0))
    budget = float(record["budget"])
    has_history = 1.0 if hist_days > 0 else 0.0
    return {
        "platform": record["platform"],
        "budget": budget,
        "duration_days": float(record["duration_days"]),
        "start_month": float(record["start_month"]),
        "seasonality_index": seasonality_index(int(record["start_month"]), int(record["duration_days"])),
        "day_budget_planned": budget / float(record["duration_days"]),
        "has_history": has_history,
        "hist_days": hist_days,
        "hist_ratio": revenue / cost if cost > 0 else 0.0,
        "hist_ratio_last7": revenue7 / cost7 if cost7 > 0 else 0.0,
        "hist_spent_fraction": cost / budget if cost > 0 else 0.0,
        "hist_daily_cost": cost / hist_days if hist_days > 0 else 0.0,
        "hist_daily_revenue": revenue / hist_days if hist_days > 0 else 0.0,
    }


def _pool(records: list[dict], with_target: bool) -> Pool:
    data = [[_common(record)[feature] for feature in FEATURES] for record in records]
    kwargs = {"cat_features": [FEATURES.index(name) for name in CATEGORICAL]}
    if with_target:
        kwargs["label"] = [record[TARGET] for record in records]
    return Pool(data=data, **kwargs)


def _fit_member(pool: Pool, seed: int, records_count: int) -> CatBoostRegressor:
    indices = np.random.default_rng(seed).integers(0, records_count, records_count)
    model = CatBoostRegressor(
        iterations=300,
        depth=6,
        learning_rate=0.05,
        loss_function="RMSE",
        random_seed=seed,
        verbose=False,
    )
    model.fit(pool.slice(indices))
    return model


def _metrics(actual: np.ndarray, predicted: np.ndarray) -> dict:
    mape = float(np.mean(np.abs((actual - predicted) / actual)) * 100)
    r2 = float(1 - np.sum((actual - predicted) ** 2) / np.sum((actual - actual.mean()) ** 2))
    mae = float(np.mean(np.abs(actual - predicted)))
    return {"mape": round(mape, 2), "r2": round(r2, 4), "mae": round(mae, 4)}


def train_ensemble(
    records: list[dict],
    version: str,
    ensemble_size: int = ENSEMBLE_SIZE,
    holdout_fraction: float = HOLDOUT_FRACTION,
) -> Ensemble:
    # holdout по кампаниям, а не по записям: отсечки одной кампании не должны
    # оказаться по разные стороны разбиения, иначе метрики завышены
    campaign_ids = np.array(sorted({record["campaign_id"] for record in records}))
    rng = np.random.default_rng(RANDOM_SEED)
    rng.shuffle(campaign_ids)
    holdout_count = max(5, int(len(campaign_ids) * holdout_fraction))
    holdout_ids = set(campaign_ids[:holdout_count].tolist())
    train_records = [record for record in records if record["campaign_id"] not in holdout_ids]
    holdout_records = [record for record in records if record["campaign_id"] in holdout_ids]

    pool = _pool(train_records, with_target=True)
    members = [_fit_member(pool, RANDOM_SEED + offset, len(train_records)) for offset in range(ensemble_size)]

    holdout_pool = _pool(holdout_records, with_target=False)
    member_predictions = np.array([member.predict(holdout_pool) for member in members])
    mean_predictions = member_predictions.mean(axis=0)
    actual = np.array([record[TARGET] for record in holdout_records])

    metrics = _metrics(actual, mean_predictions)
    with_history = np.array([record["cutoff_days"] > 0 for record in holdout_records])
    metrics_history = _metrics(actual[with_history], mean_predictions[with_history]) if with_history.any() else None
    metrics_planned = _metrics(actual[~with_history], mean_predictions[~with_history]) if (~with_history).any() else None

    # калибровка интервала: во сколько раз членский разброс надо увеличить,
    # чтобы интервал ±k·spread покрывал заданную долю фактических ошибок
    spread = member_predictions.std(axis=0)
    errors = np.abs(actual - mean_predictions)
    ratios = errors / np.maximum(spread, 1e-6)
    calibration = float(np.quantile(ratios, INTERVAL_COVERAGE))
    absolute_floor = float(np.quantile(errors, INTERVAL_COVERAGE))

    budgets = np.array([record["budget"] for record in train_records])
    day_budgets = np.array([record["budget"] / record["duration_days"] for record in train_records])
    platforms = sorted({str(record["platform"]) for record in train_records})
    meta = {
        "name": "adforecast_roi",
        "version": version,
        "trained_at": datetime.now(timezone.utc).isoformat(),
        "features": FEATURES,
        "target": TARGET,
        "ensemble_size": ensemble_size,
        "holdout_size": len(holdout_records),
        # границы бюджета обучающих данных: поиск окупаемого бюджета не выходит за них,
        # иначе прогноз становится экстраполяцией и совет ненадёжен
        "budget_p05": round(float(np.quantile(budgets, 0.05)), 2),
        "budget_p95": round(float(np.quantile(budgets, 0.95)), 2),
        # границы дневного бюджета: вне них экономика канала в данных не наблюдалась,
        # прогноз был бы экстраполяцией
        "day_budget_p05": round(float(np.quantile(day_budgets, 0.05)), 2),
        "day_budget_p95": round(float(np.quantile(day_budgets, 0.95)), 2),
        # каналы, встречавшиеся в обучении: по незнакомому каналу прогноз недостоверен
        "platforms": platforms,
        **metrics,
        "mape_with_history": None if metrics_history is None else metrics_history["mape"],
        "r2_with_history": None if metrics_history is None else metrics_history["r2"],
        "mape_planned": None if metrics_planned is None else metrics_planned["mape"],
        "r2_planned": None if metrics_planned is None else metrics_planned["r2"],
        "interval_calibration": round(calibration, 3),
        "interval_absolute_floor": round(absolute_floor, 4),
    }
    return Ensemble(version=version, members=members, meta=meta)


def predict_ensemble(ensemble: Ensemble, record: dict) -> dict:
    pool = _pool([record], with_target=False)
    member_values = np.array([float(member.predict(pool)[0]) for member in ensemble.members])
    mean = float(member_values.mean())
    spread = float(member_values.std())
    tolerance = max(0.1 * mean, 0.05)
    agreement = float(np.mean(np.abs(member_values - mean) <= tolerance))
    calibration = ensemble.meta.get("interval_calibration", 2.0)
    floor = ensemble.meta.get("interval_absolute_floor", 0.1)
    half_width = max(calibration * spread, 0.25 * floor)
    return {
        "payback_ratio": round(mean, 4),
        "member_spread": round(spread, 4),
        "agreement": round(agreement, 2),
        "interval_lower": round(max(0.0, mean - half_width), 4),
        "interval_upper": round(mean + half_width, 4),
    }


def payback_days_planned(ratio: float, duration_days: int) -> float | None:
    """Срок окупаемости планируемой кампании по траектории накопленного потока
    с лагом дохода L = 0.5 · D (формула 1.3 ПЗ)."""
    if ratio <= 1.0:
        return None
    lag = LAG_FRACTION * duration_days
    inside_campaign = ratio * lag / (ratio - 1.0)
    if inside_campaign <= duration_days:
        return inside_campaign
    return duration_days / ratio + lag


def payback_days_observed(ratio: float, duration_days: int, budget: float, observed: dict) -> float | None:
    """Срок окупаемости активной кампании: достраиваем поток от текущего состояния,
    считая, что плановый темп расхода сохранится до конца кампании."""
    hist_days = int(observed["days"])
    cost = float(observed["cost_to_date"])
    revenue = float(observed["revenue_to_date"])
    if revenue >= cost > 0:
        return float(hist_days)
    remaining_days = duration_days - hist_days
    if remaining_days <= 0:
        return None
    daily_cost = budget / duration_days
    total_cost = cost + daily_cost * remaining_days
    total_revenue = ratio * total_cost
    remaining_revenue = max(0.0, total_revenue - revenue)
    daily_revenue = remaining_revenue / remaining_days
    if daily_revenue <= daily_cost:
        return None
    gap = cost - revenue
    days_to_close = gap / (daily_revenue - daily_cost)
    payback = hist_days + days_to_close
    return payback if payback <= duration_days else None


def platform_unknown(ensemble: Ensemble, record: dict) -> bool:
    """Канал, которого не было в обучающих данных: модель о нём ничего не знает."""
    known = ensemble.meta.get("platforms")
    if not known:
        return False
    return str(record["platform"]) not in known


def day_budget_bounds(ensemble: Ensemble) -> tuple[float, float]:
    """Границы дневного бюджета, наблюдавшиеся в обучающих данных."""
    low = float(ensemble.meta.get("day_budget_p05") or 0.0)
    high = float(ensemble.meta.get("day_budget_p95") or 0.0)
    return low, high


def budget_out_of_range(ensemble: Ensemble, record: dict) -> bool:
    """Бюджет вне области, на которой обучалась модель.

    Допуск: ниже 40 % от пятого процентиля или выше 250 % от девяносто пятого —
    тогда это заведомо экстраполяция, и честный ответ — не прогнозировать.
    """
    low, high = day_budget_bounds(ensemble)
    if low <= 0 or high <= 0:
        return False
    day_budget = float(record["budget"]) / float(record["duration_days"])
    return day_budget < low * 0.4 or day_budget > high * 2.5


def profitable_budget_range(ensemble: Ensemble, record: dict) -> tuple[float | None, float | None, bool]:
    """Окно бюджета, при котором прогноз окупаемости >= 1 (тот же канал, длительность,
    месяц и состояние кампании). Возвращает (минимум, максимум, окупается_везде):
    окупается_везде = True означает, что вся проверенная область бюджетов окупается,
    оба None при False — окна окупаемости нет. Поиск не выходит за границы бюджета
    обучающих данных, иначе это была бы экстраполяция."""
    lower_bound = max(float(ensemble.meta.get("budget_p05") or 500.0), 100.0)
    upper_bound = max(float(ensemble.meta.get("budget_p95") or 150000.0), lower_bound * 1.2)
    grid = np.geomspace(lower_bound, upper_bound, 24)
    probes = []
    for budget in grid:
        probe = dict(record)
        probe["budget"] = float(budget)
        probes.append(probe)
    pool = _pool(probes, with_target=False)
    member_predictions = np.array([member.predict(pool) for member in ensemble.members])
    means = member_predictions.mean(axis=0)

    profitable = means >= 1.0
    if not profitable.any():
        return None, None, False
    if profitable.all():
        return None, None, True

    indices = np.where(profitable)[0]
    first, last = int(indices[0]), int(indices[-1])

    lower = None
    if first > 0:
        lo, hi = float(grid[first - 1]), float(grid[first])
        for _ in range(8):
            middle = math.sqrt(lo * hi)
            probe = dict(record)
            probe["budget"] = middle
            value = predict_ensemble(ensemble, probe)["payback_ratio"]
            if value >= 1.0:
                hi = middle
            else:
                lo = middle
        lower = round(hi, 2)

    upper = None
    if last < len(grid) - 1:
        lo, hi = float(grid[last]), float(grid[last + 1])
        for _ in range(8):
            middle = math.sqrt(lo * hi)
            probe = dict(record)
            probe["budget"] = middle
            value = predict_ensemble(ensemble, probe)["payback_ratio"]
            if value >= 1.0:
                lo = middle
            else:
                hi = middle
        upper = round(lo, 2)

    return lower, upper, False
