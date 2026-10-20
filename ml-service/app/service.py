from __future__ import annotations

import threading
import uuid
from dataclasses import dataclass, field

from .model import Ensemble, predict_ensemble_from, train_ensemble
from .store import ModelStore


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

    def submit_training(self, records: list[dict], ensemble_size: int, holdout_fraction: float) -> str:
        task_id = uuid.uuid4().hex[:12]
        with self._lock:
            self._tasks[task_id] = TrainingTask(status="RUNNING")
        thread = threading.Thread(
            target=self._run_training,
            args=(task_id, records, ensemble_size, holdout_fraction),
            daemon=True,
        )
        thread.start()
        return task_id

    def training_status(self, task_id: str) -> TrainingTask:
        task = self._tasks.get(task_id)
        if task is None:
            raise TaskNotFoundError(task_id)
        return task

    def _run_training(self, task_id: str, records: list[dict], ensemble_size: int, holdout_fraction: float) -> None:
        try:
            ensemble = train_ensemble(records, self.store.next_version(), ensemble_size, holdout_fraction)
            self.store.save(ensemble)
            with self._lock:
                self._tasks[task_id] = TrainingTask(status="COMPLETED", model=ensemble.meta)
        except Exception as error:
            with self._lock:
                self._tasks[task_id] = TrainingTask(status="FAILED", error=str(error))

    def predict(self, campaign: dict) -> dict:
        version = campaign.get("model_version") or self.store.latest()
        if version is None:
            raise ModelNotReadyError("нет обученных моделей")
        if version not in self.store.versions():
            raise VersionNotFoundError(version)
        ensemble = self.store.load(version)
        result = predict_ensemble_from(ensemble.members, [campaign])[0]
        horizon = campaign["horizon_days"]
        payback_days = result["payback_days"]
        return {
            "model_version": version,
            "payback_ratio": result["payback_ratio"],
            "forecast_roi": round((result["payback_ratio"] - 1.0) * 100, 2),
            "payback_months": result["payback_months"],
            "payback_days": payback_days,
            "payback_within_horizon": payback_days is not None and payback_days <= horizon,
            "payback_probability": result["payback_probability"],
        }
