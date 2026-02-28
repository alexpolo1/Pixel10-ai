# Pixel10 AI Server

Turn your Pixel 10 into a free AI API server. This Android app exposes the Tensor G5's on-device AI chip via a REST API, letting any device on your network make AI inference requests — no cloud, no API keys, no costs.

## How It Works

The app runs an HTTP server directly on your phone that accepts OpenAI-compatible API requests. Under the hood, it uses Google's on-device AI stack:

1. **Gemini Nano** (preferred) — The system-provided model via ML Kit Prompt API, hardware-accelerated on the Tensor G5 TPU with a 32K token context window
2. **MediaPipe LLM** (fallback) — For custom open-weight models like Gemma 3n or Gemma 2B that you supply yourself

All inference runs entirely on-device. Your data never leaves the phone.

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/v1/chat/completions` | Chat completion (OpenAI-compatible) |
| `POST` | `/v1/completions` | Text completion |
| `GET` | `/v1/models` | List available models |
| `GET` | `/health` | Server status and device info |

## Quick Start

### 1. Install and Launch

Build the APK in Android Studio and install on your Pixel 10 (or Pixel 9/8 series).

### 2. Start the Server

Open the app and tap **Start Server**. The app will:
- Load the AI model (Gemini Nano or your custom model)
- Start the HTTP server on the configured port (default: 8080)
- Display the local IP address to connect to

### 3. Make Requests

From any device on the same WiFi network:

```bash
# Chat completion
curl http://<phone-ip>:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "messages": [
      {"role": "system", "content": "You are a helpful assistant."},
      {"role": "user", "content": "What is the Tensor G5 chip?"}
    ]
  }'

# Simple completion
curl http://<phone-ip>:8080/v1/completions \
  -H "Content-Type: application/json" \
  -d '{"prompt": "Explain quantum computing in simple terms"}'

# Health check
curl http://<phone-ip>:8080/health

# List models
curl http://<phone-ip>:8080/v1/models
```

### Use with Python OpenAI Library

```python
from openai import OpenAI

client = OpenAI(
    base_url="http://<phone-ip>:8080/v1",
    api_key="not-needed"  # no auth required
)

response = client.chat.completions.create(
    model="pixel10-on-device",
    messages=[
        {"role": "user", "content": "Hello from my laptop!"}
    ]
)
print(response.choices[0].message.content)
```

## Using Custom Models (MediaPipe)

If Gemini Nano isn't available on your device, you can use custom models:

1. Download a compatible model (e.g., [Gemma 3n E2B](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android))
2. Push to the device:
   ```bash
   adb push gemma-3n-E2B.task /data/data/com.pixel10.ai/files/
   ```
3. Restart the app — it will auto-detect the model file

Supported model formats: `.task`, `.bin`, `.tflite`

## Supported Devices

- **Pixel 10 / 10 Pro / 10 Pro XL** — Full Tensor G5 TPU acceleration
- **Pixel 9 series** — Tensor G4 TPU
- **Pixel 8 series** — Tensor G3 TPU
- Other Android 12+ devices — MediaPipe backend with custom models

## Requirements

- Android 12 (API 31) or higher
- WiFi connection (for network access to the API)
- For Gemini Nano: Pixel device with AICore support
- For custom models: Compatible model file placed in app directory

## Building

```bash
# Clone the repo
git clone <repo-url>
cd Pixel10-ai

# Open in Android Studio and build, or:
./gradlew assembleDebug

# Install on connected device
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Architecture

```
com.pixel10.ai/
├── inference/
│   ├── OnDeviceModel.kt      # Unified model interface
│   ├── GeminiNanoModel.kt     # Gemini Nano via ML Kit Prompt API
│   └── MediaPipeModel.kt     # Custom models via MediaPipe LLM
├── server/
│   ├── AIApiServer.kt        # NanoHTTPD-based REST API server
│   ├── ApiModels.kt          # Request/response data classes
│   └── ApiServerService.kt   # Foreground service for background operation
├── ui/
│   └── MainActivity.kt       # Server controls and status dashboard
└── Pixel10AIApp.kt           # Application class
```

## License

MIT
