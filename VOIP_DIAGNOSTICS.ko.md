# VoIP 통화 방향 진단 로그

WhatsApp, LINE, Rakuten Link 등의 발신 통화가 `UNKNOWN`으로 판정될 때 이 안내를 사용하세요. 추가 로그는 Android가 제공하는 신호를 수집합니다. 통화 앱이 통화 방향을 공개하지 않는 경우 자동 판정을 보장할 수는 없습니다.

## 로그 수집

ADB로 기기를 연결하고 다음을 실행합니다.

```bash
adb logcat -c
adb logcat -v threadtime -s RLAudio:I '*:S'
```

Aiphone의 통화 모니터링을 켜고 WhatsApp, LINE, Rakuten Link에서 각각 발신합니다. 통화 연결 후 최소 10초 기다린 다음 종료합니다. `DIRECTION_DIAG`, `AudioManager mode changed`, `Telephony state`, `call ACTIVE`가 포함된 로그를 저장하세요.

로그에는 통화 오디오, 전화번호, 연락처, API 키를 의도적으로 기록하지 않습니다. 다만 지원되는 VoIP 앱의 패키지 이름/UID, 최근 사용 시각, 프로세스 중요도, 오디오 형식 및 장치 유형은 포함될 수 있으므로 공유 전에 확인하세요.

## 선택 사항: 사용정보 접근

최근 앱 사용 이벤트도 기록하려면 Android 설정에서 Aiphone에 '사용정보 접근' 특별 권한을 부여하세요. 기기에 따라 메뉴 이름과 위치가 다르며 보통 앱 → 특별 앱 액세스 → 사용정보 접근에 있습니다. 이 권한은 선택 사항이고 통화 모니터링 자체에는 필요하지 않습니다. 권한이 없으면 `usageStatsAccess=false`가 기록되고 사용 이벤트는 생략됩니다.

## 기록 항목

- Android 버전, AudioManager 모드, Telephony 상태 및 권한 상태
- 활성 녹음/재생 구성과 오디오 장치 유형
- 설치된 지원 VoIP 앱 및 UID, Android가 공개하는 경우 프로세스 중요도
- 사용정보 접근 권한이 있을 때 최근 사용 이벤트와 시각
- Aiphone 발신 마커의 설정 여부

## 현재 제한

`OutboundCallTracker.markAppInitiatedOutbound()`는 Aiphone가 통화 앱의 발신 Intent를 실행하기 직전에 호출하도록 설계되었지만, 현재 소스에는 이 메서드의 실제 호출 지점이 없습니다. 따라서 WhatsApp, LINE 또는 Rakuten Link에서 직접 발신해도 발신 마커는 설정되지 않습니다. `AudioManager.MODE_IN_COMMUNICATION`은 통신 오디오状態를 나타낼 뿐、発信か着信かは区別できません。
