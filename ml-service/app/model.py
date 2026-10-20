from __future__ import annotations

import math
from dataclasses import dataclass
from datetime import datetime, timezone

import numpy as np
from catboost import CatBoostRegressor, Pool

FEATURES = ["platform", "budget", "duration_days", "start_month", "seasonality_index"]
CATEGORICAL = ["platform"]
TARGET = "payback_ratio"
ENSEMBLE_SIZE = 10
HOLDOUT_FRACTION = 0.2
RANDOM_SEED = 42
DAYS_PER_MONTH = 30.44
LAG_FRACTION = 0.5


@dataclass
class Ensemble:
    version: str
    members: list[CatBoostRegressor]
    meta: dict


MONTH_FACTORS = [0.85, 0.85, 0.95, 1.00, 1.05, 0.95, 0.90, 0.90, 1.00, 1.10, 1.25, 1.30]


def seasonality_index(start_month: int, duration_days: int) -> float:
    months = max(1, math.ceil(duration_days / 30))
    values = [MONTH_FACTORS[(start_month - 1 + offset) % 12] for offset in range(months)]
    return sum(values) / len(values)


def _pool(records: list[dict], with_target: bool) -> Pool:
    data = [
        [record[feature] for feature in FEATURES[:-1]] + [seasonality_index(record["start_month"], record["duration_days"])]
        for record in records
    ]
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


def _metrics(actual: list[float], predicted: list[float]) -> dict:
    actual_arr = np.asarray(actual)
    predicted_arr = np.asarray(predicted)
    mape = float(np.mean(np.abs((actual_arr - predicted_arr) / actual_arr)) * 100)
    r2 = float(1 - np.sum((actual_arr - predicted_arr) ** 2) / np.sum((actual_arr - actual_arr.mean()) ** 2))
    mae = float(np.mean(np.abs(actual_arr - predicted_arr)))
    return {
        "mape": round(mape, 2),
        "r2": round(r2, 4),
        "mae": round(mae, 4),
        "holdout_size": len(actual),
    }


def train_ensemble(
    records: list[dict],
    version: str,
    ensemble_size: int = ENSEMBLE_SIZE,
    holdout_fraction: float = HOLDOUT_FRACTION,
) -> Ensemble:
    rng = np.random.default_rng(RANDOM_SEED)
    indices = rng.permutation(len(records))
    holdout_size = max(10, int(len(records) * holdout_fraction))
    holdout_positions = set(indices[:holdout_size].tolist())
    train_records = [record for i, record in enumerate(records) if i not in holdout_positions]
    holdout_records = [record for i, record in enumerate(records) if i in holdout_positions]

    pool = _pool(train_records, with_target=True)
    members = [_fit_member(pool, RANDOM_SEED + offset, len(train_records)) for offset in range(ensemble_size)]

    predictions = [predict_ensemble_from(members, [record])[0] for record in holdout_records]
    actual = [record[TARGET] for record in holdout_records]
    metrics = _metrics(actual, [p["payback_ratio"] for p in predictions])

    meta = {
        "name": "adforecast_roi",
        "version": version,
        "trained_at": datetime.now(timezone.utc).isoformat(),
        "features": FEATURES,
        "target": TARGET,
        "ensemble_size": ensemble_size,
        **metrics,
    }
    return Ensemble(version=version, members=members, meta=meta)


def predict_ensemble_from(members: list[CatBoostRegressor], records: list[dict]) -> list[dict]:
    pool = _pool(records, with_target=False)
    member_predictions = [np.atleast_1d(member.predict(pool)) for member in members]
    results = []
    for i, record in enumerate(records):
        values = [float(predictions[i]) for predictions in member_predictions]
        ratio = sum(values) / len(values)
        probability = sum(1 for value in values if value > 1.0) / len(values)
        days = payback_days(ratio, record["duration_days"])
        results.append(
            {
                "payback_ratio": round(ratio, 4),
                "payback_probability": round(probability, 4),
                "payback_days": None if days is None else round(days, 1),
                "payback_months": None if days is None else round(days / DAYS_PER_MONTH, 2),
            }
        )
    return results


def payback_days(ratio: float, duration_days: int) -> float | None:
    """Срок окупаемости по прогнозной траектории накопленного денежного потока.

    Расход распределен равномерно по дням кампании [0, D], доход начинает
    поступать с задержкой L = 0.5 * D и продолжается до D + L. Окупаемость
    достигается в точке пересечения накопленного дохода с накопленным расходом.
    """
    if ratio <= 1.0:
        return None
    lag = LAG_FRACTION * duration_days
    inside_campaign = ratio * lag / (ratio - 1.0)
    if inside_campaign <= duration_days:
        return inside_campaign
    return duration_days / ratio + lag
