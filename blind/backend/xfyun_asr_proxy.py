from __future__ import annotations

import base64
import hashlib
import hmac
import json
from dataclasses import dataclass
from datetime import datetime, timezone
from email.utils import format_datetime
from typing import Any
from urllib.parse import quote


@dataclass(frozen=True)
class XfyunAsrConfig:
    app_id: str
    api_key: str
    api_secret: str
    host: str = "iat-api.xfyun.cn"
    path: str = "/v2/iat"
    language: str = "zh_cn"
    domain: str = "iat"
    accent: str = "mandarin"
    dwa: str = "wpgs"
    vad_eos: int = 5000


class XfyunAsrProxy:
    def __init__(self, cfg: XfyunAsrConfig) -> None:
        self.cfg = cfg

    @property
    def enabled(self) -> bool:
        return bool(self.cfg.app_id.strip() and self.cfg.api_key.strip() and self.cfg.api_secret.strip())

    def build_signed_url(self) -> str:
        host = self.cfg.host.strip() or "iat-api.xfyun.cn"
        path = self.cfg.path.strip() or "/v2/iat"
        if not path.startswith("/"):
            path = "/" + path
        date = format_datetime(datetime.now(timezone.utc), usegmt=True)
        signature_origin = f"host: {host}\n" f"date: {date}\n" f"GET {path} HTTP/1.1"
        digest = hmac.new(
            self.cfg.api_secret.encode("utf-8"),
            signature_origin.encode("utf-8"),
            digestmod=hashlib.sha256,
        ).digest()
        signature = base64.b64encode(digest).decode("utf-8")
        auth_origin = (
            f'api_key="{self.cfg.api_key}", algorithm="hmac-sha256", '
            f'headers="host date request-line", signature="{signature}"'
        )
        authorization = base64.b64encode(auth_origin.encode("utf-8")).decode("utf-8")
        return (
            f"wss://{host}{path}"
            f"?authorization={quote(authorization)}"
            f"&date={quote(date)}"
            f"&host={quote(host)}"
        )

    def build_start_frame(self, audio_b64: str, sample_rate: int, overrides: dict[str, Any] | None = None) -> str:
        o = overrides or {}
        language = str(o.get("language", self.cfg.language) or self.cfg.language)
        domain = str(o.get("domain", self.cfg.domain) or self.cfg.domain)
        accent = str(o.get("accent", self.cfg.accent) or self.cfg.accent)
        dwa = str(o.get("dwa", self.cfg.dwa) or self.cfg.dwa)
        vad_eos = int(o.get("vad_eos", self.cfg.vad_eos) or self.cfg.vad_eos)
        payload = {
            "common": {"app_id": self.cfg.app_id},
            "business": {
                "language": language,
                "domain": domain,
                "accent": accent,
                "vad_eos": max(1000, vad_eos),
                "dwa": dwa,
            },
            "data": {
                "status": 0,
                "format": f"audio/L16;rate={sample_rate}",
                "encoding": "raw",
                "audio": audio_b64,
            },
        }
        return json.dumps(payload, ensure_ascii=False)

    @staticmethod
    def build_continue_frame(audio_b64: str, sample_rate: int) -> str:
        payload = {
            "data": {
                "status": 1,
                "format": f"audio/L16;rate={sample_rate}",
                "encoding": "raw",
                "audio": audio_b64,
            }
        }
        return json.dumps(payload, ensure_ascii=False)

    @staticmethod
    def build_end_frame(sample_rate: int) -> str:
        payload = {
            "data": {
                "status": 2,
                "format": f"audio/L16;rate={sample_rate}",
                "encoding": "raw",
                "audio": "",
            }
        }
        return json.dumps(payload, ensure_ascii=False)

    @staticmethod
    def parse_result_text(msg_obj: dict[str, Any]) -> tuple[int, str, bool, str, list[int]]:
        data = msg_obj.get("data") or {}
        result = data.get("result") or {}
        pieces = []
        for ws_item in result.get("ws") or []:
            for cw in ws_item.get("cw") or []:
                w = cw.get("w")
                if isinstance(w, str):
                    pieces.append(w)
        text = "".join(pieces)
        sn = int(result.get("sn", 0) or 0)
        is_last = bool(result.get("ls", False))
        pgs = str(result.get("pgs", "") or "")
        rg_raw = result.get("rg")
        rg: list[int] = []
        if isinstance(rg_raw, list) and len(rg_raw) == 2:
            try:
                rg = [int(rg_raw[0]), int(rg_raw[1])]
            except Exception:
                rg = []
        return sn, text, is_last, pgs, rg

