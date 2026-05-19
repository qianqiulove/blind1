# blind v1 (minimal)

## Scope
- Blind path detection (`yolo-seg.pt`)
- Traffic light detection (`trafficlight.pt`)
- Navigation guidance text + backend voice stream
- AI and map APIs reserved (return 501)

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
- `POST /api/nav/start`
- `POST /api/nav/stop`
- `POST /api/assistant/chat` -> 501
- `POST /api/map/route` -> 501
- `GET /api/map/nearby` -> 501

