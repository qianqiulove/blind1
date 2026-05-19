from __future__ import annotations

import asyncio
import time
from pathlib import Path
from typing import Optional, Set

import cv2
import numpy as np
from fastapi import FastAPI, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.responses import JSONResponse
from starlette.websockets import WebSocketState

from .audio_rules import VoiceRuleEngine
from .config import PROJECT_ROOT, VOICE_DIR, build_config
from .model_runtime import ModelRuntime
from .navigation import NavigationOrchestrator
from .schemas import FeatureDisabledResponse, GuidancePayload, NavCommandResponse


cfg = build_config()
app = FastAPI(title="blind-v1-minimal")

runtime: Optional[ModelRuntime] = None
orchestrator = NavigationOrchestrator(guidance_interval_sec=cfg.guidance_interval_sec)
voice_engine = VoiceRuleEngine(VOICE_DIR)

camera_uploader: Optional[WebSocket] = None
guidance_clients: Set[WebSocket] = set()
audio_clients: Set[WebSocket] = set()
viewer_clients: Set[WebSocket] = set()
frame_counter = 0
VIEWER_SEND_EVERY_N = 1
VIEWER_SEND_TIMEOUT_SEC = 0.03
MAX_DRAIN_FRAMES_PER_TICK = 6
DRAIN_TIMEOUT_SEC = 0.001
INFER_DOWNSCALE_MAX_EDGE = 640
VIEWER_MAX_EDGE = 512
VIEWER_JPEG_QUALITY = 42
CAMERA_IDLE_GRACE_TIMEOUTS = 20


def _extract_contour_norm(mask: np.ndarray | None) -> list[list[float]]:
    if mask is None:
        return []
    h, w = mask.shape[:2]
    if h <= 0 or w <= 0:
        return []
    area_min = max(300, int(h * w * 0.0015))
    work = (mask > 0).astype(np.uint8) * 255
    contours, _ = cv2.findContours(work, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not contours:
        return []
    main = max(contours, key=cv2.contourArea)
    if cv2.contourArea(main) < area_min:
        return []
    pts = main.squeeze(1)
    if pts.ndim != 2 or pts.shape[0] < 3:
        return []
    step = max(1, int(len(pts) / 120))
    sampled = pts[::step]
    out: list[list[float]] = []
    for p in sampled:
        x = float(p[0]) / float(w)
        y = float(p[1]) / float(h)
        out.append([max(0.0, min(1.0, x)), max(0.0, min(1.0, y))])
    return out


def _check_models() -> None:
    missing = []
    if not Path(cfg.blind_model).exists():
        missing.append(str(cfg.blind_model))
    if not Path(cfg.traffic_model).exists():
        missing.append(str(cfg.traffic_model))
    if missing:
        raise RuntimeError("Missing model files:\n" + "\n".join(missing))


async def _broadcast_guidance(payload: GuidancePayload) -> None:
    dead = []
    for ws in list(guidance_clients):
        try:
            await ws.send_json(payload.model_dump())
        except Exception:
            dead.append(ws)
    for ws in dead:
        guidance_clients.discard(ws)


async def _broadcast_audio(pcm16: bytes) -> None:
    if not pcm16:
        return
    dead = []
    for ws in list(audio_clients):
        try:
            # stream as 20ms chunks for smoother Android playback
            chunk = 320
            for i in range(0, len(pcm16), chunk):
                if ws.client_state != WebSocketState.CONNECTED:
                    break
                await ws.send_bytes(pcm16[i : i + chunk])
        except Exception:
            dead.append(ws)
    for ws in dead:
        audio_clients.discard(ws)


def _render_viewer_frame(bgr: np.ndarray, mask: np.ndarray | None) -> bytes | None:
    frame = bgr.copy()
    h0, w0 = frame.shape[:2]
    edge0 = max(h0, w0)
    if edge0 > VIEWER_MAX_EDGE:
        s = float(VIEWER_MAX_EDGE) / float(edge0)
        nw = max(2, int(round(w0 * s)))
        nh = max(2, int(round(h0 * s)))
        frame = cv2.resize(frame, (nw, nh), interpolation=cv2.INTER_AREA)
        if mask is not None:
            mask = cv2.resize(mask, (nw, nh), interpolation=cv2.INTER_NEAREST)
    if mask is not None:
        m = (mask > 0).astype(np.uint8) * 255
        if np.count_nonzero(m) > 0:
            color_layer = np.zeros_like(frame, dtype=np.uint8)
            color_layer[:, :] = (60, 230, 130)
            alpha = 0.32
            idx = m > 0
            frame[idx] = cv2.addWeighted(frame[idx], 1.0 - alpha, color_layer[idx], alpha, 0.0)
            contours, _ = cv2.findContours(m, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
            if contours:
                main = max(contours, key=cv2.contourArea)
                if cv2.contourArea(main) > 300:
                    cv2.polylines(frame, [main], True, (30, 255, 190), 2)

    ok, enc = cv2.imencode(".jpg", frame, [int(cv2.IMWRITE_JPEG_QUALITY), VIEWER_JPEG_QUALITY])
    if not ok:
        return None
    return enc.tobytes()


def _normalize_frame_orientation(bgr: np.ndarray) -> np.ndarray:
    # CameraX analysis frames are often landscape while UI is portrait.
    # Rotate to portrait for viewer rendering and consistent model behavior.
    h, w = bgr.shape[:2]
    if w > h:
        return cv2.rotate(bgr, cv2.ROTATE_90_CLOCKWISE)
    return bgr


def _downscale_for_realtime(bgr: np.ndarray, max_edge: int = 960) -> np.ndarray:
    h, w = bgr.shape[:2]
    edge = max(h, w)
    if edge <= max_edge:
        return bgr
    scale = float(max_edge) / float(edge)
    nw = max(2, int(round(w * scale)))
    nh = max(2, int(round(h * scale)))
    return cv2.resize(bgr, (nw, nh), interpolation=cv2.INTER_AREA)


async def _broadcast_viewer_jpeg(jpg_bytes: bytes | None) -> None:
    if not jpg_bytes:
        return
    dead = []
    for ws in list(viewer_clients):
        try:
            await asyncio.wait_for(ws.send_bytes(jpg_bytes), timeout=VIEWER_SEND_TIMEOUT_SEC)
        except Exception:
            dead.append(ws)
    for ws in dead:
        viewer_clients.discard(ws)


@app.on_event("startup")
async def startup() -> None:
    global runtime
    _check_models()
    runtime = ModelRuntime(str(cfg.blind_model), str(cfg.traffic_model), cfg.device)
    runtime.load()
    print(f"[BLIND] project_root={PROJECT_ROOT}")


@app.get("/health")
async def health() -> dict:
    return {
        "ok": True,
        "runtime_loaded": runtime is not None,
        "nav_enabled": orchestrator.nav_enabled,
        "state": orchestrator.state,
        "frame_counter": frame_counter,
    }


@app.post("/api/nav/start", response_model=NavCommandResponse)
async def nav_start() -> NavCommandResponse:
    orchestrator.start()
    return NavCommandResponse(nav_enabled=orchestrator.nav_enabled, state=orchestrator.state)


@app.post("/api/nav/stop", response_model=NavCommandResponse)
async def nav_stop() -> NavCommandResponse:
    orchestrator.stop()
    return NavCommandResponse(nav_enabled=orchestrator.nav_enabled, state=orchestrator.state)


@app.post("/api/assistant/chat")
async def assistant_reserved() -> JSONResponse:
    payload = FeatureDisabledResponse(message="AI assistant is reserved in blind v1.").model_dump()
    return JSONResponse(content=payload, status_code=501)


@app.post("/api/map/route")
async def map_route_reserved() -> JSONResponse:
    payload = FeatureDisabledResponse(message="Map route API is reserved in blind v1.").model_dump()
    return JSONResponse(content=payload, status_code=501)


@app.get("/api/map/nearby")
async def map_nearby_reserved() -> JSONResponse:
    payload = FeatureDisabledResponse(message="Nearby map API is reserved in blind v1.").model_dump()
    return JSONResponse(content=payload, status_code=501)


@app.websocket("/ws/guidance")
async def ws_guidance(ws: WebSocket) -> None:
    await ws.accept()
    print("[BLIND][GUIDANCE] connected")
    guidance_clients.add(ws)
    try:
        while True:
            await ws.receive_text()
    except WebSocketDisconnect:
        pass
    finally:
        guidance_clients.discard(ws)
        print("[BLIND][GUIDANCE] disconnected")


@app.websocket("/ws/audio")
async def ws_audio(ws: WebSocket) -> None:
    await ws.accept()
    print("[BLIND][AUDIO] connected")
    audio_clients.add(ws)
    try:
        while True:
            await ws.receive_text()
    except WebSocketDisconnect:
        pass
    finally:
        audio_clients.discard(ws)
        print("[BLIND][AUDIO] disconnected")


@app.websocket("/ws/viewer")
async def ws_viewer(ws: WebSocket) -> None:
    await ws.accept()
    print("[BLIND][VIEWER] connected")
    viewer_clients.add(ws)
    try:
        while True:
            await ws.receive_text()
    except WebSocketDisconnect:
        pass
    finally:
        viewer_clients.discard(ws)
        print("[BLIND][VIEWER] disconnected")


@app.websocket("/ws/camera")
async def ws_camera(ws: WebSocket) -> None:
    global camera_uploader, frame_counter
    if camera_uploader is not None and camera_uploader.client_state == WebSocketState.CONNECTED:
        try:
            await camera_uploader.close(code=1000)
        except Exception:
            pass
    camera_uploader = ws
    await ws.accept()
    print("[BLIND][CAMERA] connected")

    if runtime is None:
        await ws.close(code=1011)
        raise HTTPException(status_code=500, detail="Runtime is not initialized")

    last_recv = time.monotonic()
    last_traffic = runtime.infer_traffic(np.zeros((320, 320, 3), dtype=np.uint8))
    idle_timeouts = 0
    try:
        while True:
            try:
                msg = await asyncio.wait_for(ws.receive(), timeout=cfg.ws_idle_timeout_sec)
            except asyncio.TimeoutError:
                idle_timeouts += 1
                if idle_timeouts >= CAMERA_IDLE_GRACE_TIMEOUTS:
                    print(
                        f"[BLIND][CAMERA] idle timeout exceeded "
                        f"({idle_timeouts} x {cfg.ws_idle_timeout_sec}s), closing"
                    )
                    break
                print(
                    f"[BLIND][CAMERA] idle timeout grace {idle_timeouts}/"
                    f"{CAMERA_IDLE_GRACE_TIMEOUTS}"
                )
                continue

            if msg.get("type") in ("websocket.disconnect", "websocket.close"):
                break
            data = msg.get("bytes")
            if data is None:
                continue
            idle_timeouts = 0

            # Keep only the latest frame in queued websocket messages.
            dropped = 0
            for _ in range(MAX_DRAIN_FRAMES_PER_TICK):
                try:
                    queued = await asyncio.wait_for(ws.receive(), timeout=DRAIN_TIMEOUT_SEC)
                except asyncio.TimeoutError:
                    break
                if queued.get("type") in ("websocket.disconnect", "websocket.close"):
                    data = None
                    break
                q_bytes = queued.get("bytes")
                if q_bytes is not None:
                    data = q_bytes
                    dropped += 1
            if data is None:
                break

            last_recv = time.monotonic()
            frame_counter += 1

            arr = np.frombuffer(data, dtype=np.uint8)
            bgr = cv2.imdecode(arr, cv2.IMREAD_COLOR)
            if bgr is None:
                continue
            bgr = _normalize_frame_orientation(bgr)
            bgr = _downscale_for_realtime(bgr, max_edge=INFER_DOWNSCALE_MAX_EDGE)

            blind = runtime.infer_blind(bgr)
            if frame_counter % 5 == 0:
                last_traffic = runtime.infer_traffic(bgr)
            traffic = last_traffic
            nav = orchestrator.process(blind, traffic)
            contour_norm = _extract_contour_norm(blind.mask)
            payload = GuidancePayload(
                state=nav.state,
                nav_enabled=orchestrator.nav_enabled,
                guidance_text=nav.guidance_text,
                blind_detected=nav.blind_detected,
                traffic_light=nav.traffic_light,
                blind_contour=contour_norm,
                frame_id=frame_counter,
                timestamp=time.time(),
            )
            await _broadcast_guidance(payload)
            if nav.guidance_text:
                pcm16 = voice_engine.synthesize(nav.guidance_text)
                await _broadcast_audio(pcm16)
            if frame_counter % VIEWER_SEND_EVERY_N == 0:
                viewer_jpg = _render_viewer_frame(bgr, blind.mask)
                await _broadcast_viewer_jpeg(viewer_jpg)

            if frame_counter % 30 == 0:
                mask_area = int(np.count_nonzero(blind.mask)) if blind.mask is not None else 0
                print(
                    f"[BLIND][FRAME] id={frame_counter} state={nav.state} "
                    f"blind={nav.blind_detected} mask_area={mask_area} "
                    f"traffic={nav.traffic_light} dropped={dropped} text={nav.guidance_text}"
                )
    except WebSocketDisconnect:
        pass
    finally:
        if camera_uploader is ws:
            camera_uploader = None
        print(
            f"[BLIND][CAMERA] disconnected uptime_sec={time.monotonic()-last_recv:.1f} "
            f"frames={frame_counter}"
        )
