# Groq Voice Typer for Android 🎙️🚀

Groq Voice Typer is a lightweight, modern, and privacy-focused Android Voice Keyboard (IME) powered by the ultra-fast **Groq Whisper API**. It lets you dictate directly into any application's text fields at blistering speeds.

---

## Key Features

* **High-Speed Transcription:** Direct integration with Groq's `whisper-large-v3` API for near-instant, high-accuracy English speech-to-text.
* **Privacy & Security First:**
  * **Encryption at Rest:** Your Groq API Key is encrypted locally using Android's hardware-backed Keystore system (`EncryptedSharedPreferences`).
  * **No Telemetry:** Zero third-party analytics, tracking, or telemetry libraries.
  * **Direct Calls:** App communicates strictly with the official Groq API endpoint (`https://api.groq.com`).
  * **Ephemeral Storage:** Audio files are stored temporarily in the internal cache directory and explicitly deleted immediately after transcription finishes.
* **Premium User Interface:**
  * Beautiful dark-mode interface built using **Jetpack Compose**.
  * Dynamic, canvas-drawn live audio waveform visualization.
  * Easy-to-use setup wizard to configure permissions and keyboard settings.
* **Smart Input Interactions:**
  * Supports both **Tap-to-speak** (toggles start/stop) and **Hold-to-speak** (release to transcribe) gestures.
  * Includes quick Spacebar, Enter, Backspace, and IME Switch keys.

---

## How to Install & Build

### Option A: Build in the Cloud (No local installation needed)

1. **Fork/Clone** this repository to your GitHub account.
2. Go to the **Actions** tab of your repository on GitHub.
3. Click on the completed **Build Android APK** workflow run.
4. Under the **Artifacts** section at the bottom, click **GroqVoiceTyper-APK** to download the zip file.
5. Extract the zip and install the `app-debug.apk` directly on your Android phone!

### Option B: Build Locally via Android Studio

1. Clone this repository:
   ```bash
   git clone https://github.com/raviumeshkulkarni-web/Voice-typer.git
   ```
2. Open the project in **Android Studio** (Koala or newer recommended).
3. Connect your Android device (with USB debugging enabled) or start an Emulator.
4. Click the **Run** button to build and install the application.

---

## Setup & Configuration Guide

After installing the app, follow these simple steps inside the configuration wizard:

1. **Paste your Groq API Key** (starts with `gsk_`) and click **Save**. (You can get an API key for free from the [Groq Console](https://console.groq.com)).
2. **Grant Microphone Permission** so the keyboard can record audio.
3. **Enable Keyboard Service** in Android System Settings.
4. **Switch Default Keyboard** to make **Groq Voice Typer** your active input method.
5. Practice and test typing directly within the app's **Practice Area** text field!

---

## Tech Stack

* **Language:** Kotlin
* **UI Toolkit:** Jetpack Compose (Material 3)
* **Network:** OkHttp
* **Security:** AndroidX Security Crypto (EncryptedSharedPreferences)
* **API Engine:** Groq Whisper Speech-to-Text (`whisper-large-v3`)

---

## License

This project is open-source and available under the [MIT License](LICENSE).
