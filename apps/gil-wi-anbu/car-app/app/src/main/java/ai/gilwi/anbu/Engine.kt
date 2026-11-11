package ai.gilwi.anbu

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/**
 * 앱의 두뇌. AnbuService가 살려 두므로 내비가 앞에 떠 있어도 계속 돈다.
 * - 기어·위치를 보고 근처 메시지를 모으고, 정차(P)하면 재생한다.
 * - 화면(MainActivity)은 이 상태를 보여 주기만 하고, 없어도 알림으로 「들었어요」를 누를 수 있다.
 */
object Engine : CarSignals.Listener {

    /** 화면이 열려 있을 때만 붙는다 */
    interface Ui {
        fun changed()
        /** 이 차의 주인이 정해지지 않았다 (폰에서 주인 없이 남은 차) */
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
    val appContext: Context get() = app
    lateinit var store: TripStore
        private set
    private lateinit var car: CarSignals
    private lateinit var player: Player

    /** 이 차가 들어간 모든 모임 (id → 모임) */
    val circles = LinkedHashMap<String, Circle>()
    /** 지금 관리하는 모임 (설정·모임 관리·남기기가 다룬다). 바뀐 모임을 넣으면 목록도 고친다. */
    var circle: Circle?
        get() = store.active?.circleId?.let { circles[it] }
        set(v) { if (v != null) circles[v.id] = v }
    /** 모든 모임의 메시지 (지도·근처 계산·재생은 모임을 가리지 않는다) */
    var messages: List<PlaceMessage> = emptyList()
        private set
    private val messagesByKey = HashMap<String, List<PlaceMessage>>()
    private val heardByKey = HashMap<String, List<Api.Heard>>()
    /** 처음 메시지 목록을 받은 연결. 모두 받기 전에는 저장된 대기열을 덮어쓰지 않는다. */
    private val loadedKeys = mutableSetOf<String>()
    var serverOk = false
        private set
    /** 지금 기어. 시동 직후 모르면 null */
    var gear: CarSignals.Gear? = null
        private set
    /** P인지. 모르면 null (화면은 null도 운전 중처럼 조작을 막는다) */
    val parked: Boolean? get() = gear?.let { it == CarSignals.Gear.P }
    /** 지난 정차 뒤 D로 달렸는지. P→R→P 같은 주차 조작은 쉬어간 횟수로 세지 않는다. */
    private var droveSinceLastPark = false
    /** 이번 주행을 시작한 때 (정차했을 때 이번에 저장한 곳을 센다) */
    private var driveStartedAt = 0L
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
    private var seatOccupied = false
    private var otherBelt = false
    // 열리는 조건의 규약 (ConditionRules): 신호가 잠깐 튄 것으로 안부가 열리지 않게
    private var frontBelt = false
    private var rearBelt = false
    /** 「옆자리에 누가 탄 날」을 여는 동승: 착석+벨트가 이어질 때. passengerNow(사생활 보호)보다 엄격하다 */
    var togetherNow = false
        private set
    private var wiperOn = false
    private var lowBeamOn = false
    /** 시연에서 켠 밤길은 해 지는 시각과 상관없이 전조등을 끌 때까지 둔다 */
    private var nightForced = false
    /** P로 바꾼 때 (오래 운전: 짧게 쉰 것은 이어서 센다) */
    private var parkedAt = 0L
    /** 차가 켜진 뒤 이때까지 메시지가 들어올 때마다 「차에 탈 때」·「시동 걸 때」를 연다 (모임마다 늦게 받아도 놓치지 않게) */
    private var bootOpenUntil = 0L
    /** 「오래 운전할 때」를 세기 시작한 때 */
    private var longStartAt = 0L
    /** 이번 주행에 GPS로 달린 거리 */
    private var tripMeters = 0.0
    private var lastWarnCount = 0
    private var prevRegionCodes: Set<String> = emptySet()
    private val rainCheck = Runnable { if (wiperOn) setRain(true) }
    private val nightCheck = Runnable { evalNight() }
    private val togetherCheck = Runnable { if (togetherCandidate()) setTogether(true) }
    private var parkCheck: Runnable? = null
    var feedHeard: List<Api.Heard> = emptyList()
        private set
    var feedDiaries: List<Api.Diary> = emptyList()
        private set
    /** 이 차가 아직 모임에 들어가지 않았다. 화면은 「새 모임 만들기 / 초대 코드로 들어가기」를 보여 준다. */
    var needPairing = false
        private set
    /** 처음 화면에 계속 보여 줄 마지막 실패 이유 (토스트는 금방 사라져서) */
    var setupError: String? = null
        private set
    /** 처음 화면에서 서버가 닿는지 5초마다 본다 (모임에 들어가면 멈춘다) */
    private val pingTask = object : Runnable {
        override fun run() {
            if (!needPairing) return
            Api.ping { ok ->
                if (ok != serverOk) { serverOk = ok; if (ok && setupError?.contains("연결") == true) setupError = null; changed() }
                if (needPairing) main.postDelayed(this, 5000)
            }
        }
    }

    /** 모임을 만들거나 들어가는 중 (버튼을 두 번 누르지 않게) */
    var setupBusy = false
        private set
    /** 차 고유번호(VIN)의 해시. 서버는 같은 차가 두 번 들어오면 예전 연결을 끊는다. 원래 번호는 보내지 않는다. */
    private var vinHash: String? = null
    /** 옆자리에 누가 있어 들을지 묻는 중. 화면은 보낸 사람을 보이지 않고 「지금 듣기 / 다음에」만 묻는다. */
    var askingPassenger = false
        private set
    /** 동승 중에 「지금 듣기」를 골랐다. 옆자리가 비면 다시 묻는다. */
    private var passengerOk = false
    /** 동승 중에 「다음에」를 골랐다. 옆자리가 비거나 직접 「지금 듣기」를 누를 때까지 틀지 않는다. */
    private var passengerLater = false
    /** 「오늘 온 메시지 모아 듣기」로 다시 듣는 것. 「들었어요」를 또 보내지 않고 끝나면 저절로 넘어간다. */
    private val replayIds = mutableSetOf<String>()

    fun start(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        if (BuildConfig.STANDALONE) LocalServer.init(app)
        store = TripStore(app)
        player = Player(app)
        car = CarSignals(app, this)
        car.start()
        Regions.load(app)
        main.post(longDriveTick)
        Api.key = store.deviceKey
        Api.onUnauthorized = ::dropKey
        if (Api.key == null) beginPairing() else refreshFromServer()
        if (BuildConfig.STANDALONE && Api.key == null && !store.demoSkipped) main.postDelayed({ joinDemo() }, 2500)
        // 다른 모임에 더 들어갈 때도 차 고유번호(해시)를 쓴다
        main.postDelayed({ car.readVin { vin -> if (vinHash == null) useVin(vin) } }, 2000)
    }

    fun stop() {
        if (!started) return
        started = false
        main.removeCallbacksAndMessages(null)
        car.stop()
        player.release()
    }

    private fun changed() {
        if (pendingRestored) store.pendingIds = (listOfNotNull(playing) + pending + skipped).map { it.id }.filter { it !in replayIds }.distinct()
        ui?.changed()
        Notifier.updateStatus()
    }

    // 서버 ------------------------------------------------------------

    // 모임 들어가기 (로그인 없음: 모임을 만들거나 초대 코드를 넣는다) --------------------

    private fun beginPairing() {
        needPairing = true
        setupBusy = false
        circles.clear(); messagesByKey.clear(); heardByKey.clear(); loadedKeys.clear()
        messages = emptyList(); feedHeard = emptyList(); pending.clear(); skipped.clear()
        main.removeCallbacks(refreshTask)
        changed()
        main.removeCallbacks(pingTask); main.post(pingTask)
        // Vehicle 서비스가 붙을 시간을 준 뒤 고유번호를 읽는다. 시동 직후엔 답이 아예 안 올 때가 있어 4초 뒤엔 설치 번호로 간다.
        main.postDelayed({
            main.postDelayed({ if (vinHash == null) useVin(null) }, 4000)
            car.readVin { vin -> if (vinHash == null) useVin(vin) }
        }, 2000)
    }

    private fun useVin(vin: String?) {
        Log.d("차 고유번호 ${if (vin != null) "읽음" else "못 읽어 설치 번호 사용"}")
        vinHash = sha256("gilwi-car:" + (vin ?: store.installId))
    }

    /** 새 모임 만들기 (이미 다른 모임에 있어도 하나 더). 만든 사람이 이 차의 주인이 된다. 실패하면 오류 문구 */
    fun createCircle(circleName: String, type: String, myName: String, title: String, color: String, done: (String?) -> Unit) {
        if (vinHash == null) useVin(null)
        setupBusy = true; changed()
        Api.createCircle(circleName, type, myName, title, color, vinHash!!) { r, err -> joined(r, err, done) }
    }

    /** 초대 코드로 들어가기. 들어간 뒤 홈에서 「나는 누구」를 고른다. */
    fun joinCircle(code: String, done: (String?) -> Unit) {
        if (vinHash == null) useVin(null)
        setupBusy = true; changed()
        Api.joinCircle(code, vinHash!!) { r, err -> joined(r, err, done) }
    }

    private fun joined(r: Api.Joined?, err: String?, done: (String?) -> Unit) {
        setupBusy = false
        if (r == null) {
            serverOk = err != null
            setupError = err ?: "서버에 연결하지 못했어요"
            changed(); done(setupError); return
        }
        setupError = null
        Log.d("모임에 들어감: ${r.circle.name} (${r.carName})")
        serverOk = true
        // 다른 모임에 이미 들어가 있으면 대기열은 그대로 두고 모임만 더한다
        if (needPairing) pendingRestored = false
        store.savePairing(r.key, r.circle.id, r.carName, r.memberId)
        Api.key = r.key
        circles[r.circle.id] = r.circle
        needPairing = false
        r.circle.members.firstOrNull { it.id == r.memberId }?.name?.let { followDriver(r.circle.id, it) }
        refreshFromServer()
        done(null)
    }

    /**
     * 누가 자기 코드로 이 차에 들어왔다: 차 한 대 = 한 사람이므로 다른 모임도 그 사람 차로 맞춘다.
     * 다른 모임에 같은 이름의 사람이 있으면 그 사람을 주인으로, 없으면 그 모임은 쉰다(안부를 열지도 보여 주지도 않음).
     * 원래 주인이 자기 코드로 돌아오면 같은 규칙으로 다시 켜진다.
     */
    private fun followDriver(joinedCircleId: String, name: String) {
        val paused = store.pausedCircles.toMutableSet().apply { remove(joinedCircleId) }
        store.memberships.filter { it.circleId != joinedCircleId }.forEach { ms ->
            val c = circles[ms.circleId] ?: return@forEach
            val same = c.members.firstOrNull { it.name == name }
            if (same == null) { paused += ms.circleId; return@forEach }
            paused -= ms.circleId
            if (ms.ownerId != same.id) Api.setOwner(same.id, ms.key) { car -> if (car != null) store.syncMembership(ms.key, same.id, car) }
        }
        if (paused != store.pausedCircles) Log.d("차 주인이 $name(으)로: 쉬는 모임 ${paused.mapNotNull { circles[it]?.name }}")
        store.pausedCircles = paused
        pending.removeAll { it.circleId in paused }; skipped.removeAll { it.circleId in paused }
        if (playing?.circleId in paused) { player.stop(); playing = null; Notifier.clearPlaying(); ui?.onPlaybackEnd() }
    }

    /** 관리하는 모임 바꾸기 (설정의 「모임 바꾸기」). 지도·재생은 그대로 모든 모임을 쓴다. */
    fun switchCircle(circleId: String) {
        if (store.keyOf(circleId) == null) return
        store.activeCircleId = circleId
        Api.key = store.deviceKey
        Log.d("관리하는 모임: ${circles[circleId]?.name}")
        changed()
        loadFeed()
    }

    /**
     * 서버가 그 열쇠를 모른다(모임에서 이 차를 뺐거나 모임이 지워짐) 또는 이 차가 연결을 끊었다: 그 모임만 뺀다.
     * 남은 모임이 없으면 처음 화면으로 돌아간다.
     */
    private fun dropKey(key: String) {
        if (needPairing) return
        val gone = store.memberships.firstOrNull { it.key == key } ?: return
        Log.d("모임에서 빠짐: ${circles[gone.circleId]?.name ?: gone.circleId}")
        val left = store.removeMembership(key)
        if (left == 0) { unpairedAll(); return }
        circles.remove(gone.circleId); messagesByKey.remove(key); heardByKey.remove(key); loadedKeys.remove(key)
        rebuildMessages(); rebuildHeard()
        if (playing?.circleId == gone.circleId) { player.stop(); playing = null; Notifier.clearPlaying(); ui?.onPlaybackEnd() }
        pending.removeAll { it.circleId == gone.circleId }; skipped.removeAll { it.circleId == gone.circleId }
        Api.key = store.deviceKey
        changed()
    }

    // 앱만 버전: 처음 켜면 시연 가족에 「원우의 차」로 바로 들어간다 ---------------------

    /** 우리집(원우) → 대학 동기(원우) → 우리 둘(원우) 순서로 들어가고, 관리하는 모임은 우리집 */
    fun joinDemo(done: () -> Unit = {}) {
        if (!BuildConfig.STANDALONE) return
        Log.d("시연 가족으로 시작")
        joinCircle(DEMO_HOME) { e1 ->
            if (e1 != null) { Log.d("시연 가족 실패: $e1"); done(); return@joinCircle }
            setOwner(DEMO_OWNER) {
                joinCircle(DEMO_FRIENDS) { e2 ->
                    // 열쇠는 그 모임에 들어간 직후의 것으로 붙잡아 둔다 (다음 모임에 들어가면 Api.key가 바뀐다)
                    val k2 = Api.key
                    if (e2 == null && k2 != null) Api.setOwner(DEMO_FRIEND_OWNER, k2) { name -> store.syncMembership(k2, DEMO_FRIEND_OWNER, name) }
                    joinCircle(DEMO_COUPLE) { e3 ->
                        val k3 = Api.key
                        if (e3 == null && k3 != null) Api.setOwner(DEMO_COUPLE_OWNER, k3) { name -> store.syncMembership(k3, DEMO_COUPLE_OWNER, name) }
                        // 시연 이야기: 운전 중 「녹음해 줘」로 남긴 말은 받는 사람이 다음에 시동을 걸 때 들린다
                        store.voiceWhen = "start"
                        main.postDelayed({
                            store.memberships.firstOrNull { it.circleId == "home" }?.let { switchCircle(it.circleId) }
                            refreshFromServer()
                            done()
                        }, 800)
                    }
                }
            }
        }
    }

    /** 설정의 「시연 데이터로 되돌리기」: 앱 안 서버와 이 차의 기록을 처음으로 */
    fun resetDemo(done: () -> Unit) {
        if (!BuildConfig.STANDALONE) return
        player.stop(); playing = null; pending.clear(); skipped.clear()
        car.demoLocation = null
        LocalServer.reset()
        store.clearPairing()
        Api.key = null
        beginPairing()
        main.postDelayed({ joinDemo(done) }, 500)
    }

    /** 모든 모임에서 빠졌다: 처음부터 다시 연결 */
    private fun unpairedAll() {
        Log.d("들어간 모임이 없어 처음 화면으로")
        store.clearPairing()
        // 앱만 버전: 직접 연결을 끊었으면 다음에 켤 때 시연 가족으로 저절로 들어가지 않는다
        if (BuildConfig.STANDALONE) store.demoSkipped = true
        Api.key = null
        player.stop()
        playing = null
        beginPairing()
    }

    /** 설정의 「이 차 연결 끊기」: 지금 관리하는 모임에서만 빠진다 */
    fun unlink(done: (Boolean) -> Unit) {
        val key = Api.key ?: return done(false)
        Api.unlinkCar { ok ->
            if (ok) dropKey(key)
            done(ok)
        }
    }

    /** 모임 통째로 지우기. 이 차의 그 모임 열쇠도 함께 없어진다. 실패하면 오류 문구 */
    fun deleteCircle(done: (String?) -> Unit) {
        val name = circle?.name ?: return done("모임을 불러오지 못했어요")
        val key = Api.key ?: return done("모임을 불러오지 못했어요")
        Api.deleteCircle(name) { err ->
            if (err == null) dropKey(key)
            done(err)
        }
    }

    fun renameCar(name: String, done: (Boolean) -> Unit) = Api.renameCar(name) { ok ->
        if (ok) store.carName = name
        done(ok)
        changed()
    }

    private fun sha256(s: String) = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    // 서버 (연결된 뒤) ---------------------------------------------------

    private val refreshTask = Runnable { refreshFromServer() }

    private fun refreshFromServer() {
        if (needPairing) return
        main.removeCallbacks(refreshTask)
        val list = store.memberships
        var answered = 0
        var okAny = false
        list.forEach { ms ->
            Api.loadCircle(CIRCLE, ms.key) { c ->
                if (c != null) {
                    okAny = true
                    circles[c.id] = c
                    store.setCircleId(ms.key, c.id)
                }
                if (++answered < list.size) return@loadCircle
                serverOk = okAny
                // 빠진 모임은 목록에서도 뺀다
                val ids = store.memberships.map { it.circleId }.toSet()
                circles.keys.retainAll(ids)
                changed()
                ensureOwners()
                // 시동 직후엔 네트워크가 늦게 붙으므로 실패하면 금방 다시 시도한다
                if (!needPairing) main.postDelayed(refreshTask, if (okAny) REFRESH_MS else RETRY_MS)
            }
            // 폰이나 다른 차에서 이 차 주인·이름을 바꿨을 수 있다
            Api.me(ms.key) { owner, name, ok ->
                if (!ok) return@me
                val prev = store.memberships.firstOrNull { it.key == ms.key } ?: return@me
                store.syncMembership(ms.key, owner, name)
                if (owner == prev.ownerId) return@me
                Log.d("차 주인 바뀜 (${circles[prev.circleId]?.name}): $owner")
                if (ms.key == store.deviceKey) setDriver(owner)
                else { if (owner != null) pending.removeAll { it.authorId == owner }; changed() }
            }
        }
        refreshMessages()
        loadFeed()
    }

    fun refreshMessages() = store.memberships.forEach { ms ->
        Api.loadMessages(CIRCLE, ms.key) { list ->
            if (list != null) {
                // 서버가 모임을 안 붙여 보내도 어느 모임 것인지 안다
                messagesByKey[ms.key] = list.map { if (it.circleId.isEmpty()) it.copy(circleId = ms.circleId) else it }
                loadedKeys += ms.key
                rebuildMessages()
                // 그사이 신고로 숨겨졌거나 지워진 메시지는 대기열에서도 뺀다
                val ids = messages.map { it.id }.toSet()
                pending.removeAll { it.id !in ids }
                skipped.removeAll { it.id !in ids }
                // 앱이 꺼졌다 켜져도 오늘 받은 메시지를 잃지 않게 (「보내지 않기」한 것은 들은 것으로 쳐서 빼고)
                if (!pendingRestored) {
                    store.pendingIds.filter { !store.isNoAutoHeard(it) }
                        .mapNotNull { id -> messages.firstOrNull { it.id == id } }
                        .forEach { if (it !in pending && it.id != playing?.id) pending.add(it) }
                    if (store.memberships.all { it.key in loadedKeys }) pendingRestored = true
                }
                lastLoc?.let { matchNearby(it.first, it.second) }
                matchCarState()
                tryBootOpen()
            }
            changed()
        }
    }

    /** 차가 켜진 뒤 모든 모임의 메시지를 받았으면 「차에 탈 때」·「시동 걸 때」를 연다 (받기 전에 열면 놓친다) */
    private fun tryBootOpen() {
        // 앱만 버전(시연)은 앱을 켠 것을 시동으로 치지 않는다: 「시동을 걸면」·「차에 타면」 시연 카드가 그 순간을 만든다
        if (BuildConfig.STANDALONE) return
        if (System.currentTimeMillis() > bootOpenUntil) return
        if (messages.isEmpty()) return
        Log.d("차 켜짐: 탈 때·시동 안부 열기 (받은 모임 ${loadedKeys.size}곳)")
        // 같은 안부는 하루 한 번(markDelivered)이라 여러 번 불러도 한 번만 열린다
        deliver(messages.filter { it.opensOn == "door" || it.opensOn == "start" })
    }

    private fun rebuildMessages() {
        messages = store.liveMemberships.flatMap { messagesByKey[it.key].orEmpty() }
    }

    /** 「들었어요」 기록은 모든 모임 것을 모은다 (지도의 찾은 수). 일기는 지금 관리하는 모임 것만 (소식 화면). */
    fun loadFeed() = store.memberships.forEach { ms ->
        Api.loadFeed(CIRCLE, ms.key) { h, d ->
            if (h != null) {
                heardByKey[ms.key] = h
                rebuildHeard()
                dropHeardElsewhere()
            }
            if (d != null && ms.key == store.deviceKey) feedDiaries = d.sortedByDescending { it.date }
            ui?.changed()
        }
    }

    private fun rebuildHeard() {
        feedHeard = store.memberships.flatMap { heardByKey[it.key].orEmpty() }.sortedByDescending { it.at }
    }

    /** 이 메시지가 속한 모임에서 이 차의 주인 */
    fun ownerOf(m: PlaceMessage): String? = if (m.circleId.isEmpty()) store.driverId else store.ownerOf(m.circleId)
    /** 이 메시지가 속한 모임의 열쇠 */
    private fun keyOf(m: PlaceMessage): String? = store.keyOf(m.circleId) ?: Api.key

    /**
     * 가족 차가 여러 대면 같은 사람이 오늘 다른 차에서 이미 들었을 수 있다.
     * "하루 한 번"은 차마다 따로 기억하므로, 서버의 「들었어요」 기록(마지막으로 들은 때)으로 한 번 더 거른다.
     */
    private fun heardElsewhereToday(m: PlaceMessage): Boolean {
        val driver = ownerOf(m) ?: return false
        return feedHeard.any { it.messageId == m.id && it.memberId == driver && localDate(it.lastAt) == store.today }
    }

    /** 대기열에 있던 메시지를 다른 차에서 이미 들었으면 뺀다 (재생 중인 것은 그대로) */
    private fun dropHeardElsewhere() {
        val gone = pending.filter(::heardElsewhereToday)
        if (gone.isEmpty()) return
        Log.d("다른 차에서 이미 들음: ${gone.joinToString { it.id }}")
        pending.removeAll(gone.toSet())
        changed() // 저장된 대기열과 상태 알림도 고친다
    }

    /** "2026-11-04T01:30:00Z" → 이 차 시간대의 "2026-11-04" */
    private fun localDate(iso: String): String? = runCatching {
        java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
    }.getOrNull()

    fun uploadDiary() {
        val driver = store.driverId ?: return
        val c = circle ?: return
        if (!store.sharingAnything) return
        Api.sendDiary(c.id, driver, store.today, store.diary(full = false, circleType = c.type)) { ok -> Log.d("일기 업로드: $ok") }
    }

    // 차 주인 ------------------------------------------------------------

    /** 설정의 「차 주인 바꾸기」: 서버에도 알린다 */
    fun setOwner(id: String, done: (Boolean) -> Unit) = Api.setOwner(id) { name ->
        if (name != null) { store.carName = name; setDriver(id) }
        done(name != null)
    }

    /** 주인을 정하는 중인 연결 (같은 요청을 두 번 보내지 않게) */
    private val ownerPending = mutableSetOf<String>()

    /**
     * 「이 차는 누구 차인가요?」는 묻지 않는다 (팀 결정, 시나리오는 한 사람의 차로 흘러간다).
     * 주인이 없는 모임(가족 공용 코드로 들어온 경우 등)은 기본 사람으로 저절로 정한다: 시연 가족이면 원우, 아니면 모임의 첫 사람.
     * 바꾸려면 폰 웹이나 그 사람의 「내 코드」로 다시 들어오면 된다.
     */
    private fun ensureOwners() {
        store.memberships.filter { it.ownerId == null && it.key !in ownerPending }.forEach { ms ->
            val c = circles[ms.circleId] ?: return@forEach
            val pick = c.members.firstOrNull { it.id in setOf(DEMO_OWNER, DEMO_FRIEND_OWNER, DEMO_COUPLE_OWNER) } ?: c.members.firstOrNull() ?: return@forEach
            ownerPending += ms.key
            Api.setOwner(pick.id, ms.key) { name ->
                ownerPending -= ms.key
                if (name == null) return@setOwner
                Log.d("차 주인 저절로 정함: ${c.name} → ${pick.name}")
                store.syncMembership(ms.key, pick.id, name)
                if (ms.key == store.deviceKey) setDriver(pick.id) else changed()
            }
        }
    }

    fun setDriver(id: String?) {
        store.driverId = id
        if (id != null) pending.removeAll { it.authorId == id }
        dropHeardElsewhere()
        Notifier.clearNeedDriver()
        changed()
        maybeAutoPlay()
    }

    fun setDemoLocation(loc: Pair<Double, Double>?) { if (loc != null) demoBypass { car.demoLocation = loc } else car.demoLocation = null }

    /** 시연 동작(이동·시동·세우기) 동안에는 시간대·요일·날짜 조건과 「하루 한 번」을 보지 않는다. 한 번만 들을 안부를 이미 들었으면 그대로 안 연다. */
    private var demoNow = false
    private fun demoBypass(action: () -> Unit) { demoNow = true; try { action() } finally { demoNow = false } }
    /** 시연으로 옮겨 둔 자리 (없으면 실제 GPS·내비 위치) */
    val demoLocation get() = car.demoLocation

    /** 시연: 시동을 건 것처럼 「시동 걸 때」 안부를 연다 */
    fun demoStart(): Int = demoCount { demoBypass { deliver(messages.filter { it.opensOn == "start" }) } }

    /** 시연: 달리다가 지금 자리에서 P로 세운 것처럼 (「도착해 차를 세울 때」). 내비 화면으로 넘어가지 않게 D는 거치지 않는다. */
    fun demoPark(): Int = demoCount {
        if (driveStartedAt == 0L) driveStartedAt = System.currentTimeMillis()
        droveSinceLastPark = true
        if (gear == CarSignals.Gear.P) gear = CarSignals.Gear.D // 같은 기어는 무시하므로
        demoBypass { onGear(CarSignals.Gear.P) }
    }

    /**
     * 시연: 차 신호를 받은 것처럼. kind = door 운전석 문 · rain 와이퍼 · night 전조등 · together 조수석 · warning 경고등 · long 2시간 넘게 달림.
     * 새로 열린 안부 개수를 돌려준다.
     */
    fun demoSignal(kind: String, on: Boolean = true): Int = demoCount {
        demoBypass {
            when (kind) {
                "door" -> deliver(messages.filter { it.opensOn == "door" })
                "rain" -> onWiper(on)
                "night" -> onLowBeam(on)
                "together" -> { otherBelt = false; frontBelt = on; onPassenger(on) }
                "warning" -> onWarning(1)
                "long" -> deliver(messages.filter { it.opensOn == "long" })
            }
        }
    }

    /** 시연 동작으로 새로 열린 안부 개수 */
    private fun demoCount(action: () -> Unit): Int {
        val before = pending.map { it.id }.toSet() + listOfNotNull(playing?.id)
        action()
        return (pending.map { it.id } + listOfNotNull(playing?.id)).count { it !in before }
    }

    /** 위치 권한을 받은 뒤 GPS를 다시 듣는다 */
    fun restartGps() { if (started) car.restartGps() }

    // 차 신호 ------------------------------------------------------------

    override fun onGear(gear: CarSignals.Gear) {
        if (gear == this.gear) return // 같은 값이 여러 번 온다
        val prev = this.gear
        this.gear = gear
        Log.d("기어 $prev → $gear driver=${store.driverId}")
        // 이 차는 시동 신호(IGNITION_STATE)가 없어서, 켜진 뒤 처음 받은 기어를 「시동 걸 때」로 본다
        // 규약: 처음 받은 기어가 P일 때만 (달리는 중에 앱만 다시 켜진 것은 빼고), 마지막 시동 안부에서 30분이 지났을 때
        // 「차에 탈 때」도 같은 때 연다: 시동이 꺼진 차에서는 앱이 없어 타면서 연 문을 볼 수 없다
        if (prev == null && gear == CarSignals.Gear.P && System.currentTimeMillis() - store.lastStartAt > ConditionRules.START_GAP_MS) {
            store.lastStartAt = System.currentTimeMillis()
            bootOpenUntil = System.currentTimeMillis() + ConditionRules.BOOT_WINDOW_MS
            main.postDelayed({ tryBootOpen() }, 2000)
        }
        if (gear == CarSignals.Gear.D) {
            val now = System.currentTimeMillis()
            if (!droveSinceLastPark) { driveStartedAt = now; tripMeters = 0.0 }
            // 오래 운전: 10분 넘게 쉬었으면 새로 센다
            if (longStartAt == 0L || (parkedAt > 0 && now - parkedAt >= ConditionRules.REST_RESET_MS)) longStartAt = now
            parkCheck?.let { main.removeCallbacks(it) }; parkCheck = null
            droveSinceLastPark = true
            recordDriveConditions()
        }
        if (gear == CarSignals.Gear.P) {
            parkedAt = System.currentTimeMillis()
            if (prev != null && droveSinceLastPark) {
                store.onParkedAfterDriving()
                uploadDiary()
                // 이번 주행에 말로 저장한 곳이 있으면, 세웠을 때 한 번 알려 준다 (들을 안부가 있으면 그 안내에 맡긴다)
                val n = store.spots.count { it.at >= driveStartedAt }
                if (n > 0 && pending.isEmpty()) main.postDelayed({ say("달리면서 저장한 곳이 ${n}곳 있어요. 화면에서 안부로 남길 수 있어요.") }, 1500)
                // 「도착해 차를 세울 때」: 장소가 없으면 어디서든, 있으면 그 근처에서 세웠을 때 (시동 끔 신호가 없어 P로 본다)
                // 규약: 이번 주행이 2분 이상이거나 300m 이상이고, P를 20초 넘게 유지할 때 (신호대기 중 잠깐 P는 빼고)
                val longEnough = parkedAt - driveStartedAt >= ConditionRules.PARK_MIN_DRIVE_MS || tripMeters >= ConditionRules.PARK_MIN_DRIVE_M
                val openPark = { deliver(messages.filter { m -> m.opensOn == "park" && (!m.hasPlace || lastLoc?.let { MainActivity.distanceM(it.first, it.second, m.lat, m.lon) <= m.radiusM } == true) }) }
                if (demoNow) openPark()
                else if (longEnough) {
                    parkCheck?.let { main.removeCallbacks(it) }
                    parkCheck = Runnable { parkCheck = null; if (this.gear == CarSignals.Gear.P) openPark() }.also { main.postDelayed(it, ConditionRules.PARK_HOLD_MS) }
                } else Log.d("도착 안부: 이번 주행이 짧아 열지 않음")
            }
            // 달리면서 듣던 것은 세워도 그대로, 화면만 재생 화면으로
            if (playing != null) ui?.onPlayback()
            droveSinceLastPark = false
            // 차 주인은 고르게 하지 않는다: 없으면 저절로 정하고(정해지면 setDriver가 재생을 이어 간다)
            if (store.driverId == null) ensureOwners() else maybeAutoPlay()
        } else {
            askingPassenger = false
            // 끝까지 듣고 출발했으면 「들었어요」로 본다
            if (autoHeardMsg != null && autoHeardMsg?.id == playing?.id) heard()
            // 운전 중에도 소리로 들려주는 설정이면 듣던 것은 이어 간다. 아니면(또는 후진) 멈추고 대기열로 돌려놓는다
            if (!(gear == CarSignals.Gear.D && drivingPlayOk())) playing?.let {
                player.stop()
                // 「보내지 않기」한 것은 들은 것으로 친다(알리지만 않음). 다시 넣으면 정차할 때마다 또 나온다.
                if (!store.isNoAutoHeard(it.id)) pending.add(0, it)
                playing = null
                pending.addAll(skipped); skipped.clear()
                dropReplays()
                Notifier.clearPlaying()
            }
            // 후진(R)일 땐 후방 카메라를 가리지 않게 내비를 띄우지 않는다
            ui?.onDriving(toNavi = gear == CarSignals.Gear.D)
            if (gear == CarSignals.Gear.D) main.postDelayed({ maybeAutoPlay() }, 1500)
        }
        changed()
    }

    /** 운전 중에 소리로만 들려줘도 되는지: 설정을 켰고 동승자가 없을 때 */
    private fun drivingPlayOk() = store.playWhileDriving && !passengerNow

    /** 지금 재생할 수 있는지: 세워 있거나, 달리는 중이면 소리로만 */
    private fun canPlayNow() = parked == true || (gear == CarSignals.Gear.D && drivingPlayOk())

    /** 「오래 운전할 때」: 세우지 않고 달린 시간을 1분마다 본다 */
    private val longDriveTick = object : Runnable {
        override fun run() {
            if (lowBeamOn) evalNight() // 해 지는 시각을 넘겼는지
            if (gear == CarSignals.Gear.D && droveSinceLastPark && longStartAt > 0) {
                val min = (System.currentTimeMillis() - longStartAt) / 60000
                deliver(messages.filter { it.opensOn == "long" && min >= it.longMin })
            }
            main.postDelayed(this, 60_000)
        }
    }

    /** 시연용: 이번 주행을 N분 전에 시작한 것으로 */
    fun demoDriveMinutes(min: Int) { driveStartedAt = System.currentTimeMillis() - min * 60_000L; longStartAt = driveStartedAt; droveSinceLastPark = true; longDriveTick.run() }

    override fun onLocation(lat: Double, lon: Double) {
        val prev = lastLoc
        lastLoc = lat to lon
        if (prev == null || MainActivity.distanceM(prev.first, prev.second, lat, lon) > 1) Log.d("위치 $lat, $lon")
        if (prev != null && gear == CarSignals.Gear.D) {
            val d = MainActivity.distanceM(prev.first, prev.second, lat, lon)
            if (d < 2000) { store.addGpsDistance(d); tripMeters += d } // 시연용 순간이동은 거리에서 뺀다
        }
        matchNearby(lat, lon)
        changed()
    }

    override fun onOdometer(km: Float) {
        store.onOdometer(km)
        ui?.changed()
    }

    /** 규약: 와이퍼가 15초 넘게 계속 돌아야 비 (워셔액 뿌릴 때 잠깐 도는 것은 빼고). 멈추면 바로 끝. */
    override fun onWiper(on: Boolean) {
        Log.d("와이퍼 $on")
        wiperOn = on
        main.removeCallbacks(rainCheck)
        when {
            !on -> setRain(false)
            demoNow -> setRain(true)
            else -> main.postDelayed(rainCheck, ConditionRules.RAIN_HOLD_MS)
        }
    }

    private fun setRain(v: Boolean) {
        if (v == rainNow) return
        rainNow = v
        Log.d("비 $v")
        recordDriveConditions()
        matchCarState()
        changed()
    }

    /** 규약: 전조등이 10초 넘게 켜져 있고 차 시계로 해가 진 뒤여야 밤길 (낮의 터널·궂은 날은 빼고) */
    override fun onLowBeam(on: Boolean) {
        Log.d("하향등 $on")
        lowBeamOn = on
        main.removeCallbacks(nightCheck)
        when {
            !on -> { nightForced = false; evalNight() }
            demoNow -> { nightForced = true; evalNight() }
            else -> main.postDelayed(nightCheck, ConditionRules.NIGHT_HOLD_MS)
        }
    }

    private fun evalNight() {
        val v = lowBeamOn && (nightForced || ConditionRules.isDark(lastLoc?.first, lastLoc?.second))
        if (v == nightNow) return
        nightNow = v
        Log.d("밤길 $v")
        recordDriveConditions()
        matchCarState()
        changed()
    }

    override fun onPassenger(occupied: Boolean) {
        seatOccupied = occupied
        updatePassenger("조수석 착석 $occupied")
    }

    override fun onOtherBelt(buckled: Boolean) {
        otherBelt = buckled
        updatePassenger("다른 좌석 안전벨트 $buckled")
    }

    override fun onBelts(front: Boolean, rear: Boolean) {
        frontBelt = front; rearBelt = rear
        updatePassenger("조수석 벨트 $front · 뒷좌석 벨트 $rear")
    }

    /** 규약: 조수석 착석+조수석 벨트, 또는 뒷좌석 벨트 (가방만 올린 것은 빼고) */
    private fun togetherCandidate() = (seatOccupied && frontBelt) || rearBelt

    private fun setTogether(v: Boolean) {
        if (v == togetherNow) return
        togetherNow = v
        Log.d("옆자리 동승(열기) $v")
        recordDriveConditions()
        matchCarState()
        changed()
    }

    /** 경고등이 켜지면 「경고등이 켜질 때」 메시지 */
    override fun onWarning(count: Int) {
        Log.d("경고등 $count")
        // 규약: 고장·주의 경고등이 새로 켜질 때만 (CarSignals가 표시등은 빼고 센다)
        val rising = count > lastWarnCount
        lastWarnCount = count
        if (count > 0 && (rising || demoNow)) deliver(messages.filter { it.opensOn == "warning" })
    }

    private fun updatePassenger(why: String) {
        // 「옆자리에 누가 탄 날」을 여는 쪽은 30초 넘게 이어질 때만
        main.removeCallbacks(togetherCheck)
        if (!togetherCandidate()) setTogether(false)
        else if (demoNow) setTogether(true)
        else if (!togetherNow) main.postDelayed(togetherCheck, ConditionRules.TOGETHER_HOLD_MS)
        // 사생활 보호(보낸 사람 가리기·먼저 묻기)는 하나만 감지돼도 바로
        val occupied = seatOccupied || otherBelt
        Log.d(why)
        if (occupied == passengerNow) return
        passengerNow = occupied
        Log.d("동승자 $occupied")
        if (!occupied) {
            // 옆자리가 비었으니 미뤄 둔 메시지를 들려준다
            passengerOk = false; passengerLater = false; askingPassenger = false
            main.post { maybeAutoPlay() }
        }
        recordDriveConditions()
        matchCarState()
        changed()
    }

    /** 운전석 문을 열면 "차에 탈 때" 메시지를 연다. 정차 중이라 바로 들려준다. */
    /**
     * 운전석 문 신호는 「차에 탈 때」에 쓰지 않는다: 시동이 꺼진 차에서는 앱이 없어 타면서 연 문을 볼 수 없고,
     * 켜진 뒤 연 문은 대개 내릴 때다. 「차에 탈 때」는 차가 켜져 앱이 처음 뜰 때 연다 (tryBootOpen).
     */
    override fun onDriverDoorOpened() {
        Log.d("운전석 문 열림")
    }

    /** 일기용: D로 달리는 동안 비·밤길·동승이었는지 */
    private fun recordDriveConditions() {
        if (gear != CarSignals.Gear.D) return
        if (rainNow) store.markCondition("rain")
        if (nightNow) store.markCondition("night")
        // 동승은 기록하지 않는다 (옆자리 사람 보호)
    }

    /** 차 안에서만 하는 근처 계산. */
    private fun matchNearby(lat: Double, lon: Double) {
        deliver(messages.filter { it.isPlace && MainActivity.distanceM(lat, lon, it.lat, it.lon) <= it.radiusM })
        // 「그 지역에 들어가면」: 지금 있는 시·군·구(또는 시·도) 코드로
        val regionMsgs = messages.filter { it.opensOn == "region" && it.regionCode != null }
        if (regionMsgs.isNotEmpty()) {
            val d = Regions.districtOf(lat, lon)
            val codes = listOfNotNull(d?.code, d?.code?.take(2), Regions.provinceOf(lat, lon)?.code).toSet()
            // 규약: 같은 지역이 위치 신호 두 번 연속일 때 (경계에서 한 번 튄 것은 빼고)
            val steady = demoNow || codes == prevRegionCodes
            prevRegionCodes = codes
            if (steady) deliver(regionMsgs.filter { it.regionCode in codes })
        }
    }

    /** 지금 차 상태(비·밤길·동승)에 맞는 메시지를 연다. "차에 탈 때"는 문이 열릴 때만. */
    private fun matchCarState() {
        deliver(messages.filter {
            when (it.opensOn) {
                "rain" -> rainNow
                "night" -> nightNow
                // 새로 남길 수는 없지만 예전에 남긴 것은 연다. 틀기 전에 옆자리 배려로 먼저 묻는다
                "together" -> togetherNow
                else -> false
            }
        })
    }

    /** 조건이 맞은 메시지를 대기열에 넣는다. 같은 메시지는 하루 한 번. 내가 남긴 것인지는 그 메시지 모임의 주인으로 본다. */
    private fun deliver(candidates: List<PlaceMessage>) {
        val added = mutableListOf<PlaceMessage>()
        for (m in candidates) {
            val driver = ownerOf(m)
            if (m.authorId == driver) continue
            if (m.to.isNotEmpty() && driver !in m.to) continue // 다른 사람에게 보낸 것
            // 시연은 시간대·요일은 건너뛰지만 시작·끝 날짜는 지킨다 (「1년 뒤부터」 남긴 안부가 바로 열리면 안 된다)
            if (!(if (demoNow) dateOk(m) else timeOk(m))) continue
            if (!repeatOk(m, driver)) continue
            if (m.kind == "text" && !store.acceptTts) continue
            if (m in pending || m.id == playing?.id) continue
            if (heardElsewhereToday(m)) continue // 오늘 다른 차에서 이미 들음
            if (!store.markDelivered(m.id, driver ?: "") && !demoNow) continue
            Log.d("메시지 열림: ${m.placeName} (${m.authorName}) [${m.opensOn}]")
            pending.add(m)
            added += m
        }
        if (added.isEmpty()) return
        // 운전 중에 소리로 들려줄 수 없으면(설정 끔·동승자) 알림음과 한 줄만. 본문은 차를 세우면 들려준다.
        // 옆자리에 누가 있으면 알림에도 보낸 사람을 쓰지 않는다
        if (!canPlayNow()) Notifier.arrived(added.last(), pending.size, discreet = passengerNow && store.askWithPassenger) else maybeAutoPlay()
    }

    /** 시간대·요일·날짜 조건 (차 시계로) */
    private fun dateOk(m: PlaceMessage): Boolean {
        val today = java.time.LocalDate.now().toString()
        return (m.notBefore == null || today >= m.notBefore) && (m.notAfter == null || today <= m.notAfter)
    }

    private fun timeOk(m: PlaceMessage): Boolean {
        // 규약: 차 시계가 아직 맞춰지지 않았으면 시간대·요일·날짜가 있는 안부는 미룬다
        val hasTime = m.notBefore != null || m.notAfter != null || m.days.isNotEmpty() || (m.hourFrom != null && m.hourTo != null)
        if (hasTime && !ConditionRules.clockOk()) return false
        val now = java.time.LocalDateTime.now()
        if (m.notBefore != null && now.toLocalDate().toString() < m.notBefore) return false
        if (m.notAfter != null && now.toLocalDate().toString() > m.notAfter) return false
        if (m.days.isNotEmpty() && now.dayOfWeek.value !in m.days) return false
        val f = m.hourFrom; val t = m.hourTo
        if (f != null && t != null) {
            val h = now.hour
            val inside = if (f <= t) h in f until t else h >= f || h < t
            if (!inside) return false
        }
        return true
    }

    /** 횟수: once 한 번만 · weekly 7일에 한 번 · always 매번(하루 한 번) */
    private fun repeatOk(m: PlaceMessage, driver: String?): Boolean {
        if (m.repeat == "always" || driver == null) return true
        val mine = feedHeard.filter { it.messageId == m.id && it.memberId == driver }
        val heardHere = store.heard.any { it.messageId == m.id }
        return when (m.repeat) {
            "weekly" -> mine.none { h -> runCatching { java.time.Duration.between(java.time.Instant.parse(h.lastAt), java.time.Instant.now()).toDays() < 7 }.getOrDefault(false) } && !heardHere
            else -> mine.isEmpty() && !heardHere
        }
    }

    /** 들러 가기: 지금 메시지의 장소를 내비 경유지로 */
    fun addStopover(m: PlaceMessage): Boolean = m.isPlace && car.addStopover(m.lat, m.lon, m.placeName)

    // 재생 ------------------------------------------------------------

    /** 정차 중이고 운전자가 정해졌고 화면에서 다른 일을 하고 있지 않으면 재생을 시작한다. */
    fun maybeAutoPlay() {
        if (!canPlayNow() || pending.isEmpty() || playing != null) return
        if (parked == true && store.driverId == null) return
        if (parked == true && uiVisible && uiBusy) return
        // 달리는 중: 화면 없이 소리로만 (동승자가 있으면 canPlayNow가 막는다)
        if (parked != true) { Log.d("운전 중 재생"); startPlayback(announce = true, auto = true); return }
        if (needsPassengerOk()) {
            if (passengerLater || askingPassenger) return
            // 옆자리에 누가 있으면 바로 틀지 않고 묻는다. 누가 보냈는지는 말하지 않는다.
            askingPassenger = true
            Log.d("동승 중: 들을지 묻기")
            if (!uiVisible && store.popUpScreen) Notifier.bringToFront()
            changed()
            player.speak(if (store.popUpScreen || uiVisible) "메시지가 도착했어요. 지금 들으시려면 화면의 「지금 듣기」를 눌러 주세요"
                else "메시지가 도착했어요. 들으시려면 다온을 열어 「지금 듣기」를 눌러 주세요") {}
            return
        }
        // 기본은 화면을 띄우지 않는다: 보던 화면(내비 등)은 그대로 두고 소리로만. 보고 싶으면 메인의 「화면 보기」
        if (!uiVisible && store.popUpScreen) Notifier.bringToFront()
        startPlayback(announce = true, auto = true)
    }

    /** 옆자리에 누가 있으면 어떤 안부든 틀기 전에 묻는다 (예전 「옆자리에 누가 탄 날」 안부도 예외 없이) */
    private fun needsPassengerOk() = passengerNow && store.askWithPassenger && !passengerOk && pending.isNotEmpty()

    /** 동승 중 묻기에 「다음에 들을게요」 */
    fun passengerLater() {
        askingPassenger = false
        passengerLater = true
        player.stop()
        changed()
    }

    /** 오늘 들은 메시지를 다시 모아 듣는다. 아직 안 들은 것이 있으면 그것부터. */
    /** 기다리는 안부 지금 듣기. 모임을 골라 두었으면 그 모임 것부터 */
    fun playNow(circleId: String? = null) {
        if (circleId != null) { val (mine, rest) = pending.partition { it.circleId == circleId }; pending.clear(); pending.addAll(mine + rest) }
        startPlayback(announce = false)
    }

    fun playToday(circleId: String? = null) {
        val ids = store.heard.map { it.messageId }
        val again = ids.mapNotNull { id -> messages.firstOrNull { it.id == id } }.filter { it !in pending && it.id != playing?.id }
            .filter { circleId == null || it.circleId == circleId }
        replayIds += again.map { it.id }
        pending.addAll(again)
        // 모임을 골라 두었으면 그 모임 안부부터 (다른 모임 것은 지우지 않고 뒤로)
        if (circleId != null) { val (mine, rest) = pending.partition { it.circleId == circleId }; pending.clear(); pending.addAll(mine + rest) }
        startPlayback(announce = false)
    }

    // 재생 막대 (재생 화면) ---------------------------------------------------

    val playPosition get() = player.position
    val playDuration get() = player.duration
    /** 목소리 메시지라 멈추고 옮길 수 있다 (기계음은 막대만 보인다) */
    val playSeekable get() = player.seekable
    val playPaused get() = player.paused
    /** 기계음(TTS)을 쓸 수 있는지. 못 쓰면 글로만 보여 준다 */
    val ttsReady get() = player.ttsReady
    val playPlayingNow get() = player.playingNow

    fun togglePause() {
        if (playing == null) return
        if (player.playingNow) player.pause() else {
            // 끝까지 들은 뒤 다시 누르면 처음부터
            if (player.duration > 0 && player.position >= player.duration - 300) player.seekTo(0)
            player.resume()
            playing?.let { m -> playState = (if (m.kind == "voice") "${m.authorName}님의 목소리" else "${m.authorName}님의 글 (기계음)") + if (m.id in replayIds) " · 다시 듣기" else "" }
        }
        changed()
    }

    fun seekPlay(ms: Int) {
        if (playing == null) return
        player.seekTo(ms)
        changed()
    }

    // 주행 중 말로 저장 (Gleo 「여기 저장」) ---------------------------------------------

    enum class SpotResult { SAVED, MERGED, NO_LOCATION, NOT_READY, NO_GROUP }

    /**
     * 지금 자리를 임시로 저장한다. 1분 안에 200m 안에서 또 저장하면 하나로 합친다 (메모는 새것으로).
     * groupQuery(「가족」「친구」·모임 이름)를 주면 그 그룹 자리로 저장하고, 비면 지금 관리하는 모임.
     */
    fun saveSpot(memo: String, groupQuery: String = ""): SpotResult {
        if (needPairing) return SpotResult.NOT_READY
        val group = findGroup(groupQuery) ?: return if (groupQuery.isBlank()) SpotResult.NOT_READY else SpotResult.NO_GROUP
        val (lat, lon) = lastLoc ?: return SpotResult.NO_LOCATION
        // 메모가 없으면 기본 내비가 알려 준 지금 주소를 이름으로 ("수정구 시흥동")
        // 시연으로 옮겨 둔 자리면 내비 주소는 다른 곳이라 쓰지 않는다 (화면에서 시·군·구 이름으로)
        val place = if (car.demoLocation != null) "" else car.address.orEmpty()
        val now = System.currentTimeMillis()
        val cond = listOfNotNull("rain".takeIf { rainNow }, "night".takeIf { nightNow }).joinToString(",") // 동승은 남기지 않는다
        val last = store.spots.lastOrNull()
        if (last != null && now - last.at < SPOT_MERGE_MS && MainActivity.distanceM(last.lat, last.lon, lat, lon) < 200) {
            store.updateSpot(last.copy(memo = memo.ifBlank { last.memo }, circleId = if (groupQuery.isNotBlank()) group.id else last.circleId.ifEmpty { group.id }))
            changed()
            return SpotResult.MERGED
        }
        store.addSpot(TripStore.Spot("s" + now.toString(36), lat, lon, now, memo, cond, place, group.id))
        Log.d("주행 중 저장: $lat,$lon ${memo.ifBlank { "" }} [${group.name}]")
        changed()
        return SpotResult.SAVED
    }

    /** 방금 저장한 곳 지우기 */
    fun undoSpot(): Boolean {
        val last = store.spots.lastOrNull() ?: return false
        store.removeSpot(last.id)
        changed()
        return true
    }

    /** 오늘 저장한 곳 수 */
    fun spotCount(): Int {
        val today = java.time.LocalDate.now()
        return store.spots.count { java.time.Instant.ofEpochMilli(it.at).atZone(java.time.ZoneId.systemDefault()).toLocalDate() == today }
    }

    fun removeSpot(id: String) { store.removeSpot(id); changed() }

    // 말로 메시지 남기기 (Gleo 「LEAVE_DAON」) -----------------------------------------
    // 운전 중이든 정차 중이든 같은 길로 화면 없이 보낸다. 글은 Gleo가 받아 적은 그대로, 보내기 전 확인은 Gleo가 한다.

    enum class LeaveResult { SENT, EMPTY_TEXT, BAD_WHEN, NOT_READY, NO_GROUP, NO_AUTHOR, NO_RECIPIENT, NO_LOCATION, NO_SPOT, FAILED }

    /** 그룹 종류를 부르는 말 (서클 type → 말). 사람 이름 대신 그룹으로 부른다: 사람을 말하면 Gleo가 전화·문자로 넘긴다. */
    private val GROUP_WORDS = mapOf(
        "family" to listOf("가족", "식구", "family"),
        "friends" to listOf("친구", "동기", "friend"),
        "couple" to listOf("연인", "커플", "애인", "couple"),
    )

    /** "가족" · "친구들한테" · "대학 동기" · "우리집" → 이 차가 들어간 모임. 비면 지금 관리하는 모임. */
    fun findGroup(query: String): Circle? {
        val joined = store.memberships.mapNotNull { circles[it.circleId] }
        val q = query.trim().removeSuffix("에게").removeSuffix("한테").removeSuffix("께").removeSuffix("들").removeSuffix("방").trim()
        if (q.isEmpty()) return circle
        return joined.firstOrNull { it.name == q }
            ?: joined.firstOrNull { c -> GROUP_WORDS[c.type].orEmpty().any { q.contains(it, ignoreCase = true) } }
            ?: joined.firstOrNull { it.name.contains(q) || q.contains(it.name) }
    }

    /** 이 차가 들어간 모임 이름들 (「녹음해 줘」에서 이 중 하나를 정확히 말해야 그 모임에 남는다) */
    fun groupNames(): List<String> = store.liveMemberships.mapNotNull { circles[it.circleId]?.name }

    /**
     * 모임 이름을 정확히 말했을 때만 그 모임: 「우리집」「대학 동기」(띄어쓰기·「우리집에」「우리집으로」 같은 조사는 봐준다).
     * 「가족」「친구」처럼 종류로만 말하면 찾지 않는다 — 운전 중 녹음은 엉뚱한 모임에 남지 않게 정확히 고른다.
     */
    fun findGroupExact(query: String): Circle? {
        fun squash(s: String) = s.replace(Regex("\\s+"), "")
        val raw = squash(query)
        if (raw.isEmpty()) return null
        val stripped = listOf("에게", "한테", "으로", "로", "에", "의", "께").fold(raw) { acc, p -> if (acc.length > p.length && acc.endsWith(p)) acc.removeSuffix(p) else acc }
        val joined = store.liveMemberships.mapNotNull { circles[it.circleId] }
        return joined.firstOrNull { squash(it.name) == raw } ?: joined.firstOrNull { squash(it.name) == stripped }
    }

    /** recipients: 실제로 받는 사람 이름 (비면 모임 모두), place: 장소 조건이면 장소 이름, detail: 실패 이유(못 찾은 이름·서버 오류) */
    class LeaveOutcome(val result: LeaveResult, val recipients: List<String> = emptyList(), val whenKey: String = "",
                       val place: String = "", val detail: String = "", val text: String = "", val group: String = "")

    /** 열리는 조건의 한국어 이름 (MainActivity.WHEN_OPTIONS, 서버와 같은 키) */
    val LEAVE_WHEN = linkedMapOf(
        // 「옆자리에 누가 탄 날」(together)은 뺐다: 동승자 앞에서 트는 조건이라 옆자리 배려와 어긋난다
        "place" to "이 장소를 지나면", "rain" to "비 오는 날", "night" to "밤길", "door" to "차에 탈 때",
        "start" to "시동 걸 때", "park" to "도착해 차를 세울 때", "long" to "오래 운전할 때", "region" to "그 지역에 들어가면", "warning" to "경고등이 켜질 때",
    )
    private val LEAVE_WITH_PLACE = setOf("place", "park", "region")
    private const val LEAVE_TEXT_MAX = 300

    /** "엄마", "엄마에게", "아버지님" → 모임 구성원. 이름 → 호칭 → 포함 순으로 찾고, 남긴 사람 자신은 뺀다. */
    private fun findMember(c: Circle, query: String, authorId: String): Member? {
        val q = query.trim().removeSuffix("에게").removeSuffix("한테").removeSuffix("께").removeSuffix("님").trim()
        if (q.isEmpty()) return null
        val others = c.members.filter { it.id != authorId }
        return others.firstOrNull { it.name == q } ?: others.firstOrNull { it.title == q }
            ?: others.firstOrNull { it.name.contains(q) || (it.title.isNotBlank() && it.title.contains(q)) }
    }

    /**
     * 메시지를 남긴다. whenKey가 비면 「이 장소를 지나면」(지금 위치). useSpot이면 방금 저장한 곳을 장소로 쓰고 보낸 뒤 지운다.
     * done은 메인 스레드에서 한 번 불린다.
     */
    fun leaveMessage(text: String, toQueries: List<String>, whenKey: String, useSpot: Boolean, groupQuery: String = "",
                     audioBase64: String? = null, done: (LeaveOutcome) -> Unit) {
        // audioBase64가 있으면 목소리 그대로 남긴다 (운전 중 「녹음해 줘」)
        val body = if (audioBase64 != null) "" else text.trim().take(LEAVE_TEXT_MAX)
        if (audioBase64 == null && body.isEmpty()) return done(LeaveOutcome(LeaveResult.EMPTY_TEXT))
        // 말로 남길 때는 조건을 말하지 않는다: 메시지 화면에서 정해 둔 조건을 쓴다
        val w = whenKey.trim().ifEmpty { store.voiceWhen.takeIf { it in LEAVE_WHEN } ?: "place" }
        if (w !in LEAVE_WHEN) return done(LeaveOutcome(LeaveResult.BAD_WHEN, detail = w))
        if (needPairing) return done(LeaveOutcome(LeaveResult.NOT_READY))
        // Gleo가 운전자가 말하지 않았는데도 「저장한 곳에」(useSavedSpot)를 켜서 보낼 때가 있다 (11/10 로그).
        // 저장한 곳이 없으면 실패하지 않고 지금 자리에 남긴다.
        val useSpot = useSpot && store.spots.isNotEmpty()
        // 받는 쪽은 그룹(모임) 단위: 「가족에게」면 가족 모임 모두, 「친구에게」면 친구 모임 모두
        // 저장한 곳에 남기면서 그룹을 말하지 않았으면, 저장할 때 고른 그룹으로 간다
        val spotGroup = if (useSpot && groupQuery.isBlank()) store.spots.lastOrNull()?.circleId?.let { circles[it] } else null
        val c = spotGroup ?: findGroup(groupQuery) ?: return done(LeaveOutcome(if (groupQuery.isBlank()) LeaveResult.NOT_READY else LeaveResult.NO_GROUP, detail = groupQuery.trim()))
        val author = store.ownerOf(c.id) ?: store.driverId.takeIf { c.id == circle?.id } ?: return done(LeaveOutcome(LeaveResult.NO_AUTHOR))

        val to = mutableListOf<Member>()
        for (q in toQueries.filter { it.isNotBlank() }) {
            val m = findMember(c, q, author) ?: return done(LeaveOutcome(LeaveResult.NO_RECIPIENT, detail = q.trim()))
            if (m !in to) to += m
        }

        val json = JSONObject().put("authorId", author).put("when", w).put("kind", if (audioBase64 != null) "voice" else "text").put("text", body).put("repeat", "once")
        audioBase64?.let { json.put("audioBase64", it).put("audioExt", "m4a") }
        if (to.isNotEmpty()) json.put("to", JSONArray(to.map { it.id }))

        var placeName = ""
        val spot = if (useSpot && w in LEAVE_WITH_PLACE) (store.spots.lastOrNull() ?: return done(LeaveOutcome(LeaveResult.NO_SPOT))) else null
        if (w in LEAVE_WITH_PLACE) {
            val (lat, lon) = if (spot != null) spot.lat to spot.lon else lastLoc ?: return done(LeaveOutcome(LeaveResult.NO_LOCATION))
            val district = Regions.districtOf(lat, lon)
            placeName = when {
                w == "region" -> district?.let { "${Regions.provinceOfDistrict(it)?.name.orEmpty()} ${it.name}".trim() } ?: return done(LeaveOutcome(LeaveResult.NO_LOCATION))
                spot != null -> spot.memo.ifBlank { spot.address.ifBlank { district?.name ?: "저장한 곳" } }
                else -> (if (car.demoLocation != null) "" else car.address.orEmpty()).ifBlank { district?.name ?: "지금 있는 곳" }
            }.take(40)
            json.put("placeName", placeName).put("lat", lat).put("lon", lon).put("radiusM", 300)
            if (w == "region") district?.let { json.put("regionCode", it.code) }
        }

        Api.postMessage(c.id, json, store.keyOf(c.id) ?: Api.key) { err ->
            if (err != null) return@postMessage done(LeaveOutcome(LeaveResult.FAILED, detail = err))
            spot?.let { store.removeSpot(it.id) }
            Log.d("Gleo 메시지 남김: $w ${to.joinToString { it.name }.ifEmpty { "모두" }} $placeName")
            refreshMessages()
            changed()
            done(LeaveOutcome(LeaveResult.SENT, to.map { it.name }, w, placeName, text = body, group = c.name))
        }
    }

    // 기본 내비(NaviHelper)와 함께 쓰기 -------------------------------------------

    /** 기본 내비의 집·회사·즐겨찾기·최근 목적지 */
    val naviPlaces get() = car.naviPlaces
    fun showInNavi(lat: Double, lon: Double, name: String) = car.showInNavi(lat, lon, name)
    fun routeTo(lat: Double, lon: Double, name: String) = car.routeTo(lat, lon, name)

    /** 화면 없이 짧게 말해 준다 (재생 중이면 끼어들지 않는다) */
    fun say(text: String) {
        if (playing != null || text.isBlank()) return
        player.speak(text) {}
    }

    /** 내가 남긴 안부 들어 보기 등 짧게 읽던 것을 멈춘다 (받은 안부 재생 중이면 건드리지 않는다) */
    fun stopSpeaking() { if (playing == null) player.stop() }

    /** 말하고, 다 말한 뒤(또는 못 했으면 바로) then. 「녹음해 줘」에서 안내 → 삐 → 녹음 순서를 지키려고 쓴다 */
    fun speakThen(text: String, then: () -> Unit) {
        if (playing != null) player.stop()
        player.speak(text) { then() }
    }

    /** 다시 듣는 중인 안부인지 (「들었어요」를 또 보내지 않는다) */
    fun isReplay(m: PlaceMessage) = m.id in replayIds

    /** 지도·목록에서 고른 메시지 하나를 다시 듣는다 (「들었어요」는 또 보내지 않는다) */
    fun replay(m: PlaceMessage) {
        if (parked != true) return
        playing?.let { player.stop(); if (it.id !in replayIds) pending.add(0, it); playing = null }
        replayIds += m.id
        pending.remove(m)
        pending.add(0, m)
        startPlayback(announce = false)
    }

    private fun dropReplays() {
        pending.removeAll { it.id in replayIds }
        replayIds.clear()
    }

    /**
     * 지금 재생이 화면 없이 소리로만인지: 저절로 시작됐고 「화면 띄우기」가 꺼져 있을 때.
     * 사람이 직접 누른 재생(지금 듣기·모아 듣기)은 재생 화면을 연다.
     */
    var quietPlay = false
        private set

    /** 소리로만 듣던 것을 재생 화면으로 */
    fun showPlayback() {
        if (playing == null) return
        quietPlay = false
        ui?.onPlayback()
        changed()
    }

    fun startPlayback(announce: Boolean, auto: Boolean = false) {
        if (pending.isEmpty() || !canPlayNow()) return
        quietPlay = auto && !store.popUpScreen
        askingPassenger = false
        if (passengerNow) passengerOk = true // 직접 들으려고 했다
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
        playState = (if (m.kind == "voice") "${m.authorName}님의 목소리" else "${m.authorName}님의 글 (기계음)") + if (m.id in replayIds) " · 다시 듣기" else ""
        Notifier.playing(m, playState)
        changed()
        player.play(m) { ok ->
            if (playing?.id != m.id) return@play
            playState = if (ok) "다 들었어요" else if (m.kind == "voice") "재생하지 못했어요" else "기계음을 쓸 수 없어 글로 보여 드려요"
            Notifier.playing(m, playState)
            changed()
            // 다시 듣기는 이미 알린 것이라 잠시 뒤 다음으로 넘어간다
            // 기계음을 못 써 글로 보여 줄 때는 읽을 시간을 줘야 하니 넘어가지 않는다 (「다음 안부·나중에」를 누를 때까지)
            // 모아 듣기 중이면 다음으로 넘어간다. 하나만 다시 들었으면 화면을 닫지 않고 「처음부터」로 다시 들을 수 있게 둔다.
            if (m.id in replayIds) {
                if (ok && pending.any { it.id in replayIds }) main.postDelayed({ if (playing?.id == m.id) next(skip = false) }, REPLAY_GAP_MS)
                return@play
            }
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
        if (m.id in replayIds) { next(skip = false); return }
        val driver = ownerOf(m) ?: return
        Api.sendHeard(m.id, driver, keyOf(m)) { loadFeed() }
        // 지도의 「발견한 곳」: 들은 자리를 이 차 안에만 남긴다
        lastLoc?.let { store.addFound(m.id, it.first, it.second) }
        store.addHeard(TripStore.HeardItem(m.id, m.authorName, m.placeName, m.isPlace))
        uploadDiary()
        next(skip = false)
    }

    fun report() {
        val m = playing ?: return
        Api.report(m.id, ownerOf(m) ?: "unknown", keyOf(m)) { ok -> if (ok) Log.d("신고됨 ${m.id}") }
        messages = messages.filter { it.id != m.id }
        next(skip = false)
    }

    /** 다음 메시지로. skip=true면 지금 것은 나중에 다시 들을 수 있게 둔다. */
    fun next(skip: Boolean = true) {
        stopAutoHeard()
        player.stop()
        playing?.let { if (skip && it.id !in replayIds) skipped += it }
        playing = null
        if (pending.isNotEmpty() && canPlayNow()) startPlayback(announce = false, auto = quietPlay) else endPlayback()
    }

    /** 지금 것과 남은 것 모두 나중에 */
    fun later() {
        stopAutoHeard()
        player.stop()
        playing?.let { if (it.id !in replayIds) pending.add(0, it) }
        playing = null
        endPlayback()
    }

    private fun endPlayback() {
        quietPlay = false
        pending.addAll(skipped); skipped.clear()
        dropReplays()
        Notifier.clearPlaying()
        ui?.onPlaybackEnd()
        changed()
    }

    /** 서버는 열쇠로 모임을 정하므로 주소에는 "me"를 쓴다 */
    private const val CIRCLE = "me"
    private const val REFRESH_MS = 30_000L
    private const val RETRY_MS = 5_000L
    private const val AUTO_HEARD_SEC = 5
    private const val AUTO_HEARD_TEXT_SEC = 10
    private const val REPLAY_GAP_MS = 2_000L
    private const val SPOT_MERGE_MS = 60_000L
    private const val DEMO_HOME = "demo-home"
    private const val DEMO_FRIENDS = "demo-friends"
    private const val DEMO_COUPLE = "demo-couple"
    private const val DEMO_OWNER = "wonwoo"
    private const val DEMO_FRIEND_OWNER = "f_wonwoo"
    private const val DEMO_COUPLE_OWNER = "c_wonwoo"
}
