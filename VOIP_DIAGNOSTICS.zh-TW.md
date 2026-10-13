# VoIP 通話方向診斷

當 WhatsApp、LINE、Rakuten Link 等 VoIP 外撥被判定為 `UNKNOWN` 時，請參考本指南。新增記錄用來收集 Android 實際提供的訊號；若通話應用程式沒有公開通話方向，記錄也無法保證自動識別方向。

## 擷取記錄

透過 ADB 連接裝置並執行：

```bash
adb logcat -c
adb logcat -v threadtime -s RLAudio:I '*:S'
```

啟用 Aiphone 的通話監控，分別在 WhatsApp、LINE 和 Rakuten Link 中撥出電話。每次保持通話至少 10 秒後結束。保存包含 `DIRECTION_DIAG`、`AudioManager mode changed`、`Telephony state` 和 `call ACTIVE` 的記錄。

記錄不會主動包含通話音訊、電話號碼、聯絡人或 API 金鑰，但可能包含支援的 VoIP 應用程式套件名稱/UID、近期使用時間、程序重要性、音訊格式及裝置類型。分享前請先檢查內容。

## 可選的「使用情況存取權」

若要記錄近期應用程式使用事件，可在 Android 設定中授予 Aiphone「使用情況存取權」（選單位置因裝置而異，通常在「應用程式 → 特殊應用程式存取權 → 使用情況存取權」附近）。此權限為選用，不影響基本通話監控。未授權時會顯示 `usageStatsAccess=false`，並省略使用事件。

## 記錄內容

- Android 版本、AudioManager 模式、Telephony 狀態及權限狀態
- 使用中的錄音/播放設定及音訊裝置類型
- 已安裝的支援 VoIP 應用程式及 UID；若 Android 允許，也會記錄可見程序的重要性
- 授權後取得的近期使用事件與時間戳記
- Aiphone 是否設定了外撥標記

## 目前限制

`OutboundCallTracker.markAppInitiatedOutbound()` 預期在 Aiphone 啟動通話應用程式的外撥 Intent 前呼叫。目前原始碼中沒有實際呼叫該方法的位置。因此，直接從 WhatsApp、LINE 或 Rakuten Link 撥號不會設定外撥標記。`AudioManager.MODE_IN_COMMUNICATION` 只能表示目前處於通訊音訊模式，本身無法區分來電或外撥。
