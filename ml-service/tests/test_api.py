from __future__ import annotations

import time

import pytest
from fastapi.testclient import TestClient

import app.main as main_module
from app.main import create_app
from tests.conftest import make_records


@pytest.fixture
def client(tmp_path, monkeypatch) -> TestClient:
    monkeypatch.setattr(main_module, "MODELS_DIR", tmp_path)
    return TestClient(create_app())


def wait_completed(client: TestClient, task_id: str, timeout: float = 240.0) -> dict:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        response = client.get(f"/train/{task_id}")
        body = response.json()
        if body["status"] != "RUNNING":
            return body
        time.sleep(0.2)
    raise AssertionError("обучение не завершилось за отведенное время")


class TestHealth:
    def test_health_reports_ok(self, client):
        response = client.get("/health")

        assert response.status_code == 200
        assert response.json() == {"status": "ok"}


class TestTrainApi:
    def test_train_requires_at_least_forty_records(self, client):
        response = client.post("/train", json={"records": make_records(campaigns=20, cutoffs=2)[:39]})

        assert response.status_code == 422

    def test_train_rejects_bad_record_values(self, client):
        records = make_records(campaigns=20, cutoffs=2)
        records[0]["budget"] = -5.0

        response = client.post("/train", json={"records": records})

        assert response.status_code == 422

    def test_train_rejects_invalid_model_name(self, client):
        response = client.post("/train", json={
            "records": make_records(campaigns=20, cutoffs=2), "model_name": "плохое имя"})

        assert response.status_code == 422

    def test_train_accepts_task_and_reports_completion(self, client):
        accepted = client.post("/train", json={"records": make_records(campaigns=20, cutoffs=2),
                                               "ensemble_size": 3})

        assert accepted.status_code == 202
        assert accepted.json()["status"] == "RUNNING"
        task_id = accepted.json()["task_id"]

        status = wait_completed(client, task_id)

        assert status["status"] == "COMPLETED"
        model = status["model"]
        assert model["model_name"] == "adforecast_roi"
        assert model["version"] == "v1"
        assert model["ensemble_size"] == 3
        assert model["mape_with_history"] is not None

    def test_unknown_task_returns_404(self, client):
        response = client.get("/train/нет-такой")

        assert response.status_code == 404

    def test_models_lists_trained_versions(self, client):
        wait_completed(client, client.post(
            "/train", json={"records": make_records(campaigns=20, cutoffs=2), "ensemble_size": 3})
            .json()["task_id"])

        response = client.get("/models")

        assert response.status_code == 200
        models = response.json()["models"]
        assert len(models) == 1
        assert models[0]["version"] == "v1"
        assert len(models[0]["features"]) == 13


class TestPredictApi:
    def test_predict_without_models_is_service_unavailable(self, client):
        response = client.post("/predict", json={
            "platform": "VK Реклама", "budget": 10000.0, "duration_days": 60, "start_month": 11})

        assert response.status_code == 503

    def test_predict_returns_full_response(self, client):
        wait_completed(client, client.post(
            "/train", json={"records": make_records(campaigns=20, cutoffs=2), "ensemble_size": 3})
            .json()["task_id"])

        response = client.post("/predict", json={
            "platform": "VK Реклама", "budget": 10000.0, "duration_days": 60, "start_month": 11,
            "observed": {"days": 8, "cost_to_date": 1000.0, "revenue_to_date": 1700.0,
                         "cost_last7": 800.0, "revenue_last7": 1300.0}})

        assert response.status_code == 200
        body = response.json()
        assert body["model_version"] == "v1"
        assert body["interval_lower"] <= body["payback_ratio"] <= body["interval_upper"]
        assert body["payback_days"] is not None
        assert body["payback_within_duration"] is True
        assert body["budget_out_of_range"] is False
        assert body["platform_unknown"] is False

    def test_predict_unknown_version_returns_404(self, client):
        wait_completed(client, client.post(
            "/train", json={"records": make_records(campaigns=20, cutoffs=2), "ensemble_size": 3})
            .json()["task_id"])

        response = client.post("/predict", json={
            "platform": "VK Реклама", "budget": 10000.0, "duration_days": 60, "start_month": 11,
            "model_version": "v9"})

        assert response.status_code == 404
        assert "v9" in response.json()["detail"]

    def test_predict_validates_input(self, client):
        response = client.post("/predict", json={
            "platform": "VK Реклама", "budget": 0, "duration_days": 60, "start_month": 11})

        assert response.status_code == 422


class TestDeleteApi:
    def test_delete_removes_version_and_ignores_missing(self, client):
        wait_completed(client, client.post(
            "/train", json={"records": make_records(campaigns=20, cutoffs=2), "ensemble_size": 3})
            .json()["task_id"])

        removed = client.delete("/models/adforecast_roi/v1")
        missing = client.delete("/models/adforecast_roi/v1")
        again = client.delete("/models/adforecast_roi/v9")

        assert removed.status_code == 204
        assert missing.status_code == 204
        assert again.status_code == 204
        assert client.get("/models").json()["models"] == []
