"""Request/response models of the detection API (validated, size-bounded)."""

from __future__ import annotations

from datetime import datetime
from typing import Optional

from pydantic import BaseModel, ConfigDict, Field, field_validator

from .detection import Metric, Sensitivity

MAX_SERIES = 200
MAX_POINTS = 1_440


class SeriesPoint(BaseModel):
    model_config = ConfigDict(extra="forbid")

    t: datetime
    v: Optional[float] = None
    n: Optional[int] = Field(default=None, ge=0)


class Series(BaseModel):
    model_config = ConfigDict(extra="forbid")

    key: str = Field(min_length=1, max_length=300, description="Caller's identifier, echoed back")
    metric: Metric
    step_seconds: int = Field(default=60, ge=10, le=3_600)
    points: list[SeriesPoint] = Field(max_length=MAX_POINTS)

    @field_validator("points")
    @classmethod
    def ordered(cls, points: list[SeriesPoint]) -> list[SeriesPoint]:
        for a, b in zip(points, points[1:]):
            if b.t <= a.t:
                raise ValueError("points must be in strictly increasing time order")
        return points


class DetectRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    series: list[Series] = Field(max_length=MAX_SERIES)
    evaluation_points: int = Field(default=5, ge=1, le=60)
    sensitivity: Sensitivity = Sensitivity.MEDIUM


class AnomalyOut(BaseModel):
    key: str
    metric: Metric
    anomaly_type: str
    severity: str
    score: float
    observed: float
    expected: float
    spread: float
    window_start: datetime
    window_end: datetime
    baseline_points: int
    reason: str
    detector: str = "robust-zscore-v1"


class SkippedOut(BaseModel):
    key: str
    reason: str


class DetectResponse(BaseModel):
    anomalies: list[AnomalyOut]
    skipped: list[SkippedOut]
    evaluated: int
