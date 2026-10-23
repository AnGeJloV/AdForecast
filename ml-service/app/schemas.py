from pydantic import BaseModel, Field


class TrainRecord(BaseModel):
    """Обучающая запись «на отсечке»: состояние кампании в день cutoff_days
    и итоговая окупаемость всей кампании как целевая переменная.
    cutoff_days = 0 соответствует планируемой кампании без истории.
    """

    campaign_id: int
    platform: str
    budget: float = Field(gt=0)
    duration_days: int = Field(gt=0, le=365)
    start_month: int = Field(ge=1, le=12)
    cutoff_days: int = Field(ge=0)
    cost_to_date: float = Field(ge=0)
    revenue_to_date: float = Field(ge=0)
    cost_last7: float = Field(ge=0)
    revenue_last7: float = Field(ge=0)
    target_ratio: float = Field(ge=0)


class DatasetSnapshot(BaseModel):
    campaign_ids: list[int]
    rule: str
    hash: str


class TrainRequest(BaseModel):
    records: list[TrainRecord] = Field(min_length=40)
    ensemble_size: int = Field(default=10, ge=2, le=50)
    holdout_fraction: float = Field(default=0.2, gt=0, lt=0.5)
    model_name: str = Field(default="adforecast_roi", pattern=r"^[A-Za-z0-9_-]{1,64}$")
    dataset_snapshot: DatasetSnapshot | None = None


class TrainAcceptedResponse(BaseModel):
    task_id: str
    status: str


class ModelMetrics(BaseModel):
    model_name: str
    version: str
    trained_at: str
    mape: float
    r2: float
    mae: float
    holdout_size: int
    ensemble_size: int
    features: list[str]
    mape_with_history: float | None = None
    r2_with_history: float | None = None
    mape_planned: float | None = None
    r2_planned: float | None = None
    interval_calibration: float | None = None
    dataset_snapshot: DatasetSnapshot | None = None


class TrainStatusResponse(BaseModel):
    task_id: str
    status: str
    model: ModelMetrics | None = None
    error: str | None = None


class ObservedState(BaseModel):
    """Наблюдаемое состояние кампании на дату прогноза."""

    days: int = Field(ge=1, le=365)
    cost_to_date: float = Field(ge=0)
    revenue_to_date: float = Field(ge=0)
    cost_last7: float = Field(ge=0)
    revenue_last7: float = Field(ge=0)


class PredictRequest(BaseModel):
    platform: str
    budget: float = Field(gt=0)
    duration_days: int = Field(gt=0, le=365)
    start_month: int = Field(ge=1, le=12)
    observed: ObservedState | None = None
    model_name: str = Field(default="adforecast_roi", pattern=r"^[A-Za-z0-9_-]{1,64}$")
    model_version: str | None = None


class PredictResponse(BaseModel):
    model_name: str
    model_version: str
    payback_ratio: float
    forecast_roi: float
    interval_lower: float
    interval_upper: float
    agreement: float
    member_spread: float
    payback_days: float | None
    payback_months: float | None
    payback_within_duration: bool
    profitable_budget_min: float | None
    profitable_budget_max: float | None
    profitable_at_any_budget: bool
    budget_out_of_range: bool
    day_budget_min: float
    day_budget_max: float
    platform_unknown: bool


class ModelListResponse(BaseModel):
    models: list[ModelMetrics]
