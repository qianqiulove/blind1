from __future__ import annotations

import time
from dataclasses import dataclass

from .model_runtime import BlindInference, TrafficInference


STATE_BLIND_NAV = "BLIND_NAV"
STATE_TRAFFIC_LIGHT_CHECK = "TRAFFIC_LIGHT_CHECK"
STATE_IDLE = "IDLE"


@dataclass
class NavOutput:
    state: str
    guidance_text: str
    traffic_light: str
    blind_detected: bool


class NavigationOrchestrator:
    def __init__(self, guidance_interval_sec: float = 1.0) -> None:
        self.nav_enabled = False
        self.state = STATE_IDLE
        self.last_guidance_ts = 0.0
        self.last_guidance_text = ""
        self.guidance_interval_sec = guidance_interval_sec
        self.red_streak = 0
        self.green_streak = 0

    def start(self) -> None:
        self.nav_enabled = True
        self.state = STATE_BLIND_NAV
        self.red_streak = 0
        self.green_streak = 0
        self.last_guidance_text = ""
        self.last_guidance_ts = 0.0

    def stop(self) -> None:
        self.nav_enabled = False
        self.state = STATE_IDLE
        self.red_streak = 0
        self.green_streak = 0
        self.last_guidance_text = ""
        self.last_guidance_ts = 0.0

    def process(self, blind: BlindInference, traffic: TrafficInference) -> NavOutput:
        if not self.nav_enabled:
            return NavOutput(
                state=STATE_IDLE,
                guidance_text="",
                traffic_light=traffic.light,
                blind_detected=blind.detected,
            )

        if traffic.light == "red":
            self.red_streak += 1
            self.green_streak = 0
        elif traffic.light == "green":
            self.green_streak += 1
            self.red_streak = 0
        else:
            self.red_streak = 0
            self.green_streak = 0

        if self.red_streak >= 2:
            self.state = STATE_TRAFFIC_LIGHT_CHECK
        elif self.green_streak >= 2 and self.state == STATE_TRAFFIC_LIGHT_CHECK:
            self.state = STATE_BLIND_NAV

        if self.state == STATE_TRAFFIC_LIGHT_CHECK:
            if traffic.light == "green":
                text = "绿灯可通行，请沿盲道继续前进。"
            else:
                text = "红灯请等待，注意来车。"
        else:
            text = blind.guidance_text

        text = self._rate_limit(text)
        return NavOutput(
            state=self.state,
            guidance_text=text,
            traffic_light=traffic.light,
            blind_detected=blind.detected,
        )

    def _rate_limit(self, text: str) -> str:
        if not text:
            return ""
        now = time.time()
        if text == self.last_guidance_text and (now - self.last_guidance_ts) < self.guidance_interval_sec:
            return ""
        self.last_guidance_text = text
        self.last_guidance_ts = now
        return text
