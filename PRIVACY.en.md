# Privacy Notice

**Other languages:** [日本語](PRIVACY.md) · [简体中文](PRIVACY.zh-CN.md) · [繁體中文](PRIVACY.zh-TW.md) · [한국어](PRIVACY.ko.md) · [Español](PRIVACY.es.md)

Last updated: 2026-10-13

Aiphone is an experimental Android app under development. Use it at your own risk and read this notice before enabling call or translation features.

## 1. Data collection by the developer

The current source code does not include a feature that sends call content to a server operated by the Aiphone developer, nor does it include an analytics service. This does not mean the app never handles data locally: it processes and stores call state, audio, user-entered call briefs, settings, and personal details as needed for its features. When Gemini API is used, data sent to the API is provided to Google.

## 2. Information that may be sent to Gemini API

Depending on the enabled AI call or translation feature, the following may be sent:

- Audio or voice data captured from the other party and/or the user during a call
- Text, system instructions, and language settings needed for responses or translation
- The call purpose, brief, key points, and other information entered by the user that the AI needs to respond
- Personal details for which the user has authorized disclosure
- The Gemini API key used for authentication and technical information attached to API requests

The design attempts to include only whether a personal-info field is available in general instructions, then request permission before obtaining a specific value when needed. However, personal data entered into free-form call briefs or prompts, or otherwise sent to an AI feature, may also be transmitted.

## 3. Important warning about the free tier

Google's Gemini API Additional Terms explain that inputs and outputs from free services may be used to provide, improve, and develop Google services and machine-learning technologies, and that human reviewers may review or process API inputs and outputs for quality improvement. The terms also warn against sending confidential or personal information to free services.

**Think carefully before sending real call audio, names, phone numbers, addresses, medical information, contract details, authentication data, or confidential business information using the free tier.** Aiphone cannot change or control Google's data practices. Terms may change; review the current terms before use.

- [Gemini API Additional Terms](https://ai.google.dev/gemini-api/terms)

## 4. Data stored on the device

- The Gemini API key, app settings, personal information, and call briefs are stored in the app's private Android storage.
- The API key, personal information, and saved call briefs are encrypted with an AES-GCM key from Android Keystore before being stored in the app's private storage. If encryption of new data fails, saving is aborted instead of falling back to plaintext.
- Data stored in plaintext by older versions is migrated to encrypted storage when read. If migration fails, the original value is not overwritten, so legacy plaintext may remain on the device. If encrypted personal data cannot be decrypted, saving remains blocked while the corresponding field is blank. Re-enter that field to explicitly replace the unreadable value. This prevents blank UI fields from erasing existing data. If saved call briefs cannot be decrypted, editing and deletion are blocked rather than overwriting the original data.
- Some settings, including the user-editable system prompt and test utterance, are not encrypted with the same mechanism. Do not enter secrets or unnecessary personal information in them.
- Normal diagnostic logs are intended not to include call transcriptions, translations, or tool-response payloads. However, complete sanitization of all app and Android system logs cannot be guaranteed.
- The current version does not provide a dedicated call-recording file archive or transcription archive. However, audio may be streamed to Gemini API while an AI feature is active.

Before handing your device to someone else, check for stored information and API keys and clear app data if appropriate. Uninstalling normally deletes private app data, but this does not guarantee deletion of every backup or system-side copy.

### Call recording

When "Record call audio" is turned on in Settings (on by default), every call is recorded and saved as an M4A file in the shared Music folder of the device (`Music/Aiphone/`). The recording contains the other party's voice and the voice that reaches them (including AI/translation voice; your own voice when neither is used).

- Recordings are not sent to the developer's servers. They are stored only on the device.
- Because they are saved in the shared Music folder, other apps that are allowed to access music files, and backup or sync services, may be able to see them. Delete recordings you no longer need with a file manager.
- To record, the app captures the call app's playback through privileged access (priv-kit) during the call, and also records your microphone when AI/translation is not in use.
- Recording a call may require the other party's consent. You are responsible for the legality of recording and for informing the other party.

### Call-direction diagnostic logs

When call direction is unknown, diagnostic logs may include AudioManager mode, audio recording/playback configuration, audio-device types, supported VoIP app package names and UIDs, and visible process information. If the user grants Android Usage Access, recent usage events and timestamps for supported VoIP apps may also be logged. These diagnostic logs do not intentionally include audio content, phone numbers, contacts, or API keys. Logs remain in Android Logcat and may be exposed if collected or shared through ADB; review them before sharing.

## 5. priv-kit and Wireless debugging

The app uses priv-kit and Android Wireless debugging to connect to a bridge running in a privileged process with shell permissions. This is used for functions such as call-audio routing and provides more powerful access than a normal Android app. Configure it only on trusted devices and carefully manage pairing and network access.

## 6. Third-party services and user responsibility

Google Gemini API, Android, calling apps, priv-kit, and other services or libraries have their own terms and privacy practices. Aiphone does not control their processing or retention of data.

AI may misunderstand, mistranslate, or respond inaccurately. You are responsible for notifying the other party, obtaining consent where required, handling personal data, ensuring the call is lawful, and making final decisions about contracts or money. Do not leave important calls entirely to AI.

## 7. Changes

This notice may be updated when features or third-party services change. Refer to the latest version in this repository.
