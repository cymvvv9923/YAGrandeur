package ai.gilwi.anbu

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** 프로필. title은 호칭("아버지"), color는 얼굴 색 이름, photoUrl은 폰에서 올린 사진 */
data class Member(val id: String, val name: String, val title: String = "", val color: String = "gray", val photoUrl: String? = null)

data class Circle(val id: String, val name: String, val type: String, val members: List<Member>) {
    fun nameOf(memberId: String?) = members.firstOrNull { it.id == memberId }?.name ?: "누군가"
    fun member(memberId: String?) = members.firstOrNull { it.id == memberId }
    fun memberByName(name: String) = members.firstOrNull { it.name == name }
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
    /** 어느 모임의 메시지인지 (한 차가 여러 모임에 들어갈 수 있다) */
    val circleId: String = "",
    /** 받는 사람 (비면 남긴 사람 말고 모임 모두) */
    val to: List<String> = emptyList(),
    /** 답장이면 원래 메시지 id */
    val replyTo: String? = null,
    /** 시간대 (둘 다 있으면 그 시간에만, from > to면 밤을 넘김) */
    val hourFrom: Int? = null,
    val hourTo: Int? = null,
    /** 요일 1(월)~7(일). 비면 매일 */
    val days: Set<Int> = emptySet(),
    /** 이 날짜(yyyy-MM-dd)부터 */
    val notBefore: String? = null,
    /** 이 날짜(yyyy-MM-dd)까지 (그날 포함) */
    val notAfter: String? = null,
    /** once 한 번 · always 매번(하루 한 번) · weekly 주 1회까지 */
    val repeat: String = "once",
    /** 「오래 운전할 때」 기준(분) */
    val longMin: Int = 120,
    /** 「그 지역에 들어가면」 시·군·구(5자리) 또는 시·도(2자리) 코드 */
    val regionCode: String? = null,
) {
    val hasPlace get() = lat != 0.0 || lon != 0.0
    val isPlace get() = opensOn == "place"
    /** "양재 사거리에" / "「비 오는 날」에" */
    val occasion get() = if (isPlace) "${placeName}에" else "「${placeName}」에"
}

/**
 * 서버 통신. 현재 위치는 절대 보내지 않는다.
 * 가족에 연결된 뒤에는 모든 요청에 이 차의 열쇠(X-Key)를 붙인다. 서버는 열쇠로 어느 가족인지 정한다.
 */
object Api {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val base = BuildConfig.SERVER_URL
    /**
     * 지금 관리하는 모임의 열쇠. 없으면 등록 전.
     * 차는 모임마다 열쇠를 따로 받는다. 다른 모임의 일은 함수의 k에 그 모임 열쇠를 넘긴다.
     */
    @Volatile var key: String? = null
    /** 서버가 그 열쇠를 모른다고 할 때(401). 차를 모임에서 뺐거나 서버 데이터가 지워진 경우 */
    var onUnauthorized: ((String) -> Unit)? = null

    /** 서버가 닿는지 (열쇠 없이) */
    fun ping(done: (Boolean) -> Unit) = call("GET", "/privacy.html", null, k = null) { done(it != null) }

    fun mediaUrl(path: String) = when {
        path.startsWith("http") -> path
        BuildConfig.STANDALONE -> LocalServer.mediaFile(path).absolutePath // 이 차 안의 파일
        else -> base + path
    }

    fun loadCircle(circleId: String, k: String? = key, done: (Circle?) -> Unit) = call("GET", "/api/circles/$circleId", null, k = k) { body ->
        done(body?.let { parseCircle(JSONObject(it)) })
    }

    fun loadMessages(circleId: String, k: String? = key, done: (List<PlaceMessage>?) -> Unit) = call("GET", "/api/circles/$circleId/messages", null, k = k) { body ->
        done(body?.let { parseMessages(JSONArray(it)) })
    }

    // 모임 만들기·들어가기 (열쇠 없이) ----------------------------------------------

    /** 들어간 결과. memberId는 이 차의 주인 (초대로 들어가면 아직 없음) */
    data class Joined(val key: String, val circle: Circle, val memberId: String?, val carName: String)

    fun createCircle(circleName: String, type: String, myName: String, title: String, color: String, vinHash: String, done: (Joined?, String?) -> Unit) =
        joinCall("/api/circles", JSONObject().put("circleName", circleName).put("type", type).put("memberName", myName)
            .put("title", title).put("color", color), vinHash, done)

    fun joinCircle(inviteCode: String, vinHash: String, done: (Joined?, String?) -> Unit) =
        joinCall("/api/join", JSONObject().put("inviteCode", inviteCode), vinHash, done)

    /** 차 화면에서 시작할 때 개인정보 처리 안내를 보여 주고 동의를 받는다 (agreed) */
    private fun joinCall(path: String, body: JSONObject, vinHash: String, done: (Joined?, String?) -> Unit) =
        call("POST", path, body.put("device", "car").put("vinHash", vinHash).put("agreed", true), keepError = true) { text ->
            val o = text?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (o != null && o.has("key")) done(Joined(o.getString("key"), parseCircle(o.getJSONObject("circle")),
                // 주인: 새 모임은 만든 사람, 내 코드로 들어오면 그 코드의 사람
                o.optString("memberId").ifBlank { null } ?: o.optJSONObject("device")?.optString("ownerId")?.takeIf { it.isNotBlank() && it != "null" },
                o.optJSONObject("device")?.optString("name").orEmpty()), null)
            else done(null, o?.optString("error")?.takeIf { it.isNotBlank() })
        }

    /** 사람마다 「내 코드」 (설정의 「초대」). memberId를 주면 그 사람 코드를 새로 */
    data class Invitee(val id: String, val name: String, val code: String)
    fun invitees(resetFor: String? = null, done: (List<Invitee>?) -> Unit) =
        call(if (resetFor != null) "POST" else "GET", "/api/circles/me/invite" + if (resetFor != null) "/reset" else "",
            if (resetFor != null) JSONObject().put("memberId", resetFor) else null) { text ->
            done(text?.let { t -> runCatching { JSONObject(t).getJSONArray("people").let { a -> (0 until a.length()).map { a.getJSONObject(it) }
                .map { Invitee(it.getString("id"), it.optString("name"), it.getString("code")) } } }.getOrNull() })
        }

    /** 초대 코드 보기 · 새로 만들기 (설정의 「초대」) */
    fun invite(reset: Boolean = false, done: (String?) -> Unit) =
        call(if (reset) "POST" else "GET", "/api/circles/me/invite" + if (reset) "/reset" else "", if (reset) JSONObject() else null) { text ->
            done(text?.let { runCatching { JSONObject(it).optString("inviteCode") }.getOrNull() }?.takeIf { it.isNotBlank() })
        }

    /** 이 차 정보. 폰에서 차 주인·이름을 바꿨을 수 있어 주기적으로 묻는다. done(ownerId, 차 이름, 성공했는지) */
    fun me(k: String? = key, done: (String?, String?, Boolean) -> Unit) = call("GET", "/api/me", null, k = k) { body ->
        val d = body?.let { runCatching { JSONObject(it).getJSONObject("device") }.getOrNull() }
        done(d?.optString("ownerId")?.takeIf { it.isNotBlank() && it != "null" }, d?.optString("name")?.takeIf { it.isNotBlank() }, d != null)
    }

    /** 차 주인 바꾸기. 성공하면 차 이름 (기본 이름이면 서버가 「○○의 차」로 바꾼다) */
    fun setOwner(ownerId: String, k: String? = key, done: (String?) -> Unit) =
        call("POST", "/api/devices/me/owner", JSONObject().put("ownerId", ownerId), k = k) { text ->
            done(text?.let { runCatching { JSONObject(it).optString("name") }.getOrNull() })
        }

    fun renameCar(name: String, done: (Boolean) -> Unit) =
        call("POST", "/api/devices/me/rename", JSONObject().put("name", name)) { done(it != null) }

    /** 이 차 연결 끊기 */
    fun unlinkCar(done: (Boolean) -> Unit) = call("POST", "/api/devices/me/delete", JSONObject()) { done(it != null) }

    // 모임의 차 관리 (폰 웹의 「차 관리」와 같은 일을 차 화면에서) -------------------------

    /** me: 이 차인지 */
    data class Car(val id: String, val name: String, val ownerId: String?, val me: Boolean)

    fun cars(done: (List<Car>?) -> Unit) = call("GET", "/api/circles/me/cars", null) { text ->
        done(text?.let { t -> runCatching { JSONArray(t) }.getOrNull() }?.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Car(o.getString("id"), o.optString("name"), o.optString("ownerId").takeIf { s -> s.isNotBlank() && s != "null" }, o.optBoolean("me")) } }
        })
    }

    fun setCarOwner(carId: String, ownerId: String, done: (Boolean) -> Unit) =
        call("POST", "/api/devices/$carId/owner", JSONObject().put("ownerId", ownerId)) { done(it != null) }

    fun renameOtherCar(carId: String, name: String, done: (Boolean) -> Unit) =
        call("POST", "/api/devices/$carId/rename", JSONObject().put("name", name)) { done(it != null) }

    fun removeCar(carId: String, done: (Boolean) -> Unit) =
        call("POST", "/api/devices/$carId/delete", JSONObject()) { done(it != null) }

    /** 모임 통째로 지우기. 서버는 확인용으로 모임 이름을 그대로 받는다. 실패하면 오류 문구 */
    fun deleteCircle(name: String, done: (String?) -> Unit) =
        call("POST", "/api/circles/me/delete", JSONObject().put("confirm", name), keepError = true) { text ->
            val o = text?.let { runCatching { JSONObject(it) }.getOrNull() }
            done(if (o?.optBoolean("ok") == true) null else o?.optString("error")?.takeIf { it.isNotBlank() } ?: "서버에 연결하지 못했어요")
        }

    fun sendHeard(messageId: String, memberId: String, k: String? = key, done: () -> Unit = {}) =
        call("POST", "/api/messages/$messageId/heard", JSONObject().put("memberId", memberId), k = k) { done() }

    fun report(messageId: String, memberId: String, k: String? = key, done: (Boolean) -> Unit) =
        call("POST", "/api/messages/$messageId/report", JSONObject().put("memberId", memberId).put("reason", "차량 앱에서 신고"), k = k) { done(it != null) }

    fun sendDiary(circleId: String, memberId: String, date: String, text: String, done: (Boolean) -> Unit) =
        call("POST", "/api/circles/$circleId/diaries", JSONObject().put("memberId", memberId).put("date", date).put("text", text)) { done(it != null) }

    fun deleteDiaries(circleId: String, memberId: String, done: (Boolean) -> Unit) =
        call("POST", "/api/circles/$circleId/diaries/delete", JSONObject().put("memberId", memberId)) { done(it != null) }

    // 메시지 남기기·소식 (폰 웹과 같은 기능을 차에서도)

    /** 실패하면 오류 문구, 성공하면 null. */
    fun postMessage(circleId: String, body: JSONObject, k: String? = key, done: (String?) -> Unit) =
        call("POST", "/api/circles/$circleId/messages", body, keepError = true, k = k) { text ->
            val o = text?.let { runCatching { JSONObject(it) }.getOrNull() }
            done(if (o != null && o.has("id")) null else o?.optString("error")?.takeIf { it.isNotBlank() } ?: "서버에 연결하지 못했어요")
        }

    /** 글 메시지의 내용(과 받는 사람)을 고친다. 실패하면 오류 문구, 성공하면 null. */
    fun editMessage(messageId: String, memberId: String, text: String?, to: List<String>? = null, audioBase64: String? = null, k: String? = key, done: (String?) -> Unit) =
        call("POST", "/api/messages/$messageId/edit",
            JSONObject().put("memberId", memberId).apply {
                text?.let { put("text", it) }
                to?.let { put("to", JSONArray(it)) }
                audioBase64?.let { put("audioBase64", it).put("audioExt", "m4a") } // 목소리: 예전 녹음을 이것으로 바꾼다
            }, keepError = true, k = k) { body ->
            val o = body?.let { runCatching { JSONObject(it) }.getOrNull() }
            done(if (o?.optBoolean("ok") == true) null else o?.optString("error")?.takeIf { it.isNotBlank() } ?: "서버에 연결하지 못했어요")
        }

    fun deleteMessage(messageId: String, memberId: String, k: String? = key, done: (Boolean) -> Unit) =
        call("POST", "/api/messages/$messageId/delete", JSONObject().put("memberId", memberId), k = k) { done(it != null) }

    /** at: 처음 들은 때, lastAt: 마지막으로 들은 때 (다른 차에서 들은 것까지) */
    data class Heard(val messageId: String, val memberName: String, val authorId: String, val placeName: String, val at: String, val isPlace: Boolean = true,
                     val memberId: String = "", val lastAt: String = at) {
        /** "양재 사거리" / "「비 오는 날」" */
        val placeLabel get() = if (isPlace) placeName else "「$placeName」"
    }
    data class Diary(val memberName: String, val date: String, val text: String)

    fun loadFeed(circleId: String, k: String? = key, done: (List<Heard>?, List<Diary>?) -> Unit) = call("GET", "/api/circles/$circleId/feed", null, k = k) { body ->
        val o = body?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@call done(null, null)
        val h = o.getJSONArray("heard").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            .map {
                val at = it.optString("at")
                Heard(it.getString("messageId"), it.optString("memberName"), it.optString("authorId"), it.optString("placeName"), at,
                    it.optString("when", "place") == "place", it.optString("memberId"), it.optString("lastAt", at).ifBlank { at })
            }
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
        circleCall("/api/circles/$circleId/members/$memberId/update", JSONObject().put("name", name), done)

    fun setMemberColor(circleId: String, memberId: String, color: String, done: (Circle?, String?) -> Unit) =
        circleCall("/api/circles/$circleId/members/$memberId/update", JSONObject().put("color", color), done)

    /** 호칭 (빈 글이면 호칭을 뺀다) */
    fun setMemberTitle(circleId: String, memberId: String, title: String, done: (Circle?, String?) -> Unit) =
        circleCall("/api/circles/$circleId/members/$memberId/update", JSONObject().put("title", title), done)

    fun deletePhoto(circleId: String, memberId: String, done: (Circle?, String?) -> Unit) =
        circleCall("/api/circles/$circleId/members/$memberId/photo/delete", JSONObject(), done)

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
    private fun call(method: String, path: String, body: JSONObject?, keepError: Boolean = false, k: String? = key, done: (String?) -> Unit) {
        io.execute {
            // 앱만 버전: 네트워크 대신 앱 안 서버로 (같은 주소, 같은 JSON)
            if (BuildConfig.STANDALONE) {
                val r = LocalServer.handle(method, path, body, k)
                if (r.code == 401 && k != null) main.post { onUnauthorized?.invoke(k) }
                val ok = r.code in 200..299
                main.post { done(if (ok || keepError) r.body else null) }
                return@execute
            }
            val result = try {
                val conn = URL(base + path).openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.connectTimeout = 4000
                conn.readTimeout = 6000
                k?.let { conn.setRequestProperty("X-Key", it) }
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val ok = conn.responseCode in 200..299
                if (conn.responseCode == 401 && k != null) main.post { onUnauthorized?.invoke(k) }
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
        val members = (0 until arr.length()).map {
            arr.getJSONObject(it).let { m ->
                Member(m.getString("id"), m.getString("name"), m.optString("title"), m.optString("color", "gray"),
                    m.optString("photoUrl").takeIf { u -> u.isNotBlank() })
            }
        }
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
            circleId = m.optString("circleId"),
            to = m.optJSONArray("to")?.let { a -> (0 until a.length()).map { i -> a.getString(i) } } ?: emptyList(),
            replyTo = m.optString("replyTo").ifBlank { null },
            hourFrom = if (m.has("hourFrom")) m.getInt("hourFrom") else null,
            hourTo = if (m.has("hourTo")) m.getInt("hourTo") else null,
            days = m.optJSONArray("days")?.let { a -> (0 until a.length()).map { i -> a.getInt(i) }.toSet() } ?: emptySet(),
            notBefore = m.optString("notBefore").ifBlank { null },
            notAfter = m.optString("notAfter").ifBlank { null },
            repeat = m.optString("repeat", "once").ifBlank { "once" },
            longMin = m.optInt("longMin", 120),
            regionCode = m.optString("regionCode").ifBlank { null },
        )
    }
}

/** 프로필 사진. 한 번 받은 것은 메모리에 둔다 (실패도 기억해서 계속 다시 받지 않는다). */
object Photos {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cache = HashMap<String, android.graphics.Bitmap?>()
    private val loading = HashSet<String>()

    /** 있으면 바로 돌려주고, 없으면 받기 시작한 뒤 null. 다 받으면 onLoaded를 부른다. */
    fun get(url: String, onLoaded: () -> Unit): android.graphics.Bitmap? {
        if (cache.containsKey(url)) return cache[url]
        if (loading.add(url)) io.execute {
            val bmp = runCatching {
                if (BuildConfig.STANDALONE) android.graphics.BitmapFactory.decodeFile(Api.mediaUrl(url))
                else URL(Api.mediaUrl(url)).openStream().use { android.graphics.BitmapFactory.decodeStream(it) }
            }.getOrNull()
            main.post { cache[url] = bmp; loading.remove(url); if (bmp != null) onLoaded() }
        }
        return null
    }
}

object Log {
    fun d(msg: String) = android.util.Log.d("GILWI", msg)
}
