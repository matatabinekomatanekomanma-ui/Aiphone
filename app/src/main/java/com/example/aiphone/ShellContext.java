package com.example.aiphone;

import android.content.Context;
import android.content.ContextWrapper;

/** shell UID のプロセスで「com.android.shell」として振る舞う Context。 */
public class ShellContext extends ContextWrapper {
    public ShellContext(Context base) {
        super(base);
    }

    @Override
    public String getPackageName() {
        return "com.android.shell";
    }

    // hidden API。@Override を付けずに定義する(ランタイムでは仮想呼び出しで上書きされる)
    public String getOpPackageName() {
        return "com.android.shell";
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    /**
     * AudioRecord(Android 13)は Context#getAttributionSource() の packageName を AppOps に渡す。
     * 何もしないと system context の "android" が渡り、uid 2000 と一致せず SecurityException →
     * startRecording が PERMISSION_DENIED になる(実機ログ: Package "android" does not belong to uid 2000)。
     * そこで packageName を "com.android.shell" にした AttributionSource を返す。
     */
    @Override
    @androidx.annotation.RequiresApi(31)
    public android.content.AttributionSource getAttributionSource() {
        try {
            android.content.AttributionSource.Builder b =
                new android.content.AttributionSource.Builder(android.os.Process.myUid())
                    .setPackageName("com.android.shell");
            try { // setPid は環境によって隠れていることがあるのでリフレクション
                android.content.AttributionSource.Builder.class
                    .getMethod("setPid", int.class).invoke(b, android.os.Process.myPid());
            } catch (Throwable ignored) { }
            return b.build();
        } catch (Throwable t) {
            return super.getAttributionSource();
        }
    }
}
