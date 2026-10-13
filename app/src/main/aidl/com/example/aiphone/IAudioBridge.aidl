package com.example.aiphone;

import android.os.ParcelFileDescriptor;

interface IAudioBridge {
    // Shizuku の規約: destroy は 16777114 固定
    void destroy() = 16777114;

    String diagnose(int targetUid) = 1;
    // 相手音声を取得し PCM(s16le/mono) を書き込む。特権パターンは16kHz、それ以外は48kHz(戻り値の rate= 参照)
    String startCapture(int targetUid, int variant, in ParcelFileDescriptor sink) = 2;
    String stopCapture() = 3;
    // input から読んだ PCM(s16le/mono/48kHz)を、通話アプリのマイク入力として注入する
    String startInject(int targetUid, int variant, in ParcelFileDescriptor input) = 4;
    String stopInject() = 5;
    // 前回の呼び出し以降の取得音声のピーク値(0-32767)
    int captureLevel() = 6;
    // 直近の失敗/診断の詳細レポート(手順ログ・スタックトレース・権限状況・システムログ抜粋)
    String lastReport() = 7;
    // shell 権限で SYSTEM_ALERT_WINDOW を allow する（パッケージ名）
    String grantOverlayPermission(String packageName) = 8;

    // 本物のマイクを 16kHz/mono/s16le で録音して sink へ書く(翻訳機能用)。source は MediaRecorder.AudioSource の値(7=VOICE_COMMUNICATION, 1=MIC)
    String startMicCapture(int source, in ParcelFileDescriptor sink) = 9;
    String stopMicCapture() = 10;

    // いま録音/再生している音声クライアントの一覧(通話アプリの特定用)。1 行 1 クライアント(R=録音 / P=再生)。失敗時は "NG: ..."
    String listAudioClients() = 11;
}
