package ai.gilwi.anbu

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * 앱의 두뇌. AnbuService가 살려 두므로 내비가 앞에 떠 있어도 계속 돈다.
 * - 기어·위치를 보고 근처 메시지를 모으고, 정차(P)하면 재생한다.
 * - 화면(MainActivity)은 이 상태를 보여 주기만 하고, 없어도 알림으로 「들었어요」를 누를 수 있다.
 */
object Engine : CarSignals.Listener {

    /** 화면이 열려 있을 때만 붙는다 */
    interface Ui {
        fun changed()
        fun onNeedDriver()
        /** 출발·후진 등 P가 아닌 기어. toNavi=true(D)면 기본 내비 화면으로 */
        fun onDriving(toNavi: Boolean)
        fun onPlayback()
        fun onPlaybackEnd()
    }

    var ui: Ui? = null
    /** 화면이 앞에 보이는지 */
    var uiVisible = false
    /** 화면에서 설정·입력 중이면 자동 재생을 미룬다 */
    var uiBusy = false

    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private lateinit var app: Context
    lateinit var store: TripStore
        private set
    private lateinit var car: CarSignals
    private lateinit var player: Player

    var circle: Circle? = null
    var messages: List<PlaceMessage> = emptyList()
        private set
    var serverOk = false
        private set
    /** 지금 기어. 시동 직후 모르면 null */
    var gear: CarSignals.Gear? = null
        private set
    /** P인지. 모르면 null (화면은 null도 운전 중처럼 조작을 막는다) */
    val parked: Boolean? get() = gear?.let { it == CarSignals.Gear.P }
    /** 지난 정차 뒤 D로 달렸는지. P→R→P 같은 주차 조작은 쉬어간 횟수로 세지 않는다. */
    private var droveSinceLastPark = false
    /** 저장해 둔 대기 메시지를 서버 목록으로 되살렸는지 */
    private var pendingRestored = false
    var lastLoc: Pair<Double, Double>? = null
        private set
    val pending = mutableListOf<PlaceMessage>()
    var playing: PlaceMessage? = null
        private set
    var playState = ""
        private set
    /** 이번 재생에서 「나중에/다음」으로 넘긴 것. 재생이 끝나면 대기열로 돌아간다. */
    private val skipped = mutableListOf<PlaceMessage>()
    // 지금 차 상태 (메시지 열림 조건과 일기에 쓴다)
    var rainNow = false
        private set
    var nightNow = false
        private set
    var passengerNow = false
        private set
    var feedHeard: List<Api.Heard> = emptyList()
        private set
    var feedDiaries: List<Api.Diary> = emptyList()
        private set

    fun start(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        store = TripStore(app)
        player = Player(app)
        car = CarSignals(app, this)
        car.start()
        refreshFromServer()
    }

    fun stop() {
        if (!started) return
        started = false
        main.removeCallbacksAndMessages(null)
        car.stop()
        player.release()
    }

    private fun changed() {
        if (pendingRestored) store.pendingIds = (listOfNotNull(playing) + pending + skipped).map { it.id }.distinct()
        ui?.changed()
        Notifier.updateStatus()
    }

    // 서버 ------------------------------------------------------------

    private fun refreshFromServer() {
        val circleId = BuildConfig.CIRCLE_ID
        Api.loadCircle(circleId) { c ->
            serverOk = c != null
            if (c != null) circle = c
            changed()
            // 시동 직후엔 네트워크가 늦게 붙으므로 실패하면 금방 다시 시도한다
            main.postDelayed(::refreshFromServer, if (c != null) REFRESH_MS else RETRY_MS)
        }
        refreshMessages()
        loadFeed()
    }

    fun refreshMessages() = Api.loadMessages(BuildConfig.CIRCLE_ID) { list ->
        if (list != null) {
            messages = list
            // 그사이 신고로 숨겨졌거나 지워진 메시지는 대기열에서도 뺀다
            val ids = list.map { it.id }.toSet()
            pending.removeAll { it.id !in ids }
            skipped.removeAll { it.id !in ids }
            if (!pendingRestored) {
                pendingRestored = true
                // 앱이 꺼졌다 켜져도 오늘 받은 메시지를 잃지 않게 (「보내지 않기」한 것은 들은 것으로 쳐서 빼고)
                store.pendingIds.filter { !store.isNoAutoHeard(it) }
                    .mapNotNull { id -> list.firstOrNull { it.id == id } }.forEach { if (it !in pending) pending.add(it) }
            }
            lastLoc?.let { matchNearby(it.first, it.second) }
            matchCarState()
        }
        changed()
    }

    fun loadFeed() = Api.loadFeed(BuildConfig.CIRCLE_ID) { h, d ->
        if (h != null) feedHeard = h.sortedByDescending { it.at }
        if (d != null) feedDiaries = d.sortedByDescending { it.date }
        ui?.changed()
    }

    fun uploadDiary() {
        val driver = store.driverId ?: return
        val c = circle ?: return
        if (!store.sharingAnything) return
        Api.sendDiary(c.id, driver, store.today, store.diary(full = false, circleType = c.type)) { ok -> Log.d("일기 업로드: $ok") }
    }

    // 운전자 ------------------------------------------------------------

    fun setDriver(id: String?) {
        store.driverId = id
        if (id != null) pending.removeAll { it.authorId == id }
        Notifier.clearNeedDriver()
        changed()
        maybeAutoPlay()
    }

    fun setDemoLocation(loc: Pair<Double, Double>?) { car.demoLocation = loc }

    // 차 신호 ------------------------------------------------------------

    override fun onGear(gear: CarSignals.Gear) {
        if (gear == this.gear) return // 같은 값이 여러 번 온다
        val prev = this.gear
        this.gear = gear
        Log.d("기어 $prev → $gear driver=${store.driverId}")
        if (gear == CarSignals.Gear.D) {
            droveSinceLastPark = true
            recordDriveConditions()
        }
        if (gear == CarSignals.Gear.P) {
            if (prev != null && droveSinceLastPark) {
                store.onParkedAfterDriving()
                uploadDiary()
            }
            droveSinceLastPark = false
            if (store.driverId == null) {
                ui?.onNeedDriver()
                if (!uiVisible) Notifier.needDriver()
            } else {
                maybeAutoPlay()
            }
        } else {
            // 끝까지 듣고 출발했으면 「들었어요」로 본다
            if (autoHeardMsg != null && autoHeardMsg?.id == playing?.id) heard()
            // 운전을 시작하면 재생을 멈추고 대기열로 돌려놓는다
            playing?.let {
                player.stop()
                // 「보내지 않기」한 것은 들은 것으로 친다(알리지만 않음). 다시 넣으면 정차할 때마다 또 나온다.
                if (!store.isNoAutoHeard(it.id)) pending.add(0, it)
                playing = null
                pending.addAll(skipped); skipped.clear()
                Notifier.clearPlaying()
            }
            // 후진(R)일 땐 후방 카메라를 가리지 않게 내비를 띄우지 않는다
            ui?.onDriving(toNavi = gear == CarSignals.Gear.D)
        }
        changed()
    }

    override fun onLocation(lat: Double, lon: Double) {
        val prev = lastLoc
        lastLoc = lat to lon
        if (prev == null || MainActivity.distanceM(prev.first, prev.second, lat, lon) > 1) Log.d("위치 $lat, $lon")
        if (prev != null && gear == CarSignals.Gear.D) {
            val d = MainActivity.distanceM(prev.first, prev.second, lat, lon)
            if (d < 2000) store.addGpsDistance(d) // 시연용 순간이동은 거리에서 뺀다
        }
        matchNearby(lat, lon)
        changed()
    }

    override fun onOdometer(km: Float) {
        store.onOdometer(km)
        ui?.changed()
    }

    override fun onWiper(on: Boolean) {
        if (on == rainNow) return
        rainNow = on
        Log.d("와이퍼 $on")
        recordDriveConditions()
        matchCarState()
        changed()
    }

    override fun onLowBeam(on: Boolean) {
        if (on == nightNow) return
        nightNow = on
        Log.d("하향등 $on")
        recordDriveConditions()
        matchCarState()
        changed()
    }

    override fun onPassenger(occupied: Boolean) {
        if (occupied == passengerNow) return
        passengerNow = occupied
        Log.d("조수석 탑승 $occupied")
        recordDriveConditions()
        matchCarState()
        changed()
    }

    /** 운전석 문을 열면 "차에 탈 때" 메시지를 연다. 정차 중이라 바로 들려준다. */
    override fun onDriverDoorOpened() {
        Log.d("운전석 문 열림")
        deliver(messages.filter { it.opensOn == "door" })
        changed()
    }

    /** 일기용: D로 달리는 동안 비·밤길·동승이었는지 */
    private fun recordDriveConditions() {
        if (gear != CarSignals.Gear.D) return
        if (rainNow) store.markCondition("rain")
        if (nightNow) store.markCondition("night")
        if (passengerNow) store.markCondition("together")
    }

    /** 차 안에서만 하는 근처 계산. */
    private fun matchNearby(lat: Double, lon: Double) {
        deliver(messages.filter { it.isPlace && MainActivity.distanceM(lat, lon, it.lat, it.lon) <= it.radiusM })
    }

    /** 지금 차 상태(비·밤길·동승)에 맞는 메시지를 연다. "차에 탈 때"는 문이 열릴 때만. */
    private fun matchCarState() {
        deliver(messages.filter {
            when (it.opensOn) {
                "rain" -> rainNow
                "night" -> nightNow
                "together" -> passengerNow
                else -> false
            }
        })
    }

    /** 조건이 맞은 메시지를 대기열에 넣는다. 같은 메시지는 하루 한 번. */
    private fun deliver(candidates: List<PlaceMessage>) {
        val driver = store.driverId
        val added = mutableListOf<PlaceMessage>()
        for (m in candidates) {
            if (m.authorId == driver) continue
            if (m.kind == "text" && !store.acceptTts) continue
            if (!store.markDelivered(m.id)) continue
            Log.d("메시지 열림: ${m.placeName} (${m.authorName}) [${m.opensOn}]")
            pending.add(m)
            added += m
        }
        if (added.isEmpty()) return
        // 운전 중에는 알림음과 한 줄만. 본문은 차를 세우면 들려준다.
        if (parked != true) Notifier.arrived(added.last(), pending.size) else maybeAutoPlay()
    }

    /** 들러 가기: 지금 메시지의 장소를 내비 경유지로 */
    fun addStopover(m: PlaceMessage): Boolean = m.isPlace && car.addStopover(m.lat, m.lon, m.placeName)

    // 재생 ------------------------------------------------------------

    /** 정차 중이고 운전자가 정해졌고 화면에서 다른 일을 하고 있지 않으면 재생을 시작한다. */
    fun maybeAutoPlay() {
        if (parked != true || store.driverId == null || pending.isEmpty() || playing != null) return
        if (uiVisible && uiBusy) return
        if (!uiVisible) Notifier.bringToFront()
        startPlayback(announce = true)
    }

    fun startPlayback(announce: Boolean) {
        if (pending.isEmpty() || parked != true) return
        val first = pending.removeAt(0)
        playing = first
        val count = pending.size + 1
        ui?.onPlayback()
        if (announce) {
            playState = "지나온 길에 메시지가 ${count}개 있어요"
            Notifier.playing(first, playState)
            changed()
            player.speak(playState) { playCurrent() }
        } else {
            playCurrent()
        }
    }

    fun playCurrent() {
        val m = playing ?: return
        stopAutoHeard()
        playState = if (m.kind == "voice") "${m.authorName}님의 목소리" else "${m.authorName}님의 글 (기계음)"
        Notifier.playing(m, playState)
        changed()
        player.play(m) { ok ->
            if (playing?.id != m.id) return@play
            playState = if (ok) "다 들었어요" else if (m.kind == "voice") "재생하지 못했어요" else "기계음을 쓸 수 없어 글로 보여 드려요"
            Notifier.playing(m, playState)
            changed()
            // 운전자가 버튼을 누르지 않아도 되게: 끝까지 들었으면(글은 화면에 보였으면) 잠시 뒤 「들었어요」를 보낸다
            if (ok || m.kind == "text") startAutoHeard(m, if (ok) AUTO_HEARD_SEC else AUTO_HEARD_TEXT_SEC)
        }
    }

    // 자동 「들었어요」 ------------------------------------------------------------

    /** 남은 초. 0이면 기다리는 중이 아님 */
    var autoHeardLeft = 0
        private set
    private var autoHeardMsg: PlaceMessage? = null
    private var autoHeardFrom = 0
    private val autoHeardTick = object : Runnable {
        override fun run() {
            val m = autoHeardMsg ?: return
            if (playing?.id != m.id) return
            if (autoHeardLeft <= 0) { autoHeardMsg = null; heard(); return }
            playState = "${autoHeardLeft}초 뒤 「들었어요」를 보내요"
            // 알림은 떠 있는 동안 내용이 늦게 바뀌어 화면과 숫자가 어긋난다. 그래서 알림에는 처음 한 번만 숫자 없이 쓴다.
            if (autoHeardLeft == autoHeardFrom) Notifier.playing(m, "잠시 뒤 「들었어요」를 보내요", countdown = true)
            changed()
            autoHeardLeft--
            main.postDelayed(this, 1000)
        }
    }

    private fun startAutoHeard(m: PlaceMessage, sec: Int) {
        if (!store.autoHeard) return
        if (store.isNoAutoHeard(m.id)) {
            playState = "알리지 않았어요 · 「들었어요」를 누르면 알려요"
            Notifier.playing(m, playState)
            changed()
            return
        }
        autoHeardMsg = m
        autoHeardLeft = sec
        autoHeardFrom = sec
        main.removeCallbacks(autoHeardTick)
        main.post(autoHeardTick)
    }

    private fun stopAutoHeard() {
        main.removeCallbacks(autoHeardTick)
        autoHeardMsg = null
        autoHeardLeft = 0
    }

    /** 「보내지 않기」: 이번 메시지는 자동으로 알리지 않는다 */
    fun cancelAutoHeard() {
        val m = playing ?: return
        if (autoHeardLeft <= 0 && autoHeardMsg == null) return
        stopAutoHeard()
        store.markNoAutoHeard(m.id)
        playState = "알리지 않았어요 · 「들었어요」를 누르면 알려요"
        Notifier.playing(m, playState)
        changed()
    }

    fun heard() {
        stopAutoHeard()
        val m = playing ?: return
        val driver = store.driverId ?: return
        Api.sendHeard(m.id, driver) { loadFeed() }
        store.addHeard(TripStore.HeardItem(m.id, m.authorName, m.placeName, m.isPlace))
        uploadDiary()
        next(skip = false)
    }

    fun report() {
        val m = playing ?: return
        Api.report(m.id, store.driverId ?: "unknown") { ok -> if (ok) Log.d("신고됨 ${m.id}") }
        messages = messages.filter { it.id != m.id }
        next(skip = false)
    }

    /** 다음 메시지로. skip=true면 지금 것은 나중에 다시 들을 수 있게 둔다. */
    fun next(skip: Boolean = true) {
        stopAutoHeard()
        player.stop()
        playing?.let { if (skip) skipped += it }
        playing = null
        if (pending.isNotEmpty() && parked == true) startPlayback(announce = false) else endPlayback()
    }

    /** 지금 것과 남은 것 모두 나중에 */
    fun later() {
        stopAutoHeard()
        player.stop()
        playing?.let { pending.add(0, it) }
        playing = null
        endPlayback()
    }

    private fun endPlayback() {
        pending.addAll(skipped); skipped.clear()
        Notifier.clearPlaying()
        ui?.onPlaybackEnd()
        changed()
    }

    private const val REFRESH_MS = 30_000L
    private const val RETRY_MS = 5_000L
    private const val AUTO_HEARD_SEC = 5
    private const val AUTO_HEARD_TEXT_SEC = 10
}
