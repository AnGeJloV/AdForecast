from __future__ import annotations

import threading
import uuid
from dataclasses import dataclass, field

from .model import (
    Ensemble,
    DAYS_PER_MONTH,
    budget_out_of_range,
    day_budget_bounds,
    platform_unknown,
    payback_days_observed,
    payback_days_planned,
    predict_ensemble,
    profitable_budget_range,
    train_ensemble,
)
from .store import DEFAULT_MODEL_NAME, ModelStore


class ModelNotReadyError(RuntimeError):
    pass


class VersionNotFoundError(RuntimeError):
    pass


class TaskNotFoundError(RuntimeError):
    pass


@dataclass
class TrainingTask:
    status: str
    model: dict | None = None
    error: str | None = None


@dataclass
class MlService:
    store: ModelStore
    _tasks: dict[str, TrainingTask] = field(default_factory=dict)
    _lock: threading.Lock = field(default_factory=threading.Lock)

    def submit_training(
        self,
        records: list[dict],
        ensemble_size: int,
        holdout_fraction: float,
        model_name: str,
        snapshot: dict | None,
    ) -> str:
        task_id = uuid.uuid4().hex[:12]
        with self._lock:
            self._tasks[task_id] = TrainingTask(status="RUNNING")
        thread = threading.Thread(
            target=self._run_training,
            args=(task_id, records, ensemble_size, holdout_fraction, model_name, snapshot),
            daemon=True,
        )
        thread.start()
        return task_id

    def training_status(self, task_id: str) -> TrainingTask:
        task = self._tasks.get(task_id)
        if task is None:
            raise TaskNotFoundError(task_id)
        return task

    def _run_training(
        self,
        task_id: str,
        records: list[dict],
        ensemble_size: int,
        holdout_fraction: float,
        model_name: str,
        snapshot: dict | None,
    ) -> None:
        try:
            ensemble = train_ensemble(records, self.store.next_version(model_name), ensemble_size, holdout_fraction)
            ensemble.meta["name"] = model_name
            if snapshot is not None:
                ensemble.meta["dataset_snapshot"] = snapshot
            self.store.save(ensemble, model_name)
            with self._lock:
                self._tasks[task_id] = TrainingTask(status="COMPLETED", model=ensemble.meta)
        except Exception as error:
            with self._lock:
                self._tasks[task_id] = TrainingTask(status="FAILED", error=str(error))

    def predict(self, request: dict) -> dict:
        model_name = request.get("model_name") or DEFAULT_MODEL_NAME
        version = request.get("model_version") or self.store.latest(model_name)
        if version is None:
            raise ModelNotReadyError(f"нет обученных моделей в семействе {model_name}")
        if version not in self.store.versions(model_name):
            raise VersionNotFoundError(version)
        ensemble = self.store.load(version, model_name)
        prediction = predict_ensemble(ensemble, request)

        duration = int(request["duration_days"])
        observed = request.get("observed")
        if observed is not None:
            payback_days = payback_days_observed(prediction["payback_ratio"], duration, request["budget"], observed)
        else:
            payback_days = payback_days_planned(prediction["payback_ratio"], duration)
        unknown_platform = platform_unknown(ensemble, request)
        out_of_range = budget_out_of_range(ensemble, request)
        if out_of_range:
            # вне области данных искать окно бюджета бессмысленно
            budget_min, budget_max, budget_any = None, None, False
        else:
            budget_min, budget_max, budget_any = profitable_budget_range(ensemble, request)
        day_min, day_max = day_budget_bounds(ensemble)

        return {
            "model_name": model_name,
            "model_version": version,
            "payback_ratio": prediction["payback_ratio"],
            "forecast_roi": round((prediction["payback_ratio"] - 1.0) * 100, 2),
            "interval_lower": prediction["interval_lower"],
            "interval_upper": prediction["interval_upper"],
            "agreement": prediction["agreement"],
            "member_spread": prediction["member_spread"],
            "payback_days": None if payback_days is None else round(payback_days, 1),
            "payback_months": None if payback_days is None else round(payback_days / DAYS_PER_MONTH, 2),
            "payback_within_duration": payback_days is not None and payback_days <= duration,
            "profitable_budget_min": budget_min,
            "profitable_budget_max": budget_max,
            "profitable_at_any_budget": budget_any,
            "budget_out_of_range": out_of_range,
            "day_budget_min": day_min,
            "day_budget_max": day_max,
            "platform_unknown": unknown_platform,
        }
