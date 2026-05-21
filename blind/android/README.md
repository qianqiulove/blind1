# blind android minimal shell

This app is intentionally minimal for blind v1:
- Camera capture with CameraX
- WS upload to `/ws/camera`
- WS receive guidance from `/ws/guidance`
- WS receive PCM audio from `/ws/audio`
- Start/Stop navigation via `/api/nav/start` and `/api/nav/stop`
- AI assistant and map buttons are placeholders

Before running, set backend URL in:
`app/src/main/java/com/blind/v1/MainActivity.kt`

