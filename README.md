# blind v1 (minimal)

## Scope
- Blind path detection (`yolo-seg.pt`)
- Traffic light detection (`best.pt`)
- Navigation guidance text + backend voice stream
- AI assistant and map APIs
- Lightweight web console for user mode

## Structure
- `backend/` FastAPI websocket backend
- `android/` minimal Android shell
- `models/` local model files
- `assets/voice/` local voice wav resources
- `config/` environment templates
- `scripts/` startup scripts

## Quick Start (backend)
```powershell
cd E:\OpenAIglasses_for_Navigation-main\blind
Copy-Item .\config\.env.example .\config\.env
.\scripts\run_backend.ps1
```

## Contracts
- `WS /ws/camera` upload camera JPEG frames
- `WS /ws/guidance` receive state + guidance text
- `WS /ws/audio` receive PCM16 mono 8k audio stream
- `WS /ws/asr_proxy` stream ASR proxy for app microphone
- `POST /api/nav/start`
- `POST /api/nav/stop`
- `POST /api/assistant/chat`
- `POST /api/map/route`
- `GET /api/map/nearby`
- `GET /web` lightweight web UI entry
