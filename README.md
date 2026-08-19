# Vidhi AI — upgraded Android AI assistant

Vidhi is an Android AI assistant with:

- 💬 Natural AI chat
- 🧠 Recent conversation memory stored locally on the device
- 🤖 Gemini + OpenAI backend support
- 🔁 Automatic provider fallback
- 🎙️ Hindi/Hinglish/English voice input
- 🔊 Text-to-speech replies and optional auto-speak
- 🇮🇳 Hindi, Hinglish and English language preferences
- ✍️ Dedicated Hinglish mode: Roman Hindi + English responses
- ⚙️ Provider, language, voice and appearance settings
- 🌙 Dark/light appearance
- 🧹 Clear conversation memory
- ❤️ Vidhi personality prompt
- 📡 Backend health status
- 🛡️ Optional backend bearer-token protection
- 🚦 Basic server-side rate limiting
- 🚀 GitHub Actions debug APK build

## 1. Configure the backend

Copy `backend/.env.example` to `backend/.env` and add your private provider keys:

```text
OPENAI_API_KEY=...
GEMINI_API_KEY=...
```

Never commit `.env` and never put these keys in Android source code.

Install and run:

```bash
cd backend
npm install
npm start
```

For production, deploy the backend behind HTTPS. If you set `VIDHI_API_TOKEN`, update `AiApi.kt` to send the same bearer token, or place authentication behind your own gateway.

## 2. Configure the Android app

Edit:

`app/src/main/java/com/vidhi/ai/ApiConfig.kt`

Set `BASE_URL` to your HTTPS backend URL.

For Android Emulator talking to a backend running on the host machine:

```text
http://10.0.2.2:3000
```

For a real phone, use the LAN address of your development machine or, preferably, a public HTTPS deployment.

## 3. Build the APK

Use Android Studio or the included GitHub Actions workflow. The workflow builds:

`app/build/outputs/apk/debug/app-debug.apk`

The current repository's `gradlew` is intentionally kept compatible with the original package; on GitHub Actions the workflow uses the Gradle setup action.

## Architecture

```text
Android app
   │
   ├── local conversation memory
   ├── voice input / TTS
   ├── settings
   │
   ▼
HTTPS backend
   │
   ├── OpenAI
   └── Gemini
```

The Android app never needs the provider API keys.

## Production checklist

- Use HTTPS.
- Set `VIDHI_API_TOKEN` or put auth at your gateway.
- Add persistent server-side rate limiting for multiple backend instances.
- Keep provider keys in server secrets.
- Configure a signed release build before Play Store distribution.
- Review privacy/retention requirements before storing long-term user memories.
