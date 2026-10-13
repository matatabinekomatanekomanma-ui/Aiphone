# VoIP 通话方向诊断

当 WhatsApp、LINE、Rakuten Link 等 VoIP 外呼被判定为 `UNKNOWN` 时，请使用本指南。新增日志用于记录 Android 实际提供的信号；如果通话应用不公开通话方向，日志也无法保证自动识别方向。

## 获取日志

通过 ADB 连接设备并执行：

```bash
adb logcat -c
adb logcat -v threadtime -s RLAudio:I '*:S'
```

启用 Aiphone 的通话监控，分别在 WhatsApp、LINE 和 Rakuten Link 中发起外呼。每次保持通话至少 10 秒后结束。保存包含 `DIRECTION_DIAG`、`AudioManager mode changed`、`Telephony state` 和 `call ACTIVE` 的日志。

日志不会主动记录通话音频、电话号码、联系人或 API 密钥。但可能包含受支持 VoIP 应用的包名/UID、近期使用时间、进程重要性、音频格式和设备类型。分享前请检查日志内容。

## 可选的「使用情况访问权限」

如需记录近期应用使用事件，可在 Android 设置中授予 Aiphone「使用情况访问权限」（菜单位置因设备而异，通常在「应用 → 特殊应用访问权限 → 使用情况访问权限」附近）。这是可选权限，不影响基本通话监控。未授权时会显示 `usageStatsAccess=false`，并省略使用事件。

## 日志包含的内容

- Android 版本、AudioManager 模式、Telephony 状态和权限状态
- 活动录音/播放配置及音频设备类型
- 已安装的受支持 VoIP 应用及 UID；若 Android 允许，还包括可见进程的重要性
- 授权后获取的近期使用事件和时间戳
- Aiphone 是否有外呼标记

## 当前限制

`OutboundCallTracker.markAppInitiatedOutbound()` 原本用于在 Aiphone 启动通话应用的外呼 Intent 之前调用。目前源码中没有实际调用该方法的位置。因此，直接在 WhatsApp、LINE 或 Rakuten Link 中拨号不会设置外呼标记。`AudioManager.MODE_IN_COMMUNICATION` 只能说明处于通信音频模式，本身不能区分呼入和呼出。
