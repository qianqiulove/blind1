# Run Android (blind v1 minimal shell)

## 1) Configure backend endpoint
- Open:
`blind/android/app/src/main/java/com/blind/v1/MainActivity.kt`
- Set:
`private const val BASE_URL = "http://<YOUR_PC_IP>:8088"`

## 2) Open project
- Open folder `blind/android` in Android Studio.
- Let Gradle sync and install SDK components.

## 3) Run
- Select a real device or emulator.
- Press Run.

## 4) Use
- `Connect` to open websocket links.
- `Start Nav` to call backend `/api/nav/start`.
- Walk with camera stream active.
- `Stop Nav` to call `/api/nav/stop`.

## 5) Notes
- AI assistant and map buttons are placeholders in v1 and intentionally disabled.
- Audio endpoint receives PCM16 mono 8k stream from backend.

