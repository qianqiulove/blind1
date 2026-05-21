from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
MODELS_DIR = PROJECT_ROOT / "models"
VOICE_DIR = PROJECT_ROOT / "assets" / "voice"
ANDROID_LOCAL_PROPS = PROJECT_ROOT / "android" / "local.properties"


def _load_android_local_props() -> dict[str, str]:
    if not ANDROID_LOCAL_PROPS.exists():
        return {}
    out: dict[str, str] = {}
    for raw in ANDROID_LOCAL_PROPS.read_text(encoding="utf-8", errors="ignore").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        out[k.strip()] = v.strip()
    return out


_ANDROID_PROPS = _load_android_local_props()


def _env_or_prop(*keys: str, default: str = "") -> str:
    for k in keys:
        v = os.getenv(k)
        if v is not None and v.strip():
            return v.strip()
        pv = _ANDROID_PROPS.get(k)
        if pv:
            return pv.strip()
    return default


@dataclass(frozen=True)
class RuntimeConfig:
    host: str = os.getenv("BLIND_HOST", "0.0.0.0")
    port: int = int(os.getenv("BLIND_PORT", "8088"))
    device: str = os.getenv("BLIND_DEVICE", "cuda:0")
    blind_model: Path = Path(os.getenv("BLIND_PATH_MODEL", str(MODELS_DIR / "yolo-seg.pt")))
    traffic_model: Path = Path(os.getenv("BLIND_TRAFFIC_MODEL", str(MODELS_DIR / "best.pt")))
    ws_idle_timeout_sec: float = float(os.getenv("BLIND_WS_IDLE_TIMEOUT_SEC", "60"))
    guidance_interval_sec: float = float(os.getenv("BLIND_GUIDANCE_INTERVAL_SEC", "1.0"))
    baidu_map_ak: str = os.getenv("BAIDU_MAP_AK", "")
    baidu_map_timeout_sec: float = float(os.getenv("BAIDU_MAP_TIMEOUT_SEC", "8.0"))
    dashscope_api_key: str = os.getenv("DASHSCOPE_API_KEY", "")
    dashscope_model: str = os.getenv("DASHSCOPE_MODEL", "qwen-plus")
    dashscope_base_url: str = os.getenv(
        "DASHSCOPE_BASE_URL",
        "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
    )
    assistant_timeout_sec: float = float(os.getenv("BLIND_ASSISTANT_TIMEOUT_SEC", "20.0"))
    assistant_max_iterations: int = int(os.getenv("BLIND_ASSISTANT_MAX_ITERS", "10"))
    xfyun_asr_app_id: str = _env_or_prop("XFYUN_ASR_APP_ID", "IFLYTEK_APP_ID")
    xfyun_asr_api_key: str = _env_or_prop("XFYUN_ASR_API_KEY", "IFLYTEK_API_KEY")
    xfyun_asr_api_secret: str = _env_or_prop("XFYUN_ASR_API_SECRET", "IFLYTEK_API_SECRET")
    xfyun_asr_host: str = os.getenv("XFYUN_ASR_HOST", "iat-api.xfyun.cn")
    xfyun_asr_path: str = os.getenv("XFYUN_ASR_PATH", "/v2/iat")
    xfyun_asr_language: str = os.getenv("XFYUN_ASR_LANGUAGE", "zh_cn")
    xfyun_asr_domain: str = os.getenv("XFYUN_ASR_DOMAIN", "iat")
    xfyun_asr_accent: str = os.getenv("XFYUN_ASR_ACCENT", "mandarin")
    xfyun_asr_dwa: str = os.getenv("XFYUN_ASR_DWA", "wpgs")
    xfyun_asr_vad_eos: int = int(os.getenv("XFYUN_ASR_VAD_EOS", "5000"))


def build_config() -> RuntimeConfig:
    return RuntimeConfig()
