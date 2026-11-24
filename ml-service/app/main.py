"""PulseOps ML service: stateless anomaly detection over metric series sent by the backend.

Holds no tenant data: the backend sends series, receives findings and stores them under
its own row-level security. Requests are authenticated with a shared secret (constant-time
compare) and size-limited. Run: ``uvicorn --factory app.main:create_app --host 127.0.0.1 --port 8500``.
"""

from __future__ import annotations

import hmac
import logging

from fastapi import Depends, FastAPI, Header, HTTPException

from . import __version__, service
from .config import Settings
from .limits import BodyLimitMiddleware
from .schemas import DetectRequest, DetectResponse

log = logging.getLogger("pulseops.ml")


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    if settings.allow_insecure:
        log.warning("PULSEOPS_ML_ALLOW_INSECURE=true: requests are NOT authenticated. Development only.")
    app = FastAPI(title="PulseOps ML service", version=__version__, docs_url=None, redoc_url=None,
                  openapi_url=None)

    app.add_middleware(BodyLimitMiddleware, max_bytes=settings.max_body_bytes)

    def authenticate(x_pulseops_ml_secret: str | None = Header(default=None)) -> None:
        if settings.allow_insecure:
            return
        presented = (x_pulseops_ml_secret or "").encode()
        if not hmac.compare_digest(presented, settings.shared_secret.encode()):
            raise HTTPException(status_code=401, detail="invalid or missing X-PulseOps-ML-Secret")

    @app.get("/health")
    def health() -> dict:
        return {"status": "UP", "version": __version__}

    @app.post("/v1/detect", response_model=DetectResponse, dependencies=[Depends(authenticate)])
    def detect(request: DetectRequest) -> DetectResponse:
        return service.run(request)

    return app
