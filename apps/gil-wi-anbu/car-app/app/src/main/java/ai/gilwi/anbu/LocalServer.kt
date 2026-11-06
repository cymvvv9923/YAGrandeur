package ai.gilwi.anbu

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 앱만으로 동작하는 시연 버전(v002-standalone)의 「앱 안 서버」.
 * server/server.js가 하던 일(모임·프로필·메시지·들었어요·답장·일기)을 같은 주소와 같은 JSON으로 앱 안에서 처리한다.
 * Api.call이 네트워크 대신 여기로 보낸다. 데이터는 이 차 안(filesDir/local_db.json)에만 있다.
 * 처음 켜면 assets/demo/seed.json(로드레터 시나리오 가족)으로 시작한다.
 */
object LocalServer {

    private lateinit var dir: File
    private lateinit var dbFile: File
    lateinit var mediaDir: File
        private set
    private lateinit var app: Context
    private var db = JSONObject()
    private val rnd = SecureRandom()

    private val WHEN_LABELS = mapOf(
        "place" to "장소", "rain" to "비 오는 날", "night" to "밤길", "together" to "옆자리에 누가 탄 날", "door" to "차에 탈 때",
        "start" to "시동 걸 때", "park" to "도착해 차를 세울 때", "long" to "오래 운전할 때", "warning" to "경고등이 켜질 때", "region" to "그 지역에 들어가면",
    )
    private val WITH_PLACE = setOf("place", "park", "region")
    private val COLORS = listOf("blue", "green", "orange", "purple", "pink", "teal", "red", "gray")
    private val CIRCLE_TYPES = setOf("family", "couple", "friends")

    @Synchronized
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        dir = app.filesDir
        dbFile = File(dir, "local_db.json")
        mediaDir = File(dir, "media").apply { mkdirs() }
        if (!dbFile.exists()) copySeed()
        db = runCatching { JSONObject(dbFile.readText()) }.getOrElse { copySeed(); JSONObject(dbFile.readText()) }
        copyMedia()
    }

    /** 시연 데이터로 되돌리기 (모임·메시지·기기 열쇠 모두 처음 상태) */
    @Synchronized
    fun reset() {
        copySeed()
        db = JSONObject(dbFile.readText())
        copyMedia()
    }

    private fun copySeed() = app.assets.open("demo/seed.json").use { i -> dbFile.outputStream().use { i.copyTo(it) } }

    private fun copyMedia() {
        app.assets.list("demo/media")?.forEach { f ->
            val to = File(mediaDir, f)
            if (!to.exists()) app.assets.open("demo/media/$f").use { i -> to.outputStream().use { i.copyTo(it) } }
        }
    }

    private fun save() = dbFile.writeText(db.toString())

    /** "/media/v_x.m4a" → 이 차 안의 파일 */
    fun mediaFile(url: String) = File(mediaDir, url.substringAfterLast('/'))

    // 요청 처리 -------------------------------------------------------------

    class Reply(val code: Int, val body: String)

    private fun ok(o: Any, code: Int = 200) = Reply(code, o.toString())
    private fun err(code: Int, msg: String) = Reply(code, JSONObject().put("error", msg).toString())

    @Synchronized
    fun handle(method: String, path: String, body: JSONObject?, key: String?): Reply = runCatching { route(method, path, body ?: JSONObject(), key) }
        .getOrElse { Log.d("앱 안 서버 오류 $method $path: $it"); err(500, "처리하지 못했어요") }

    private fun route(m: String, path: String, b: JSONObject, key: String?): Reply {
        val p = path.substringBefore('?').trimEnd('/').split('/').drop(1) // ["api", ...]
        if (p.firstOrNull() != "api") return if (m == "GET") Reply(200, "ok") else err(404, "not found")
        val r = p.drop(1)
        // 열쇠 없이
        if (m == "POST" && r == listOf("circles")) return createCircle(b)
        if (m == "POST" && r == listOf("join")) return join(b)
        if (r.firstOrNull() == "tts") return err(404, "not found")
        // 열쇠가 있어야
        val device = deviceOf(key) ?: return err(401, "이 기기는 모임에 연결되어 있지 않아요")
        val circle = circles().firstOrNull { it.getString("id") == device.getString("circleId") } ?: return err(401, "모임이 없어졌어요")
        when {
            m == "GET" && r == listOf("me") -> return ok(JSONObject().put("device", publicDevice(device)).put("circle", publicCircle(circle)))
            r.getOrNull(0) == "devices" && r.size == 3 -> return deviceRoute(r[2], r[1], b, device, circle)
            r.getOrNull(0) == "messages" && r.size == 3 -> return messageRoute(r[2], r[1], b, device, circle)
            r.getOrNull(0) == "circles" && r.size >= 2 -> return circleRoute(m, r.drop(2), b, device, circle)
        }
        return err(404, "not found")
    }

    private fun circleRoute(m: String, r: List<String>, b: JSONObject, device: JSONObject, circle: JSONObject): Reply {
        val cid = circle.getString("id")
        val members = circle.getJSONArray("members")
        fun member(id: String) = (0 until members.length()).map { members.getJSONObject(it) }.firstOrNull { it.getString("id") == id }
        when {
            m == "GET" && r.isEmpty() -> return ok(publicCircle(circle))
            m == "GET" && r == listOf("messages") -> return ok(JSONArray(messages().filter { it.getString("circleId") == cid && !it.optBoolean("hidden") }.map { publicMessage(it) }))
            m == "POST" && r == listOf("messages") -> return postMessage(b, circle)
            m == "GET" && r == listOf("cars") -> return ok(JSONArray(devices().filter { it.getString("circleId") == cid && it.getString("kind") == "car" }
                .map { publicDevice(it).put("me", it.getString("id") == device.getString("id")) }))
            m == "GET" && r == listOf("invite") -> return ok(JSONObject().put("inviteCode", circle.optString("inviteCode")))
            m == "POST" && r == listOf("invite", "reset") -> { circle.put("inviteCode", newInviteCode()); save(); return ok(JSONObject().put("inviteCode", circle.getString("inviteCode"))) }
            m == "GET" && r == listOf("feed") -> {
                val msgs = messages().filter { it.getString("circleId") == cid }.associateBy { it.getString("id") }
                val heard = JSONArray()
                arr("heard").forEach { h ->
                    val mm = msgs[h.getString("messageId")] ?: return@forEach
                    heard.put(JSONObject(h.toString()).put("carName", devices().firstOrNull { it.getString("id") == h.optString("carId") }?.optString("name") ?: JSONObject.NULL)
                        .put("placeName", mm.optString("placeName")).put("when", mm.optString("when", "place"))
                        .put("authorId", mm.getString("authorId")).put("authorName", mm.optString("authorName")))
                }
                return ok(JSONObject().put("heard", heard).put("diaries", JSONArray(arr("diaries").filter { it.getString("circleId") == cid })))
            }
            m == "POST" && r == listOf("diaries") -> {
                val mid = b.optString("memberId"); val date = b.optString("date").ifBlank { java.time.LocalDate.now().toString() }
                if (member(mid) == null) return err(400, "not a member")
                setArr("diaries", arr("diaries").filterNot { it.getString("circleId") == cid && it.getString("memberId") == mid && it.getString("date") == date } +
                    JSONObject().put("circleId", cid).put("memberId", mid).put("memberName", member(mid)!!.getString("name")).put("date", date)
                        .put("text", b.optString("text").take(500)).put("updatedAt", now()))
                save(); return ok(JSONObject().put("ok", true))
            }
            m == "POST" && r == listOf("diaries", "delete") -> {
                setArr("diaries", arr("diaries").filterNot { it.getString("circleId") == cid && it.getString("memberId") == b.optString("memberId") })
                save(); return ok(JSONObject().put("ok", true))
            }
            m == "POST" && r == listOf("delete") -> {
                if (b.optString("confirm") != circle.getString("name")) return err(400, "확인을 위해 모임 이름을 그대로 써 주세요")
                val ids = messages().filter { it.getString("circleId") == cid }.map { it.getString("id") }.toSet()
                messages().filter { it.getString("id") in ids }.forEach { removeMedia(it.optString("audioUrl")) }
                setArr("messages", messages().filterNot { it.getString("id") in ids })
                setArr("heard", arr("heard").filterNot { it.getString("messageId") in ids })
                setArr("diaries", arr("diaries").filterNot { it.getString("circleId") == cid })
                setArr("devices", devices().filterNot { it.getString("circleId") == cid })
                setArr("circles", circles().filterNot { it.getString("id") == cid })
                save(); return ok(JSONObject().put("ok", true))
            }
            m == "POST" && r == listOf("update") -> {
                if (b.has("name")) { val n = clean(b.getString("name")); if (n.isEmpty()) return err(400, "이름을 써 주세요"); circle.put("name", n) }
                if (b.has("type")) { if (b.getString("type") !in CIRCLE_TYPES) return err(400, "type은 family, couple, friends"); circle.put("type", b.getString("type")) }
                save(); return ok(publicCircle(circle))
            }
            m == "POST" && r == listOf("members") -> {
                val n = clean(b.optString("name")); if (n.isEmpty()) return err(400, "이름을 써 주세요")
                if ((0 until members.length()).any { members.getJSONObject(it).getString("name") == n }) return err(409, "같은 이름이 이미 있어요")
                members.put(JSONObject().put("id", newId("m")).put("name", n).put("title", clean(b.optString("title"))).put("color", color(b.optString("color"), members.length())))
                save(); return ok(publicCircle(circle), 201)
            }
            m == "POST" && r == listOf("members", "order") -> {
                val ids = b.optJSONArray("ids")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                val byId = (0 until members.length()).map { members.getJSONObject(it) }.associateBy { it.getString("id") }
                if (ids.size != byId.size || !ids.all { it in byId }) return err(400, "ids가 구성원과 맞지 않아요")
                circle.put("members", JSONArray(ids.map { byId.getValue(it) })); save(); return ok(publicCircle(circle))
            }
            m == "POST" && r.size >= 3 && r[0] == "members" -> {
                val mm = member(r[1]) ?: return err(404, "member not found")
                when (r.drop(2)) {
                    listOf("update"), listOf("rename") -> {
                        if (b.has("name")) {
                            val n = clean(b.getString("name")); if (n.isEmpty()) return err(400, "이름을 써 주세요")
                            mm.put("name", n)
                            messages().filter { it.getString("authorId") == mm.getString("id") }.forEach { it.put("authorName", n) }
                            arr("heard").filter { it.getString("memberId") == mm.getString("id") }.forEach { it.put("memberName", n) }
                        }
                        if (b.has("title")) mm.put("title", clean(b.getString("title")))
                        if (b.has("color")) mm.put("color", color(b.getString("color")))
                    }
                    listOf("photo", "delete") -> mm.remove("photoUrl")
                    listOf("delete") -> {
                        if (members.length() <= 1) return err(400, "구성원이 최소 1명은 있어야 해요")
                        val id = mm.getString("id")
                        circle.put("members", JSONArray((0 until members.length()).map { members.getJSONObject(it) }.filter { it.getString("id") != id }))
                        val gone = messages().filter { it.getString("circleId") == cid && it.getString("authorId") == id }.map { it.getString("id") }.toSet()
                        setArr("messages", messages().filterNot { it.getString("id") in gone })
                        setArr("heard", arr("heard").filterNot { it.getString("memberId") == id || it.getString("messageId") in gone })
                        devices().filter { it.getString("circleId") == cid && it.optString("ownerId") == id }.forEach { it.put("ownerId", JSONObject.NULL) }
                    }
                    else -> return err(404, "not found")
                }
                save(); return ok(publicCircle(circle))
            }
        }
        return err(404, "not found")
    }

    private fun deviceRoute(action: String, idParam: String, b: JSONObject, device: JSONObject, circle: JSONObject): Reply {
        val id = if (idParam == "me") device.getString("id") else idParam
        val target = devices().firstOrNull { it.getString("id") == id && it.getString("circleId") == circle.getString("id") } ?: return err(404, "device not found")
        when (action) {
            "rename" -> { val n = clean(b.optString("name")); if (n.isEmpty()) return err(400, "이름을 써 주세요"); target.put("name", n) }
            "owner" -> {
                val owner = b.optString("ownerId")
                val mem = circle.getJSONArray("members").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.firstOrNull { it.getString("id") == owner }
                    ?: return err(400, "모임 사람 중에서 골라 주세요")
                target.put("ownerId", owner)
                if (target.optString("name") == "우리 차") target.put("name", "${mem.getString("name")}의 차")
            }
            "delete" -> { setArr("devices", devices().filterNot { it.getString("id") == id }); save(); return ok(JSONObject().put("ok", true)) }
            else -> return err(404, "not found")
        }
        save(); return ok(publicDevice(target))
    }

    private fun messageRoute(action: String, id: String, b: JSONObject, device: JSONObject, circle: JSONObject): Reply {
        val msg = messages().firstOrNull { it.getString("id") == id && it.getString("circleId") == circle.getString("id") } ?: return err(404, "message not found")
        when (action) {
            "heard" -> {
                val mid = b.optString("memberId")
                val mem = circle.getJSONArray("members").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.firstOrNull { it.getString("id") == mid }
                    ?: return err(400, "서클 구성원이 아니에요")
                val now = now()
                val old = arr("heard").firstOrNull { it.getString("messageId") == id && it.getString("memberId") == mid }
                if (old != null) old.put("lastAt", now).put("lastCarId", device.getString("id"))
                else db.getJSONArray("heard").put(JSONObject().put("messageId", id).put("memberId", mid).put("memberName", mem.getString("name"))
                    .put("at", now).put("lastAt", now).put("carId", device.getString("id")).put("lastCarId", device.getString("id")))
            }
            "report" -> { msg.put("hidden", true) }
            "delete" -> {
                if (msg.getString("authorId") != b.optString("memberId")) return err(403, "본인이 남긴 메시지만 지울 수 있어요")
                removeMedia(msg.optString("audioUrl"))
                setArr("messages", messages().filterNot { it.getString("id") == id })
                setArr("heard", arr("heard").filterNot { it.getString("messageId") == id })
            }
            else -> return err(404, "not found")
        }
        save(); return ok(JSONObject().put("ok", true))
    }

    private fun postMessage(b: JSONObject, circle: JSONObject): Reply {
        val cid = circle.getString("id")
        val members = circle.getJSONArray("members").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        val author = members.firstOrNull { it.getString("id") == b.optString("authorId") } ?: return err(400, "서클 구성원이 아니에요")
        val w = b.optString("when", "place").ifBlank { "place" }
        if (w !in WHEN_LABELS) return err(400, "열리는 조건을 다시 골라 주세요")
        val hasPlace = w in WITH_PLACE && b.has("lat") && b.has("lon")
        if ((w == "place" || w == "region") && !hasPlace) return err(400, "장소를 골라 주세요")
        val kind = b.optString("kind")
        if (kind != "voice" && kind != "text") return err(400, "kind는 voice 또는 text")
        val msg = JSONObject().put("id", newId("msg")).put("circleId", cid).put("authorId", author.getString("id")).put("authorName", author.getString("name"))
            .put("when", w).put("placeName", if (hasPlace) b.optString("placeName").ifBlank { "이름 없는 장소" }.take(40) else WHEN_LABELS.getValue(w))
            .put("lat", if (hasPlace) b.getDouble("lat") else 0.0).put("lon", if (hasPlace) b.getDouble("lon") else 0.0)
            .put("radiusM", b.optInt("radiusM", 300).coerceIn(100, 2000)).put("kind", kind).put("text", b.optString("text").take(300))
            .put("audioUrl", JSONObject.NULL).put("createdAt", now()).put("hidden", false)
        // 받는 사람·답장·시간·횟수 조건 (server.js cleanConditions와 같다)
        b.optJSONArray("to")?.let { a -> msg.put("to", JSONArray((0 until a.length()).map { a.getString(it) }.filter { id -> members.any { it.getString("id") == id } })) }
        b.optString("replyTo").takeIf { r -> r.isNotEmpty() && messages().any { it.getString("id") == r } }?.let { msg.put("replyTo", it) }
        if (b.has("hourFrom") && b.has("hourTo")) msg.put("hourFrom", b.getInt("hourFrom")).put("hourTo", b.getInt("hourTo"))
        b.optJSONArray("days")?.let { msg.put("days", it) }
        b.optString("notBefore").takeIf { Regex("""\d{4}-\d{2}-\d{2}""").matches(it) }?.let { msg.put("notBefore", it) }
        msg.put("repeat", b.optString("repeat").takeIf { it in setOf("once", "always", "weekly") } ?: "once")
        if (b.has("longMin")) msg.put("longMin", b.getInt("longMin").coerceIn(30, 600))
        b.optString("regionCode").takeIf { Regex("""\d{2,5}""").matches(it) }?.let { msg.put("regionCode", it) }
        if (kind == "voice") {
            val a = b.optString("audioBase64").ifEmpty { return err(400, "녹음이 없어요") }
            val ext = b.optString("audioExt").takeIf { Regex("[a-z0-9]{2,4}").matches(it) } ?: "m4a"
            val f = "v_${hex(12)}.$ext"
            File(mediaDir, f).writeBytes(android.util.Base64.decode(a, android.util.Base64.DEFAULT))
            msg.put("audioUrl", "/media/$f")
        } else if (msg.getString("text").isBlank()) return err(400, "메시지를 써 주세요")
        db.getJSONArray("messages").put(msg)
        save()
        return ok(publicMessage(msg), 201)
    }

    // 모임 만들기·들어가기 ------------------------------------------------------

    private fun createCircle(b: JSONObject): Reply {
        val type = b.optString("type").takeIf { it in CIRCLE_TYPES } ?: "family"
        val name = clean(b.optString("memberName")); if (name.isEmpty()) return err(400, "내 이름을 써 주세요")
        if (!b.optBoolean("agreed")) return err(400, "개인정보 처리에 동의해야 만들 수 있어요")
        val member = JSONObject().put("id", newId("m")).put("name", name).put("title", clean(b.optString("title"))).put("color", color(b.optString("color")))
        val circle = JSONObject().put("id", newId("c")).put("name", clean(b.optString("circleName")).ifEmpty { if (type == "family") "우리집" else "우리 모임" })
            .put("type", type).put("members", JSONArray().put(member)).put("inviteCode", newInviteCode()).put("createdAt", now())
        db.getJSONArray("circles").put(circle)
        val (device, key) = addCar(b, circle, member.getString("id"))
        save()
        return ok(JSONObject().put("key", key).put("circle", publicCircle(circle)).put("memberId", member.getString("id")).put("device", publicDevice(device)), 201)
    }

    private fun join(b: JSONObject): Reply {
        val norm = { s: String -> s.uppercase().replace(Regex("[\\s-]"), "") }
        val want = norm(b.optString("inviteCode"))
        val circle = circles().firstOrNull { want.isNotEmpty() && norm(it.optString("inviteCode")) == want }
            ?: return err(404, "초대 코드가 맞지 않거나 바뀌었어요. 모임 사람에게 새 코드를 받아 주세요")
        if (!b.optBoolean("agreed")) return err(400, "개인정보 처리에 동의해야 들어갈 수 있어요")
        val (device, key) = addCar(b, circle, null)
        save()
        return ok(JSONObject().put("key", key).put("circle", publicCircle(circle)).put("device", publicDevice(device)), 201)
    }

    private fun addCar(b: JSONObject, circle: JSONObject, ownerId: String?): Pair<JSONObject, String> {
        val vin = b.optString("vinHash").take(64)
        if (vin.isNotEmpty()) setArr("devices", devices().filterNot { it.optString("vinHash") == vin && it.getString("circleId") == circle.getString("id") })
        val owner = ownerId?.let { id -> circle.getJSONArray("members").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.firstOrNull { it.getString("id") == id } }
        val key = hex(24)
        val d = JSONObject().put("id", newId("car")).put("circleId", circle.getString("id")).put("kind", "car")
            .put("name", owner?.let { "${it.getString("name")}의 차" } ?: "우리 차").put("ownerId", ownerId ?: JSONObject.NULL)
            .put("vinHash", vin).put("keyHash", sha(key)).put("createdAt", now())
        db.getJSONArray("devices").put(d)
        return d to key
    }

    // 도우미 ------------------------------------------------------------------

    private fun arr(name: String): List<JSONObject> = db.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
    private fun setArr(name: String, list: List<JSONObject>) { db.put(name, JSONArray(list)) }
    private fun circles() = arr("circles")
    private fun messages() = arr("messages")
    private fun devices() = arr("devices")
    private fun deviceOf(key: String?): JSONObject? = key?.let { k -> val h = sha(k); devices().firstOrNull { it.optString("keyHash") == h } }
    private fun publicCircle(c: JSONObject) = JSONObject(c.toString()).apply { remove("inviteCode") }
    private fun publicMessage(m: JSONObject) = JSONObject(m.toString()).apply { remove("reports"); if (!has("when")) put("when", "place") }
    private fun publicDevice(d: JSONObject) = JSONObject().put("id", d.getString("id")).put("kind", d.getString("kind")).put("name", d.optString("name"))
        .put("ownerId", d.opt("ownerId") ?: JSONObject.NULL).put("createdAt", d.optString("createdAt"))
    private fun clean(s: String) = s.trim().take(12)
    private fun color(c: String, i: Int = 0) = if (c in COLORS) c else COLORS[i % COLORS.size]
    private fun now() = java.time.Instant.now().toString()
    private fun hex(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private fun newId(prefix: String) = "${prefix}_${hex(4)}"
    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun removeMedia(url: String?) { if (url != null && url.startsWith("/media/") && !url.contains("seed_")) mediaFile(url).delete() }
    private fun newInviteCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        while (true) {
            val c = (1..6).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
            if (circles().none { it.optString("inviteCode") == c }) return c
        }
    }
}
