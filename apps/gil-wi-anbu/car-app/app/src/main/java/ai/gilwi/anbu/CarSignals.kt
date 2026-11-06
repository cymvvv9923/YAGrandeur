package ai.gilwi.anbu

import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * 차에서 받는 신호: 기어, 누적 주행거리, 현재 위치, 와이퍼, 하향등, 조수석 탑승, 운전석 문.
 * 위치와 차 상태는 이 앱 안에서만 쓰고 서버로 보내지 않는다.
 * 뒷좌석은 탑승 센서가 없는 차가 많아 보지 않는다.
 */
class CarSignals(context: Context, private val listener: Listener) {

    /** 기어. 모르는 값(GEAR_UNKNOWN 등)은 넘기지 않는다. */
    enum class Gear { P, R, N, D }

    interface Listener {
        fun onGear(gear: Gear)
        fun onLocation(lat: Double, lon: Double)
        fun onOdometer(km: Float)
        /** 앞 와이퍼가 움직이는지 (비) */
        fun onWiper(on: Boolean)
        /** 하향등이 켜졌는지 (밤길·터널) */
        fun onLowBeam(on: Boolean)
        /** 조수석에 사람이 앉았는지 */
        fun onPassenger(occupied: Boolean)
        /** 운전석 문이 열렸다 (닫힐 때는 부르지 않음) */
        fun onDriverDoorOpened()
        /** 운전석 말고 누가 안전벨트를 맸는지 (동승자 판단을 좌석 센서와 함께) */
        fun onOtherBelt(buckled: Boolean) {}
        /** 경고등이 켜졌는지 (켜진 것 개수) */
        fun onWarning(count: Int) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val vehicle = Vehicle(context)
    private val navi = NaviHelper(context.applicationContext)
    private val appContext = context.applicationContext
    private var naviReady = false

    /** 기본 내비가 알려 준 지금 주소 ("수정구 시흥동"). 주행 중 저장한 곳의 이름으로 쓴다. */
    @Volatile var address: String? = null
        private set

    /** 기본 내비의 즐겨찾기(집·회사 등)와 최근 목적지. 남기기의 장소 고르기에 쓴다. */
    data class NaviPlace(val name: String, val lat: Double, val lon: Double, val address: String, val kind: String)
    var naviPlaces: List<NaviPlace> = emptyList()
        private set
    private var bookmarks: List<NaviPlace> = emptyList()
    private var recents: List<NaviPlace> = emptyList()
    /** 기본 내비에서 목적지에 도착했을 때 (이름) */
    var onArrived: ((String) -> Unit)? = null

    /** 시연용 위치. 값이 있으면 내비 위치 대신 쓴다. */
    var demoLocation: Pair<Double, Double>? = null
        set(value) {
            field = value
            value?.let { listener.onLocation(it.first, it.second) }
        }

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(p0: VehicleGear?) {
            toGear(p0)?.let { main.post { listener.onGear(it) } }
        }
        override fun onFailed(p0: Exception) { Log.d("gear 실패: $p0") }
    }

    private val odoListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(p0: Float?) {
            p0?.let { main.post { listener.onOdometer(it) } }
        }
        override fun onFailed(p0: Exception) { Log.d("odometer 실패: $p0") }
    }

    private val front = WindowArea(WindowValue.FRONT_WINDSHIELD)
    private val passengerSeat = SeatArea(SeatValue.ROW_1_RIGHT)
    private val driverDoor = DoorArea(DoorValue.ROW_1_LEFT)
    private var driverDoorOpen: Boolean? = null

    private val wiperListener = object : WiperStateListener {
        override fun onWiperStateUpdated(p0: WiperState?) {
            p0?.let { s -> main.post { listener.onWiper(s == WiperState.ON) } }
        }
        override fun onFailed(p0: Exception) { Log.d("wiper 실패: $p0") }
    }

    private val lowBeamListener = object : LightStateListener {
        override fun onLightStateUpdated(p0: LightState?) {
            p0?.let { s -> main.post { listener.onLowBeam(s == LightState.ON) } }
        }
        override fun onFailed(p0: Exception) { Log.d("lowbeam 실패: $p0") }
    }

    private val passengerListener = object : SeatOccupancyListener {
        override fun onSeatOccupancyUpdated(p0: Boolean?) {
            p0?.let { v -> main.post { listener.onPassenger(occupied(v)) } }
        }
        override fun onFailed(p0: Exception) { Log.d("seat 실패: $p0") }
    }

    private val doorListener = object : DoorPositionListener {
        override fun onPositionUpdated(p0: Int?) {
            val open = (p0 ?: return) > 0
            main.post {
                // 처음 받은 값은 지금 상태일 뿐이라 "열렸다"로 보지 않는다
                if (open && driverDoorOpen == false) listener.onDriverDoorOpened()
                driverDoorOpen = open
            }
        }
        override fun onFailed(p0: Exception) { Log.d("door 실패: $p0") }
    }

    // 안전벨트 (조수석·뒷좌석)·경고등. 값이 바뀌는 알림 대신 짧게 물어본다 (지원 안 하는 차는 조용히 넘어간다)
    private val otherSeats = listOf(SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_RIGHT).map { it to SeatArea(it) }
    private val beltState = HashMap<SeatValue, Boolean>()
    private var lastBelt: Boolean? = null
    private var lastWarning = -1
    private var safetyOk = true

    private val pollSafety = object : Runnable {
        override fun run() {
            runCatching {
                otherSeats.forEach { (v, a) ->
                    vehicle.getSeat().getCurrentSeatBeltBuckledState(a, { b ->
                        main.post {
                            beltState[v] = b == true
                            val any = beltState.values.any { it }
                            if (any != lastBelt) { lastBelt = any; listener.onOtherBelt(any) }
                        }
                    }, { })
                }
            }
            if (safetyOk) runCatching {
                vehicle.getSafety().getTelltalesInfo({ t ->
                    val n = runCatching { t.telltales.size }.getOrDefault(0)
                    main.post { if (n != lastWarning) { lastWarning = n; listener.onWarning(n) } }
                }, { e -> if (e is SecurityException) safetyOk = false })
            }
            main.postDelayed(this, SAFETY_POLL_MS)
        }
    }

    /** 차 고유번호(VIN). 못 읽으면 null. Vehicle 서비스가 연결된 뒤(start 후 잠시)에 부른다. */
    fun readVin(done: (String?) -> Unit) {
        runCatching {
            vehicle.getCarInfo().getVINInfo(
                { v -> main.post { done(v?.trim()?.takeIf { it.isNotEmpty() }) } },
                { e -> Log.d("VIN 실패: $e"); main.post { done(null) } },
            )
        }.onFailure { Log.d("VIN 실패: $it"); done(null) }
    }

    /** 메시지 장소를 경유지로 넣는다(들러 가기). 경로 안내 중일 때 의미가 있다(내비 최대 2곳). */
    fun addStopover(lat: Double, lon: Double, name: String): Boolean {
        if (!naviReady) return false
        return runCatching {
            navi.addWaypoint(RequestWaypointInfo(lat, lon, name, WaypointIndex.FIRST, "", "", "", ""))
            Log.d("경유지 추가: $name")
        }.onFailure { Log.d("경유지 추가 실패: $it") }.isSuccess
    }

    // 차의 GPS (안드로이드 위치). 에뮬레이터의 Extended Controls → Location(경로 재생)은 이쪽만 바뀌고 기본 내비는 따라가지 않아서,
    // GPS가 새로 들어오면 그것을 먼저 쓰고, GPS가 10초 넘게 조용하면 내비 위치를 쓴다.
    private var lastGpsAt = 0L
    private val gpsListener = android.location.LocationListener { l ->
        if (demoLocation != null) return@LocationListener
        lastGpsAt = android.os.SystemClock.uptimeMillis()
        main.post { listener.onLocation(l.latitude, l.longitude) }
    }

    private fun startGps() {
        if (appContext.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.d("GPS 권한 없음 (내비 위치만 씀)"); return
        }
        val lm = appContext.getSystemService(android.location.LocationManager::class.java) ?: return
        runCatching {
            lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 1000L, 0f, gpsListener, Looper.getMainLooper())
            Log.d("GPS 듣기 시작")
        }.onFailure { Log.d("GPS 듣기 실패: $it") }
    }

    /** 화면에서 위치 권한을 받은 뒤 다시 부른다 */
    fun restartGps() { stopGps(); startGps() }

    private fun stopGps() {
        runCatching { appContext.getSystemService(android.location.LocationManager::class.java)?.removeUpdates(gpsListener) }
    }

    private fun gpsFresh() = lastGpsAt > 0 && android.os.SystemClock.uptimeMillis() - lastGpsAt < GPS_FRESH_MS

    private val naviListener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            if (demoLocation != null) return
            currentLocationInfo.address?.takeIf { it.isNotBlank() }?.let { address = it }
            if (gpsFresh()) return // GPS가 움직이고 있으면 그쪽을 쓴다
            val lat = currentLocationInfo.latitude
            val lon = currentLocationInfo.longitude
            if (lat == 0.0 && lon == 0.0) return
            main.post { listener.onLocation(lat, lon) }
        }

        override fun onBookmarkInfo(bookmarkInfo: List<ai.pleos.playground.navi.data.BookmarkInfo>) {
            bookmarks = bookmarkInfo.map {
                val kind = when (it.category) {
                    ai.pleos.playground.navi.constants.BookmarkType.HOME -> "home"
                    ai.pleos.playground.navi.constants.BookmarkType.OFFICE -> "office"
                    else -> "bookmark"
                }
                NaviPlace(when (kind) { "home" -> "집"; "office" -> "회사"; else -> it.name }, it.latitude, it.longitude, it.address.orEmpty(), kind)
            }
            main.post { mergePlaces() }
        }

        override fun onRecentDestinationInfo(recentDestinationInfo: List<ai.pleos.playground.navi.data.RecentDestinationInfo>) {
            recents = recentDestinationInfo.map { NaviPlace(it.name, it.latitude, it.longitude, it.address.orEmpty(), "recent") }
            main.post { mergePlaces() }
        }

        override fun onBookMarkChanged() { if (naviReady) runCatching { navi.getBookmarkInfo() } }

        override fun onDestinationArrived(destinationArrivedInfo: ai.pleos.playground.navi.data.DestinationArrivedInfo) {
            Log.d("내비 목적지 도착: ${destinationArrivedInfo.name}")
            main.post { onArrived?.invoke(destinationArrivedInfo.name) }
        }
    }

    /** 즐겨찾기 먼저, 그다음 최근 목적지 (같은 곳은 한 번만) */
    private fun mergePlaces() {
        val all = (bookmarks.sortedBy { if (it.kind == "home") 0 else if (it.kind == "office") 1 else 2 } + recents)
        naviPlaces = all.filter { it.lat != 0.0 }.distinctBy { "%.4f,%.4f".format(it.lat, it.lon) }.take(8)
        Log.d("내비 장소 ${naviPlaces.size}곳: ${naviPlaces.joinToString { it.name }}")
    }

    /** 기본 내비 화면에서 이 장소를 보여 준다 (목적지로 안내하기 전, 장소 미리 보기) */
    fun showInNavi(lat: Double, lon: Double, name: String): Boolean {
        if (!naviReady) return false
        return runCatching {
            navi.requestTrip(ai.pleos.playground.navi.data.TripRequest(lat, lon, "", name, ""))
            // 장소 정보만 계산하고 화면은 그대로라, 기본 내비 화면을 앞으로 부른다
            navi.requestNavigationScreen(ai.pleos.playground.navi.data.NavigationScreenRequest.Foreground)
            Log.d("내비에 장소 보여 주기: $name")
        }.onFailure { Log.d("내비 장소 보여 주기 실패: $it") }.isSuccess
    }

    /** 기본 내비로 이 장소까지 길 안내 */
    fun routeTo(lat: Double, lon: Double, name: String): Boolean {
        if (!naviReady) return false
        return runCatching {
            navi.requestRoute(ai.pleos.playground.navi.data.RouteInfo(lat, lon, null, null, name, "", "", "",
                ai.pleos.playground.navi.constants.RouteOption.RECOMMENDED, "", false))
            Log.d("내비 길 안내: $name")
        }.onFailure { Log.d("내비 길 안내 실패: $it") }.isSuccess
    }

    private val pollLocation = object : Runnable {
        override fun run() {
            if (naviReady && demoLocation == null) navi.getCurrentLocationInfo()
            main.postDelayed(this, LOCATION_POLL_MS)
        }
    }

    fun start() {
        vehicle.initialize()
        // Vehicle 서비스 연결이 끝날 시간을 잠깐 준다
        main.postDelayed({
            val gear = vehicle.getGear()
            gear.getCurrentGearSelectionState({ g -> toGear(g)?.let { main.post { listener.onGear(it) } } }, { Log.d("gear get 실패: $it") })
            gear.registerGearSelection(gearListener)
            val odo = vehicle.getOdometer()
            odo.getCurrentDistanceDriven({ km -> km?.let { main.post { listener.onOdometer(it) } } }, { Log.d("odometer get 실패: $it") })
            odo.registerDistanceDriven(odoListener)
            // 차 상태 조건(비·밤길·동승·탈 때). 지원하지 않는 차면 실패 로그만 남고 장소 메시지는 그대로 된다.
            runCatching {
                vehicle.getWiper().getCurrentWiperState(front, { s -> main.post { listener.onWiper(s == WiperState.ON) } }, { Log.d("wiper get 실패: $it") })
                vehicle.getWiper().registerWiperState(front, wiperListener)
            }.onFailure { Log.d("wiper 등록 실패: $it") }
            runCatching {
                vehicle.getLight().getCurrentLowBeamLightState({ s -> main.post { listener.onLowBeam(s == LightState.ON) } }, { Log.d("lowbeam get 실패: $it") })
                vehicle.getLight().registerLowBeamLightState(lowBeamListener)
            }.onFailure { Log.d("lowbeam 등록 실패: $it") }
            runCatching {
                vehicle.getSeat().getCurrentSeatOccupancy(passengerSeat, { v -> v?.let { main.post { listener.onPassenger(occupied(it)) } } }, { Log.d("seat get 실패: $it") })
                vehicle.getSeat().registerSeatOccupancy(passengerSeat, passengerListener)
            }.onFailure { Log.d("seat 등록 실패: $it") }
            runCatching {
                vehicle.getDoor().getCurrentDoorPosition(driverDoor, { p -> main.post { if (driverDoorOpen == null) driverDoorOpen = (p ?: 0) > 0 } }, { Log.d("door get 실패: $it") })
                vehicle.getDoor().registerDoorPosition(driverDoor, doorListener)
            }.onFailure { Log.d("door 등록 실패: $it") }
            main.post(pollSafety)
        }, 1500)

        navi.initialize(
            {
                Log.d("NaviHelper 연결됨")
                naviReady = true
                navi.addListener(naviListener)
                runCatching { navi.getBookmarkInfo(); navi.getRecentDestinationInfo() }
            },
            { Log.d("NaviHelper 연결 실패: $it") },
        )
        main.post(pollLocation)
        startGps()
    }

    fun stop() {
        main.removeCallbacks(pollLocation)
        main.removeCallbacks(pollSafety)
        stopGps()
        runCatching { vehicle.getGear().unregisterGearSelection(gearListener) }
        runCatching { vehicle.getOdometer().unregisterDistanceDriven(odoListener) }
        runCatching { vehicle.getWiper().unregisterWiperState(front, wiperListener) }
        runCatching { vehicle.getLight().unregisterLowBeamLightState(lowBeamListener) }
        runCatching { vehicle.getSeat().unregisterSeatOccupancy(passengerSeat, passengerListener) }
        runCatching { vehicle.getDoor().unregisterDoorPosition(driverDoor, doorListener) }
        runCatching { navi.removeListener(naviListener) }
        runCatching { navi.release() }
        runCatching { vehicle.release() }
    }

    companion object {
        const val LOCATION_POLL_MS = 3000L
        const val SAFETY_POLL_MS = 5000L
        /** GPS가 이 시간 안에 들어왔으면 내비 위치보다 GPS를 쓴다 */
        const val GPS_FRESH_MS = 10_000L

        /**
         * 탑승 센서 값 → 사람이 있는지. 에뮬레이터는 반대로 들어온다(콘솔 Occupied → false, 2026-11-02 확인, T01 기록과 같음).
         * 실차에서 방향이 맞게 들어오면 이 값만 false로 바꾼다.
         */
        const val OCCUPANCY_INVERTED = true
        fun occupied(raw: Boolean) = raw != OCCUPANCY_INVERTED

        fun toGear(g: VehicleGear?): Gear? = when (g) {
            VehicleGear.GEAR_PARK -> Gear.P
            VehicleGear.GEAR_REVERSE -> Gear.R
            VehicleGear.GEAR_NEUTRAL -> Gear.N
            null, VehicleGear.GEAR_UNKNOWN -> null
            else -> Gear.D // DRIVE, 1~9단
        }
    }
}
