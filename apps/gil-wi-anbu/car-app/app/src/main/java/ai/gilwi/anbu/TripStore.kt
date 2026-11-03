package ai.gilwi.anbu

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 오늘의 운전 기록과 설정. 기기 안(SharedPreferences)에만 저장한다. */
class TripStore(context: Context) {

    private val prefs = context.getSharedPreferences("gilwi", Context.MODE_PRIVATE)

    /** isPlace=false면 placeName은 "비 오는 날" 같은 조건 이름 */
    data class HeardItem(val messageId: String, val authorName: String, val placeName: String, val isPlace: Boolean = true)

    val today: String get() = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())

    // 설정 ------------------------------------------------------------

    /** 오늘 운전하는 사람. 날짜가 바뀌면 다시 고른다. */
    var driverId: String?
        get() = if (prefs.getString("driverDate", null) == today) prefs.getString("driverId", null) else null
        set(v) = prefs.edit().putString("driverId", v).putString("driverDate", today).apply()

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
                driverId?.let { putString("driverId", it); putString("driverDate", today) }
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

    /** 근처를 지나서 받은 메시지(아직 안 들었을 수 있음). 같은 메시지는 하루 한 번. */
    fun markDelivered(messageId: String): Boolean {
        resetIfNewDay()
        val set = prefs.getStringSet("delivered", emptySet())!!
        if (messageId in set) return false
        prefs.edit().putStringSet("delivered", set + messageId).apply()
        return true
    }

    /** 「보내지 않기」를 고른 메시지. 앱이 다시 켜져 또 재생돼도 그날은 자동으로 「들었어요」를 보내지 않는다. */
    fun markNoAutoHeard(messageId: String) {
        resetIfNewDay()
        val set = prefs.getStringSet("noAutoHeard", emptySet())!!
        if (messageId !in set) prefs.edit().putStringSet("noAutoHeard", set + messageId).apply()
    }
    fun isNoAutoHeard(messageId: String): Boolean { resetIfNewDay(); return messageId in prefs.getStringSet("noAutoHeard", emptySet())!! }

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
    }

    /** "원우" → "원우가", "용민" → "용민이가" */
    private fun subject(name: String): String {
        val c = name.lastOrNull() ?: return name
        val hasFinal = c in '가'..'힣' && (c - '가') % 28 != 0
        return if (hasFinal) name + "이가" else name + "가"
    }
}
