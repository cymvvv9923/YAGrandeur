package ai.gilwi.anbu

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

data class Member(val id: String, val name: String)

data class Circle(val id: String, val name: String, val type: String, val members: List<Member>) {
    fun nameOf(memberId: String?) = members.firstOrNull { it.id == memberId }?.name ?: "누군가"
}

data class PlaceMessage(
    val id: String,
    val authorId: String,
    val authorName: String,
    val placeName: String,
    val lat: Double,
    val lon: Double,
    val radiusM: Double,
    val kind: String, // "voice" | "text"
    val text: String,
    val audioUrl: String?,
    /** 열리는 조건: "place" 장소 · "rain" 비 · "night" 밤길 · "together" 옆자리 동승 · "door" 차에 탈 때 */
    val opensOn: String = "place",
) {
    val isPlace get() = opensOn == "place"
    /** "양재 사거리에" / "「비 오는 날」에" */
    val occasion get() = if (isPlace) "${placeName}에" else "「${placeName}」에"
}

/** 서버 통신. 현재 위치는 절대 보내지 않는다. */
object Api {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val base = BuildConfig.SERVER_URL

    fun mediaUrl(path: String) = if (path.startsWith("http")) path else base + path

    fun loadCircle(circleId: String, done: (Circle?) -> Unit) = call("GET", "/api/circles/$circleId", null) { body ->
        done(body?.let { parseCircle(JSONObject(it)) })
    }

    fun loadMessages(circleId: String, done: (List<PlaceMessage>?) -> Unit) = call("GET", "/api/circles/$circleId/messages", null) { body ->
        done(body?.let { parseMessages(JSONArray(it)) })
    }

    fun sendHeard(messageId: String, memberId: String, done: () -> Unit = {}) =
        call("POST", "/api/messages/$messageId/heard", JSONObject().put("memberId", memberId)) { done() }

    fun report(messageId: String, memberId: String, done: (Boolean) -> Unit) =
        call("POST", "/api/messages/$messageId/report", JSONObject().put("memberId", memberId).put("reason", "차량 앱에서 신고")) { done(it != null) }

    fun sendDiary(circleId: String, memberId: String, date: String, text: String, done: (Boolean) -> Unit) =
        call("POST", "/api/circles/$circleId/diaries", JSONObject().put("memberId", memberId).put("date", date).put("text", text)) { done(it != null) }

    fun deleteDiaries(circleId: String, memberId: String, done: (Boolean) -> Unit) =
        call("POST", "/api/circles/$circleId/diaries/delete", JSONObject().put("memberId", memberId)) { done(it != null) }

    // 메시지 남기기·소식 (폰 웹과 같은 기능을 차에서도)

    /** 실패하면 오류 문구, 성공하면 null. */
    fun postMessage(circleId: String, body: JSONObject, done: (String?) -> Unit) =
        call("POST", "/api/circles/$circleId/messages", body, keepError = true) { text ->
            val o = text?.let { runCatching { JSONObject(it) }.getOrNull() }
            done(if (o != null && o.has("id")) null else o?.optString("error")?.takeIf { it.isNotBlank() } ?: "서버에 연결하지 못했어요")
        }

    fun deleteMessage(messageId: String, memberId: String, done: (Boolean) -> Unit) =
        call("POST", "/api/messages/$messageId/delete", JSONObject().put("memberId", memberId)) { done(it != null) }

    data class Heard(val messageId: String, val memberName: String, val authorId: String, val placeName: String, val at: String, val isPlace: Boolean = true) {
        /** "양재 사거리" / "「비 오는 날」" */
        val placeLabel get() = if (isPlace) placeName else "「$placeName」"
    }
    data class Diary(val memberName: String, val date: String, val text: String)

    fun loadFeed(circleId: String, done: (List<Heard>?, List<Diary>?) -> Unit) = call("GET", "/api/circles/$circleId/feed", null) { body ->
        val o = body?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@call done(null, null)
        val h = o.getJSONArray("heard").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            .map { Heard(it.getString("messageId"), it.optString("memberName"), it.optString("authorId"), it.optString("placeName"), it.optString("at"), it.optString("when", "place") == "place") }
        val d = o.getJSONArray("diaries").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            .map { Diary(it.optString("memberName"), it.optString("date"), it.optString("text")) }
        done(h, d)
    }

    // 사용자 관리. 성공하면 바뀐 서클, 실패하면 null과 오류 문구.

    fun updateCircle(circleId: String, name: String? = null, type: String? = null, done: (Circle?, String?) -> Unit) =
        circleCall("/api/circles/$circleId/update", JSONObject().apply { name?.let { put("name", it) }; type?.let { put("type", it) } }, done)

    fun addMember(circleId: String, name: String, done: (Circle?, String?) -> Unit) =
        circleCall("/api/circles/$circleId/members", JSONObject().put("name", name), done)

    fun renameMember(circleId: String, memberId: String, name: String, done: (Circle?, String?) -> Unit) =
        circleCall("/api/circles/$circleId/members/$memberId/rename", JSONObject().put("name", name), done)

    fun deleteMember(circleId: String, memberId: String, done: (Circle?, String?) -> Unit) =
        circleCall("/api/circles/$circleId/members/$memberId/delete", JSONObject(), done)

    fun reorderMembers(circleId: String, ids: List<String>, done: (Circle?, String?) -> Unit) =
        circleCall("/api/circles/$circleId/members/order", JSONObject().put("ids", JSONArray(ids)), done)

    private fun circleCall(path: String, body: JSONObject, done: (Circle?, String?) -> Unit) =
        call("POST", path, body, keepError = true) { text ->
            val o = text?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (o != null && o.has("members")) done(parseCircle(o), null)
            else done(null, o?.optString("error")?.takeIf { it.isNotBlank() } ?: "서버에 연결하지 못했어요")
        }

    /** keepError=true면 실패 응답 본문도 넘긴다(오류 문구 표시용). */
    private fun call(method: String, path: String, body: JSONObject?, keepError: Boolean = false, done: (String?) -> Unit) {
        io.execute {
            val result = try {
                val conn = URL(base + path).openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.connectTimeout = 4000
                conn.readTimeout = 6000
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val ok = conn.responseCode in 200..299
                val text = (if (ok) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }
                conn.disconnect()
                if (ok || keepError) text else null
            } catch (e: Exception) {
                Log.d("API $method $path 실패: $e")
                null
            }
            main.post { done(result) }
        }
    }

    private fun parseCircle(o: JSONObject): Circle {
        val arr = o.getJSONArray("members")
        val members = (0 until arr.length()).map { arr.getJSONObject(it).let { m -> Member(m.getString("id"), m.getString("name")) } }
        return Circle(o.getString("id"), o.getString("name"), o.optString("type", "family"), members)
    }

    private fun parseMessages(arr: JSONArray) = (0 until arr.length()).map {
        val m = arr.getJSONObject(it)
        PlaceMessage(
            id = m.getString("id"),
            authorId = m.getString("authorId"),
            authorName = m.optString("authorName"),
            placeName = m.optString("placeName"),
            lat = m.getDouble("lat"),
            lon = m.getDouble("lon"),
            radiusM = m.optDouble("radiusM", 300.0),
            kind = m.getString("kind"),
            text = m.optString("text"),
            audioUrl = if (m.isNull("audioUrl")) null else m.getString("audioUrl"),
            opensOn = m.optString("when", "place"),
        )
    }
}

object Log {
    fun d(msg: String) = android.util.Log.d("GILWI", msg)
}
