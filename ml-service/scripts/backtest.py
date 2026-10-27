"""Бэктест модели: обучение на 70 % кампаний, проверка на 30 % отложенных.

Метрики считаются раздельно для плановых записей (без истории) и записей
с историей: у них разные калибровки интервала и разная точность. Результат
пишется в scripts/backtest-results.json для раздела 4 пояснительной записки.
"""

from __future__ import annotations

import argparse
import csv
import json
import time
from pathlib import Path

import numpy as np

from app.model import INTERVAL_COVERAGE, _pool, train_ensemble


def load_records(path: Path) -> list[dict]:
    records = []
    with path.open(encoding="utf-8") as source:
        for row in csv.DictReader(source):
            records.append({
                "campaign_id": int(row["campaign_id"]),
                "platform": row["platform"],
                "budget": float(row["budget"]),
                "duration_days": int(row["duration_days"]),
                "start_month": int(row["start_month"]),
                "cutoff_days": int(row["cutoff_days"]),
                "cost_to_date": float(row["cost_to_date"]),
                "revenue_to_date": float(row["revenue_to_date"]),
                "cost_last7": float(row["cost_last7"]),
                "revenue_last7": float(row["revenue_last7"]),
                "target_ratio": float(row["target_ratio"]),
            })
    return records


def split_by_campaigns(records: list[dict], holdout_fraction: float, seed: int = 42):
    campaign_ids = np.array(sorted({record["campaign_id"] for record in records}))
    rng = np.random.default_rng(seed)
    rng.shuffle(campaign_ids)
    holdout_count = max(1, int(round(len(campaign_ids) * holdout_fraction)))
    holdout_ids = set(campaign_ids[:holdout_count].tolist())
    train = [record for record in records if record["campaign_id"] not in holdout_ids]
    holdout = [record for record in records if record["campaign_id"] in holdout_ids]
    return train, holdout, len(campaign_ids) - holdout_count, holdout_count


def verdict_of(lower: float, upper: float) -> str:
    if lower >= 1.0:
        return "PROFITABLE"
    if upper < 1.0:
        return "UNPROFITABLE"
    return "BORDERLINE"


def evaluate(mean: np.ndarray, spread: np.ndarray, actual: np.ndarray,
             calibration: dict) -> dict:
    half = np.maximum(calibration["scale"] * spread, 0.25 * calibration["floor"])
    lower = np.maximum(0.0, mean - half)
    upper = mean + half

    covered = (actual >= lower) & (actual <= upper)
    predicted_ratio = mean
    direction = (predicted_ratio >= 1.0) == (actual >= 1.0)
    confident = (lower >= 1.0) | (upper < 1.0)
    confident_correct = ((lower >= 1.0) & (actual >= 1.0)) | ((upper < 1.0) & (actual < 1.0))

    errors = np.abs(actual - mean)
    denominator = np.sum((actual - actual.mean()) ** 2)
    return {
        "count": int(len(actual)),
        "mape": round(float(np.mean(errors / actual) * 100), 2),
        "r2": round(float(1 - np.sum((actual - mean) ** 2) / denominator), 4),
        "mae": round(float(np.mean(errors)), 4),
        "interval_coverage": round(float(covered.mean()) * 100, 1),
        "direction_accuracy": round(float(direction.mean()) * 100, 1),
        "confident_share": round(float(confident.mean()) * 100, 1),
        "confident_accuracy": round(float(confident_correct[confident].mean()) * 100, 1)
        if confident.any() else None,
        "correlation": round(float(np.corrcoef(actual, mean)[0, 1]), 4),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Бэктест ансамбля на отложенных кампаниях")
    parser.add_argument("--records", type=Path, required=True, help="CSV с отсечками генератора")
    parser.add_argument("--ensemble-size", type=int, default=10)
    parser.add_argument("--holdout-fraction", type=float, default=0.3,
                        help="доля кампаний, откладываемая для проверки")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--output", type=Path, default=Path("scripts/backtest-results.json"))
    args = parser.parse_args()

    records = load_records(args.records)
    train, holdout, train_campaigns, holdout_campaigns = split_by_campaigns(
        records, args.holdout_fraction, args.seed)
    print(f"записей всего {len(records)}: обучение {len(train)} ({train_campaigns} кампаний), "
          f"проверка {len(holdout)} ({holdout_campaigns} кампаний)")

    started = time.perf_counter()
    ensemble = train_ensemble(train, "backtest", args.ensemble_size, 0.15)
    training_seconds = time.perf_counter() - started
    print(f"обучение: {training_seconds:.1f} с, калибровка по внутренней выборке "
          f"история {ensemble.meta['interval_calibration_history']}, "
          f"план {ensemble.meta['interval_calibration_planned']}")

    holdout_pool = _pool(holdout, with_target=False)
    predict_started = time.perf_counter()
    member_predictions = np.array([member.predict(holdout_pool) for member in ensemble.members])
    predict_seconds = time.perf_counter() - predict_started

    mean = member_predictions.mean(axis=0)
    spread = member_predictions.std(axis=0)
    actual = np.array([record["target_ratio"] for record in holdout])
    planned = np.array([record["cutoff_days"] == 0 for record in holdout])

    calibration = {
        "history": {"scale": ensemble.meta["interval_calibration_history"],
                    "floor": ensemble.meta["interval_floor_history"]},
        "planned": {"scale": ensemble.meta["interval_calibration_planned"],
                    "floor": ensemble.meta["interval_floor_planned"]},
    }

    modes = {}
    for name, mask in (("plan", planned), ("history", ~planned)):
        mode_calibration = calibration["planned" if name == "plan" else "history"]
        modes[name] = evaluate(mean[mask], spread[mask], actual[mask], mode_calibration)
    overall = evaluate(mean, spread, actual, calibration["history"])

    report = {
        "records_file": str(args.records),
        "records_total": len(records),
        "train_records": len(train),
        "holdout_records": len(holdout),
        "train_campaigns": train_campaigns,
        "holdout_campaigns": holdout_campaigns,
        "ensemble_size": args.ensemble_size,
        "training_seconds": round(training_seconds, 1),
        "batch_prediction_seconds": round(predict_seconds, 3),
        "prediction_ms_per_record": round(predict_seconds / len(holdout) * 1000, 2),
        "target_coverage": INTERVAL_COVERAGE,
        "internal_calibration": {
            "history": ensemble.meta["interval_calibration_history"],
            "planned": ensemble.meta["interval_calibration_planned"],
        },
        "overall": overall,
        "plan": modes["plan"],
        "history": modes["history"],
    }
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    print()
    print(f"{'выборка':12} {'n':>5} {'MAPE':>6} {'R2':>6} {'MAE':>6} {'покрытие':>9} "
          f"{'направление':>12} {'уверенных':>10} {'точность ув.':>12}")
    rows = [("весь holdout", overall), ("план", modes["plan"]), ("с историей", modes["history"])]
    for name, metrics in rows:
        confident = f"{metrics['confident_accuracy']:.1f}" if metrics["confident_accuracy"] else "—"
        print(f"{name:12} {metrics['count']:>5} {metrics['mape']:>6} {metrics['r2']:>6} "
              f"{metrics['mae']:>6} {metrics['interval_coverage']:>8}% "
              f"{metrics['direction_accuracy']:>11}% {metrics['confident_share']:>9}% {confident:>11}%")
    print(f"\nвремя прогноза: {report['prediction_ms_per_record']} мс/запись "
          f"(требование — не более 60 с на прогноз кампании)")
    print(f"результат: {args.output}")


if __name__ == "__main__":
    main()