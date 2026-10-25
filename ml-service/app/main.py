from __future__ import annotations

from pathlib import Path

from fastapi import FastAPI, HTTPException

from . import schemas
from .service import ModelNotReadyError, MlService, TaskNotFoundError, VersionNotFoundError
from .store import ModelStore, model_metrics

MODELS_DIR = Path(__file__).resolve().parent.parent / "models"


def create_app() -> FastAPI:
    app = FastAPI(title="AdForecast ML Service", version="0.2.0")
    service = MlService(ModelStore(MODELS_DIR))

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.post("/train", status_code=202)
    def train(request: schemas.TrainRequest) -> schemas.TrainAcceptedResponse:
        records = [record.model_dump() for record in request.records]
        snapshot = None if request.dataset_snapshot is None else request.dataset_snapshot.model_dump()
        task_id = service.submit_training(
            records, request.ensemble_size, request.holdout_fraction, request.model_name, snapshot
        )
        return schemas.TrainAcceptedResponse(task_id=task_id, status="RUNNING")

    @app.get("/train/{task_id}")
    def train_status(task_id: str) -> schemas.TrainStatusResponse:
        try:
            task = service.training_status(task_id)
        except TaskNotFoundError:
            raise HTTPException(status_code=404, detail="задача не найдена")
        return schemas.TrainStatusResponse(
            task_id=task_id,
            status=task.status,
            model=None
            if task.model is None
            else schemas.ModelMetrics(**model_metrics(task.model, task.model.get("name", "adforecast_roi"))),
            error=task.error,
        )

    @app.post("/predict")
    def predict(request: schemas.PredictRequest) -> schemas.PredictResponse:
        try:
            result = service.predict(request.model_dump())
        except ModelNotReadyError as error:
            raise HTTPException(status_code=503, detail=str(error))
        except VersionNotFoundError as error:
            raise HTTPException(status_code=404, detail=f"версия модели не найдена: {error}")
        return schemas.PredictResponse(**result)

    @app.get("/models")
    def models() -> schemas.ModelListResponse:
        return schemas.ModelListResponse(models=[schemas.ModelMetrics(**meta) for meta in service.store.list()])

    @app.delete("/models/{model_name}/{version}", status_code=204)
    def delete_model(model_name: str, version: str) -> None:
        # отсутствующая/сиротская версия удалению уже не подлежит — считаем успехом
        try:
            service.store.delete(model_name, version)
        except ValueError:
            pass

    return app


app = create_app()
