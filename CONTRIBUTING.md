# Contributing to Pixel10 AI Server

Contributions are welcome! Here's how to get started.

## Development Setup

1. Clone the repo
2. Open in Android Studio (Ladybug or newer) or build from CLI
3. Ensure you have JDK 17+ and Android SDK 35 installed
4. Build: `./gradlew assembleDebug`

## Making Changes

1. Fork the repo and create a branch from `main`
2. Make your changes
3. Test on a physical device (emulators don't have Tensor TPU or AICore)
4. Submit a pull request

## Code Style

- Follow existing Kotlin conventions in the project
- Use coroutines for async work (no callbacks)
- Keep the OpenAI API compatibility — don't break existing endpoints

## Reporting Issues

Use the GitHub issue templates for bug reports and feature requests. Include your device model and Android version.

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.
