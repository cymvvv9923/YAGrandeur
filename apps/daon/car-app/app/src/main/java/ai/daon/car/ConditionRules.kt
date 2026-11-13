package ai.daon.car

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * 열리는 조건의 규약 (오작동을 막는 기준값과 판단).
 * 시각은 모두 차 시계(시스템 시간, 자동 동기)를 쓴다. 서버·폰 시간은 쓰지 않는다.
 *
 *  비        와이퍼가 RAIN_HOLD_MS 넘게 계속 돌 때 (워셔액 뿌릴 때 잠깐 도는 것은 빼고)
 *  밤길      전조등(하향등)이 NIGHT_HOLD_MS 넘게 켜져 있고, 지금 자리의 해가 진 뒤(시민박명 끝 ~ 시작)일 때.
 *            낮에 터널·궂은 날 켠 전조등은 밤길이 아니다. 해 지는 시각을 넘기면 1분 안에 다시 판단한다.
 *  옆자리    열기: 조수석 착석 + 조수석 안전벨트, 또는 뒷좌석 안전벨트가 TOGETHER_HOLD_MS 넘게 이어질 때 (가방만 올린 것은 빼고)
 *            사생활(보낸 사람 가리기·먼저 묻기)은 반대로 하나만 감지돼도 바로 켠다
 *  탈 때·시동 차가 켜져 앱이 처음 뜨고 받은 기어가 P일 때, 마지막으로 연 때에서 START_GAP_MS가 지났을 때 (달리다 앱만 다시 켜진 것은 빼고).
 *            켜진 뒤 BOOT_WINDOW_MS 동안 메시지가 들어올 때마다 다시 본다(그룹마다 늦게 받아도 놓치지 않게). 문 신호는 쓰지 않는다: 시동이 꺼진 차에서는 앱이 없어 타면서 연 문을 볼 수 없다
 *  도착      이번 주행이 PARK_MIN_DRIVE_MS 이상이거나 PARK_MIN_DRIVE_M 이상이고, P를 PARK_HOLD_MS 넘게 유지할 때 (신호대기 P는 빼고)
 *  오래 운전 REST_RESET_MS 넘게 쉬어야 처음부터 다시 센다 (잠깐 선 것은 이어서 센다)
 *  지역      같은 지역이 위치 신호 두 번 연속일 때 (경계에서 GPS가 한 번 튄 것은 빼고)
 *  경고등    고장·주의 경고등(WARNING_TYPES)이 켜지거나 깜빡일 때, 새로 켜질 때만. 방향지시·전조등·주행모드 같은 표시등은 빼고
 *  시간대    차 시계가 아직 맞춰지지 않았으면(CLOCK_MIN_YEAR 이전) 시간대·요일·날짜가 있는 메시지는 미룬다
 */
object ConditionRules {
    const val RAIN_HOLD_MS = 15_000L
    const val NIGHT_HOLD_MS = 10_000L
    const val TOGETHER_HOLD_MS = 30_000L
    const val START_GAP_MS = 30 * 60_000L
    /** 차가 켜진 뒤 이 시간 안에 받은 메시지까지 「차에 탈 때」·「시동 걸 때」로 연다 */
    const val BOOT_WINDOW_MS = 2 * 60_000L
    const val PARK_MIN_DRIVE_MS = 2 * 60_000L
    const val PARK_MIN_DRIVE_M = 300.0
    const val PARK_HOLD_MS = 20_000L
    const val REST_RESET_MS = 10 * 60_000L
    const val CLOCK_MIN_YEAR = 2025

    /** 위치를 모를 때 해 지는 시각을 셀 자리 (서울) */
    private const val DEFAULT_LAT = 37.5665
    private const val DEFAULT_LON = 126.9780

    /** 경고로 볼 계기판 등 (Pleos Vehicle SDK TelltaleType 이름). 표시등·주행 모드·문·벨트·주차브레이크는 넣지 않는다. */
    val WARNING_TYPES = setOf(
        "MASTER_WARNING", "ENGINE", "ENGINE_OIL_PRESSURE", "ENGINE_TEMPERATURE", "ABS", "ESC", "MDPS", "TPMS",
        "AIRBAG", "LOW_VOLT_BATTERY", "EV_SYSTEM", "HEV_SYSTEM", "FCEV_SYSTEM", "EREV_SYSTEM", "POWER_DOWN", "RBS",
        "PARTICLE_FILTER", "SCR", "WATER_SEPARATOR", "LPI", "H2_SENSOR", "H2_LEAK", "AFS", "EMERGENCY_CALL",
        "LOW_FUEL", "LOW_FUEL_FCEV", "LOW_EV_BATTERY",
    )

    /** 차 시계가 맞춰졌는지 */
    fun clockOk(): Boolean = LocalDate.now().year >= CLOCK_MIN_YEAR

    /** 지금 자리에서 해가 졌는지 (시민박명 기준: 해가 지평선 아래 6°). 시계가 안 맞으면 true(전조등만 본다). */
    fun isDark(lat: Double?, lon: Double?, now: ZonedDateTime = ZonedDateTime.now()): Boolean {
        if (!clockOk()) return true
        val la = lat ?: DEFAULT_LAT; val lo = lon ?: DEFAULT_LON
        val (dawn, dusk) = civilTwilight(la, lo, now.toLocalDate(), now.zone) ?: return false
        return now.isBefore(dawn) || now.isAfter(dusk)
    }

    /** 그날의 시민박명 시작·끝 (NOAA 근사식). 백야·극야면 null */
    fun civilTwilight(lat: Double, lon: Double, date: LocalDate, zone: ZoneId): Pair<ZonedDateTime, ZonedDateTime>? {
        val n = date.dayOfYear
        val gamma = 2 * Math.PI / 365 * (n - 1)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) - 0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))
        val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) - 0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma) -
            0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)
        val latR = Math.toRadians(lat)
        val zenith = Math.toRadians(96.0)
        val cosH = cos(zenith) / (cos(latR) * cos(decl)) - tan(latR) * tan(decl)
        if (cosH < -1 || cosH > 1) return null
        val ha = Math.toDegrees(acos(cosH))
        val dawnUtcMin = 720 - 4 * (lon + ha) - eqTime
        val duskUtcMin = 720 - 4 * (lon - ha) - eqTime
        val base = date.atStartOfDay(ZoneId.of("UTC"))
        fun at(min: Double) = base.plusSeconds((min * 60).toLong()).withZoneSameInstant(zone)
        return at(dawnUtcMin) to at(duskUtcMin)
    }

}
