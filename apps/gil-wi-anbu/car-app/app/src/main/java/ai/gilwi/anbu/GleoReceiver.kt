package ai.gilwi.anbu

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject

/**
 * Gleo(차의 음성 비서)가 부르는 기능 (assets/schema.json, Pleos App Integration).
 * 주행 중에는 화면을 쓰지 않으므로, 이 앱은 말로만 받는다: 여기 저장 · 방금 것 취소 · 다온 남기기.
 *
 * Gleo는 schema.json에서 component "service"인 기능을 Android Service로 부른다 (GleoActionService, 실제 Gleo 호출 경로).
 * 같은 이름의 브로드캐스트(GleoReceiver)도 받는다 — adb 시험용이고, 문서 예제가 브로드캐스트라서 둘 다 둔다.
 * 어느 쪽이든 7초 안에 APP_ACTION_RESPONSE로 답한다. 응답은 mode "silent"로 보내고 한국어 안내는 이 앱이 직접 읽는다.
 *
 * 시연용: adb shell am broadcast --user current -n ai.gilwi.anbu/.GleoReceiver -a LEAVE_DAON --es ACTION_PARAM '{"text":"조심히 와","to":"엄마"}'
 *        adb shell am startservice --user current -n ai.gilwi.anbu/.GleoActionService -a SAVE_SPOT --es ACTION_PARAM '{"memo":"커피 맛집"}'
 */
class GleoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        GleoActions.run(context, intent, "broadcast") { pending.finish() }
    }
}

/** Gleo가 component "service"로 부르는 길. 처리하고 응답을 보낸 뒤 스스로 끝낸다. */
class GleoActionService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService로 불렸을 때 5초 안에 포그라운드로 올라가야 한다
        runCatching {
            Notifier.init(this)
            if (Build.VERSION.SDK_INT >= 34) startForeground(FG_ID, Notifier.status(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(FG_ID, Notifier.status())
        }.onFailure { Log.d("Gleo 서비스 포그라운드 실패: $it") }
        if (intent == null) { stopSelf(startId); return START_NOT_STICKY }
        GleoActions.run(this, intent, "service") {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object { const val FG_ID = 4207 }
}

/** Gleo 기능 처리 (브로드캐스트·서비스 공통). onDone은 답을 보낸 뒤 한 번 불린다. */
object GleoActions {
    const val RESPONSE_ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
    private const val PREFIX = "ai.gilwi.anbu."
    private const val MEMO_MAX = 40
    /** Gleo는 7초 안에 답을 기다린다 */
    private const val TIMEOUT_MS = 6000L

    fun run(context: Context, intent: Intent, via: String, onDone: () -> Unit) {
        // 어떤 길로 어떤 모양으로 오는지 남겨 둔다 (Gleo가 이름 앞에 패키지를 붙여 보낼 수도 있어 벗겨서 본다)
        val raw = intent.action
        Log.d("Gleo 수신($via): action=$raw extras=${intent.extras?.keySet()?.joinToString()}")
        val action = raw?.removePrefix(PREFIX)
        if (action == null || action !in setOf("SAVE_SPOT", "UNDO_SPOT", "COUNT_SPOTS", "LEAVE_DAON", "LEAVE_ROAD_NOTE", "RECORD_DAON")) { onDone(); return }
        Notifier.init(context)
        Engine.start(context)

        var finished = false
        fun finish(status: String, english: String, korean: String, mode: String = "silent") {
            if (finished) return
            finished = true
            Engine.say(korean)
            respond(context, action, status, english, mode)
            onDone()
        }
        Handler(Looper.getMainLooper()).postDelayed({ finish("fail", "That took too long.", "처리하는 데 시간이 걸려 확인하지 못했어요.") }, TIMEOUT_MS)

        // 문서에는 파라미터를 키마다 넣는다는 설명과, ACTION_PARAM 하나에 JSON으로 넣는 예제가 함께 있어 둘 다 받는다
        val params = intent.getStringExtra("ACTION_PARAM")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        fun str(key: String) = params.optString(key).ifBlank { intent.getStringExtra(key).orEmpty() }.trim()

        // LEAVE_DAON이 지금 이름. 「NOTE」가 메시지 기능처럼 읽혀 바꿨고, 예전 이름도 받는다
        if (action == "LEAVE_DAON" || action == "LEAVE_ROAD_NOTE") { leaveNote(intent, params, ::str, ::finish); return }

        // 「다온, 녹음해 줘」: 글레오에는 바로 답하고(조용히), 남길 말은 이 앱이 직접 묻고 받는다 (VoiceNote)
        if (action == "RECORD_DAON") {
            // 글 방식도 앱이 직접 받는다 (VoiceNote). 글레오에게 되묻게(asking) 해 봤지만, 대답을 글레오가 새 말로 다시 분류해
            // 「안녕」 같은 짧은 대답이 잡담으로 빠졌다(11/10) → 되돌림.
            val busy = VoiceNote.busy
            finish(if (busy) "fail" else "success", if (busy) "Already recording." else "Recording the driver's words now.", "")
            if (!busy) VoiceNote.start(context, str("group"), params.optBoolean("useSavedSpot", false))
            return
        }

        val memo = str("memo").take(MEMO_MAX)
        val (status, english, korean) = when (action) {
            // 그룹별로 저장: 「다온, 가족 자리로 여기 저장해 줘」 → 가족 그룹의 자리. 그룹을 말하지 않으면 지금 그룹.
            "SAVE_SPOT" -> {
                val group = str("group")
                val g = Engine.findGroup(group)?.name.orEmpty()
                val forGroup = if (g.isNotEmpty()) "「$g」 자리로 " else ""
                when (val r = Engine.saveSpot(memo, group)) {
                    Engine.SpotResult.SAVED -> Triple("success", "Saved this spot for ${g.ifEmpty { "the group" }}. I'll remind you when you park.",
                        if (memo.isNotEmpty()) "「$memo」${withRo(memo)} ${forGroup}여기 저장했어요. 차를 세우면 알려 드릴게요." else "${forGroup}여기 저장했어요. 차를 세우면 알려 드릴게요.")
                    Engine.SpotResult.MERGED -> Triple("success", "This spot is already saved.", "방금 저장한 곳이라 하나로 합쳤어요.")
                    Engine.SpotResult.NO_LOCATION -> Triple("fail", "I couldn't find the current location.", "지금 위치를 몰라서 저장하지 못했어요.")
                    Engine.SpotResult.NOT_READY -> Triple("fail", "The app isn't connected to a group yet.", "아직 그룹에 연결되지 않아 저장하지 못했어요.")
                    Engine.SpotResult.NO_GROUP -> Triple("fail", "I couldn't find the group $group.", "「$group」 그룹을 찾지 못했어요. 가족이나 친구처럼 말해 주세요.")
                }.also { Log.d("Gleo 저장 결과: ${it.first} [$g]") }
            }
            "UNDO_SPOT" -> if (Engine.undoSpot()) Triple("success", "Deleted the spot you just saved.", "방금 저장한 곳을 지웠어요.")
                else Triple("fail", "There is no saved spot.", "지울 곳이 없어요.")
            else -> Engine.spotCount().let { n ->
                Triple("success", if (n == 0) "No spots saved today." else "You saved $n spots today.",
                    if (n == 0) "오늘 저장한 곳이 없어요." else "오늘 ${n}곳 저장했어요. 차를 세우면 메시지로 남길 수 있어요.")
            }
        }
        finish(status, english, korean)
    }

    /**
     * 다온 남기기. 운전 중이든 정차 중이든 화면 없이 보낸다 (전송 전 확인은 Gleo가 한다).
     * 파라미터: text(내용) · to(받는 사람 이름, 쉼표로 여럿) · when(열리는 조건 키, 비면 지금 위치) · useSavedSpot(방금 저장한 곳에 남기기)
     */
    private fun leaveNote(intent: Intent, params: JSONObject, str: (String) -> String, finish: (String, String, String) -> Unit) {
        // Gleo가 이미 바르게 풀어 보냈으면 그대로 두고, "조심히 오라고" 같은 간접 인용만 말 그대로 고친다
        // 받는 그룹 이름이 내용 앞에 섞여 오면 뗀다: 「가족한테 조심히 와」 → 「조심히 와」 (듣는 사람에게 그대로 들리므로)
        val text = stripGroup(direct(str("text").ifBlank { str("message") }), str("group"))
        // to는 "엄마" · "엄마,아빠" · ["엄마","아빠"] 모두 받는다
        val toList = params.optJSONArray("to")?.let { a -> (0 until a.length()).map { a.optString(it) } }
            ?: str("to").split(',', '、').toList()
        val whenKey = str("when")
        val useSpot = params.optBoolean("useSavedSpot", false) || intent.getBooleanExtra("useSavedSpot", false)
        // 받는 쪽은 그룹으로 말한다 (「가족에게」「친구들한테」). 사람 이름(to)은 adb 시험용으로만 남겨 둔다.
        val group = str("group")

        Engine.leaveMessage(text, toList, whenKey, useSpot, group) { r ->
            val who = r.recipients.joinToString(", ")
            val whoKo = if (who.isNotEmpty()) "${who}에게" else if (r.group.isNotEmpty()) "「${r.group}」 모두에게" else "모두에게"
            val whenKo = Engine.LEAVE_WHEN[r.whenKey].orEmpty()
            when (r.result) {
                // 무엇이 남았는지 되읽어 줘서, 화면을 못 봐도 알아들은 대로 남았는지 바로 알 수 있게
                Engine.LeaveResult.SENT -> finish("success",
                    "Left a note for ${who.ifEmpty { "everyone in ${r.group}" }}: ${r.text}",
                    if (r.place.isNotEmpty()) "${whoKo} 「${r.text}」라고, ${r.place}에 남겼어요." else "${whoKo} 「${r.text}」라고, ${whenKo} 들려주도록 남겼어요.")
                Engine.LeaveResult.EMPTY_TEXT -> finish("fail", "There is no note to leave.", "남길 말이 없어요.")
                Engine.LeaveResult.BAD_WHEN -> finish("fail", "I don't know that timing.", "언제 들려줄지 알 수 없어서 남기지 못했어요.")
                Engine.LeaveResult.NOT_READY -> finish("fail", "The app isn't connected to a group yet.", "아직 그룹에 연결되지 않아 남기지 못했어요.")
                Engine.LeaveResult.NO_GROUP -> finish("fail", "I couldn't find the group ${r.detail}.", "「${r.detail}」 그룹을 찾지 못했어요. 가족이나 친구처럼 말해 주세요.")
                Engine.LeaveResult.NO_AUTHOR -> finish("fail", "The driver isn't set yet.", "운전자가 정해지지 않아 남기지 못했어요.")
                Engine.LeaveResult.NO_RECIPIENT -> finish("fail", "I couldn't find ${r.detail} in the group.", "그룹에서 「${r.detail}」님을 찾지 못했어요.")
                Engine.LeaveResult.NO_LOCATION -> finish("fail", "I couldn't find the current location.", "지금 위치를 몰라서 남기지 못했어요.")
                Engine.LeaveResult.NO_SPOT -> finish("fail", "There is no saved spot.", "저장한 곳이 없어요.")
                Engine.LeaveResult.FAILED -> finish("fail", "Couldn't leave the note.", "쪽지를 남기지 못했어요. ${r.detail}".trim())
            }
        }
    }

    /**
     * 간접 인용을 말 그대로로: "조심히 오라고" → "조심히 와", "밥 챙겨 먹으라고" → "밥 챙겨 먹어".
     * Gleo는 말을 이름·때·내용으로 나눠서 주는데 내용이 "~라고"로 끝나기 쉽다. 스키마 설명으로 먼저 풀어 달라고 하고,
     * 그래도 남은 흔한 꼴만 여기서 고친다. 모르는 꼴은 건드리지 않는다.
     */
    /**
     * 내용 맨 앞의 받는 쪽 부르는 말을 뗀다: 「가족 오늘 고마웠어」 → 「오늘 고마웠어」, 「친구들한테 주말에 보자」 → 「주말에 보자」.
     * Gleo가 그룹을 group으로 따로 주면서도 내용에 같이 남기는 경우가 있다. 다 떼면 비는 경우엔 그대로 둔다.
     */
    fun stripGroup(text: String, group: String): String {
        val words = (listOf(group.trim()) + Engine.circles.values.map { it.name } + GROUP_CALLS)
            .filter { it.isNotBlank() }.distinct().sortedByDescending { it.length }
        if (words.isEmpty()) return text
        val r = Regex("^(" + words.joinToString("|") { Regex.escape(it) } + ")(들)?(한테|에게|께|에|아|야)?[,\\s]+")
        return text.replace(r, "").trim().ifEmpty { text }
    }

    private val GROUP_CALLS = listOf("가족", "식구", "친구", "동기", "연인", "커플", "애인", "여러분", "모두")

    fun direct(raw: String): String {
        var t = raw.trim().trim('"', '\'', '「', '」', '“', '”', '‘', '’').trim()
        t = t.replace(Regex("(^|\\s)(다온|DAON|로드레터|RoadLetter)(에서|에게|에|으로|로|한테)?(?=\\s|[,.!?]|$)", RegexOption.IGNORE_CASE), " ").trim().trimStart(',', ' ')
        // 끝의 부탁("남겨 줘", "전해 줘")은 내용이 아니다
        t = t.replace(Regex("\\s*(남겨|전해|말해|얘기해|이야기해)\\s*(줘|줘요|주세요|줄래|줄래요)[.!?]*$"), "").trim()
        for ((from, to) in INDIRECT) if (t.endsWith(from) && t.length > from.length) return (t.removeSuffix(from) + to).trim()
        // "먹으라고" → "먹어", "앉으라고" → "앉아" (ㅏ·ㅗ 모음이면 아)
        if (t.endsWith("으라고") && t.length > 3) {
            val stem = t.dropLast(3)
            val last = stem.last()
            val jung = if (last in '가'..'힣') ((last - '가') % 588) / 28 else -1
            return stem + if (jung == 0 || jung == 8) "아" else "어"
        }
        return t
    }

    private val INDIRECT = listOf(
        "지 말라고" to "지 마", "하라고" to "해", "오라고" to "와", "가라고" to "가", "보라고" to "봐", "주라고" to "줘",
        "쉬라고" to "쉬어", "타라고" to "타", "자라고" to "자", "서라고" to "서", "싶다고" to "싶어", "했다고" to "했어", "한다고" to "해", "자고" to "자",
    )

    /** "커피 맛집" → "으로", "바다" → "로" (ㄹ 받침도 "로") */
    private fun withRo(word: String): String {
        val c = word.lastOrNull() ?: return "로"
        if (c !in '가'..'힣') return "(으)로"
        val jong = (c - '가') % 28
        return if (jong == 0 || jong == 8) "로" else "으로"
    }

    /** mode: silent(이 앱이 한국어로 직접 읽음) · voice(글레오가 말함, 되물을 때) */
    private fun respond(context: Context, function: String, status: String, message: String, mode: String = "silent") {
        val response = JSONObject()
            .put("functionName", function)
            .put("status", status)
            .put("message", message)
            .put("data", JSONObject())
            .put("mode", mode)
        context.sendBroadcast(Intent(RESPONSE_ACTION)
            .putExtra("packageName", context.packageName)
            .putExtra("response", response.toString()))
    }
}
