from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
MODELS_DIR = PROJECT_ROOT / "models"
VOICE_DIR = PROJECT_ROOT / "assets" / "voice"


@dataclass(frozen=True)
class RuntimeConfig:
    host: str = os.getenv("BLIND_HOST", "0.0.0.0")
    port: int = int(os.getenv("BLIND_PORT", "8088"))
    device: str = os.getenv("BLIND_DEVICE", "cuda:0")
    blind_model: Path = Path(os.getenv("BLIND_PATH_MODEL", str(MODELS_DIR / "yolo-seg.pt")))
    traffic_model: Path = Path(os.getenv("BLIND_TRAFFIC_MODEL", str(MODELS_DIR / "trafficlight.pt")))
    ws_idle_timeout_sec: float = float(os.getenv("BLIND_WS_IDLE_TIMEOUT_SEC", "60"))
    guidance_interval_sec: float = float(os.getenv("BLIND_GUIDANCE_INTERVAL_SEC", "1.0"))


def build_config() -> RuntimeConfig:
    return RuntimeConfig()
