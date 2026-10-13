package com.example.aiphone

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * 通話音声の取得・注入対象となる主要 VoIP / 通話アプリ。
 *
 * UID は「インストール順」ではなく、次の優先で選ぶ:
 * 2) 前回キャプチャに成功した UID
 * 3) 実行中（importance が良いもの優先）
 * 4) 使用履歴が新しいもの（PACKAGE_USAGE_STATS がある場合）
 * 5) リスト順
 *
 * 実際の開始時は [candidateUids] を順に試し、音声 peak が出た UID を採用するのが確実
 * （LINE 通話中に楽天リンク UID を掴んで無音になるのを防ぐ）。
 */
object TargetApps {
    private const val TAG = "RLAudio"

    data class App(val packageName: String, val label: String)

    val ALL: List<App> = listOf(
        App("jp.naver.line.android", "LINE"),
        App("jp.co.rakuten.mobile.rcs", "楽天リンク"),
        App("com.whatsapp", "WhatsApp"),
        App("com.whatsapp.w4b", "WhatsApp Business"),
        App("com.viber.voip", "Viber"),
        App("org.telegram.messenger", "Telegram"),
        App("org.telegram.messenger.web", "Telegram"),
        App("com.skype.raider", "Skype"),
        App("com.discord", "Discord"),
        App("com.facebook.orca", "Messenger"),
        App("com.google.android.apps.tachyon", "Google Meet"),
        App("us.zoom.videomeetings", "Zoom"),
        App("com.microsoft.teams", "Teams"),
        App("com.tencent.mm", "WeChat"),
        App("com.kakao.talk", "KakaoTalk"),
    )

    /** 前回、取得または注入に成功した UID（次回の優先候補） */
    @Volatile var lastSuccessfulUid: Int? = null

    fun installed(context: Context): List<Pair<App, Int>> {
        val pm = context.packageManager
        val out = ArrayList<Pair<App, Int>>()
        for (app in ALL) {
            try {
                val info = pm.getApplicationInfo(app.packageName, 0)
                out.add(app to info.uid)
            } catch (_: PackageManager.NameNotFoundException) {
            } catch (_: Throwable) {
            }
        }
        return out
    }

    fun resolveLabel(context: Context, uid: Int): String? =
        installed(context).firstOrNull { it.second == uid }?.first?.label

    /** 優先順付きの候補（同じ UID は1回だけ） */
    fun candidateUids(context: Context): List<Pair<App, Int>> {
        val installed = installed(context)
        if (installed.isEmpty()) {
            Log.w(TAG, "TargetApps: 対応 VoIP アプリなし")
            return emptyList()
        }
        val byPkg = installed.associateBy { it.first.packageName }
        val importance = processImportance(context)
        val recent = recentUsageScore(context) // package -> higher is more recent

        val last = lastSuccessfulUid

        fun score(app: App): Long {
            var s = 0L
            val uid = byPkg[app.packageName]?.second
            // 前回成功した UID は弱いタイブレーク(約10分ぶん)。以前は +5 億で、一度でも成功すると常に先頭に固定され、
            // LINE / WhatsApp の通話でも楽天リンクを掴む原因になっていた。通話アプリは CallAppDetector が OS 状態から特定する
            if (uid != null && last != null && uid == last) s += 600L
            val imp = importance[app.packageName]
            if (imp != null) {
                // importance が小さいほど手前 → 大きい加点
                s += (1000 - imp.coerceIn(0, 999)).toLong() * 100_000L
            }
            s += (recent[app.packageName] ?: 0L)
            // リスト順は弱いタイブレーク
            s += (ALL.size - ALL.indexOf(app)).toLong()
            return s
        }

        val ranked = installed.sortedByDescending { score(it.first) }
        Log.i(
            TAG,
            "TargetApps candidates: " + ranked.joinToString { (a, u) ->
                "${a.label}(uid=$u,imp=${importance[a.packageName] ?: "-"},score=${score(a)})"
            },
        )
        return ranked
    }

    fun resolveUid(context: Context): Int? {
        val c = candidateUids(context)
        if (c.isEmpty()) return null
        val (app, uid) = c.first()
        Log.i(TAG, "TargetApps: primary ${app.label} uid=$uid")
        return uid
    }

    fun rememberSuccess(uid: Int) {
        lastSuccessfulUid = uid
        Log.i(TAG, "TargetApps: remember success uid=$uid")
    }

    private fun processImportance(context: Context): Map<String, Int> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return emptyMap()
        val best = HashMap<String, Int>()
        try {
            @Suppress("DEPRECATION")
            val procs = am.runningAppProcesses ?: return emptyMap()
            val known = ALL.map { it.packageName }.toSet()
            for (p in procs) {
                val pkgs = buildList {
                    p.processName?.let { add(it.substringBefore(":")) }
                    p.pkgList?.forEach { add(it) }
                }
                for (pkg in pkgs) {
                    if (pkg !in known) continue
                    val prev = best[pkg]
                    if (prev == null || p.importance < prev) best[pkg] = p.importance
                }
            }
        } catch (_: Throwable) {
        }
        return best
    }

    /** 直近使用ほど大きいスコア（権限が無ければ空） */
    @SuppressLint("MissingPermission")
    private fun recentUsageScore(context: Context): Map<String, Long> {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return emptyMap()
            val end = System.currentTimeMillis()
            val begin = end - 30L * 60L * 1000L
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, begin, end)
                ?: return emptyMap()
            val known = ALL.map { it.packageName }.toSet()
            val map = HashMap<String, Long>()
            for (s in stats) {
                if (s.packageName !in known) continue
                // lastTimeUsed をスコアに
                map[s.packageName] = s.lastTimeUsed
            }
            // 相対化
            val max = map.values.maxOrNull() ?: return emptyMap()
            if (max <= 0L) return emptyMap()
            map.mapValues { (_, v) -> v / 1000L } // 秒単位で十分
        } catch (_: Throwable) {
            emptyMap()
        }
    }
}
