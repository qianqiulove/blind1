from __future__ import annotations

import audioop
import json
import re
import wave
from pathlib import Path
from typing import Dict


def _normalize_text(v: str) -> str:
    v = v.lower().strip()
    v = re.sub(r"[，。！？、,.!?\s]", "", v)
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

        # fallback manual mapping for key commands if map file key encoding is unusable
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
        fallback_pairs = [
            ("红灯", "红灯"),
            ("绿灯", "绿灯"),
            ("右平移", "向右平移"),
            ("左平移", "向左平移"),
            ("右微调", "请向右微调，对准盲道。"),
            ("左微调", "请向左微调，对准盲道。"),
            ("继续前进", "方向正确，请继续前进。"),
            ("未检测到盲道", "没看到盲道，请向右侧小幅移动。"),
        ]
        for token, alias in fallback_pairs:
            if token in key:
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

