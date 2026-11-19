# Talk-n-Learn

An Android app for practising spoken German (A2 → B1) with Lena, an AI conversation partner. You speak German, Lena answers by voice, and after each turn you get a correction and a short speaking tip, with comparisons to Hindi where they help.

## Features

- **Role-play conversations:** 12 everyday situations (doctor, Bürgeramt, team meeting…). Pick one, or start with a random one.
- **Pause-tolerant speech input:** listening continues across pauses. Tap the mic to send.
- **Hands-free mode:** Lena speaks, then the app listens again. A long silence sends your answer, and saying "Stopp" ends the chat.
- **Pocket mode:** a black, touch-locked screen while hands-free keeps running. Double-tap to wake.
- **Feedback on every turn:** a corrected version of what you said, a speaking tip, and a 💡 example answer you can listen to.
- **Progress:** daily 10-minute goal, streak, minutes per day, words used, and your most frequent mistakes, each with a Drill button.
- **Pronunciation practice:** words Lena thinks you mispronounced are collected. You drill them against the speech recognizer, with no AI cost.
- **Providers:**
  - Anthropic (Haiku 5.5 / Sonnet 5.5 / Opus 5.5).
  - Azure AI Foundry, with any deployed model: names starting with `claude` use Foundry's Claude endpoint, everything else uses the OpenAI-style chat completions endpoint.

## Costs

Only the AI text generation is billed. Speech-to-text and text-to-speech use Android's built-in services and are free.

- Haiku 5.5 is about 40× cheaper than Opus 5.5.
- Prompt caching is on, so long chats re-read earlier turns at the cache price.

## Setup

Requirements: JDK 17+ and the Android SDK (compileSdk 36). The minimum Android version is 8.0 (API 26).

1. Put your SDK path in `local.properties`:
   ```
   sdk.dir=/path/to/Android/Sdk
   ```
2. Build and run the tests:
   ```
   ./gradlew :app:testDebugUnitTest :app:assembleDebug
   ```
3. Install on a connected phone with USB debugging on:
   ```
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
4. In the app, open **Settings** and choose a provider:
   - **Anthropic:** an API key from console.anthropic.com. Billing is separate from a Claude.ai subscription.
   - **Azure:** your endpoint (`https://YOUR-RESOURCE.services.ai.azure.com`), key, and deployment name.

Optional: download German for offline speech recognition (Google voice typing → Offline speech recognition → Deutsch). Without it, the app uses online recognition automatically.

## Project layout

| File | What it does |
|---|---|
| `app/src/main/java/app/talknlearn/MainActivity.kt` | App shell, navigation, chat screen (speech, hands-free, pocket mode), settings |
| `app/src/main/java/app/talknlearn/Lena.kt` | System prompt, JSON reply schema, Anthropic and Azure calls, reply parsing |
| `app/src/main/java/app/talknlearn/Progress.kt` | Progress tracking in DataStore (minutes, streak, words, mistakes, pronunciation words), daily tips |
| `app/src/main/java/app/talknlearn/Screens.kt` | Home, Progress and pronunciation practice screens |
| `app/src/test/java/app/talknlearn/` | Unit tests for parsing, the Azure request/response, and progress logic |

Stack: Kotlin, Jetpack Compose (Material 3), DataStore Preferences, and the [Anthropic Java SDK](https://github.com/anthropics/anthropic-sdk-java). Android `SpeechRecognizer` and `TextToSpeech` handle voice.

## Known limits

- API keys are stored unencrypted in app-private storage. Move them to the Android Keystore before sharing the app.
- The AI never hears audio, only the recognizer's text. Pronunciation problems are inferred from recognizer mistakes.
- The Azure paths for non-OpenAI models have not been tested against a live resource yet.
- "Minutes spoken" counts time the mic is listening, including pauses.
