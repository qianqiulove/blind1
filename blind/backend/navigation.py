from __future__ import annotations

import time
from dataclasses import dataclass

from .model_runtime import BlindInference, TrafficInference


MODE_BLIND_NAV = "blind_nav"
MODE_TRAFFIC_TEST = "traffic_test"

STATE_BLIND_NAV = "BLIND_NAV"
STATE_TRAFFIC_TEST = "TRAFFIC_TEST"
STATE_IDLE = "IDLE"


@dataclass
class NavOutput:
    state: str
    guidance_text: str
    traffic_light: str
    blind_detected: bool


class NavigationOrchestrator:
    """Navigation decision layer with text matching rules.

    Rules:
    - traffic_test mode: only output traffic light guidance.
    - blind_nav mode:
      - red light has highest priority (safety first)
      - otherwise keep blind guidance text
      - green light can be a fallback when blind text is empty
    - idle: still allow traffic guidance output when recognized, for easier testing.
    """

    def __init__(self, guidance_interval_sec: float = 1.0) -> None:
        self.nav_enabled = False
        self.state = STATE_IDLE
        self.mode = MODE_BLIND_NAV

        self.guidance_interval_sec = guidance_interval_sec
        self.last_guidance_ts = 0.0
        self.last_guidance_text = ""

        # Debounce counters for traffic guidance.
        self.red_streak = 0
        self.green_streak = 0
        self.stable_frames = 2

    @property
    def is_traffic_test_mode(self) -> bool:
        return self.mode == MODE_TRAFFIC_TEST and self.nav_enabled

    def start(self, mode: str = MODE_BLIND_NAV) -> None:
        self.nav_enabled = True
        self.mode = MODE_TRAFFIC_TEST if mode == MODE_TRAFFIC_TEST else MODE_BLIND_NAV
        self.state = STATE_TRAFFIC_TEST if self.mode == MODE_TRAFFIC_TEST else STATE_BLIND_NAV
        self._reset_runtime_state()

    def stop(self) -> None:
        self.nav_enabled = False
        self.mode = MODE_BLIND_NAV
        self.state = STATE_IDLE
        self._reset_runtime_state()

    def _reset_runtime_state(self) -> None:
        self.last_guidance_ts = 0.0
        self.last_guidance_text = ""
        self.red_streak = 0
        self.green_streak = 0

    def process(self, blind: BlindInference, traffic: TrafficInference) -> NavOutput:
        # Update traffic streaks in all modes so idle/test can also produce text.
        self._update_traffic_streaks(traffic.light)

        if not self.nav_enabled:
            # In IDLE we still expose traffic text when stable to support direct module testing.
            idle_text = self._traffic_guidance_text(green_when_no_blind=True, blind_has_text=False)
            return NavOutput(
                state=STATE_IDLE,
                guidance_text=self._rate_limit(idle_text),
                traffic_light=traffic.light,
                blind_detected=False,
            )

        if self.mode == MODE_TRAFFIC_TEST:
            guidance_text = self._traffic_guidance_text(green_when_no_blind=True, blind_has_text=False)
            return NavOutput(
                state=STATE_TRAFFIC_TEST,
                guidance_text=self._rate_limit(guidance_text),
                traffic_light=traffic.light,
                blind_detected=False,
            )

        # blind_nav mode: red can override; green only as fallback.
        blind_text = blind.guidance_text or ""
        traffic_text = self._traffic_guidance_text(green_when_no_blind=(not bool(blind_text)), blind_has_text=bool(blind_text))
        final_text = traffic_text if traffic_text else blind_text
        return NavOutput(
            state=STATE_BLIND_NAV,
            guidance_text=self._rate_limit(final_text),
            traffic_light=traffic.light,
            blind_detected=blind.detected,
        )

    def _update_traffic_streaks(self, light: str) -> None:
        if light == "red":
            self.red_streak += 1
            self.green_streak = 0
        elif light == "green":
            self.green_streak += 1
            self.red_streak = 0
        else:
            self.red_streak = 0
            self.green_streak = 0

    def _traffic_guidance_text(self, green_when_no_blind: bool, blind_has_text: bool) -> str:
        if self.red_streak >= self.stable_frames:
            return "红灯，请等待。"

        if self.green_streak >= self.stable_frames and green_when_no_blind and not blind_has_text:
            return "绿灯，可以通行。"

        return ""

    def _rate_limit(self, text: str) -> str:
        if not text:
            return ""
        now = time.time()
        if text == self.last_guidance_text and (now - self.last_guidance_ts) < self.guidance_interval_sec:
            return ""
        self.last_guidance_text = text
        self.last_guidance_ts = now
        return text
