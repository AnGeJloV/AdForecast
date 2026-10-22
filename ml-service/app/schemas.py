from pydantic import BaseModel, Field


class TrainRecord(BaseModel):
    platform: str
    budget: float = Field(gt=0)
    duration_days: int = Field(gt=0, le=365)
    start_month: int = Field(ge=1, le=12)
    payback_ratio: float = Field(ge=0)


class DatasetSnapshot(BaseModel):
    campaign_ids: list[int]
    rule: str
    hash: str


class TrainRequest(BaseModel):
    records: list[TrainRecord] = Field(min_length=50)
    ensemble_size: int = Field(default=10, ge=2, le=50)
    holdout_fraction: float = Field(default=0.2, gt=0, lt=0.5)
    dataset_snapshot: DatasetSnapshot | None = None


class TrainAcceptedResponse(BaseModel):
    task_id: str
    status: str


class ModelMetrics(BaseModel):
    version: str
    trained_at: str
    mape: float
    r2: float
    mae: float
    holdout_size: int
    ensemble_size: int
    features: list[str]
    dataset_snapshot: DatasetSnapshot | None = None


class TrainStatusResponse(BaseModel):
    task_id: str
    status: str
    model: ModelMetrics | None = None
    error: str | None = None


class PredictRequest(BaseModel):
    platform: str
    budget: float = Field(gt=0)
    duration_days: int = Field(gt=0, le=365)
    start_month: int = Field(ge=1, le=12)
    horizon_days: int = Field(default=90, gt=0, le=730)
    model_version: str | None = None


class PredictResponse(BaseModel):
    model_version: str
    payback_ratio: float
    forecast_roi: float
    payback_months: float | None
    payback_days: float | None
    payback_within_horizon: bool
    payback_probability: float


class ModelListResponse(BaseModel):
    models: list[ModelMetrics]
