package ai.gilwi.anbu

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * Gleo(차의 음성 비서)가 부르는 기능 (assets/schema.json, Pleos App Integration).
 * 주행 중에는 화면을 쓰지 않으므로, 이 앱은 말로만 받는다: 여기 저장 · 방금 것 취소 · 몇 곳 저장했는지.
 * Gleo는 component "service"인 기능을 이 이름의 브로드캐스트로 보낸다. 7초 안에 APP_ACTION_RESPONSE로 답한다.
 * 응답은 mode "silent"로 보내고 한국어 안내는 이 앱이 직접 읽는다 (Gleo 응답 문장은 영어로 정해져 있다).
 *
 * 시연용: adb shell am broadcast --user current -n ai.gilwi.anbu/.GleoReceiver -a SAVE_SPOT --es ACTION_PARAM '{"memo":"커피 맛집"}'
 */
class GleoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Notifier.init(context)
        Engine.start(context)
        // 문서에는 파라미터를 키마다 넣는다는 설명과, ACTION_PARAM 하나에 JSON으로 넣는 예제가 함께 있어 둘 다 받는다
        val params = intent.getStringExtra("ACTION_PARAM")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val memo = (params.optString("memo").ifBlank { intent.getStringExtra("memo").orEmpty() }).trim().take(MEMO_MAX)
        Log.d("Gleo: $action ${if (memo.isNotEmpty()) "($memo)" else ""}")
        val (status, english, korean) = when (action) {
            "SAVE_SPOT" -> when (val r = Engine.saveSpot(memo)) {
                Engine.SpotResult.SAVED -> Triple("success", "Saved this spot. I'll remind you when you park.",
                    if (memo.isNotEmpty()) "「$memo」${withRo(memo)} 여기 저장했어요. 차를 세우면 알려 드릴게요." else "여기 저장했어요. 차를 세우면 알려 드릴게요.")
                Engine.SpotResult.MERGED -> Triple("success", "This spot is already saved.", "방금 저장한 곳이라 하나로 합쳤어요.")
                Engine.SpotResult.NO_LOCATION -> Triple("fail", "I couldn't find the current location.", "지금 위치를 몰라서 저장하지 못했어요.")
                Engine.SpotResult.NOT_READY -> Triple("fail", "The app isn't connected to a group yet.", "아직 모임에 연결되지 않아 저장하지 못했어요.")
            }.also { Log.d("Gleo 저장 결과: ${it.first}") }
            "UNDO_SPOT" -> if (Engine.undoSpot()) Triple("success", "Deleted the spot you just saved.", "방금 저장한 곳을 지웠어요.")
                else Triple("fail", "There is no saved spot.", "지울 곳이 없어요.")
            "COUNT_SPOTS" -> Engine.spotCount().let { n ->
                Triple("success", if (n == 0) "No spots saved today." else "You saved $n spots today.",
                    if (n == 0) "오늘 저장한 곳이 없어요." else "오늘 ${n}곳 저장했어요. 차를 세우면 안부로 남길 수 있어요.")
            }
            else -> return
        }
        Engine.say(korean)
        respond(context, action, status, english)
    }

    /** "커피 맛집" → "으로", "바다" → "로" (ㄹ 받침도 "로") */
    private fun withRo(word: String): String {
        val c = word.lastOrNull() ?: return "로"
        if (c !in '가'..'힣') return "(으)로"
        val jong = (c - '가') % 28
        return if (jong == 0 || jong == 8) "로" else "으로"
    }

    private fun respond(context: Context, function: String, status: String, message: String) {
        val response = JSONObject()
            .put("functionName", function)
            .put("status", status)
            .put("message", message)
            .put("data", JSONObject())
            .put("mode", "silent") // 이 앱이 한국어로 직접 읽는다
        context.sendBroadcast(Intent(RESPONSE_ACTION)
            .putExtra("packageName", context.packageName)
            .putExtra("response", response.toString()))
    }

    companion object {
        const val RESPONSE_ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
        private const val MEMO_MAX = 40
    }
}
