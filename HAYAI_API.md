# Local emotion API (added fork feature)

Start: send an explicit intent to `dev.ahmedmohamed.hayaitts/.tts.HayaiApiService` (Sherley does this itself).
Stop: "Stop" button in its notification. Listens on 127.0.0.1:11435 only.

- `GET /health`, `GET /emotions`
- `POST /speak` body `{"text","emotion","intensity":1-3,"voice":"zf_xiaoyi","speed","pitch"}` -> raw 16-bit mono PCM, chunked, header `X-Sample-Rate`.
- `POST /stop`

Emotions: calm, happy, sad, angry, shy, sleepy, curious, excited, sarcastic (see `tts/EmotionPresets.kt`).
Install the Kokoro v1.0 voice that contains `zf_xiaoyi` (the Chinese+English 54-speaker build) from the app first.
