from __future__ import annotations

from pydantic import BaseModel, Field


class NavCommandResponse(BaseModel):
    ok: bool = True
    nav_enabled: bool
    state: str
    mode: str = "blind_nav"


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


class MapRouteRequest(BaseModel):
    origin_lat: float
    origin_lng: float
    dest_lat: float
    dest_lng: float
    mode: str = "walking"
    coordtype: str = "bd09ll"


class MapGeocodeRequest(BaseModel):
    address: str
    city: str | None = None


class MapReverseGeocodeRequest(BaseModel):
    lat: float
    lng: float
    coordtype: str = "bd09ll"


class ChatHistoryItem(BaseModel):
    role: str
    content: str


class AssistantChatRequest(BaseModel):
    message: str
    user_location: dict[str, float] | None = None
    chat_history: list[ChatHistoryItem] = Field(default_factory=list)


class AssistantToolHistoryItem(BaseModel):
    step: int
    action: str
    params: dict = Field(default_factory=dict)
    reasoning: str = ""
    result: dict = Field(default_factory=dict)


class AssistantChatResponse(BaseModel):
    status: str = "ok"
    content: str
    iterations: int = 0
    tool_history: list[AssistantToolHistoryItem] = Field(default_factory=list)
    error: str = ""


class MobileLoginRequest(BaseModel):
    username: str
    password: str


class MobileAuthUser(BaseModel):
    user_id: int
    username: str
    email: str = ""
    user_settings: dict = Field(default_factory=dict)


class MobileLoginResponse(BaseModel):
    status: str = "ok"
    token: str
    expires_at: str
    user: MobileAuthUser


class MobileSimpleResponse(BaseModel):
    status: str = "ok"
    message: str = ""


class MobileRegisterRequest(BaseModel):
    username: str
    email: str
    code: str
    password: str


class MobileResetPasswordRequest(BaseModel):
    email: str
    code: str
    new_password: str
