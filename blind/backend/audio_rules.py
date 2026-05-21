from __future__ import annotations

import audioop
import json
import re
import wave
from pathlib import Path
from typing import Dict


def _normalize_text(v: str) -> str:
    """Keep CJK/alnum and strip punctuation/spaces for robust key matching."""
    v = (v or "").lower().strip()
    v = re.sub(r"[^\w\u4e00-\u9fff]+", "", v, flags=re.UNICODE)
    return v


class VoiceRuleEngine:
    """Map guidance text to local wav files and output PCM16 8k mono bytes."""

    def __init__(self, voice_dir: Path) -> None:
        self.voice_dir = voice_dir
        self.key_to_file: Dict[str, Path] = {}
        self._cache: Dict[Path, bytes] = {}
        self._load_mapping()

    def _load_mapping(self) -> None:
        map_file = self.voice_dir / "map.zh-CN.json"
        if map_file.exists():
            data = json.loads(map_file.read_text(encoding="utf-8", errors="ignore"))
            for key, info in data.items():
                files = info.get("files", [])
                if not files:
                    continue
                f = self.voice_dir / files[0]
                if f.exists():
                    self.key_to_file[_normalize_text(key)] = f

        # Fallback: map each wav stem directly.
        for p in self.voice_dir.glob("*.wav"):
            self.key_to_file.setdefault(_normalize_text(p.stem), p)
        for p in self.voice_dir.glob("*.WAV"):
            self.key_to_file.setdefault(_normalize_text(p.stem), p)

    def synthesize(self, text: str) -> bytes:
        if not text:
            return b""
        key = _normalize_text(text)
        wav_path = self.key_to_file.get(key)
        if wav_path is None:
            wav_path = self._fallback_for_text(key)
        if wav_path is None:
            return b""
        return self._load_pcm_8k_mono(wav_path)

    def _fallback_for_text(self, key: str) -> Path | None:
        # Token-based fallback so slightly different wording can still play audio.
        fallback_pairs = [
            ("等待绿灯", "正在等待绿灯…"),
            ("绿灯稳定", "绿灯稳定，开始通行。"),
            ("绿灯可以通行", "绿灯稳定，开始通行。"),
            ("绿灯快没了", "绿灯快没了"),
            ("红灯请等待", "红灯"),
            ("红灯，请等待。", "红灯"),
            ("绿灯，可以通行。", "绿灯稳定，开始通行。"),
            ("保持直行", "保持直行"),
            ("未检测到盲道", "没看到盲道，请向左侧小幅移动。"),
            ("未识别到红绿灯", "正在等待绿灯…"),
            ("向左微调", "请向左微调，对准盲道。"),
            ("向右微调", "请向右微调，对准盲道。"),
            ("向左转", "请向左转动。"),
            ("向右转", "请向右转动。"),
            ("丢失路径", "丢失路径，重新搜索。"),
            ("没看到盲道", "没看到盲道，请向左侧小幅移动。"),
            ("红灯", "红灯"),
            ("绿灯", "绿灯"),
        ]
        for token, alias in fallback_pairs:
            if _normalize_text(token) in key:
                p = self.key_to_file.get(_normalize_text(alias))
                if p is not None:
                    return p
        return None

    def _load_pcm_8k_mono(self, wav_path: Path) -> bytes:
        if wav_path in self._cache:
            return self._cache[wav_path]

        with wave.open(str(wav_path), "rb") as wf:
            nchannels = wf.getnchannels()
            sampwidth = wf.getsampwidth()
            framerate = wf.getframerate()
            frames = wf.readframes(wf.getnframes())

        if nchannels == 2:
            frames = audioop.tomono(frames, sampwidth, 1, 0)
            nchannels = 1
        if framerate != 8000:
            frames, _ = audioop.ratecv(frames, sampwidth, nchannels, framerate, 8000, None)
        if sampwidth != 2:
            frames = audioop.lin2lin(frames, sampwidth, 2)

        self._cache[wav_path] = frames
        return frames
