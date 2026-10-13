package com.example.aiphone

import android.content.Context

/**
 * 「いま通話中のアプリ」を、音量ではなく OS の音声状態から特定する。
 *
 * 通話アプリ(LINE / WhatsApp / 楽天リンクなど)は、通話中に
 *  - マイクを VOICE_COMMUNICATION(7) で録音し、
 *  - 相手の声を USAGE_VOICE_COMMUNICATION(2) で再生する。
 * この録音/再生の「クライアント UID」を shell 権限(AudioBridge)で読めば、相手が黙っていても特定できる。
 * 従来の「候補を順に 1.2 秒取って、音が出たものを採用」は、相手が無言だと全滅し、先頭候補(楽天リンク)で続行していた。
 */
object CallAppDetector {
    private const val SRC_VOICE_COMMUNICATION = 7
    private const val USAGE_VOICE_COMMUNICATION = 2
    private const val STATE_STARTED = 2
    private const val FIRST_APP_UID = 10000

    /** kind: 'R'=録音 / 'P'=再生 */
    data class Client(
        val kind: Char,
        val uid: Int,
        val pkg: String?,
        val source: Int,
        val usage: Int,
        val state: Int,
        val silenced: Boolean,
    )

    data class Found(val uid: Int, val label: String, val packageName: String?, val evidence: String)

    fun parse(raw: String): List<Client> {
        val out = ArrayList<Client>()
        for (line in raw.lineSequence()) {
            if (line.length < 3 || (line[0] != 'R' && line[0] != 'P') || line[1] != ' ') continue
            val kv = HashMap<String, String>()
            for (tok in line.substring(2).split(' ')) {
                val i = tok.indexOf('=')
                if (i > 0) kv[tok.substring(0, i)] = tok.substring(i + 1)
            }
            out.add(
                Client(
                    kind = line[0],
                    uid = kv["uid"]?.toIntOrNull() ?: -1,
                    pkg = kv["pkg"]?.takeIf { it != "-" && it != "null" },
                    source = kv["src"]?.toIntOrNull() ?: -1,
                    usage = kv["usage"]?.toIntOrNull() ?: -1,
                    state = kv["state"]?.toIntOrNull() ?: -1,
                    silenced = kv["silenced"] == "true",
                ),
            )
        }
        return out
    }

    /** 一覧から通話アプリを 1 つ選ぶ。強い根拠の順: 通話用の「録音+再生」→「録音」→「再生」。 */
    fun pick(clients: List<Client>, ownUid: Int, ctx: Context): Found? {
        fun isApp(c: Client) = c.uid >= FIRST_APP_UID && c.uid != ownUid // shell(2000)・システム・自分自身は除く
        val rec = clients.filter { it.kind == 'R' && isApp(it) && it.source == SRC_VOICE_COMMUNICATION }.map { it.uid }.toSet()
        val play = clients
            .filter { it.kind == 'P' && isApp(it) && it.usage == USAGE_VOICE_COMMUNICATION && it.state == STATE_STARTED }
            .map { it.uid }.toSet()
        val both = rec.intersect(play)
        val (evidence, uids) = when {
            both.isNotEmpty() -> "通話用の録音と再生" to both
            rec.isNotEmpty() -> "通話用の録音(VOICE_COMMUNICATION)" to rec
            play.isNotEmpty() -> "通話用の再生(USAGE_VOICE_COMMUNICATION)" to play
            else -> return null
        }
        // 複数あるときは、対応アプリ一覧にあるものを優先
        val known = TargetApps.installed(ctx)
        val uid = uids.firstOrNull { u -> known.any { it.second == u } } ?: uids.first()
        val app = known.firstOrNull { it.second == uid }?.first
        val pkg = app?.packageName ?: clients.firstOrNull { it.uid == uid && it.pkg != null }?.pkg
        return Found(uid, app?.label ?: pkg ?: "uid=$uid", pkg, evidence)
    }

    /**
     * 通話アプリを特定する。見つからなければ最大 waitMs 待つ(着信に出た直後は、録音がまだ始まっていないことがある)。
     * null を返したら、呼び出し側は従来の「音量で試す」方式にフォールバックする。
     */
    fun resolve(
        bridge: IAudioBridge,
        ctx: Context,
        waitMs: Long,
        isActive: () -> Boolean,
        log: (String) -> Unit,
    ): Found? {
        val own = ctx.applicationInfo.uid
        val deadline = System.currentTimeMillis() + waitMs
        var lastSummary = ""
        while (isActive() && System.currentTimeMillis() < deadline) {
            val raw = try {
                bridge.listAudioClients()
            } catch (e: Throwable) {
                log("音声クライアント一覧の取得に失敗(${e.javaClass.simpleName})。UserService が古い可能性があります")
                return null
            }
            if (!raw.startsWith("OK")) {
                log("音声クライアント一覧を取得できません: ${raw.take(160)}")
                return null
            }
            val clients = parse(raw)
            val summary = describe(clients)
            if (summary != lastSummary) {
                log("音声クライアント: $summary")
                lastSummary = summary
            }
            val found = pick(clients, own, ctx)
            if (found != null) {
                log("通話アプリ検出: ${found.label} uid=${found.uid}（${found.evidence}）")
                return found
            }
            try { Thread.sleep(400) } catch (_: InterruptedException) { return null }
        }
        log("通話アプリを検出できませんでした（通話用の録音/再生が見つかりません）。音量で試す従来方式に切り替えます")
        return null
    }

    private fun describe(clients: List<Client>): String =
        if (clients.isEmpty()) {
            "なし"
        } else {
            clients.joinToString(" / ") { c ->
                if (c.kind == 'R') {
                    "録音 uid=${c.uid} src=${c.source}" + if (c.silenced) " 無音化" else ""
                } else {
                    "再生 uid=${c.uid} usage=${c.usage} state=${c.state}"
                }
            }
        }
}
