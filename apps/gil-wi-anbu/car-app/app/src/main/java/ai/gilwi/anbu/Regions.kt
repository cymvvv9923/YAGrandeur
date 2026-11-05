package ai.gilwi.anbu

import android.content.Context
import org.json.JSONArray

/**
 * 시·도 / 시·군·구 경계 (assets/geo, 통계청 2013 간략판).
 * 메인 지도에서 멀리 볼 때 안부를 지역마다 묶어 세는 데 쓴다. 계산은 모두 차 안에서 한다.
 */
object Regions {

    /** rings: 고리마다 [위도, 경도, 위도, 경도, …] */
    class Region(val code: String, val name: String, val rings: List<DoubleArray>) {
        val south: Double; val north: Double; val west: Double; val east: Double
        /** 말풍선을 놓을 자리 (가장 큰 고리의 무게중심) */
        val labelLat: Double; val labelLon: Double

        init {
            var s = 90.0; var n = -90.0; var w = 180.0; var e = -180.0
            rings.forEach { r ->
                for (i in r.indices step 2) {
                    s = minOf(s, r[i]); n = maxOf(n, r[i]); w = minOf(w, r[i + 1]); e = maxOf(e, r[i + 1])
                }
            }
            south = s; north = n; west = w; east = e
            val big = rings.maxByOrNull { kotlin.math.abs(area(it)) }
            val c = big?.let { centroid(it) } ?: ((s + n) / 2 to (w + e) / 2)
            // 서울·인천을 품은 경기처럼 무게중심이 다른 지역 위에 놓이면 말풍선이 겹친다. 그런 곳은 자리를 정해 둔다.
            val fixed = LABELS[code]
            labelLat = fixed?.first ?: c.first; labelLon = fixed?.second ?: c.second
        }

        fun contains(lat: Double, lon: Double): Boolean {
            if (lat < south || lat > north || lon < west || lon > east) return false
            return rings.any { inRing(it, lat, lon) }
        }
    }

    private val LABELS = mapOf(
        "11" to (37.56 to 126.99), // 서울
        "23" to (37.47 to 126.62), // 인천
        "31" to (37.22 to 127.40), // 경기 (서울 아래 용인·이천 쪽)
        "29" to (36.56 to 127.26), // 세종
        "25" to (36.30 to 127.40), // 대전
    )

    /** 시·도 17개 (이름은 짧게: 서울·경기…) */
    var provinces: List<Region> = emptyList()
        private set
    /** 시·군·구 251개. code 앞 두 자리가 시·도 code */
    var districts: List<Region> = emptyList()
        private set

    fun load(context: Context) {
        if (provinces.isNotEmpty()) return
        provinces = read(context, "geo/provinces.json")
        districts = read(context, "geo/districts.json")
        Log.d("지역 경계 ${provinces.size}개 · ${districts.size}개")
    }

    private fun read(context: Context, path: String): List<Region> = runCatching {
        val arr = JSONArray(context.assets.open(path).bufferedReader().use { it.readText() })
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val rs = o.getJSONArray("rings")
            Region(o.getString("code"), o.getString("name"), (0 until rs.length()).map { j ->
                val r = rs.getJSONArray(j)
                DoubleArray(r.length()) { k -> r.getDouble(k) }
            })
        }
    }.getOrElse { Log.d("지역 경계를 읽지 못함: $it"); emptyList() }

    fun provinceOf(lat: Double, lon: Double) = provinces.firstOrNull { it.contains(lat, lon) }
    /** 바닷가처럼 간략한 경계 바로 밖이면 가장 가까운 시·군·구로 (5km 안) */
    fun districtOf(lat: Double, lon: Double) = districts.firstOrNull { it.contains(lat, lon) }
        ?: districts.minByOrNull { nearestDist(it, lat, lon) }?.takeIf { nearestDist(it, lat, lon) < NEAR_DEG }

    private fun nearestDist(r: Region, lat: Double, lon: Double): Double {
        var best = Double.MAX_VALUE
        r.rings.forEach { ring -> for (i in ring.indices step 2) best = minOf(best, kotlin.math.hypot(ring[i] - lat, ring[i + 1] - lon)) }
        return best
    }
    private const val NEAR_DEG = 0.05
    /** 시·군·구가 속한 시·도 */
    fun provinceOfDistrict(d: Region) = provinces.firstOrNull { it.code == d.code.take(2) }

    private fun inRing(r: DoubleArray, lat: Double, lon: Double): Boolean {
        var inside = false
        var j = r.size - 2
        var i = 0
        while (i < r.size) {
            val yi = r[i]; val xi = r[i + 1]; val yj = r[j]; val xj = r[j + 1]
            if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
            j = i; i += 2
        }
        return inside
    }

    private fun area(r: DoubleArray): Double {
        var a = 0.0
        var j = r.size - 2
        var i = 0
        while (i < r.size) { a += r[j + 1] * r[i] - r[i + 1] * r[j]; j = i; i += 2 }
        return a / 2
    }

    private fun centroid(r: DoubleArray): Pair<Double, Double> {
        var cx = 0.0; var cy = 0.0
        val a = area(r)
        if (kotlin.math.abs(a) < 1e-12) return r[0] to r[1]
        var j = r.size - 2
        var i = 0
        while (i < r.size) {
            val f = r[j + 1] * r[i] - r[i + 1] * r[j]
            cx += (r[j + 1] + r[i + 1]) * f; cy += (r[j] + r[i]) * f
            j = i; i += 2
        }
        return cy / (6 * a) to cx / (6 * a)
    }
}
