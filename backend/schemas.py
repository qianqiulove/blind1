from __future__ import annotations

from pydantic import BaseModel, Field


class NavCommandResponse(BaseModel):
    ok: bool = True
    nav_enabled: bool
    state: str


class FeatureDisabledResponse(BaseModel):
    error: str = "feature_disabled"
    message: str = "This feature is reserved for future versions."


class GuidancePayload(BaseModel):
    type: str = "guidance"
    state: str
    nav_enabled: bool
    guidance_text: str = ""
    blind_detected: bool = False
    traffic_light: str = "unknown"
    blind_contour: list[list[float]] = Field(default_factory=list)
    frame_id: int = 0
    timestamp: float = Field(default=0.0)
