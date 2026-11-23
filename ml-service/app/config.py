"""Settings from environment variables (no files, no defaults that weaken security)."""

from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    shared_secret: str
    allow_insecure: bool
    max_body_bytes: int

    @staticmethod
    def from_env() -> "Settings":
        secret = os.environ.get("PULSEOPS_ML_SECRET", "")
        allow_insecure = os.environ.get("PULSEOPS_ML_ALLOW_INSECURE", "false").lower() == "true"
        if not allow_insecure and len(secret) < 32:
            raise RuntimeError(
                "PULSEOPS_ML_SECRET must be set to at least 32 characters "
                "(or PULSEOPS_ML_ALLOW_INSECURE=true for local development only)")
        return Settings(secret, allow_insecure, int(os.environ.get("PULSEOPS_ML_MAX_BODY_BYTES", 4 * 1024 * 1024)))
