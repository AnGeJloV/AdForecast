from __future__ import annotations

import json
import time

import pytest

from app.service import MlService, ModelNotReadyError, TaskNotFoundError, VersionNotFoundError
from app.store import ModelStore
from tests.conftest import make_records
from tests.test_store import tiny_ensemble


def wait_for_task(service: MlService, task_id: str, timeout: float = 60.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        task = service.training_status(task_id)
        if task.status != "RUNNING":
            return task
        time.sleep(0.2)
    raise AssertionError(f"обучение не завершилось за {timeout} с")


@pytest.fixture
def service(tmp_path) -> MlService:
    return MlService(ModelStore(tmp_path))


@pytest.fixture
def trained_service(service) -> MlService:
    task_id = service.submit_training(make_records(campaigns=20, cutoffs=2), 3, 0.2,
                                      "adforecast_roi", None)
    wait_for_task(service, task_id)
    return service


class TestPredict:
    def test_predict_fails_without_trained_models(self, service):
        request = {"platform": "VK Реклама", "budget": 10000.0, "duration_days": 60, "start_month": 11}

        with pytest.raises(ModelNotReadyError, match="нет обученных моделей"):
            service.predict(request)

    def test_predict_rejects_unknown_explicit_version(self, service):
        service.store.save(tiny_ensemble("v1"))
        request = {"platform": "VK Реклама", "budget": 10000.0, "duration_days": 60,
                   "start_month": 11, "model_version": "v9"}

        with pytest.raises(VersionNotFoundError):
            service.predict(request)

    def test_predict_after_training_returns_full_contract(self, trained_service):
        result = trained_service.predict({
            "platform": "VK Реклама", "budget": 10000.0, "duration_days": 60, "start_month": 11,
        })

        assert result["model_name"] == "adforecast_roi"
        assert result["model_version"] == "v1"
        assert result["payback_ratio"] > 0
        assert result["interval_lower"] <= result["payback_ratio"] <= result["interval_upper"]
        assert result["forecast_roi"] == round((result["payback_ratio"] - 1.0) * 100, 2)
        assert result["budget_out_of_range"] is False
        assert result["platform_unknown"] is False
        assert result["day_budget_min"] > 0

    def test_predict_marks_unknown_channel(self, trained_service):
        result = trained_service.predict({
            "platform": "Авито", "budget": 10000.0, "duration_days": 60, "start_month": 11,
        })

        assert result["platform_unknown"] is True

    def test_predict_unprofitable_plan_has_no_payback_days(self, service):
        task_id = service.submit_training(make_records(campaigns=20, cutoffs=2, base_ratio=0.5),
                                          2, 0.2, "adforecast_roi", None)
        wait_for_task(service, task_id)

        result = service.predict({
            "platform": "Telegram Ads", "budget": 5000.0, "duration_days": 30, "start_month": 1,
        })

        assert result["payback_within_duration"] is False

    def test_predict_active_campaign_returns_payback_from_observed_state(self, trained_service):
        result = trained_service.predict({
            "platform": "VK Реклама", "budget": 10000.0, "duration_days": 60, "start_month": 11,
            "observed": {"days": 8, "cost_to_date": 1000.0, "revenue_to_date": 1700.0,
                         "cost_last7": 800.0, "revenue_last7": 1300.0},
        })

        assert result["payback_days"] is not None


class TestTrainingTasks:
    def test_unknown_task_raises(self, service):
        with pytest.raises(TaskNotFoundError):
            service.training_status("нет-такой-задачи")

    def test_failed_training_reports_error(self, service):
        broken = make_records(campaigns=20, cutoffs=2)
        broken[0]["budget"] = "не число"

        task_id = service.submit_training(broken, 2, 0.2, "adforecast_roi", None)
        task = wait_for_task(service, task_id)

        assert task.status == "FAILED"
        assert task.error

    def test_snapshot_is_attached_to_saved_meta(self, service, tmp_path):
        snapshot = {"campaign_ids": [1, 2, 3], "rule": "правило", "hash": "abc"}
        task_id = service.submit_training(make_records(campaigns=20, cutoffs=2), 2, 0.2,
                                          "adforecast_roi", snapshot)
        wait_for_task(service, task_id)

        meta = json.loads((tmp_path / "adforecast_roi" / "v1" / "meta.json").read_text(encoding="utf-8"))
        assert meta["dataset_snapshot"]["hash"] == "abc"
        assert meta["name"] == "adforecast_roi"

    def test_organization_family_is_stored_separately(self, service):
        task_id = service.submit_training(make_records(campaigns=20, cutoffs=2), 2, 0.2,
                                          "adforecast_org_1", None)
        wait_for_task(service, task_id)

        assert service.store.latest("adforecast_org_1") == "v1"
        assert service.store.latest("adforecast_roi") is None
