package ai.gilwi.anbu

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 오늘의 운전 기록과 설정. 기기 안(SharedPreferences)에만 저장한다. */
class TripStore(context: Context) {

    private val prefs = context.getSharedPreferences("gilwi", Context.MODE_PRIVATE)
    /** 이 차의 가족 연결(열쇠). 날짜가 바뀌어도 지우지 않으므로 오늘 기록과 다른 파일에 둔다. */
    private val device = context.getSharedPreferences("gilwi_device", Context.MODE_PRIVATE)

    // 모임 연결 ------------------------------------------------------------
    // 차 한 대가 가족·친구 등 여러 모임에 들어갈 수 있다. 모임마다 서버가 준 열쇠와 그 모임에서의 차 주인이 따로 있다.
    // 화면의 모임 관리·남기기는 「지금 관리하는 모임」(active) 하나를 다루고, 지도·재생은 모든 모임을 함께 쓴다.

    /** 모임 하나와의 연결. circleId가 빈 것은 예전 형식에서 옮겨 와 아직 모임을 받지 못한 것 */
    data class Membership(val key: String, val circleId: String, val ownerId: String?, val carName: String)

    val memberships: List<Membership>
        get() {
            migrate()
            val arr = runCatching { org.json.JSONArray(device.getString("memberships", "[]")) }.getOrElse { org.json.JSONArray() }
            return (0 until arr.length()).map { i ->
                arr.getJSONObject(i).let { Membership(it.getString("key"), it.optString("circleId"), it.optString("ownerId").ifBlank { null }, it.optString("carName")) }
            }
        }

    private fun saveMemberships(list: List<Membership>) {
        val arr = org.json.JSONArray()
        list.forEach { arr.put(org.json.JSONObject().put("key", it.key).put("circleId", it.circleId).put("ownerId", it.ownerId ?: "").put("carName", it.carName)) }
        device.edit().putString("memberships", arr.toString()).apply()
    }

    /** 예전(모임 하나) 형식: key·ownerId·carName을 모임 목록으로 옮긴다 */
    private fun migrate() {
        val old = device.getString("key", null) ?: return
        if (device.contains("memberships")) { device.edit().remove("key").apply(); return }
        val m = Membership(old, "", device.getString("ownerId", null), device.getString("carName", "")!!)
        device.edit().remove("key").remove("ownerId").remove("carName").apply()
        saveMemberships(listOf(m))
    }

    /** 지금 관리하는 모임 */
    var activeCircleId: String?
        get() = device.getString("activeCircle", null)
        set(v) = device.edit().putString("activeCircle", v).apply()

    val active: Membership? get() = memberships.let { list -> list.firstOrNull { it.circleId == activeCircleId } ?: list.firstOrNull() }

    private fun updateActive(f: (Membership) -> Membership) {
        val a = active ?: return
        saveMemberships(memberships.map { if (it.key == a.key) f(it) else it })
    }

    /** 지금 관리하는 모임의 열쇠. 없으면 아직 어느 모임에도 들어가지 않은 차다. */
    val deviceKey: String? get() = active?.key
    /** 지금 관리하는 모임에서 이 차 이름 ("아버지 그랜저") */
    var carName: String
        get() = active?.carName.orEmpty()
        set(v) = updateActive { it.copy(carName = v) }

    fun ownerOf(circleId: String): String? = memberships.firstOrNull { it.circleId == circleId }?.ownerId
    fun keyOf(circleId: String): String? = memberships.firstOrNull { it.circleId == circleId }?.key

    /** 예전 형식에서 옮겨 온 연결에 모임 id를 채운다 */
    fun setCircleId(key: String, circleId: String) {
        val list = memberships
        if (list.none { it.key == key && it.circleId != circleId }) return
        saveMemberships(list.map { if (it.key == key) it.copy(circleId = circleId) else it })
        if (activeCircleId == null) activeCircleId = circleId
    }

    /** 서버가 알려 준 그 모임의 차 주인·이름 */
    fun syncMembership(key: String, ownerId: String?, carName: String?) =
        saveMemberships(memberships.map { if (it.key == key) it.copy(ownerId = ownerId, carName = carName ?: it.carName) else it })

    /** 모임에 들어갔다. 같은 모임이면 예전 연결을 바꾼다. 들어간 모임을 관리하는 모임으로 한다. */
    fun savePairing(key: String, circleId: String, carName: String, ownerId: String?) {
        saveMemberships(memberships.filter { it.circleId != circleId } + Membership(key, circleId, ownerId, carName))
        activeCircleId = circleId
    }

    /** 모임 하나에서 빠졌다 (연결 끊기·모임 지우기·서버가 열쇠를 모름). 남은 연결 수 */
    fun removeMembership(key: String): Int {
        val left = memberships.filter { it.key != key }
        saveMemberships(left)
        if (left.none { it.circleId == activeCircleId }) activeCircleId = left.firstOrNull()?.circleId
        return left.size
    }

    /** 모든 모임에서 빠졌다: 이 차에 저장된 기록을 모두 지운다 (설정은 남긴다) */
    fun clearPairing() {
        device.edit().remove("key").remove("carName").remove("ownerId").remove("memberships").remove("activeCircle").remove("found").remove("spots").apply()
        val keep = theme
        prefs.edit().clear().putString("theme", keep).apply()
    }

    /** 메인 지도에서 가린 모임·사람 (이 차 안에만) */
    var hiddenCircles: Set<String>
        get() = device.getStringSet("hiddenCircles", emptySet())!!
        set(v) = device.edit().putStringSet("hiddenCircles", v).apply()
    var hiddenMembers: Set<String>
        get() = device.getStringSet("hiddenMembers", emptySet())!!
        set(v) = device.edit().putStringSet("hiddenMembers", v).apply()

    /** 차 고유번호를 못 읽을 때 대신 쓰는, 이 앱 설치에만 있는 번호 */
    val installId: String
        get() = device.getString("installId", null) ?: java.util.UUID.randomUUID().toString().also { device.edit().putString("installId", it).apply() }

    /** isPlace=false면 placeName은 "비 오는 날" 같은 조건 이름 */
    data class HeardItem(val messageId: String, val authorName: String, val placeName: String, val isPlace: Boolean = true)

    val today: String get() = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())

    // 설정 ------------------------------------------------------------

    /**
     * 지금 관리하는 모임에서 이 차의 주인(이 차를 타는 사람). 날짜가 바뀌어도 그대로다. 다른 모임의 주인은 ownerOf.
     * 차 한 대 = 한 사람: 이 사람에게 온 메시지를 들려주고, 이 사람이 남긴 메시지는 틀지 않는다.
     */
    var driverId: String?
        get() = active?.ownerId
        set(v) = updateActive { it.copy(ownerId = v) }

    /** 옆자리에 누가 있으면 바로 틀지 않고 먼저 물어본다 (기본 켬) */
    var askWithPassenger: Boolean
        get() = prefs.getBoolean("askWithPassenger", true)
        set(v) = prefs.edit().putBoolean("askWithPassenger", v).apply()

    // 일기 공유 항목. 기본값은 모두 "공유 안 함".
    var shareDistance: Boolean
        get() = prefs.getBoolean("shareDistance", false)
        set(v) = prefs.edit().putBoolean("shareDistance", v).apply()
    var shareRests: Boolean
        get() = prefs.getBoolean("shareRests", false)
        set(v) = prefs.edit().putBoolean("shareRests", v).apply()
    var shareHeard: Boolean
        get() = prefs.getBoolean("shareHeard", false)
        set(v) = prefs.edit().putBoolean("shareHeard", v).apply()
    /** 비·밤길 (동승 여부는 공유하지 않는다) */
    var shareWeather: Boolean
        get() = prefs.getBoolean("shareWeather", false)
        set(v) = prefs.edit().putBoolean("shareWeather", v).apply()
    val sharingAnything get() = shareDistance || shareRests || shareHeard || shareWeather

    /** 받는 사람 선호: 기계음(글→TTS) 메시지도 들을지. */
    var acceptTts: Boolean
        get() = prefs.getBoolean("acceptTts", true)
        set(v) = prefs.edit().putBoolean("acceptTts", v).apply()

    /** 끝까지 들으면 버튼 없이 「들었어요」를 보낼지 (운전자가 누르지 않아도 되게, 기본 켬) */
    var autoHeard: Boolean
        get() = prefs.getBoolean("autoHeard", true)
        set(v) = prefs.edit().putBoolean("autoHeard", v).apply()

    /** 운전 중에도 조건이 맞으면 소리로만 들려준다 (화면은 띄우지 않음). 기본 켬 */
    var playWhileDriving: Boolean
        get() = prefs.getBoolean("playWhileDriving", true)
        set(v) = prefs.edit().putBoolean("playWhileDriving", v).apply()

    /** 화면 밝기: auto 차 설정(주간·야간) 따르기 · day 밝게 · night 어둡게 */
    var theme: String
        get() = prefs.getString("theme", "auto")!!
        set(v) = prefs.edit().putString("theme", v).apply()

    /** 아직 안 들은 대기 메시지. 앱이 꺼졌다 켜져도 잃어버리지 않게 저장한다. */
    var pendingIds: List<String>
        get() { resetIfNewDay(); return prefs.getString("pendingIds", "")!!.split(",").filter { it.isNotBlank() } }
        set(v) { resetIfNewDay(); prefs.edit().putString("pendingIds", v.joinToString(",")).apply() }

    fun stopSharing() {
        shareDistance = false; shareRests = false; shareHeard = false; shareWeather = false
    }

    // 오늘 기록 ------------------------------------------------------------

    private fun resetIfNewDay() {
        if (prefs.getString("tripDate", null) != today) {
            prefs.edit().clear().apply {
                // 설정은 남긴다
                putBoolean("shareDistance", shareDistance); putBoolean("shareRests", shareRests)
                putBoolean("shareHeard", shareHeard); putBoolean("shareWeather", shareWeather)
                putBoolean("acceptTts", acceptTts); putBoolean("autoHeard", autoHeard)
                putBoolean("askWithPassenger", askWithPassenger)
                putBoolean("playWhileDriving", playWhileDriving)
                putString("theme", theme)
                putString("tripDate", today)
            }.apply()
        }
    }

    /** 누적 주행거리(km)로 오늘 달린 거리를 계산한다. */
    fun onOdometer(km: Float) {
        resetIfNewDay()
        if (km <= 0f) return // 0은 "값 없음"으로 본다
        if (!prefs.contains("odoStart")) prefs.edit().putFloat("odoStart", km).apply()
        val prev = prefs.getFloat("odoNow", km)
        // 계기판 값이 튀면(교체·시연 조작) 오늘 달린 거리는 유지하고 기준만 옮긴다
        if (km < prev || km - prev > ODO_JUMP_KM) prefs.edit().putFloat("odoStart", prefs.getFloat("odoStart", km) + (km - prev)).apply()
        prefs.edit().putFloat("odoNow", km).apply()
    }

    /** 오도미터가 없을 때 위치 이동으로 거리를 더한다. */
    fun addGpsDistance(meters: Double) {
        resetIfNewDay()
        prefs.edit().putFloat("gpsKm", prefs.getFloat("gpsKm", 0f) + (meters / 1000).toFloat()).apply()
    }

    val todayKm: Float
        get() {
            resetIfNewDay()
            val odo = prefs.getFloat("odoNow", 0f) - prefs.getFloat("odoStart", 0f)
            return if (odo > 0.05f) odo else prefs.getFloat("gpsKm", 0f)
        }

    /** 주행하다가 P로 바꾼 횟수 = 쉬어간 횟수. */
    fun onParkedAfterDriving() {
        resetIfNewDay()
        prefs.edit().putInt("rests", prefs.getInt("rests", 0) + 1).apply()
    }
    val rests: Int get() { resetIfNewDay(); return prefs.getInt("rests", 0) }

    /**
     * 근처를 지나서 받은 메시지(아직 안 들었을 수 있음). 같은 메시지는 운전자마다 하루 한 번.
     * 가족이 한 차를 번갈아 타므로 차 하나에 한 번이 아니라 사람마다 센다.
     */
    fun markDelivered(messageId: String, driverId: String): Boolean {
        resetIfNewDay()
        val key = "$driverId|$messageId"
        val set = prefs.getStringSet("delivered", emptySet())!!
        if (key in set) return false
        prefs.edit().putStringSet("delivered", set + key).apply()
        return true
    }

    /** 「보내지 않기」를 고른 메시지. 앱이 다시 켜져 또 재생돼도 그날은 자동으로 「들었어요」를 보내지 않는다. */
    fun markNoAutoHeard(messageId: String) {
        resetIfNewDay()
        val set = prefs.getStringSet("noAutoHeard", emptySet())!!
        if (messageId !in set) prefs.edit().putStringSet("noAutoHeard", set + messageId).apply()
    }
    fun isNoAutoHeard(messageId: String): Boolean { resetIfNewDay(); return messageId in prefs.getStringSet("noAutoHeard", emptySet())!! }

    /**
     * 지도에 찍을 "발견한 곳": 들은 메시지 id → 이 차가 그때 있던 자리.
     * 장소 메시지는 메시지 좌표를 쓰므로, 비·밤길 같은 조건 메시지를 들은 자리만 쓴다.
     * 이 차 안에만 두고 서버에는 보내지 않는다. 날짜가 바뀌어도 남긴다.
     */
    data class Found(val messageId: String, val lat: Double, val lon: Double, val date: String)

    fun addFound(messageId: String, lat: Double, lon: Double) {
        val list = found.filter { it.messageId != messageId } + Found(messageId, lat, lon, today)
        device.edit().putString("found", list.joinToString("\n") { "${it.messageId}\t${it.lat}\t${it.lon}\t${it.date}" }).apply()
    }
    val found: List<Found>
        get() = device.getString("found", "")!!.lines().filter { it.isNotBlank() }.mapNotNull {
            val p = it.split("\t")
            runCatching { Found(p[0], p[1].toDouble(), p[2].toDouble(), p.getOrElse(3) { "" }) }.getOrNull()
        }

    /**
     * 주행 중 말로 저장한 곳 (Gleo 「여기 저장」). 정차한 뒤 안부로 남기거나 지운다. 이 차 안에만 두고 7일이 지나면 지운다.
     * conditions: 저장할 때 차 상황 (rain · night · together)
     */
    data class Spot(val id: String, val lat: Double, val lon: Double, val at: Long, val memo: String, val conditions: String, val address: String = "")

    val spots: List<Spot>
        get() {
            val arr = runCatching { org.json.JSONArray(device.getString("spots", "[]")) }.getOrElse { org.json.JSONArray() }
            val cut = System.currentTimeMillis() - SPOT_KEEP_MS
            return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                Spot(it.getString("id"), it.getDouble("lat"), it.getDouble("lon"), it.getLong("at"), it.optString("memo"), it.optString("cond"), it.optString("addr"))
            }.filter { it.at >= cut }
        }

    private fun saveSpots(list: List<Spot>) {
        val arr = org.json.JSONArray()
        list.forEach { arr.put(org.json.JSONObject().put("id", it.id).put("lat", it.lat).put("lon", it.lon).put("at", it.at).put("memo", it.memo).put("cond", it.conditions).put("addr", it.address)) }
        device.edit().putString("spots", arr.toString()).apply()
    }

    fun addSpot(s: Spot) = saveSpots(spots + s)
    fun updateSpot(s: Spot) = saveSpots(spots.map { if (it.id == s.id) s else it })
    fun removeSpot(id: String) = saveSpots(spots.filter { it.id != id })

    fun addHeard(item: HeardItem) {
        resetIfNewDay()
        val list = heard.filter { it.messageId != item.messageId } + item
        prefs.edit().putString("heard", list.joinToString("\n") { "${it.messageId}\t${it.authorName}\t${it.placeName}\t${it.isPlace}" }).apply()
    }
    val heard: List<HeardItem>
        get() = prefs.getString("heard", "")!!.lines().filter { it.isNotBlank() }.map {
            val p = it.split("\t")
            HeardItem(p[0], p.getOrElse(1) { "" }, p.getOrElse(2) { "" }, p.getOrElse(3) { "true" } != "false")
        }

    /** 오늘 달리는 동안 있었던 일: "rain" 비, "night" 밤길, "together" 옆자리 동승 */
    fun markCondition(key: String) {
        resetIfNewDay()
        val set = prefs.getStringSet("conditions", emptySet())!!
        if (key !in set) prefs.edit().putStringSet("conditions", set + key).apply()
    }
    val conditions: Set<String> get() { resetIfNewDay(); return prefs.getStringSet("conditions", emptySet())!! }

    // 일기 ------------------------------------------------------------

    /** 차가 1인칭으로 쓰는 오늘의 일기. full=false면 공유 허락한 항목만. */
    fun diary(full: Boolean, circleType: String): String = diaryLines(full, circleType).joinToString(" ")

    /** 화면에 한 줄씩 크게 보여 주려고 문장 단위로 나눈 일기 */
    fun diaryLines(full: Boolean, circleType: String): List<String> {
        val lines = mutableListOf<String>()
        if (full || shareDistance) lines += "오늘은 ${"%.1f".format(todayKm)}km 달렸어요."
        val c = conditions
        if (full || shareWeather) {
            when {
                "rain" in c && "night" in c -> lines += "비 오는 밤길을 달렸어요."
                "rain" in c -> lines += "비가 왔어요."
                "night" in c -> lines += "밤길도 달렸어요."
            }
        }
        // 동승 여부는 나만 본다
        if (full && "together" in c) lines += "옆자리에 누가 함께 탔어요."
        if (full || shareRests) lines += if (rests == 0) "오늘은 아직 쉬지 않았어요." else "${rests}번 쉬었어요."
        if (full || shareHeard) {
            val h = heard
            when {
                h.isEmpty() -> if (full) lines += "오늘은 들은 메시지가 없어요."
                // 연인·친구 서클에는 장소 이름을 넣지 않는다
                circleType != "family" && !full -> lines += "서클 메시지를 ${h.size}개 들으셨어요."
                else -> h.forEach {
                    lines += if (it.isPlace) "${subject(it.authorName)} 남긴 ${it.placeName} 이야기를 들으셨어요."
                    else "${subject(it.authorName)} 「${it.placeName}」에 남긴 이야기를 들으셨어요."
                }
            }
        }
        return lines
    }

    companion object {
        /** 한 번에 이보다 많이 늘면 실제 주행이 아니라 값이 바뀐 것으로 본다 */
        const val ODO_JUMP_KM = 300f
        /** 주행 중 저장한 곳을 이만큼 지나면 지운다 */
        const val SPOT_KEEP_MS = 7L * 24 * 3600 * 1000
    }

    /** "원우" → "원우가", "용민" → "용민이가" */
    private fun subject(name: String): String {
        val c = name.lastOrNull() ?: return name
        val hasFinal = c in '가'..'힣' && (c - '가') % 28 != 0
        return if (hasFinal) name + "이가" else name + "가"
    }
}
