# VoIP Call-Direction Diagnostics

Use this guide when an outgoing VoIP call (WhatsApp, LINE, Rakuten Link, etc.) is reported as `UNKNOWN`. The extra logs collect the signals Android exposes; they cannot guarantee direction detection on apps that do not expose call direction.

## Capture a log

Connect the device with ADB and run:

```bash
adb logcat -c
adb logcat -v threadtime -s RLAudio:I '*:S'
```

Enable Aiphone's call monitor, then make separate outgoing calls in WhatsApp, LINE, and Rakuten Link. Keep each call connected for at least 10 seconds, then end it. Save entries containing `DIRECTION_DIAG`, `AudioManager mode changed`, `Telephony state`, and `call ACTIVE`.

The log does not intentionally include call audio, phone numbers, contacts, or API keys. It may include supported VoIP package names/UIDs, recent usage timestamps, process importance, audio formats, and device types. Review the log before sharing it.

## Optional Usage Access

To include recent usage events, grant Aiphone Android's **Usage Access** special access in Settings (the exact menu varies by device, often under Apps → Special app access → Usage access). This is optional and not required for call monitoring. Without it, the log shows `usageStatsAccess=false` and omits usage events.

## What the log contains

- Android version, AudioManager mode, Telephony state, and permission status
- Active recording/playback configurations and audio-device types
- Supported VoIP apps installed and their UIDs; visible process importance, if Android exposes it
- Recent usage events/timestamps when Usage Access is granted
- Whether Aiphone has an outgoing-call marker

## Current limitation

`OutboundCallTracker.markAppInitiatedOutbound()` is intended to be called just before Aiphone launches a call app's outgoing-call Intent. In the current source, no call site invokes this method. Therefore, calls dialed directly from WhatsApp, LINE, or Rakuten Link do not set this marker. `AudioManager.MODE_IN_COMMUNICATION` indicates communication audio, but by itself does not identify whether the call is incoming or outgoing.
