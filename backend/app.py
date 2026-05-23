from __future__ import annotations

import asyncio
import base64
import json
import re
import time
from pathlib import Path
from typing import Any, Optional, Set

import cv2
import numpy as np
from fastapi import Depends, FastAPI, Form, Header, HTTPException, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from starlette.middleware.sessions import SessionMiddleware
from starlette.websockets import WebSocketState
import websockets
from websockets.exceptions import ConnectionClosed

from .auth import AuthConfig, AuthService
from .assistant_react import AssistantReActEngine
from .audio_rules import VoiceRuleEngine
from .baidu_map_mcp import BaiduMapMCP
from .config import PROJECT_ROOT, VOICE_DIR, build_config
from .model_runtime import BlindInference, ModelRuntime
from .navigation import MODE_BLIND_NAV, MODE_TRAFFIC_TEST, NavigationOrchestrator
from .schemas import (
    AssistantChatRequest,
    AssistantChatResponse,
    FeatureDisabledResponse,
    GuidancePayload,
    MapGeocodeRequest,
    MapReverseGeocodeRequest,
    MapRouteRequest,
    MobileLoginRequest,
    MobileLoginResponse,
    MobileRegisterRequest,
    MobileResetPasswordRequest,
    MobileSimpleResponse,
    NavCommandResponse,
)
from .xfyun_asr_proxy import XfyunAsrConfig, XfyunAsrProxy


cfg = build_config()
app = FastAPI(title="blind-v1-minimal")
app.add_middleware(SessionMiddleware, secret_key=cfg.secret_key)

runtime: Optional[ModelRuntime] = None
orchestrator = NavigationOrchestrator(guidance_interval_sec=cfg.guidance_interval_sec)
voice_engine = VoiceRuleEngine(VOICE_DIR)
map_engine: Optional[BaiduMapMCP] = None
assistant_engine: Optional[AssistantReActEngine] = None
xfyun_asr_proxy: Optional[XfyunAsrProxy] = None
auth_service: Optional[AuthService] = None
auth_init_error: str = ""

camera_uploader: Optional[WebSocket] = None
guidance_clients: Set[WebSocket] = set()
audio_clients: Set[WebSocket] = set()
viewer_clients: Set[WebSocket] = set()
frame_counter = 0
last_audio_miss_text = ""
last_audio_no_client_text = ""
audio_last_text = ""
audio_last_ts = 0.0
STOP_HOTWORDS = ("停止", "停下", "别说了", "安静")
VIEWER_SEND_EVERY_N = 2
VIEWER_SEND_TIMEOUT_SEC = 0.03
MAX_DRAIN_FRAMES_PER_TICK = 16
DRAIN_TIMEOUT_SEC = 0.001
INFER_DOWNSCALE_MAX_EDGE = 576
VIEWER_MAX_EDGE = 448
VIEWER_JPEG_QUALITY = 32
CAMERA_IDLE_GRACE_TIMEOUTS = 20


def _iso_utc_now() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def _extract_bearer(authorization: str | None) -> str:
    if not authorization:
        return ""
    raw = authorization.strip()
    if not raw:
        return ""
    if raw.lower().startswith("bearer "):
        return raw[7:].strip()
    return raw


def _require_auth_service() -> AuthService:
    if auth_service is None:
        raise HTTPException(status_code=503, detail="auth_service_not_initialized")
    return auth_service


def _auth_unavailable_response() -> HTTPException:
    if auth_init_error:
        return HTTPException(status_code=503, detail=f"auth_unavailable: {auth_init_error}")
    return HTTPException(status_code=503, detail="auth_unavailable")


def _session_user(request: Request) -> dict[str, Any] | None:
    uid = request.session.get("user_id")
    if not uid:
        return None
    try:
        svc = _require_auth_service()
        return svc.find_user_by_id(int(uid))
    except Exception:
        return None


def _is_html_request(request: Request) -> bool:
    accept = request.headers.get("accept", "").lower()
    return "text/html" in accept


async def require_authenticated_user(
    request: Request,
    authorization: str | None = Header(default=None),
    x_mobile_token: str | None = Header(default=None),
) -> dict[str, Any]:
    user = _session_user(request)
    if user:
        return user

    token = _extract_bearer(authorization) or (x_mobile_token or "").strip()
    if not token:
        raise HTTPException(status_code=401, detail="unauthorized")

    try:
        svc = _require_auth_service()
        user = svc.verify_mobile_token(token)
    except HTTPException:
        raise
    except Exception as e:
        raise _auth_unavailable_response() from e

    if not user:
        raise HTTPException(status_code=401, detail="invalid_or_expired_token")
    return user


async def require_web_session_user(request: Request) -> dict[str, Any]:
    user = _session_user(request)
    if user:
        return user
    raise HTTPException(status_code=401, detail="web_session_required")


async def authenticate_websocket(ws: WebSocket) -> dict[str, Any] | None:
    scope_session = ws.scope.get("session") if isinstance(ws.scope, dict) else None
    if isinstance(scope_session, dict):
        uid = scope_session.get("user_id")
        if uid:
            try:
                svc = _require_auth_service()
                user = svc.find_user_by_id(int(uid))
                if user:
                    return user
            except Exception:
                pass

    auth_header = ws.headers.get("authorization")
    query_token = ws.query_params.get("token", "")
    header_token = ws.headers.get("x-mobile-token", "")
    token = query_token or _extract_bearer(auth_header) or header_token
    if not token:
        await ws.close(code=4401, reason="unauthorized")
        return None
    try:
        svc = _require_auth_service()
        user = svc.verify_mobile_token(token)
    except Exception:
        await ws.close(code=1011, reason="auth_unavailable")
        return None
    if not user:
        await ws.close(code=4401, reason="invalid_token")
        return None
    return user


def _render_auth_page(
    *,
    title: str,
    subtitle: str,
    action: str,
    submit_text: str,
    fields_html: str,
    error: str = "",
    success: str = "",
    extra_links_html: str = "",
) -> HTMLResponse:
    msg_html = ""
    if error:
        msg_html = f'<div class="msg err">{error}</div>'
    elif success:
        msg_html = f'<div class="msg ok">{success}</div>'
    html = f"""<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>{title}</title>
  <style>
    body{{margin:0;background:linear-gradient(180deg,#eff6ff,#f8fafc);font-family:"Microsoft YaHei",sans-serif;color:#1f2937}}
    .wrap{{min-height:100vh;display:flex;align-items:center;justify-content:center;padding:16px}}
    .card{{width:100%;max-width:420px;background:#fff;border-radius:16px;padding:20px;box-shadow:0 10px 30px rgba(0,0,0,.08)}}
    h1{{margin:0 0 6px;color:#0ea5e9}}
    p{{margin:0 0 14px;color:#475569}}
    .field{{margin:10px 0}}
    .field label{{display:block;font-size:13px;color:#475569;margin-bottom:6px}}
    .field input{{width:100%;box-sizing:border-box;border:1px solid #cbd5e1;border-radius:10px;padding:10px 12px;font-size:15px}}
    .btn{{margin-top:14px;width:100%;border:0;background:#0ea5e9;color:#fff;border-radius:10px;padding:10px 12px;font-size:16px;cursor:pointer}}
    .links{{margin-top:12px;display:flex;gap:12px;justify-content:center;flex-wrap:wrap}}
    .links a{{color:#0284c7;text-decoration:none}}
    .msg{{padding:10px;border-radius:10px;margin-bottom:10px;font-size:14px}}
    .msg.err{{background:#fee2e2;color:#991b1b}}
    .msg.ok{{background:#dcfce7;color:#166534}}
  </style>
</head>
<body>
  <div class="wrap">
    <div class="card">
      <h1>{title}</h1>
      <p>{subtitle}</p>
      {msg_html}
      <form method="post" action="{action}">
        {fields_html}
        <button class="btn" type="submit">{submit_text}</button>
      </form>
      <div class="links">{extra_links_html}</div>
    </div>
  </div>
</body>
</html>"""
    return HTMLResponse(content=html)


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
    if not audio_clients:
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


async def _enqueue_audio_text(
    text: str,
    *,
    source: str,
    dedupe_sec: float | None = None,
) -> tuple[bool, str]:
    global audio_last_text, audio_last_ts

    t = (text or "").strip()
    if not t:
        return False, "empty_text"

    if dedupe_sec is None:
        dedupe_sec = 1.0
    now = time.monotonic()
    if t == audio_last_text and (now - audio_last_ts) < max(0.0, float(dedupe_sec)):
        return False, "deduped"

    pcm16 = voice_engine.synthesize(t)
    if not pcm16:
        return False, "miss_mapping"

    await _broadcast_audio(pcm16)
    audio_last_text = t
    audio_last_ts = now
    return True, "played"


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
    global runtime, map_engine, assistant_engine, xfyun_asr_proxy, auth_service, auth_init_error
    _check_models()
    runtime = ModelRuntime(str(cfg.blind_model), str(cfg.traffic_model), cfg.device)
    runtime.load()
    if cfg.baidu_map_ak.strip():
        map_engine = BaiduMapMCP(cfg.baidu_map_ak, timeout_sec=cfg.baidu_map_timeout_sec)
        print("[BLIND][MAP] Baidu map enabled")
    else:
        print("[BLIND][MAP] BAIDU_MAP_AK not set; map api disabled")
    assistant_engine = AssistantReActEngine(
        api_key=cfg.dashscope_api_key,
        model=cfg.dashscope_model,
        base_url=cfg.dashscope_base_url,
        timeout_sec=cfg.assistant_timeout_sec,
        max_iterations=cfg.assistant_max_iterations,
    )
    if assistant_engine.enabled:
        print(f"[BLIND][ASSISTANT] enabled model={cfg.dashscope_model}")
    else:
        print("[BLIND][ASSISTANT] disabled: DASHSCOPE_API_KEY not set")
    xfyun_asr_proxy = XfyunAsrProxy(
        XfyunAsrConfig(
            app_id=cfg.xfyun_asr_app_id,
            api_key=cfg.xfyun_asr_api_key,
            api_secret=cfg.xfyun_asr_api_secret,
            host=cfg.xfyun_asr_host,
            path=cfg.xfyun_asr_path,
            language=cfg.xfyun_asr_language,
            domain=cfg.xfyun_asr_domain,
            accent=cfg.xfyun_asr_accent,
            dwa=cfg.xfyun_asr_dwa,
            vad_eos=cfg.xfyun_asr_vad_eos,
        )
    )
    if xfyun_asr_proxy.enabled:
        print(f"[BLIND][ASR] xfyun webapi enabled host={cfg.xfyun_asr_host}{cfg.xfyun_asr_path}")
    else:
        print("[BLIND][ASR] xfyun webapi disabled: set XFYUN_ASR_APP_ID/XFYUN_ASR_API_KEY/XFYUN_ASR_API_SECRET")
    auth_service = AuthService(
        AuthConfig(
            mysql_host=cfg.mysql_host,
            mysql_port=cfg.mysql_port,
            mysql_user=cfg.mysql_user,
            mysql_password=cfg.mysql_password,
            mysql_database=cfg.mysql_database,
            token_expire_hours=cfg.token_expire_hours,
            smtp_host=cfg.smtp_host,
            smtp_port=cfg.smtp_port,
            smtp_user=cfg.smtp_user,
            smtp_password=cfg.smtp_password,
            smtp_use_tls=cfg.smtp_use_tls,
            smtp_use_ssl=cfg.smtp_use_ssl,
            mail_from=cfg.mail_from,
        )
    )
    auth_init_error = ""
    try:
        auth_service.init_schema()
        print(
            f"[BLIND][AUTH] mysql_ready host={cfg.mysql_host}:{cfg.mysql_port} db={cfg.mysql_database} token_expire_h={cfg.token_expire_hours}"
        )
    except Exception as e:
        auth_init_error = str(e)
        print(f"[BLIND][AUTH] init_failed {e}")
    print(f"[BLIND] project_root={PROJECT_ROOT}")


@app.on_event("shutdown")
async def shutdown() -> None:
    pass


@app.get("/health")
async def health() -> dict:
    return {
        "ok": True,
        "runtime_loaded": runtime is not None,
        "auth_ready": bool(auth_service is not None and not auth_init_error),
        "auth_error": auth_init_error,
        "nav_enabled": orchestrator.nav_enabled,
        "state": orchestrator.state,
        "mode": orchestrator.mode,
        "frame_counter": frame_counter,
    }


@app.get("/")
async def index(request: Request) -> RedirectResponse:
    if _session_user(request):
        return RedirectResponse(url="/web", status_code=302)
    return RedirectResponse(url="/login", status_code=302)


@app.get("/login", response_class=HTMLResponse)
async def login_page(request: Request, msg: str = "") -> HTMLResponse:
    if _session_user(request):
        return HTMLResponse('<meta http-equiv="refresh" content="0;url=/web">')
    fields = """
    <div class="field"><label>用户名或邮箱</label><input name="username" placeholder="请输入用户名或邮箱" /></div>
    <div class="field"><label>密码</label><input name="password" type="password" placeholder="请输入密码" /></div>
    """
    links = '<a href="/register">注册</a><a href="/forget_password">忘记密码</a>'
    return _render_auth_page(
        title="blind 登录",
        subtitle="登录后可访问导航控制台与移动端接口",
        action="/login",
        submit_text="登录",
        fields_html=fields,
        success=msg,
        extra_links_html=links,
    )


@app.post("/login")
async def login_submit(
    request: Request,
    username: str = Form(...),
    password: str = Form(...),
) -> RedirectResponse:
    try:
        svc = _require_auth_service()
        user = svc.verify_user(username, password)
    except Exception as e:
        raise _auth_unavailable_response() from e
    if not user:
        return RedirectResponse(url="/login?msg=用户名或密码错误", status_code=302)
    request.session["user_id"] = user["user_id"]
    request.session["username"] = user["username"]
    request.session["user_settings"] = user.get("user_settings", {})
    return RedirectResponse(url="/web", status_code=302)


@app.get("/register", response_class=HTMLResponse)
async def register_page(msg: str = "", err: str = "") -> HTMLResponse:
    fields = """
    <div class="field"><label>用户名</label><input name="username" placeholder="请输入用户名" /></div>
    <div class="field"><label>邮箱</label><input name="email" placeholder="请输入邮箱" /></div>
    <div class="field"><label>验证码</label><input name="code" placeholder="6位验证码" /></div>
    <div class="field"><button type="button" onclick="sendCode('register')">发送验证码</button></div>
    <div class="field"><label>密码</label><input name="password" type="password" placeholder="请输入密码" /></div>
    """
    script_links = """
    <a href="/login">返回登录</a>
    <script>
    async function sendCode(purpose){
      const email = document.querySelector('input[name="email"]').value.trim();
      if(!email){ alert("请先输入邮箱"); return; }
      const r = await fetch('/send_verification_code', {
        method:'POST',
        headers:{'Content-Type':'application/json'},
        body: JSON.stringify({email, purpose})
      });
      const j = await r.json();
      alert(j.message || (j.status === 'ok' ? '验证码已发送' : '发送失败'));
    }
    </script>
    """
    return _render_auth_page(
        title="注册账号",
        subtitle="验证码有效期 10 分钟",
        action="/register",
        submit_text="注册",
        fields_html=fields,
        error=err,
        success=msg,
        extra_links_html=script_links,
    )


@app.post("/register")
async def register_submit(
    username: str = Form(...),
    email: str = Form(...),
    code: str = Form(...),
    password: str = Form(...),
) -> RedirectResponse:
    try:
        svc = _require_auth_service()
        if not svc.consume_email_code(email, code, "register"):
            return RedirectResponse(url="/register?err=验证码错误或已过期", status_code=302)
        svc.create_user(username=username, email=email, password=password)
    except ValueError as e:
        if str(e) == "username_or_email_exists":
            return RedirectResponse(url="/register?err=用户名或邮箱已存在", status_code=302)
        return RedirectResponse(url="/register?err=注册参数无效", status_code=302)
    except Exception:
        return RedirectResponse(url="/register?err=注册失败，请稍后重试", status_code=302)
    return RedirectResponse(url="/login?msg=注册成功，请登录", status_code=302)


@app.get("/forget_password", response_class=HTMLResponse)
async def forget_page(msg: str = "", err: str = "") -> HTMLResponse:
    fields = """
    <div class="field"><label>邮箱</label><input name="email" placeholder="注册邮箱" /></div>
    <div class="field"><label>验证码</label><input name="code" placeholder="6位验证码" /></div>
    <div class="field"><button type="button" onclick="sendCode('reset')">发送验证码</button></div>
    <div class="field"><label>新密码</label><input name="new_password" type="password" placeholder="请输入新密码" /></div>
    """
    links = """
    <a href="/login">返回登录</a>
    <script>
    async function sendCode(purpose){
      const email = document.querySelector('input[name="email"]').value.trim();
      if(!email){ alert("请先输入邮箱"); return; }
      const r = await fetch('/send_verification_code', {
        method:'POST',
        headers:{'Content-Type':'application/json'},
        body: JSON.stringify({email, purpose})
      });
      const j = await r.json();
      alert(j.message || (j.status === 'ok' ? '验证码已发送' : '发送失败'));
    }
    </script>
    """
    return _render_auth_page(
        title="找回密码",
        subtitle="重置后旧密码将失效",
        action="/forget_password",
        submit_text="重置密码",
        fields_html=fields,
        error=err,
        success=msg,
        extra_links_html=links,
    )


@app.post("/forget_password")
async def forget_submit(
    email: str = Form(...),
    code: str = Form(...),
    new_password: str = Form(...),
) -> RedirectResponse:
    try:
        svc = _require_auth_service()
        if not svc.consume_reset_code(email, code):
            return RedirectResponse(url="/forget_password?err=验证码错误或已过期", status_code=302)
        ok = svc.update_password(email=email, new_password=new_password)
        if not ok:
            return RedirectResponse(url="/forget_password?err=邮箱不存在", status_code=302)
    except Exception:
        return RedirectResponse(url="/forget_password?err=重置失败，请稍后重试", status_code=302)
    return RedirectResponse(url="/login?msg=密码重置成功，请登录", status_code=302)


@app.post("/send_verification_code")
async def send_verification_code(request: Request) -> JSONResponse:
    payload: dict[str, Any] = {}
    try:
        payload = await request.json()
    except Exception:
        form = await request.form()
        payload = dict(form.items())

    email = str(payload.get("email", "")).strip()
    purpose = str(payload.get("purpose", "register")).strip().lower()
    if purpose not in {"register", "reset"}:
        purpose = "register"
    if not email:
        return JSONResponse({"status": "error", "message": "邮箱不能为空"}, status_code=400)
    try:
        svc = _require_auth_service()
        if purpose == "reset":
            code = svc.create_reset_code(email=email)
            subject = "blind 找回密码验证码"
            content = f"您的找回密码验证码是：{code}，10分钟内有效。"
        else:
            code = svc.create_email_code(email=email, purpose="register")
            subject = "blind 注册验证码"
            content = f"您的注册验证码是：{code}，10分钟内有效。"
        sent = svc.send_mail(email, subject, content)
        msg = "验证码已发送邮箱" if sent else "验证码已生成（SMTP未配置，已输出到后端日志）"
        return JSONResponse({"status": "ok", "message": msg})
    except Exception as e:
        return JSONResponse({"status": "error", "message": f"发送失败: {e}"}, status_code=500)


@app.post("/logout")
async def web_logout(
    request: Request,
    authorization: str | None = Header(default=None),
    x_mobile_token: str | None = Header(default=None),
) -> RedirectResponse:
    token = _extract_bearer(authorization) or (x_mobile_token or "").strip()
    request.session.clear()
    try:
        if token:
            svc = _require_auth_service()
            svc.revoke_mobile_token(token)
    except Exception:
        pass
    return RedirectResponse(url="/login?msg=已退出登录", status_code=302)


@app.post("/api/mobile/auth/login", response_model=MobileLoginResponse)
async def mobile_auth_login(req: MobileLoginRequest) -> MobileLoginResponse:
    try:
        svc = _require_auth_service()
        user = svc.verify_user(req.username, req.password)
    except Exception as e:
        raise _auth_unavailable_response() from e
    if not user:
        raise HTTPException(status_code=401, detail="invalid_credentials")
    token, expires_at = svc.create_mobile_token(user_id=user["user_id"])
    return MobileLoginResponse(
        token=token,
        expires_at=expires_at.strftime("%Y-%m-%dT%H:%M:%SZ"),
        user=user,
    )


@app.post("/api/mobile/auth/register", response_model=MobileSimpleResponse)
async def mobile_auth_register(req: MobileRegisterRequest) -> MobileSimpleResponse:
    try:
        svc = _require_auth_service()
        if not svc.consume_email_code(req.email, req.code, "register"):
            raise HTTPException(status_code=400, detail="invalid_or_expired_code")
        svc.create_user(username=req.username, email=req.email, password=req.password)
    except HTTPException:
        raise
    except ValueError as e:
        if str(e) == "username_or_email_exists":
            raise HTTPException(status_code=400, detail="username_or_email_exists") from e
        raise HTTPException(status_code=400, detail="invalid_register_payload") from e
    except Exception as e:
        raise _auth_unavailable_response() from e
    return MobileSimpleResponse(status="ok", message="registered")


@app.post("/api/mobile/auth/reset-password", response_model=MobileSimpleResponse)
async def mobile_auth_reset_password(req: MobileResetPasswordRequest) -> MobileSimpleResponse:
    try:
        svc = _require_auth_service()
        if not svc.consume_reset_code(req.email, req.code):
            raise HTTPException(status_code=400, detail="invalid_or_expired_code")
        ok = svc.update_password(email=req.email, new_password=req.new_password)
        if not ok:
            raise HTTPException(status_code=404, detail="email_not_found")
    except HTTPException:
        raise
    except Exception as e:
        raise _auth_unavailable_response() from e
    return MobileSimpleResponse(status="ok", message="password_reset")


@app.post("/api/mobile/auth/logout", response_model=MobileSimpleResponse)
async def mobile_auth_logout(
    authorization: str | None = Header(default=None),
    x_mobile_token: str | None = Header(default=None),
) -> MobileSimpleResponse:
    token = _extract_bearer(authorization) or (x_mobile_token or "").strip()
    if not token:
        raise HTTPException(status_code=400, detail="token_required")
    try:
        svc = _require_auth_service()
        svc.revoke_mobile_token(token)
    except Exception as e:
        raise _auth_unavailable_response() from e
    return MobileSimpleResponse(status="ok", message="logged_out")


@app.post("/api/mobile/auth/refresh", response_model=MobileLoginResponse)
async def mobile_auth_refresh(
    authorization: str | None = Header(default=None),
    x_mobile_token: str | None = Header(default=None),
) -> MobileLoginResponse:
    token = _extract_bearer(authorization) or (x_mobile_token or "").strip()
    if not token:
        raise HTTPException(status_code=400, detail="token_required")
    try:
        svc = _require_auth_service()
        refreshed = svc.refresh_mobile_token(token)
    except Exception as e:
        raise _auth_unavailable_response() from e
    if not refreshed:
        raise HTTPException(status_code=401, detail="invalid_or_expired_token")
    new_token, expires_at, user = refreshed
    return MobileLoginResponse(
        token=new_token,
        expires_at=expires_at.strftime("%Y-%m-%dT%H:%M:%SZ"),
        user=user,
    )


@app.get("/web", response_class=HTMLResponse)
async def web_console(request: Request) -> HTMLResponse:
    if not _session_user(request):
        return RedirectResponse(url="/login", status_code=302)
    html = """<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width,initial-scale=1" />
  <title>blind-v1 轻量导航端</title>
  <style>
    body{font-family:"Microsoft YaHei",sans-serif;background:#f4f6f8;margin:0;padding:16px;color:#1f2937}
    .wrap{max-width:920px;margin:0 auto;display:grid;gap:12px}
    .card{background:#fff;border-radius:12px;padding:12px;box-shadow:0 4px 16px rgba(0,0,0,.08)}
    .row{display:flex;gap:8px;flex-wrap:wrap}
    .btn{border:0;background:#2563eb;color:#fff;padding:10px 14px;border-radius:8px;cursor:pointer}
    .btn.stop{background:#dc2626}
    .mono{font-family:Consolas,monospace}
    .status{font-size:14px;background:#eef2ff;border-radius:8px;padding:8px}
    #viewer{width:100%;max-height:340px;object-fit:contain;background:#000;border-radius:8px}
    #chatLog{max-height:280px;overflow:auto;background:#f8fafc;padding:8px;border-radius:8px}
    .msg{margin:6px 0}
    textarea{width:100%;min-height:64px}
  </style>
</head>
<body>
  <div class="wrap">
    <div class="card">
      <h3>轻量导航控制台</h3>
      <div class="row">
        <input id="base" class="mono" style="flex:1;padding:8px" value="" />
        <button class="btn" id="btnStart">一键开始导航</button>
        <button class="btn stop" id="btnStop">停止导航</button>
      </div>
      <div id="status" class="status" style="margin-top:8px">状态：未连接</div>
      <div id="guidance" class="status" style="margin-top:8px">播报：-</div>
    </div>
    <div class="card">
      <h4>视频预览</h4>
      <img id="viewer" alt="viewer" />
    </div>
    <div class="card">
      <h4>AI 助手（文本）</h4>
      <textarea id="q" placeholder="输入问题，例如：我现在在哪？"></textarea>
      <div class="row" style="margin-top:8px"><button class="btn" id="btnAsk">发送</button></div>
      <div id="chatLog"></div>
    </div>
  </div>
<script>
(() => {
  const qs = new URLSearchParams(location.search);
  const defaultBase = qs.get("base") || `${location.protocol}//${location.hostname}:8088`;
  const elBase = document.getElementById("base");
  const elStatus = document.getElementById("status");
  const elGuidance = document.getElementById("guidance");
  const elViewer = document.getElementById("viewer");
  const elChat = document.getElementById("chatLog");
  const elQ = document.getElementById("q");
  elBase.value = defaultBase;
  let wsGuidance = null;
  let wsViewer = null;

  function wsBase() {
    const b = elBase.value.trim() || defaultBase;
    return b.replace("http://", "ws://").replace("https://", "wss://");
  }
  function httpBase() { return elBase.value.trim() || defaultBase; }
  function setStatus(t){ elStatus.textContent = "状态：" + t; }
  function addMsg(t){ const d=document.createElement("div"); d.className="msg"; d.textContent=t; elChat.appendChild(d); elChat.scrollTop=elChat.scrollHeight; }

  function connectWS() {
    if (wsGuidance) wsGuidance.close();
    if (wsViewer) wsViewer.close();
    wsGuidance = new WebSocket(wsBase() + "/ws/guidance");
    wsGuidance.onopen = () => { setStatus("已连接，导航中"); wsGuidance.send("hello"); };
    wsGuidance.onmessage = (e) => {
      try {
        const o = JSON.parse(e.data);
        const g = (o.guidance_text || "").trim();
        elGuidance.textContent = "播报：" + (g || "（空闲或无新提示）");
      } catch {}
    };
    wsGuidance.onerror = () => setStatus("网络异常（guidance）");
    wsGuidance.onclose = () => setStatus("连接已断开");

    wsViewer = new WebSocket(wsBase() + "/ws/viewer");
    wsViewer.binaryType = "arraybuffer";
    wsViewer.onopen = () => wsViewer.send("hello");
    wsViewer.onmessage = (e) => {
      const blob = new Blob([e.data], {type:"image/jpeg"});
      elViewer.src = URL.createObjectURL(blob);
    };
    wsViewer.onerror = () => setStatus("网络异常（viewer）");
  }

  async function startNav() {
    setStatus("连接中...");
    connectWS();
    try {
      await fetch(httpBase()+"/api/nav/start?mode=blind_nav", {method:"POST"});
    } catch (e) {
      setStatus("网络异常（start 失败）");
    }
  }
  async function stopNav() {
    try {
      await fetch(httpBase()+"/api/nav/stop", {method:"POST"});
    } catch {}
    if (wsGuidance) wsGuidance.close();
    if (wsViewer) wsViewer.close();
    elGuidance.textContent = "播报：-";
    setStatus("已停止");
  }

  document.getElementById("btnStart").onclick = startNav;
  document.getElementById("btnStop").onclick = stopNav;
  document.getElementById("btnAsk").onclick = async () => {
    const q = elQ.value.trim(); if (!q) return;
    addMsg("你：" + q);
    elQ.value = "";
    try {
      const r = await fetch(httpBase()+"/api/assistant/chat", {
        method:"POST",
        headers:{"Content-Type":"application/json"},
        body: JSON.stringify({message:q,user_location:null,chat_history:[]})
      });
      const j = await r.json();
      addMsg("助手：" + (j.content || "无回复"));
    } catch (e) {
      addMsg("助手：请求失败");
    }
  };
})();
</script>
</body>
</html>"""
    return HTMLResponse(content=html)


@app.post("/api/nav/start", response_model=NavCommandResponse)
async def nav_start(mode: str = MODE_BLIND_NAV, _: dict[str, Any] = Depends(require_authenticated_user)) -> NavCommandResponse:
    run_mode = MODE_TRAFFIC_TEST if mode == MODE_TRAFFIC_TEST else MODE_BLIND_NAV
    orchestrator.start(mode=run_mode)
    return NavCommandResponse(nav_enabled=orchestrator.nav_enabled, state=orchestrator.state, mode=orchestrator.mode)


@app.post("/api/traffic-test/start", response_model=NavCommandResponse)
async def traffic_test_start(_: dict[str, Any] = Depends(require_authenticated_user)) -> NavCommandResponse:
    orchestrator.start(mode=MODE_TRAFFIC_TEST)
    return NavCommandResponse(nav_enabled=orchestrator.nav_enabled, state=orchestrator.state, mode=orchestrator.mode)


@app.post("/api/nav/stop", response_model=NavCommandResponse)
async def nav_stop(_: dict[str, Any] = Depends(require_authenticated_user)) -> NavCommandResponse:
    orchestrator.stop()
    return NavCommandResponse(nav_enabled=orchestrator.nav_enabled, state=orchestrator.state, mode=orchestrator.mode)


@app.post("/api/traffic-test/stop", response_model=NavCommandResponse)
async def traffic_test_stop(_: dict[str, Any] = Depends(require_authenticated_user)) -> NavCommandResponse:
    orchestrator.stop()
    return NavCommandResponse(nav_enabled=orchestrator.nav_enabled, state=orchestrator.state, mode=orchestrator.mode)


@app.post("/api/assistant/chat")
async def assistant_chat(req: AssistantChatRequest, _: dict[str, Any] = Depends(require_authenticated_user)) -> AssistantChatResponse:
    msg_text = (req.message or "").strip()
    if msg_text and any(k in msg_text for k in STOP_HOTWORDS):
        return AssistantChatResponse(
            status="ok",
            content="已停止当前语音播报。",
            iterations=0,
            tool_history=[],
            error="",
        )

    def _execute_map_tool(action: str, params: dict) -> dict:
        if map_engine is None:
            return {"success": False, "error": "map_tool_disabled: BAIDU_MAP_AK not configured"}
        act = (action or "").strip().lower()
        p = params or {}

        if act in {"geocoding", "geocode"}:
            return map_engine.geocoding(address=str(p.get("address", "")), city=p.get("city"))
        if act in {"reverse_geocoding", "reverse_geocode"}:
            return map_engine.reverse_geocoding(
                lat=float(p.get("lat", 0.0)),
                lng=float(p.get("lng", 0.0)),
                coordtype=str(p.get("coordtype", "bd09ll")),
            )
        if act in {"search_nearby_places", "search_places", "nearby"}:
            return map_engine.search_nearby_places(
                query=str(p.get("query", "")),
                lat=float(p.get("lat", 0.0)),
                lng=float(p.get("lng", 0.0)),
                radius=int(p.get("radius", 1000)),
            )
        if act in {"calculate_route", "route_planning", "route"}:
            return map_engine.calculate_route(
                origin_lat=float(p.get("origin_lat", 0.0)),
                origin_lng=float(p.get("origin_lng", 0.0)),
                dest_lat=float(p.get("dest_lat", 0.0)),
                dest_lng=float(p.get("dest_lng", 0.0)),
                mode=str(p.get("mode", "walking")),
                coordtype=str(p.get("coordtype", "bd09ll")),
            )
        return {"success": False, "error": f"unknown_tool_action: {action}"}

    def _fast_route_assistant() -> AssistantChatResponse | None:
        if map_engine is None:
            return None

        text = req.message.strip()
        if not text:
            return None

        origin_text = ""
        dest_text = ""
        m = re.search(r"从\s*(.+?)\s*到\s*(.+?)(?:怎么走|怎么去|如何走|路线|路程|导航)?[？?]?$", text)
        if m:
            origin_text = m.group(1).strip()
            dest_text = m.group(2).strip()
        else:
            m2 = re.search(r"(?:去|到)\s*(.+?)(?:怎么走|怎么去|路线|路程|导航)?[？?]?$", text)
            if m2:
                dest_text = m2.group(1).strip()
                origin_text = "当前位置"
            else:
                return None

        if not dest_text:
            return None

        tool_history: list[dict] = []

        def _resolve_origin() -> dict:
            if origin_text in {"我", "我现在", "我现在在", "当前位置", "这里", "当前", "此处"} and req.user_location:
                return {
                    "success": True,
                    "lat": float(req.user_location.get("lat", 0.0)),
                    "lng": float(req.user_location.get("lng", 0.0)),
                    "_source": "user_location",
                }
            return map_engine.geocoding(address=origin_text)

        o = _resolve_origin()
        tool_history.append(
            {
                "step": 1,
                "action": "geocoding",
                "params": {"address": origin_text},
                "reasoning": "快速路线模式：先解析起点坐标。",
                "result": o,
            }
        )
        if not o.get("success"):
            return None

        d = map_engine.geocoding(address=dest_text)
        tool_history.append(
            {
                "step": 2,
                "action": "geocoding",
                "params": {"address": dest_text},
                "reasoning": "快速路线模式：解析终点坐标。",
                "result": d,
            }
        )
        if not d.get("success"):
            return None

        r = map_engine.calculate_route(
            origin_lat=float(o.get("lat", 0.0)),
            origin_lng=float(o.get("lng", 0.0)),
            dest_lat=float(d.get("lat", 0.0)),
            dest_lng=float(d.get("lng", 0.0)),
            mode="walking",
            coordtype="bd09ll",
        )
        tool_history.append(
            {
                "step": 3,
                "action": "calculate_route",
                "params": {
                    "origin_lat": float(o.get("lat", 0.0)),
                    "origin_lng": float(o.get("lng", 0.0)),
                    "dest_lat": float(d.get("lat", 0.0)),
                    "dest_lng": float(d.get("lng", 0.0)),
                    "mode": "walking",
                    "coordtype": "bd09ll",
                },
                "reasoning": "快速路线模式：直接规划步行路线，减少 LLM 迭代。",
                "result": r,
            }
        )
        if not r.get("success"):
            return None

        distance = float(r.get("distance", 0.0))
        duration = float(r.get("duration", 0.0))
        steps = r.get("steps") or []
        first_step = str(steps[0]).strip() if steps else "请沿规划路线前进。"
        content = (
            f"已为你规划从“{origin_text}”到“{dest_text}”的步行路线。"
            f"总距离约 {distance:.0f} 米，预计 {max(1, int(duration // 60))} 分钟。"
            f"首条指引：{first_step}"
        )
        return AssistantChatResponse(
            status="ok",
            content=content,
            iterations=1,
            tool_history=tool_history,
            error="",
        )

    fast_route = _fast_route_assistant()
    if fast_route is not None:
        return fast_route

    if assistant_engine is None:
        return AssistantChatResponse(
            status="error",
            content="AI assistant is not initialized yet. Please retry shortly.",
            iterations=0,
            tool_history=[],
            error="assistant_not_initialized",
        )

    hist = [{"role": h.role, "content": h.content} for h in req.chat_history]
    result = assistant_engine.run(
        message=req.message,
        user_location=req.user_location,
        chat_history=hist,
        execute_tool=_execute_map_tool,
    )
    return AssistantChatResponse(**result)


@app.post("/api/map/route")
async def map_route(req: MapRouteRequest, _: dict[str, Any] = Depends(require_authenticated_user)) -> JSONResponse:
    if map_engine is None:
        payload = FeatureDisabledResponse(message="Map route API disabled: BAIDU_MAP_AK not configured.").model_dump()
        return JSONResponse(content=payload, status_code=501)
    result = map_engine.calculate_route(
        origin_lat=req.origin_lat,
        origin_lng=req.origin_lng,
        dest_lat=req.dest_lat,
        dest_lng=req.dest_lng,
        mode=req.mode,
        coordtype=req.coordtype,
    )
    return JSONResponse(content=result, status_code=200 if result.get("success") else 400)


@app.get("/api/map/nearby")
async def map_nearby(
    query: str,
    lat: float,
    lng: float,
    radius: int = 1000,
    _: dict[str, Any] = Depends(require_authenticated_user),
) -> JSONResponse:
    if map_engine is None:
        payload = FeatureDisabledResponse(message="Nearby map API disabled: BAIDU_MAP_AK not configured.").model_dump()
        return JSONResponse(content=payload, status_code=501)
    result = map_engine.search_nearby_places(query=query, lat=lat, lng=lng, radius=radius)
    return JSONResponse(content=result, status_code=200 if result.get("success") else 400)


@app.post("/api/map/geocode")
async def map_geocode(req: MapGeocodeRequest, _: dict[str, Any] = Depends(require_authenticated_user)) -> JSONResponse:
    if map_engine is None:
        payload = FeatureDisabledResponse(message="Geocoding disabled: BAIDU_MAP_AK not configured.").model_dump()
        return JSONResponse(content=payload, status_code=501)
    result = map_engine.geocoding(address=req.address, city=req.city)
    return JSONResponse(content=result, status_code=200 if result.get("success") else 400)


@app.post("/api/map/reverse-geocode")
async def map_reverse_geocode(
    req: MapReverseGeocodeRequest,
    _: dict[str, Any] = Depends(require_authenticated_user),
) -> JSONResponse:
    if map_engine is None:
        payload = FeatureDisabledResponse(message="Reverse geocoding disabled: BAIDU_MAP_AK not configured.").model_dump()
        return JSONResponse(content=payload, status_code=501)
    result = map_engine.reverse_geocoding(lat=req.lat, lng=req.lng, coordtype=req.coordtype)
    return JSONResponse(content=result, status_code=200 if result.get("success") else 400)


@app.websocket("/ws/asr_proxy")
async def ws_asr_proxy(ws: WebSocket) -> None:
    user = await authenticate_websocket(ws)
    if not user:
        return
    await ws.accept()
    print(f"[BLIND][ASR] client connected user={user.get('username', 'unknown')}")
    if xfyun_asr_proxy is None or not xfyun_asr_proxy.enabled:
        await ws.send_json(
            {
                "type": "error",
                "code": "asr_proxy_disabled",
                "message": "ASR proxy is disabled. Configure XFYUN_ASR_APP_ID/XFYUN_ASR_API_KEY/XFYUN_ASR_API_SECRET.",
            }
        )
        await ws.close(code=1011)
        return

    sample_rate = 16000
    overrides: dict = {}
    first_audio_sent = False
    stop_requested = False
    text_by_sn: dict[int, str] = {}

    try:
        first_msg = await ws.receive()
        first_text = first_msg.get("text") if isinstance(first_msg, dict) else None
        if not first_text:
            await ws.send_json({"type": "error", "code": "bad_start", "message": "first message must be start json"})
            await ws.close(code=1003)
            return
        try:
            start = json.loads(first_text)
        except Exception:
            start = {}
        if str(start.get("type", "")).lower() != "start":
            await ws.send_json({"type": "error", "code": "bad_start", "message": "missing type=start"})
            await ws.close(code=1003)
            return
        sample_rate = int(start.get("sample_rate", 16000) or 16000)
        sample_rate = 16000 if sample_rate not in (8000, 16000) else sample_rate
        overrides = {
            "language": start.get("language"),
            "domain": start.get("domain"),
            "accent": start.get("accent"),
            "dwa": start.get("dwa"),
            "vad_eos": start.get("vad_eos"),
        }
        await ws.send_json({"type": "ready", "provider": "xfyun_webapi"})
    except WebSocketDisconnect:
        print("[BLIND][ASR] client disconnected before start")
        return
    except Exception as e:
        await ws.send_json({"type": "error", "code": "bad_start", "message": f"invalid start payload: {e}"})
        await ws.close(code=1003)
        return

    upstream_url = xfyun_asr_proxy.build_signed_url()
    try:
        async with websockets.connect(
            upstream_url,
            open_timeout=10,
            close_timeout=3,
            ping_interval=15,
            ping_timeout=20,
            max_size=2_000_000,
        ) as upstream:
            await ws.send_json({"type": "upstream_open"})
            print("[BLIND][ASR] upstream connected")
            while True:
                msg = await ws.receive()
                mtype = msg.get("type")
                if mtype in ("websocket.disconnect", "websocket.close"):
                    stop_requested = True
                    break
                b = msg.get("bytes")
                if b is not None:
                    b64 = base64.b64encode(b).decode("utf-8")
                    if not first_audio_sent:
                        frame = xfyun_asr_proxy.build_start_frame(b64, sample_rate=sample_rate, overrides=overrides)
                        first_audio_sent = True
                    else:
                        frame = xfyun_asr_proxy.build_continue_frame(b64, sample_rate=sample_rate)
                    await upstream.send(frame)
                    while True:
                        try:
                            upstream_msg = await asyncio.wait_for(upstream.recv(), timeout=0.001)
                        except asyncio.TimeoutError:
                            break
                        except ConnectionClosed as e:
                            await ws.send_json({"type": "error", "code": 18804, "message": f"upstream_closed: {e}"})
                            stop_requested = True
                            break
                        if not isinstance(upstream_msg, str):
                            continue
                        obj = json.loads(upstream_msg)
                        code = int(obj.get("code", -1))
                        if code != 0:
                            await ws.send_json(
                                {
                                    "type": "error",
                                    "code": code,
                                    "message": str(obj.get("message", "upstream_error")),
                                    "sid": obj.get("sid", ""),
                                }
                            )
                            stop_requested = True
                            break
                        sn, piece, is_last, pgs, rg = xfyun_asr_proxy.parse_result_text(obj)
                        if pgs == "rpl" and len(rg) == 2:
                            for k in range(rg[0], rg[1] + 1):
                                text_by_sn.pop(k, None)
                        text_by_sn[sn] = piece
                        combined = "".join([text_by_sn[k] for k in sorted(text_by_sn.keys()) if text_by_sn[k]])
                        await ws.send_json(
                            {
                                "type": "partial",
                                "sn": sn,
                                "text": combined,
                                "is_last": is_last,
                            }
                        )
                        if is_last:
                            await ws.send_json(
                                {
                                    "type": "final",
                                    "text": combined,
                                    "sid": obj.get("sid", ""),
                                }
                            )
                            stop_requested = True
                            break
                t = msg.get("text")
                if t:
                    try:
                        cmd = json.loads(t)
                    except Exception:
                        cmd = {"type": t}
                    if str(cmd.get("type", "")).lower() == "stop":
                        stop_requested = True
                        break
                if stop_requested:
                    break

            if first_audio_sent:
                await upstream.send(xfyun_asr_proxy.build_end_frame(sample_rate=sample_rate))
                drain_deadline = time.monotonic() + 3.0
                while time.monotonic() < drain_deadline:
                    try:
                        upstream_msg = await asyncio.wait_for(upstream.recv(), timeout=0.2)
                    except asyncio.TimeoutError:
                        continue
                    except ConnectionClosed:
                        break
                    if not isinstance(upstream_msg, str):
                        continue
                    obj = json.loads(upstream_msg)
                    code = int(obj.get("code", -1))
                    if code != 0:
                        await ws.send_json(
                            {
                                "type": "error",
                                "code": code,
                                "message": str(obj.get("message", "upstream_error")),
                                "sid": obj.get("sid", ""),
                            }
                        )
                        break
                    sn, piece, is_last, pgs, rg = xfyun_asr_proxy.parse_result_text(obj)
                    if pgs == "rpl" and len(rg) == 2:
                        for k in range(rg[0], rg[1] + 1):
                            text_by_sn.pop(k, None)
                    text_by_sn[sn] = piece
                    combined = "".join([text_by_sn[k] for k in sorted(text_by_sn.keys()) if text_by_sn[k]])
                    await ws.send_json({"type": "partial", "sn": sn, "text": combined, "is_last": is_last})
                    if is_last:
                        await ws.send_json({"type": "final", "text": combined, "sid": obj.get("sid", "")})
                        break
        await ws.send_json({"type": "closed", "reason": "client_stop" if stop_requested else "upstream_done"})
    except WebSocketDisconnect:
        pass
    except Exception as e:
        await ws.send_json({"type": "error", "code": 18804, "message": f"proxy_exception: {e}"})
    finally:
        print("[BLIND][ASR] client disconnected")


@app.websocket("/ws/guidance")
async def ws_guidance(ws: WebSocket) -> None:
    user = await authenticate_websocket(ws)
    if not user:
        return
    await ws.accept()
    print(f"[BLIND][GUIDANCE] connected user={user.get('username', 'unknown')}")
    guidance_clients.add(ws)
    try:
        while True:
            msg = await ws.receive()
            if msg.get("type") == "websocket.disconnect":
                break
    except WebSocketDisconnect:
        pass
    except Exception as e:
        print(f"[BLIND][GUIDANCE] socket error: {e}")
    finally:
        guidance_clients.discard(ws)
        print("[BLIND][GUIDANCE] disconnected")


@app.websocket("/ws/audio")
async def ws_audio(ws: WebSocket) -> None:
    user = await authenticate_websocket(ws)
    if not user:
        return
    await ws.accept()
    print(f"[BLIND][AUDIO] connected user={user.get('username', 'unknown')}")
    audio_clients.add(ws)
    try:
        while True:
            msg = await ws.receive()
            if msg.get("type") == "websocket.disconnect":
                break
    except WebSocketDisconnect:
        pass
    except Exception as e:
        print(f"[BLIND][AUDIO] socket error: {e}")
    finally:
        audio_clients.discard(ws)
        print("[BLIND][AUDIO] disconnected")


@app.websocket("/ws/viewer")
async def ws_viewer(ws: WebSocket) -> None:
    user = await authenticate_websocket(ws)
    if not user:
        return
    await ws.accept()
    print(f"[BLIND][VIEWER] connected user={user.get('username', 'unknown')}")
    viewer_clients.add(ws)
    try:
        while True:
            msg = await ws.receive()
            if msg.get("type") == "websocket.disconnect":
                break
    except WebSocketDisconnect:
        pass
    except Exception as e:
        print(f"[BLIND][VIEWER] socket error: {e}")
    finally:
        viewer_clients.discard(ws)
        print("[BLIND][VIEWER] disconnected")


@app.websocket("/ws/camera")
async def ws_camera(ws: WebSocket) -> None:
    global camera_uploader, frame_counter, last_audio_miss_text, last_audio_no_client_text
    user = await authenticate_websocket(ws)
    if not user:
        return
    if camera_uploader is not None and camera_uploader.client_state == WebSocketState.CONNECTED:
        try:
            await camera_uploader.close(code=1000)
        except Exception:
            pass
    camera_uploader = ws
    await ws.accept()
    print(f"[BLIND][CAMERA] connected user={user.get('username', 'unknown')}")

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
            infer_edge = 960 if orchestrator.is_traffic_test_mode else INFER_DOWNSCALE_MAX_EDGE
            bgr = _downscale_for_realtime(bgr, max_edge=infer_edge)

            if orchestrator.is_traffic_test_mode:
                blind = BlindInference(False, "", 0.0, None)
                last_traffic = runtime.infer_traffic(bgr)
            else:
                blind = runtime.infer_blind(bgr)
                if frame_counter % 3 == 0:
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
                queued, reason = await _enqueue_audio_text(
                    nav.guidance_text,
                    source="nav",
                )
                if not queued and reason == "miss_mapping":
                    if nav.guidance_text != last_audio_miss_text:
                        print(f"[BLIND][AUDIO] miss_mapping text={nav.guidance_text}")
                        last_audio_miss_text = nav.guidance_text
                elif reason == "deduped":
                    pass
                elif not audio_clients:
                    if nav.guidance_text != last_audio_no_client_text:
                        print(f"[BLIND][AUDIO] no_audio_client text={nav.guidance_text}")
                        last_audio_no_client_text = nav.guidance_text
                elif not queued and reason != "deduped":
                    print(f"[BLIND][AUDIO] enqueue_skip reason={reason} text={nav.guidance_text}")
            if frame_counter % VIEWER_SEND_EVERY_N == 0:
                viewer_jpg = _render_viewer_frame(bgr, blind.mask)
                await _broadcast_viewer_jpeg(viewer_jpg)

            if frame_counter % 30 == 0:
                mask_area = int(np.count_nonzero(blind.mask)) if blind.mask is not None else 0
                print(
                    f"[BLIND][FRAME] id={frame_counter} state={nav.state} "
                    f"blind={nav.blind_detected} mask_area={mask_area} "
                    f"traffic={nav.traffic_light} raw={traffic.raw_label} stable={traffic.stable_label} "
                    f"tconf={traffic.confidence:.2f} dropped={dropped} text={nav.guidance_text}"
                )
    except WebSocketDisconnect:
        pass
    finally:
        # Safety fallback: camera uploader disconnected, force navigation to idle
        # and clear debounce/runtime state to avoid stale guidance replay.
        if orchestrator.nav_enabled:
            orchestrator.stop()
        if camera_uploader is ws:
            camera_uploader = None
        print(
            f"[BLIND][CAMERA] disconnected uptime_sec={time.monotonic()-last_recv:.1f} "
            f"frames={frame_counter}"
        )
