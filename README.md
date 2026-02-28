<p align="center">
  <img src="https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white" alt="Android 12+">
  <img src="https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin">
  <img src="https://img.shields.io/badge/API-OpenAI_Compatible-412991?logo=openai&logoColor=white" alt="OpenAI Compatible">
  <img src="https://img.shields.io/github/license/alexpolo1/Pixel10-ai" alt="License">
  <img src="https://img.shields.io/github/v/release/alexpolo1/Pixel10-ai" alt="Release">
</p>

<h1 align="center">Pixel10 AI Server</h1>

<p align="center">
  <strong>Turn your Pixel into a free, private AI API server.</strong><br>
  Run Gemini Nano on the Tensor G5 chip and expose it as a local REST API — no cloud, no API keys, no cost.
</p>

---

## What is this?

An Android app that turns your Pixel phone into a self-hosted AI inference server. It runs an HTTP server directly on the device, accepting **OpenAI-compatible** API requests over your local network.

Under the hood it uses Google's on-device AI stack:

- **Gemini Nano** via ML Kit Prompt API — hardware-accelerated on the Tensor G5 TPU
- **MediaPipe LLM** as fallback — for custom open-weight models like Gemma 3n

All inference runs entirely on-device. Your data never leaves the phone.

## Features

- **OpenAI-compatible API** — drop-in replacement for `openai.ChatCompletion.create()`
- **Streaming support** — real-time Server-Sent Events (SSE) token streaming
- **Zero configuration** — install, tap Start, done
- **Fully offline** — no internet required after install
- **Private by design** — prompts and responses stay on your device
- **Background service** — keeps serving even when the app is minimized
- **Custom model support** — bring your own Gemma, LLaMA, or other compatible models

## Quick Start

### 1. Install

Download the APK from [Releases](https://github.com/alexpolo1/Pixel10-ai/releases) and install:

```bash
adb install app-debug.apk
```

Or build from source (see [Building](#building) below).

### 2. Start the Server

Open **Pixel10 AI Server**, tap **Start Server**. The app will load the model and display your device's IP address.

### 3. Send Requests

From any device on the same network:

```bash
curl http://<phone-ip>:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "messages": [{"role": "user", "content": "What is the Tensor G5 chip?"}]
  }'
```

## API Reference

All endpoints follow the [OpenAI API](https://platform.openai.com/docs/api-reference) format.

### Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/v1/chat/completions` | Chat completion (supports streaming) |
| `POST` | `/v1/completions` | Text completion |
| `GET`  | `/v1/models` | List available models |
| `GET`  | `/health` | Server status, device info, uptime |

### Chat Completion

```bash
curl http://<phone-ip>:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "pixel10-on-device",
    "messages": [
      {"role": "system", "content": "You are a helpful assistant."},
      {"role": "user", "content": "Explain quantum computing briefly."}
    ],
    "temperature": 0.7,
    "max_tokens": 1024,
    "stream": false
  }'
```

### Streaming

```bash
curl -N http://<phone-ip>:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "pixel10-on-device",
    "messages": [{"role": "user", "content": "Write a haiku about the ocean."}],
    "stream": true
  }'
```

### Python (OpenAI SDK)

```python
from openai import OpenAI

client = OpenAI(
    base_url="http://<phone-ip>:8080/v1",
    api_key="not-needed"
)

response = client.chat.completions.create(
    model="pixel10-on-device",
    messages=[{"role": "user", "content": "Hello from my laptop!"}]
)
print(response.choices[0].message.content)
```

## Custom Models (MediaPipe)

If Gemini Nano isn't available on your device, you can use custom open-weight models:

1. Download a compatible model (e.g. [Gemma 3n E2B](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android))
2. Push to the device:
   ```bash
   adb push gemma-3n-E2B.task /data/data/com.pixel10.ai/files/
   ```
3. Restart the app — it auto-detects model files

**Supported formats:** `.task`, `.bin`, `.tflite`

## Supported Devices

| Device | Chip | Backend |
|--------|------|---------|
| Pixel 10 / Pro / Pro XL | Tensor G5 | Gemini Nano (TPU-accelerated) |
| Pixel 9 series | Tensor G4 | Gemini Nano (TPU-accelerated) |
| Pixel 8 series | Tensor G3 | Gemini Nano (TPU-accelerated) |
| Other Android 12+ | Various | MediaPipe with custom models |

## Building

```bash
git clone https://github.com/alexpolo1/Pixel10-ai.git
cd Pixel10-ai
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

**Requirements:** JDK 17+, Android SDK 35

## Architecture

```
com.pixel10.ai/
├── Pixel10AIApp.kt               # Application init
├── inference/
│   ├── OnDeviceModel.kt          # Unified inference interface
│   ├── GeminiNanoModel.kt        # ML Kit Prompt API backend
│   └── MediaPipeModel.kt         # MediaPipe LLM backend
├── server/
│   ├── AIApiServer.kt            # NanoHTTPD REST server
│   ├── ApiModels.kt              # Request/response models
│   └── ApiServerService.kt       # Foreground service
└── ui/
    └── MainActivity.kt           # Server controls & dashboard
```

## Disclaimer

> This project is provided for **educational and experimental purposes only**.
>
> The Gemini Nano model is accessed through the ML Kit GenAI API, which is subject to [Google's ML Kit Terms of Service](https://developers.google.com/ml-kit/terms) and the [GenAI API Additional Terms](https://developers.google.com/ml-kit/genai-terms). Exposing on-device models as a network API may not be a documented use case under those terms. Users are responsible for reviewing and complying with all applicable terms of service.
>
> This project is not affiliated with, endorsed by, or sponsored by Google.

## Dependencies

| Library | License |
|---------|---------|
| [ML Kit GenAI](https://developers.google.com/ml-kit) | Google ToS |
| [MediaPipe](https://github.com/google-ai-edge/mediapipe) | Apache 2.0 |
| [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) | BSD 3-Clause |
| [Gson](https://github.com/google/gson) | Apache 2.0 |
| [AndroidX](https://developer.android.com/jetpack/androidx) | Apache 2.0 |
| [Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Apache 2.0 |

## License

```
Copyright 2025 alexpolo1

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0
```

See [LICENSE](LICENSE) for the full text.
