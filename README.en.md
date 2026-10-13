# Aiphone (AI Phone Assistant)

**Languages:** [日本語](README.md) · [简体中文](README.zh-CN.md) · [繁體中文](README.zh-TW.md) · [한국어](README.ko.md) · [Español](README.es.md)

Aiphone is an experimental Android app intended to help users prepare the purpose and key points of a phone call, then let an AI assistant handle parts of an outgoing call initiated by the user. It also includes experimental two-way voice translation using the Gemini API.

> **Early-stage experimental software.** The UI and usability are not yet polished. Behavior may vary by device and calling app. Do not rely on the AI alone for important calls involving contracts, money, medical matters, government services, or other consequential decisions.

## Features

- Prepare the purpose and key points of a call before dialing, and let AI respond during an outgoing call.
- A call session that lets AI respond to the other party's speech and the conversation context.
- Two-way voice translation with Gemini Live; users can choose the source and target languages.
- Save personal details on the device and request user permission before AI discloses selected details.
- Change the app UI language, including English, Chinese (Simplified and Traditional), Korean, and Spanish.

### Planned ideas

- AI handling of incoming calls
- Call recording
- Call transcription

These are future ideas, not a promise that they are available in the current version. Call recording and transcription are not presented as implemented features in this README.

## Cost

Aiphone itself has no paid plan or in-app purchases; its app features are intended to be free. Gemini API use is designed around the free usage tier and does not depend on paid-only features.

However, **Google controls Gemini API free-tier availability, models, quotas, and terms. Permanent free availability is not guaranteed.** Check current pricing and terms when creating or using an API key.

- [Create a Gemini API key (Google AI Studio)](https://aistudio.google.com/app/apikey)
- [Gemini API Additional Terms of Service](https://ai.google.dev/gemini-api/terms)

## Tested environment

- Tested device: AQUOS sense6s (SH-RM19s)
- Tested OS: Android 13
- `minSdk` is Android 11 (API 30), but this does not mean every Android 11+ device has been tested or is supported.

Only the device listed above has been physically tested so far. Vendor audio processing, calling apps, and OS restrictions may cause different behavior on other devices.

## Requirements

1. **A Gemini API key** (you must obtain your own).
2. Android Developer Options configured to obtain shell privileges through Wireless debugging using **priv-kit**.
3. A compatible Android device, a supported calling app, and the Android permissions required by the features you use.

priv-kit is a library for starting and managing a privileged Android process. Aiphone uses Wireless debugging to connect to a bridge running with shell privileges. This involves more powerful access than a normal app, so only grant access on devices and networks you understand and trust.

- [priv-kit repository](https://github.com/priv-kit/priv-kit)

## Setup overview

1. Enable **Wireless debugging** in Android Developer Options.
2. Open the priv-kit setup guide from Aiphone's Settings and follow its pairing and connection steps.
3. Enter your own Gemini API key in the app.
4. Enter personal details and the purpose, key messages, questions, and decision boundaries for the call.
5. Grant the required permissions and enable AI assistance for an outgoing call you want it to handle.
6. For translation, choose your language and the other party's language on the translation screen before starting.

Settings names and Android screens vary by device and OS version. If connection fails, check the in-app guide and diagnostics.

## Privacy and safety

Read [the privacy notice](PRIVACY.en.md). Call audio and related text may be sent to the Gemini API. Under Google's free-tier terms, prompts and outputs may be used to improve Google services, and human reviewers may process API inputs and outputs. Be particularly cautious about using the free tier with calls containing personal or confidential information.

Do not use the app to hide the fact that an AI is participating. You are responsible for explaining the AI's role to the other party, obtaining any required consent for audio processing or recording, handling personal data lawfully, and checking applicable laws and service terms.

For some VoIP calls, the app cannot reliably determine call direction. In that case, it skips automatic AI startup for safety; confirm that the call is outgoing and start the feature manually from the app.

If call direction is `UNKNOWN`, see the [VoIP call-direction diagnostics guide](VOIP_DIAGNOSTICS.en.md).

## Build

1. Clone or download this repository.
2. Open the repository root in Android Studio.
3. After Gradle sync completes, run `:app:assembleDebug`.

The build needs network access to resolve Android Gradle Plugin, Kotlin, AndroidX, Material Components, OkHttp, priv-kit, and other dependencies.

## License

Aiphone's original code is released under the [Apache License 2.0](LICENSE). Apache-2.0 permits commercial use, modification, and redistribution subject to its conditions. Keep the license and applicable copyright/attribution notices, and mark modified files as required by the license.

Third-party libraries remain under their own licenses. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). As of the review date, the priv-kit repository's top-level listing did not show a clear license file, so its licensing terms must be confirmed separately. Aiphone's license does not override third-party terms.

## Disclaimer

The software is provided "AS IS" without guarantees of behavior, accuracy, or continued availability. AI may misunderstand speech, mistranslate, or give inaccurate responses. Verify results yourself and do not delegate consequential decisions or consent to AI without oversight.
