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
    }

    private val main = Handler(Looper.getMainLooper())
    private val vehicle = Vehicle(context)
    private val navi = NaviHelper(context.applicationContext)
    private var naviReady = false

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

    /** 메시지 장소를 경유지로 넣는다(들러 가기). 경로 안내 중일 때 의미가 있다(내비 최대 2곳). */
    fun addStopover(lat: Double, lon: Double, name: String): Boolean {
        if (!naviReady) return false
        return runCatching {
            navi.addWaypoint(RequestWaypointInfo(lat, lon, name, WaypointIndex.FIRST, "", "", "", ""))
            Log.d("경유지 추가: $name")
        }.onFailure { Log.d("경유지 추가 실패: $it") }.isSuccess
    }

    private val naviListener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            if (demoLocation != null) return
            val lat = currentLocationInfo.latitude
            val lon = currentLocationInfo.longitude
            if (lat == 0.0 && lon == 0.0) return
            main.post { listener.onLocation(lat, lon) }
        }
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
        }, 1500)

        navi.initialize(
            {
                Log.d("NaviHelper 연결됨")
                naviReady = true
                navi.addListener(naviListener)
            },
            { Log.d("NaviHelper 연결 실패: $it") },
        )
        main.post(pollLocation)
    }

    fun stop() {
        main.removeCallbacks(pollLocation)
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
