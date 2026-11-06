package ai.gilwi.anbu

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 길 위의 안부 차량 앱 화면. 정차 중 설정·입력과 재생 화면만 맡는다.
 * - 기어·위치·근처 계산·재생은 Engine(AnbuService)이 뒤에서 한다. 운전 중엔 내비가 앞에 있어도 된다.
 * - 운전 중에는 정보만 보여 주고 조작 화면은 열지 않는다.
 */
class MainActivity : Activity(), Engine.Ui {

    /** HOME은 메인 지도. TODAY는 예전 홈(모임 얼굴 줄·모아 듣기·기다리는 메시지·소식 요약) */
    private enum class Screen { HOME, TODAY, PLAY, SETTINGS, MEMBERS, LEAVE, FEED }

    /** 메시지 남기기 화면에서 고르는 값 */
    private data class Place(val name: String, val lat: Double, val lon: Double)
    private var leaveAuthor: String? = null
    private var leavePlace: Place? = null
    /** 어느 장소 버튼을 골랐는지 (이름을 바꿔도 유지) */
    private var leaveKey: String? = null
    private var leaveRadius = 500
    /** 언제 열릴지: place 장소 · rain 비 · night 밤길 · together 옆자리 · door 차에 탈 때 */
    private var leaveWhen = "place"
    /** 받는 사람 (비면 모임 모두) */
    private var leaveTo: List<String> = emptyList()
    /** 답장이면 원래 메시지 */
    private var leaveReplyTo: PlaceMessage? = null
    /** 시간대 (WHEN_HOURS의 키) · 요일 (WHEN_DAYS) · 언제부터 (WHEN_FROM) · 횟수 (once·always·weekly) */
    private var leaveHours = "any"
    private var leaveDays = "any"
    private var leaveFrom = "now"
    private var leaveRepeat = "once"
    private var leaveText = ""
    /** 남기기 단계 (leaveSteps의 몇 번째인지) */
    private var leaveStep = 0
    private var sending = false
    private lateinit var recorder: Recorder
    private lateinit var dictation: Dictation
    private var preview: android.media.MediaPlayer? = null

    private lateinit var root: LinearLayout
    /** 화면 전체를 감싸는 스크롤. 메인 지도에서는 잠가서 화면 높이에 딱 맞춘다. */
    private lateinit var page: PageScroll
    /** 왼쪽 메뉴 줄. 정차 중에만 보인다. */
    private lateinit var rail: LinearLayout
    private var screen = Screen.HOME
    private var playStateView: TextView? = null
    private var playKey: String? = null
    /** 뒤로 가기용 화면 기록 */
    private val history = ArrayDeque<Screen>()
    /** 정차 때 Engine이 앞으로 띄운 화면이면, 재생이 끝나거나 출발하면 내비로 돌아간다 */
    private var autoOpened = false
    /** 열려 있는 입력·확인 창. 출발하면 닫는다. */
    private var dialog: AlertDialog? = null
    /** 설정에 보여 줄 초대 코드 (설정을 열 때 받는다) */
    private var inviteCode: String? = null
    /** 발견 지도. 지도 화면에서만 있다 (위치가 바뀔 때마다 다시 만들면 확대·이동이 풀린다) */
    /** 남기기 「지도에서 고르기」 지도. 남기기 화면에 있는 동안 같은 지도를 쓴다 */
    private var pickMapView: org.maplibre.android.maps.MapView? = null
    private var pickLib: org.maplibre.android.maps.MapLibreMap? = null
    /** 이번에 그린 화면에 고르기 지도가 들어 있다 */
    private var pickShown = false
    /** 남기기 「지도에서 고르기」 지도가 다시 그려져도 보던 자리를 지킨다 */
    /** 고르기 지도를 처음 열 때 볼 자리 (지도에서 길게 눌러 왔을 때) */
    private var leaveMapCenter: org.maplibre.android.geometry.LatLng? = null
    /** 메인 지도. 화면을 다시 그려도 같은 지도를 쓴다 (손가락으로 확대·이동한 자리가 풀리지 않게) */
    private var mainMap: org.maplibre.android.maps.MapView? = null
    private var mainLib: org.maplibre.android.maps.MapLibreMap? = null
    private var mainStyle: org.maplibre.android.maps.Style? = null
    /** 메인 지도 오른쪽 칸 (기다리는 메시지 · 고른 지역 · 상황별 안부 · 보기) */
    private var mainSide: LinearLayout? = null
    /** 지도 단계: 0 시·도 · 1 시·군·구 · 2 실제 자리 */
    private var mapLevel = -1
    private var mapLevelView: TextView? = null
    /** 지역 말풍선을 눌러 고른 지역. 오른쪽 칸 위에 그 지역 안부를 보여 준다. */
    private var selRegion: Regions.Region? = null
    /** 지도·목록에서 고른 안부 하나 */
    private var selMessage: PlaceMessage? = null
    /** 상황별 안부에서 펼친 조건 */
    private var openCondition: String? = null
    /** 지도 그림을 다시 그려야 하는지 가리는 값 (데이터가 그대로면 다시 그리지 않는다) */
    private var mapSig = ""
    /** 메시지 id → (시·도, 시·군·구). 경계 안에 있는지 계산은 한 번만 */
    private val regionCache = HashMap<String, Pair<Regions.Region?, Regions.Region?>>()
    /** 주행 중 저장한 곳에서 시작한 남기기 (남기면 그 자리를 목록에서 지운다) */
    private var leaveSpotId: String? = null
    /** 남기기에서 고른 모임 (여러 모임에 들어가 있을 때). null이면 지금 관리하는 모임 */
    private var leaveCircleId: String? = null
    /** 모임 관리 화면의 차 목록 (화면을 열 때 받는다). null이면 받는 중 */
    private var cars: List<Api.Car>? = null

    // 상태는 모두 Engine(백그라운드 서비스)에 있다
    private val store get() = Engine.store
    private val circle get() = Engine.circle
    private val messages get() = Engine.messages
    private val serverOk get() = Engine.serverOk
    private val parked get() = Engine.parked
    private val lastLoc get() = Engine.lastLoc
    private val pending get() = Engine.pending
    private val playing get() = Engine.playing
    private val playState get() = Engine.playState
    private val feedHeard get() = Engine.feedHeard
    private val feedDiaries get() = Engine.feedDiaries

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        org.maplibre.android.MapLibre.getInstance(this)
        recorder = Recorder(this)
        dictation = Dictation(this)
        Notifier.init(this)
        Engine.start(this)
        AnbuService.start(this)
        Engine.ui = this
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTI)
        }

        val systemNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        applyTheme(when (store.theme) { "day" -> false; "night" -> true; else -> systemNight })
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(40), dp(28), dp(40), dp(28)) }
        rail =LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CARD)
            setPadding(dp(12), dp(28), dp(12), dp(28))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BG)
            addView(rail, LinearLayout.LayoutParams(dp(150), -1))
            // 내용이 짧으면 화면 높이를 꽉 채운다 (홈은 카드가 남은 높이를 나눠 갖는다)
            addView(PageScroll(this@MainActivity).apply { isFillViewport = true; addView(root, android.widget.FrameLayout.LayoutParams(-1, -1)) }.also { page = it },
                LinearLayout.LayoutParams(0, -1, 1f))
        })

        handleIntent(intent)
        // 화면 밝기를 바꾸거나 차가 주간·야간을 바꾸면 화면이 다시 만들어진다. 보던 화면으로 돌아온다.
        savedInstanceState?.getString(KEY_SCREEN)?.let { s -> runCatching { Screen.valueOf(s) }.getOrNull() }
            ?.takeIf { it != Screen.PLAY && parked == true }?.let { screen = it; if (it != Screen.HOME) history.addLast(Screen.HOME) }
        if (playing != null) screen = Screen.PLAY
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SCREEN, screen.name)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        if (playing != null && screen != Screen.PLAY) onPlayback() else render()
    }

    // Pleos는 여러 앱을 한 화면에 나눠 띄워서, 보이는데도 포커스가 없으면 pause 상태다.
    // 그래서 "보이는지"는 onStart/onStop으로 판단한다.
    override fun onStart() {
        super.onStart()
        Engine.uiVisible = true
        AnbuService.enableMic(this)
        maps().forEach { it.onStart() }
        if (::root.isInitialized) takeFocusForPlayback()
    }

    override fun onResume() {
        super.onResume()
        render()
        maps().forEach { it.onResume() }
    }

    /** 살아 있는 지도들 (생명주기를 같이 넘긴다) */
    private fun maps() = listOfNotNull(mainMap, pickMapView)

    override fun onPause() {
        maps().forEach { it.onPause() }
        super.onPause()
    }

    override fun onStop() {
        maps().forEach { it.onStop() }
        Engine.uiVisible = false
        // 재생 중에 화면이 가려지면 알림으로 「들었어요·나중에」를 누를 수 있게
        playing?.let { Notifier.playing(it, playState) }
        super.onStop()
    }

    override fun onDestroy() {
        if (Engine.ui === this) Engine.ui = null
        maps().forEach { it.onStop(); it.onDestroy() }
        mainMap = null; mainLib = null; mainStyle = null; pickMapView = null; pickLib = null
        stopPreview()
        recorder.clear()
        dictation.cancel()
        super.onDestroy()
    }

    /**
     * 정차 때 Engine이 띄운 경우(EXTRA_AUTO) 표시를 남긴다.
     * 시연용: adb shell am start -n ai.gilwi.anbu/.MainActivity --ef lat 37.49 --ef lon 127.48
     */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.getBooleanExtra(EXTRA_AUTO, false)) autoOpened = true
        if (intent.getBooleanExtra("clearDemo", false)) Engine.setDemoLocation(null)
        intent.getStringExtra("driver")?.let {
            Engine.setDriver(it)
        }
        // 시연용: adb shell am start -n ai.gilwi.anbu/.MainActivity --es invite demo-home --es member hyeonggyu
        // 이미 다른 모임에 있으면 --ez add true 일 때만 하나 더 들어간다
        intent.getStringExtra("invite")?.takeIf { Engine.needPairing || intent.getBooleanExtra("add", false) }?.let { code ->
            val member = intent.getStringExtra("member")
            Engine.joinCircle(code) { err ->
                if (err != null) toast(err) else if (member != null) Engine.setOwner(member) { render() }
                render()
            }
        }
        if (intent.hasExtra("lat") && intent.hasExtra("lon")) {
            Engine.setDemoLocation(intent.getFloatExtra("lat", 0f).toDouble() to intent.getFloatExtra("lon", 0f).toDouble())
        }
    }

    /** 자동으로 앞에 뜬 화면이면 내비로 돌려보낸다 */
    private fun returnToNavi() {
        if (!autoOpened) return
        autoOpened = false
        openNavi()
    }

    /** 기본 내비 화면을 앞으로. 이 화면이 보이는 동안에만 다른 앱을 열 수 있다. */
    private fun openNavi() {
        Log.d("내비 화면으로")
        // Pleos 화면 나눔에서는 뒤로 물러나도 내비가 다시 앞에 안 올 수 있어 내비를 직접 연다
        val navi = packageManager.getLaunchIntentForPackage(NAVI_PACKAGE)
        if (navi != null) runCatching { startActivity(navi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { moveTaskToBack(true) }
        else moveTaskToBack(true)
    }

    // Engine에서 오는 신호 ------------------------------------------------------------

    override fun changed() {
        // 재생 화면의 카운트다운은 1초마다 바뀐다. 매번 화면을 새로 만들면 누르는 사이에 버튼이 바뀌어 눌림이 사라지므로,
        // 버튼 구성이 그대로면 상태 한 줄만 고친다.
        if (screen == Screen.PLAY && playKey == playKey()) {
            playStateView?.text = playState
            return
        }
        // 지도는 위치가 바뀔 때마다 새로 그리지 않는다 (보던 자리가 풀린다)
        if (screen == Screen.LEAVE && pickShown && parked == true) return
        // 메인 지도는 지도를 그대로 두고 그림과 오른쪽 칸만 고친다
        if (screen == Screen.HOME && mainSide != null && parked == true && !Engine.needPairing) { refreshMain(); return }
        render()
    }

    /** 재생 화면의 버튼 구성이 달라지는지 가리는 값 */
    private fun playKey() = "${playing?.id}|${Engine.autoHeardLeft > 0}|${pending.isEmpty()}|${Engine.ttsReady}"

    /** 차 주인이 없으면 홈으로. 홈의 얼굴 줄에서 고른다. */
    override fun onNeedDriver() {
        Log.d("운전자 필요 screen=$screen")
        if (screen == Screen.HOME) render()
    }

    override fun onDriving(toNavi: Boolean) {
        dialog?.dismiss(); dialog = null
        // 운전 중에는 조작 화면을 닫고, 자동으로 뜬 화면이면 내비로 돌아간다
        if (screen != Screen.HOME) {
            recorder.stop(); stopPreview(); dictation.cancel()
            history.clear()
            screen = Screen.HOME
        }
        render()
        // D로 출발하면 언제 열었든 기본 내비 화면으로 바꾼다 (R은 후방 카메라가 있으니 그대로)
        autoOpened = false
        if (toNavi && Engine.uiVisible) openNavi()
    }

    override fun onPlayback() {
        if (parked != true) { render(); return } // 달리는 중에는 화면 없이 소리만
        if (screen != Screen.PLAY) { history.addLast(screen); screen = Screen.PLAY }
        render()
        takeFocusForPlayback()
    }

    /**
     * Pleos 화면 나눔에서는 정차 직후 포커스가 주행 화면에 남아 있어, 첫 번째 터치는 포커스만 옮기고 버튼은 눌리지 않는다.
     * 재생 화면이 보이는데 포커스가 없으면 스스로 앞으로 와서 포커스를 가져온다(보이는 동안에만 허용된다).
     * 정차 직후엔 Pleos가 화면을 늦게 보여 주므로 onStart에서도 부른다.
     */
    private fun takeFocusForPlayback() {
        root.postDelayed({
            if (screen == Screen.PLAY && Engine.uiVisible && !hasWindowFocus()) {
                Log.d("재생 화면에 포커스 가져오기")
                runCatching { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
            }
        }, 500)
    }

    override fun onPlaybackEnd() {
        if (screen == Screen.PLAY) { history.clear(); screen = Screen.HOME }
        render()
        returnToNavi()
    }

    // 화면 ------------------------------------------------------------

    private fun render() {
        if (!::root.isInitialized) return
        // 설정·입력 화면에 있는 동안은 자동 재생을 미룬다
        Engine.uiBusy = screen != Screen.HOME && screen != Screen.PLAY
        playKey = null // renderPlay가 다시 정한다
        pickShown = false
        if (screen != Screen.LEAVE) dropPickMap()
        mainSide = null; mapLevelView = null
        if (playing != null) stopPreview()
        root.removeAllViews()
        // 메인 지도는 아래로 스크롤하지 않는다 (지도·오른쪽 칸이 화면 높이를 나눠 갖고, 오른쪽 칸만 따로 스크롤)
        page.locked = (screen == Screen.HOME || screen == Screen.PLAY) && parked == true && !Engine.needPairing
        if (page.locked) page.scrollTo(0, 0)
        if (Engine.needPairing) { rail.visibility = View.GONE; renderPair(); return }
        val railShown = renderRail()
        // 홈이 아닌 화면에는 늘 맨 위에 뒤로 가기를 둔다 (메뉴 줄이 있어도). 입력 중인 화면엔 취소도.
        if (screen != Screen.HOME) root.addView(navBar(withHome = !railShown))
        when (screen) {
            Screen.HOME -> renderHome()
            Screen.TODAY -> renderToday()
            Screen.PLAY -> renderPlay()
            Screen.SETTINGS -> renderSettings()
            Screen.MEMBERS -> renderMembers()
            Screen.LEAVE -> renderLeave()
            Screen.FEED -> renderFeed()
        }
    }

    // 이동 ------------------------------------------------------------

    private fun go(next: Screen) {
        Log.d("화면 $screen → $next")
        if (next == screen) return
        history.addLast(screen)
        screen = next
        render()
    }

    /** 재생 중이던 메시지는 다시 대기열 앞으로 돌려놓는다. */
    private fun leaveCurrent() {
        if (screen == Screen.LEAVE) { recorder.stop(); stopPreview(); dictation.cancel() }
        if (screen == Screen.PLAY && playing != null) {
            screen = Screen.HOME // onPlaybackEnd가 화면을 다시 바꾸지 않게
            Engine.later()
        }
    }

    private fun back() {
        leaveCurrent()
        var prev = history.removeLastOrNull() ?: Screen.HOME
        if (prev == Screen.PLAY) prev = history.removeLastOrNull() ?: Screen.HOME
        screen = prev
        render()
        if (screen == Screen.HOME) Engine.maybeAutoPlay() // 설정 중에 미뤄 둔 재생
    }

    private fun home() {
        leaveCurrent()
        history.clear()
        screen = Screen.HOME
        render()
        Engine.maybeAutoPlay()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (screen == Screen.HOME) super.onBackPressed() else back()
    }

    private fun navBar(withHome: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        // 남기기 중에 「뒤로」는 이전 단계로, 첫 단계면 나가기
        addView(navButton("← 뒤로") { if (screen == Screen.LEAVE && leaveStep > 0) { leaveStep--; render() } else back() })
        if (withHome) addView(navButton("⌂ 홈") { home() }, LinearLayout.LayoutParams(dp(220), dp(72)).apply { marginStart = dp(14) })
        if (screen == Screen.LEAVE) {
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(navButton("✕ 취소") { cancelLeave() })
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
    }

    /** 남기기 취소: 쓰던 내용·녹음을 버리고 홈으로 */
    private fun cancelLeave() {
        val dirty = leaveText.isNotBlank() || recorder.hasRecording || recorder.recording
        val discard = {
            recorder.stop(); recorder.clear(); stopPreview(); dictation.cancel()
            leaveText = ""; leaveStep = 0
            home()
        }
        if (dirty) confirm("남기던 내용을 버릴까요?", "쓰거나 녹음한 내용이 지워져요.", "버리기") { discard() } else discard()
    }

    /** 왼쪽 메뉴 줄을 그린다. 정차 중이고 재생·운전자 고르기 화면이 아닐 때만 보인다. 보이면 true. */
    private fun renderRail(): Boolean {
        rail.removeAllViews()
        val show = parked == true && screen != Screen.PLAY
        rail.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return false
        // 모임 관리는 설정 안에 있다
        val current = if (screen == Screen.MEMBERS) Screen.SETTINGS else screen
        RAIL.forEach { (s, icon, name) ->
            if (s == Screen.SETTINGS) rail.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f)) // 설정은 맨 아래
            rail.addView(railItem(icon, name, s == current) { goTab(s) })
        }
        return true
    }

    private fun railItem(res: Int, name: String, selected: Boolean, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        if (selected) background = rounded(SOFT)
        setPadding(0, dp(18), 0, dp(14))
        val color = if (selected) ACCENT else MUTED
        addView(icon(res, 40, color), LinearLayout.LayoutParams(dp(40), dp(40)).apply { bottomMargin = dp(6) })
        addView(text(name, 20f, color, bold = selected).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, 0) })
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
    }

    /** 메뉴 줄로 옮기기. 뒤로 가면 홈으로 온다. */
    private fun goTab(next: Screen) {
        if (next == screen) return
        Log.d("메뉴 $screen → $next")
        leaveCurrent()
        history.clear()
        if (next != Screen.HOME) history.addLast(Screen.HOME)
        when (next) {
            Screen.LEAVE -> prepareLeave()
            Screen.FEED, Screen.HOME -> loadFeed()
            else -> {}
        }
        screen = next
        render()
        if (next == Screen.HOME) Engine.maybeAutoPlay()
    }

    private fun navButton(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s; textSize = 22f; setTextColor(INK); isAllCaps = false; typeface = font(600)
        background = pressable(rounded(SOFT)); stateListAnimator = null; elevation = 0f
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(220), dp(72))
    }

    /**
     * 오늘. 정차 중에는 화면 높이를 꽉 채운다.
     *   왼쪽: 모임 얼굴 줄 → 오늘 온 메시지 모아 듣기 → 기다리는 메시지
     *   오른쪽: 발견 지도 요약 → 모임 소식(남은 높이)
     * 운전 중에는 만질 것 없이 지금 상태 한 문장만 크게.
     */
    private fun renderToday() {
        // 기어를 모르거나 P가 아니면 조작을 막는다
        if (parked != true) { root.addView(statusBar()); root.addView(space(12)); renderDrivingHome(); return }

        root.addView(greeting())
        root.addView(space(16))
        val left = column().apply {
            // 남는 높이는 가족 얼굴 칸이 갖는다
            addView(familyCard(), LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(16) })
            addView(inboxCard(), panelParams())
            addView(waitingPanel(), LinearLayout.LayoutParams(-1, -2))
        }
        val right = column().apply {
            addView(mapCard(), panelParams())
            addView(feedCard(), LinearLayout.LayoutParams(-1, 0, 1f))
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, -1, 3f))
            addView(right, LinearLayout.LayoutParams(0, -1, 2f).apply { marginStart = dp(16) })
        }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun panelParams() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) }

    /** 홈 카드 바탕 */
    private fun panel(orientation: Int = LinearLayout.VERTICAL) = LinearLayout(this).apply {
        this.orientation = orientation
        background = pressable(rounded(CARD, 1, BORDER))
        setPadding(dp(28), dp(22), dp(28), dp(22))
    }

    /** 맨 위: 날짜 · 인사(차 주인 기준) · 오른쪽에 차 상태 칩 */
    private fun greeting() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
        // 가족이면 호칭으로("아버지"), 친구·연인 모임의 호칭(친구·자기)은 인사로 어색해서 이름으로
        val driver = circle?.member(store.driverId)?.let { if (it.title.isNotBlank() && circle?.type == "family") it.title else "${it.name}님" }
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val hello = when (hour) { in 5..10 -> "좋은 아침이에요"; in 11..16 -> "좋은 오후예요"; in 17..21 -> "좋은 저녁이에요"; else -> "오늘도 수고 많았어요" }
        addView(column().apply {
            addView(text(todayLabel(), 22f, MUTED))
            addView(text(if (driver != null) "$driver, $hello" else "이 차는 누구 차인가요? 아래에서 얼굴을 눌러 주세요", 36f, INK, bold = true))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val states = listOfNotNull("정차 중", "비".takeIf { Engine.rainNow }, "밤길".takeIf { Engine.nightNow })
        addView(text(states.joinToString(" · ") + if (!serverOk) " · 서버 연결 안 됨" else "", 22f, ACCENT, weight = 600).apply {
            background = GradientDrawable().apply { setColor(CHIP); cornerRadius = dp(40).toFloat() }
            setPadding(dp(22), dp(8), dp(22), dp(8))
        })
    }

    /**
     * 얼굴: 폰에서 올린 사진이 있으면 사진, 없으면 프로필 색 동그라미에 이름 첫 글자.
     * me면 강조색 테두리, dot이 있으면 오른쪽 아래에 작은 점.
     */
    private fun avatar(name: String, sizeDp: Int, me: Boolean = false, dot: Int? = null, member: Member? = circle?.memberByName(name)) = android.widget.FrameLayout(this).apply {
        val tone = MEMBER_COLORS[member?.color] ?: MUTED
        val photo = member?.photoUrl?.let { url -> Photos.get(url) { render() } }
        val face: View = if (photo != null) android.widget.ImageView(context).apply {
            setImageBitmap(circleCrop(photo))
            if (me) background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(3), ACCENT) }
            setPadding(if (me) dp(3) else 0, if (me) dp(3) else 0, if (me) dp(3) else 0, if (me) dp(3) else 0)
        } else text(name.take(1), sizeDp * 0.38f, tone, bold = true).apply {
            gravity = Gravity.CENTER; setPadding(0, 0, 0, 0)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(blend(CARD, tone, 0.16f))
                if (me) setStroke(dp(3), ACCENT)
            }
        }
        addView(face, android.widget.FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp)))
        if (dot != null) addView(View(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dot); setStroke(dp(3), CARD) }
        }, android.widget.FrameLayout.LayoutParams(dp(sizeDp / 3), dp(sizeDp / 3), Gravity.END or Gravity.BOTTOM))
    }

    /**
     * 모임 얼굴 줄. 초록 점: 오늘 운전함(일기가 올라옴), 파란 점: 내 메시지를 들음.
     * 차는 한 사람의 차라서 얼굴로 운전자를 바꾸지 않는다. 주인이 아직 없을 때만 눌러서 정하고, 그 밖엔 소식을 연다.
     */
    private fun familyCard() = panel().apply {
        val c = circle
        val me = store.driverId
        if (me == null) background = rounded(CHIP, 3, ACCENT)
        addView(label(c?.name ?: "우리 모임"))
        if (c == null) { addView(big(if (serverOk) "모임 정보를 불러오는 중이에요" else "서버에 연결되지 않았어요. 이 PC에서 서버(2_start_server.bat)가 켜져 있는지 확인해 주세요")); return@apply }
        val droveToday = feedDiaries.filter { it.date == store.today }.map { it.memberName }.toSet()
        val heardMine = feedHeard.filter { it.authorId == me }.map { it.memberName }.toSet()
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(4))
            c.members.forEach { m ->
                val isMe = m.id == me
                val dot = when {
                    isMe && store.todayKm >= 0.1 || m.name in droveToday -> GREEN
                    !isMe && m.name in heardMine -> BLUE
                    else -> null
                }
                addView(column().apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(avatar(m.name, 104, isMe, dot, m), LinearLayout.LayoutParams(-2, -2)) // 점이 동그라미에 붙도록 폭을 감싼다
                    addView(text(if (isMe) "나" else m.name, 24f, if (isMe) ACCENT else INK, bold = isMe).apply { gravity = Gravity.CENTER })
                    background = pressable(GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.TRANSPARENT) })
                    setPadding(0, dp(8), 0, dp(8))
                    isClickable = true
                    setOnClickListener {
                        if (me == null) confirm("${m.name}님의 차로 할까요?", "이 차에서는 ${m.name}님에게 온 메시지를 들려줘요. 설정에서 바꿀 수 있어요.", "정하기") { chooseOwner(m) }
                        else openFeed()
                    }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }, LinearLayout.LayoutParams(-1, 0, 1f)) // 얼굴 줄이 카드 가운데에 오도록 남은 높이를 차지한다
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            fun legend(color: Int, s: String) {
                addView(View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) } }, LinearLayout.LayoutParams(dp(14), dp(14)))
                addView(text(s, 20f, MUTED).apply { setPadding(dp(8), 0, dp(24), 0) })
            }
            legend(GREEN, "오늘 운전")
            legend(BLUE, "내 메시지 들음")
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            // 주인이 없으면 위 인사에 「얼굴을 눌러 주세요」가 있으니 여기엔 버튼만
            if (me == null) addView(navButton("목록에 내가 없어요") { addMeAndOwn(c) }, LinearLayout.LayoutParams(dp(300), dp(64)))
            else addView(text("${store.carName.ifBlank { "이 차" }} · ${c.nameOf(me)}님의 차", 20f, MUTED))
        })
    }

    /** 초대로 들어왔는데 모임에 내 프로필이 없을 때: 나를 추가하고 이 차 주인으로 */
    private fun addMeAndOwn(c: Circle) = askText("내 이름", "") { name ->
        Api.addMember(c.id, name) { updated, err ->
            val m = updated?.memberByName(name) ?: return@addMember toast(err ?: "추가하지 못했어요")
            Engine.circle = updated
            chooseOwner(m)
        }
    }

    private fun chooseOwner(m: Member) = Engine.setOwner(m.id) { ok ->
        toast(if (ok) "${m.name}님의 차로 정했어요" else "서버에 연결하지 못했어요")
        render()
    }

    /** 설정의 「차 주인」 */
    private fun pickOwner() {
        val c = circle ?: return
        if (parked != true) return
        pickFrom("이 차는 누구 차인가요?", c.members.map { it.name }) { i -> chooseOwner(c.members[i]) }
    }

    /** 옆자리에 누가 있어 보낸 사람을 가리는 중인지 */
    private fun discreet() = Engine.passengerNow && store.askWithPassenger && pending.any { it.opensOn != "together" }

    /** 오늘 온 메시지 모아 듣기: 오늘 들은 것과 기다리는 것을 한 번에 */
    private fun inboxCard() = panel().apply {
        val heardToday = store.heard
        val total = heardToday.size + pending.size
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(icon(R.drawable.ic_headphones, 56, ACCENT), LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(24) })
            addView(column().apply {
                addView(label("오늘 온 메시지"))
                addView(text(if (total == 0) "아직 없어요" else "${total}개", 30f, INK, bold = true))
                addView(label(when {
                    total == 0 -> "메시지 장소를 지나면 여기에 모여요"
                    Engine.passengerNow && store.askWithPassenger -> "옆자리에 누가 있어 보낸 사람은 가려 두었어요"
                    else -> (heardToday.map { it.authorName } + pending.map { it.authorName }).distinct().joinToString(", ") { "${it}님" } + "이 남겼어요"
                }))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val can = total > 0 && store.driverId != null
            addView(primary("▶ 모아 듣기") { if (can) Engine.playToday() }.apply { alpha = if (can) 1f else 0.4f },
                LinearLayout.LayoutParams(dp(300), dp(100)).apply { marginStart = dp(16) })
        })
    }

    /** 기다리는 메시지. 없으면 근처 메시지 한 줄, 있으면 보낸 사람 얼굴과 「지금 듣기」. 동승 중이면 보낸 사람 없이 묻는다. */
    private fun waitingPanel() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(28), dp(20), dp(24), dp(20))
        val m = pending.firstOrNull()
        background = if (m != null) rounded(CARD, 3, ACCENT) else rounded(CARD, 1, BORDER)
        if (m == null) {
            addView(icon(R.drawable.ic_inbox, 48, MUTED), LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(24) })
            addView(column().apply {
                addView(label("기다리는 메시지"))
                addView(text("지금은 없어요", 28f, INK, bold = true))
                nearestShort()?.let { addView(label(it)) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            return@apply
        }
        if (discreet()) {
            // 누가 무엇을 보냈는지는 말하지 않고, 들을지만 묻는다
            addView(icon(R.drawable.ic_message, 64, ACCENT), LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(24) })
            addView(column().apply {
                addView(text("메시지가 도착했어요", 30f, INK, bold = true))
                addView(label(if (Engine.askingPassenger) "지금 들을까요? 다음에 들어도 돼요" else "${pending.size}개 · 원할 때 언제든 들을 수 있어요"))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (Engine.askingPassenger) addView(secondary("다음에") { Engine.passengerLater() }, LinearLayout.LayoutParams(dp(200), dp(100)).apply { marginStart = dp(16) })
            addView(primary("▶ 지금 듣기") { Engine.startPlayback(announce = false) }, LinearLayout.LayoutParams(dp(260), dp(100)).apply { marginStart = dp(16) })
            return@apply
        }
        addView(avatar(m.authorName, 84, member = Engine.circles[m.circleId]?.member(m.authorId) ?: circle?.member(m.authorId)), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(20) })
        addView(column().apply {
            addView(text("기다리는 메시지 ${pending.size}개", 22f, ACCENT, bold = true))
            addView(text("${m.authorName}님이 ${m.occasion} 남겼어요", 30f, INK, bold = true))
            if (pending.size > 1) addView(label("그다음 · " + pending.drop(1).joinToString(", ") { "${it.authorName}님" }))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(if (store.driverId != null) primary("▶ 지금 듣기") { Engine.startPlayback(announce = false) }
            else primary("↑ 차 주인 먼저") {}, LinearLayout.LayoutParams(dp(260), dp(100)).apply { marginStart = dp(16) })
    }

    /** 가장 가까운 메시지 장소 한 줄 (홈용으로 짧게) */
    private fun nearestShort(): String? {
        val loc = lastLoc ?: return null
        // 이미 들은 곳은 빼고 아직 못 찾은 안부 중에서
        val found = foundIds()
        val near = messages.filter { it.isPlace && !isMine(it) && it.id !in found }
            .minByOrNull { distanceM(loc.first, loc.second, it.lat, it.lon) } ?: return null
        val d = distanceM(loc.first, loc.second, near.lat, near.lon)
        return "근처 메시지 · ${near.placeName} ${if (d < 1000) "${d.toInt()}m" else "%.1fkm".format(d / 1000)}"
    }

    /** 발견 지도 요약: 모임 사람들이 남긴 메시지 중 몇 개를 찾았는지. 누르면 지도. */
    private fun mapCard() = panel().apply {
        val (found, total) = discoveryCount()
        addView(label("발견 지도"))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
            addView(icon(R.drawable.ic_map, 48, ACCENT), LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(20) })
            addView(text("$found", 52f, INK, bold = true))
            addView(text(" / ${total}개 찾았어요", 24f, MUTED))
        })
        addView(progressBar(found, total))
        addView(label("내가 남긴 곳 ${myPlaces().size}곳 · 눌러서 지도 보기"))
        setOnClickListener { goTab(Screen.HOME) }
    }

    /** 가로 막대: n / total */
    private fun progressBar(n: Int, total: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = GradientDrawable().apply { setColor(SOFT); cornerRadius = dp(8).toFloat() }
        addView(View(context).apply { background = GradientDrawable().apply { setColor(ACCENT); cornerRadius = dp(8).toFloat() } },
            LinearLayout.LayoutParams(0, -1, n.toFloat()))
        addView(View(context), LinearLayout.LayoutParams(0, -1, if (total == 0) 1f else (total - n).coerceAtLeast(0).toFloat()))
        layoutParams = LinearLayout.LayoutParams(-1, dp(14)).apply { topMargin = dp(8); bottomMargin = dp(8) }
    }

    /** 내가 들은 메시지 (다른 차에서 들은 것까지, 모든 모임에서) */
    private fun foundIds(): Set<String> {
        val owners = store.memberships.mapNotNull { it.ownerId }.toSet()
        return (feedHeard.filter { it.memberId in owners }.map { it.messageId } + store.heard.map { it.messageId }).toSet()
    }
    /** 이 차 주인이 남긴 메시지인지 (그 메시지 모임에서의 주인으로 본다) */
    private fun isMine(m: PlaceMessage) = m.authorId == Engine.ownerOf(m)
    /** (찾은 수, 모임 사람들이 나에게 남긴 수) */
    private fun discoveryCount(): Pair<Int, Int> {
        val others = messages.filter { !isMine(it) && (it.to.isEmpty() || Engine.ownerOf(it) in it.to) }
        val ids = foundIds()
        return others.count { it.id in ids } to others.size
    }
    private fun myPlaces() = messages.filter { it.isPlace && isMine(it) }

    /** 사람별 요약 한 줄 (소식 카드·소식 화면) */
    private data class Person(val member: Member, val todayCount: Int, val line: String, val whenText: String, val key: String, val recent: List<Api.Heard>, val diary: Api.Diary?)

    private fun people(): List<Person> {
        val c = circle ?: return emptyList()
        val me = store.driverId
        return c.members.filter { it.id != me }.map { m ->
            val hs = feedHeard.filter { it.memberId == m.id }.sortedByDescending { it.lastAt }
            val diary = feedDiaries.firstOrNull { it.memberName == m.name && it.date == store.today }
            val last = hs.firstOrNull()
            val todayN = hs.count { localDay(it.lastAt) == store.today }
            val mineToday = hs.firstOrNull { it.authorId == me && localDay(it.lastAt) == store.today }
            val line = when {
                mineToday != null -> "내 ${mineToday.placeLabel} 메시지를 들었어요"
                todayN > 0 -> "오늘 메시지 ${todayN}개를 들었어요"
                last != null -> "${c.nameOf(last.authorId)}님의 ${last.placeLabel} 메시지를 들었어요"
                diary != null -> diary.text.lines().first()
                else -> "아직 소식이 없어요"
            }
            Person(m, todayN, line, last?.let { whenLabel(it.lastAt) } ?: diary?.let { dayLabel(it.date) } ?: "",
                last?.lastAt ?: diary?.let { "${it.date}T23:59" } ?: "", hs.take(3), diary)
        }.sortedByDescending { it.key }
    }

    private fun localDay(iso: String): String? = runCatching {
        java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
    }.getOrNull()

    /** 모임 소식: 로그를 늘어놓지 않고 사람마다 한 줄 */
    private fun feedCard() = panel().apply {
        addView(label("모임 소식"))
        val list = people().filter { it.key.isNotEmpty() }
        if (list.isEmpty()) { addView(space(12)); addView(text("아직 소식이 없어요", 26f, MUTED)); return@apply }
        list.take(5).forEachIndexed { i, p ->
            if (i > 0) addView(View(context).apply { setBackgroundColor(BORDER) }, LinearLayout.LayoutParams(-1, dp(1)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, dp(12))
                addView(avatar(p.member.name, 52, member = p.member), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(16) })
                addView(column().apply {
                    addView(text(p.member.name, 24f, INK, bold = true))
                    addView(text(p.line, 20f, MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(text(p.whenText, 20f, MUTED).apply { setPadding(dp(12), 0, 0, 0) })
            })
        }
        setOnClickListener { openFeed() }
    }

    /** 받침이 있으면 a(이·을…), 없으면 b(가·를…) */
    private fun josa(word: String, a: String, b: String): String {
        val ch = word.lastOrNull() ?: return b
        return if (ch in '가'..'힣' && (ch - '가') % 28 != 0) a else b
    }

    /** "2026-11-03T05:37:45Z" → 방금 · N분 전 · N시간 전 · 어제 · M월 d일 */
    private fun whenLabel(iso: String): String {
        val t = runCatching { java.time.Instant.parse(iso) }.getOrNull() ?: return ""
        val min = java.time.Duration.between(t, java.time.Instant.now()).toMinutes()
        if (min < 1) return "방금"
        if (min < 60) return "${min}분 전"
        val day = t.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        if (day == java.time.LocalDate.now()) return "${min / 60}시간 전"
        return dayLabel(day.toString())
    }

    /** "2026-11-03" → 오늘 · 어제 · M월 d일 */
    private fun dayLabel(date: String): String {
        val d = runCatching { java.time.LocalDate.parse(date) }.getOrNull() ?: return ""
        val today = java.time.LocalDate.now()
        return when (d) { today -> "오늘"; today.minusDays(1) -> "어제"; else -> "${d.monthValue}월 ${d.dayOfMonth}일" }
    }

    /** 맨 위 한 줄: 운전자 칩(누르면 바꾸기) · 차 상태 · 서클 이름 */
    private fun statusBar() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val c = circle
        val driver = store.driverId?.let { c?.nameOf(it) }
        addView(text(driver ?: "차 주인 고르기", 22f, ACCENT, bold = true).apply {
            background = GradientDrawable().apply { setColor(CHIP); cornerRadius = dp(40).toFloat() }
            setPadding(dp(24), dp(8), dp(24), dp(8))
            if (parked == true) setOnClickListener { home() }
        })
        val gear = when (Engine.gear) {
            null -> "차 상태 확인 중"
            CarSignals.Gear.P -> "정차 중"
            CarSignals.Gear.R -> "후진 중"
            CarSignals.Gear.N -> "중립"
            CarSignals.Gear.D -> "운전 중"
        }
        val states = listOfNotNull(gear, "비".takeIf { Engine.rainNow }, "밤길".takeIf { Engine.nightNow }, "옆자리 탑승".takeIf { Engine.passengerNow })
        addView(text(states.joinToString("  ·  "), 22f, MUTED).apply { setPadding(dp(20), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(text(c?.name ?: if (serverOk) "" else "서버 연결 안 됨", 22f, MUTED))
    }

    /** 운전 중 홈: 버튼 없이 지금 상태 한 문장만 */
    private fun renderDrivingHome() {
        val (head, sub) = when (Engine.gear) {
            null -> "차 상태를 확인하는 중이에요" to ""
            CarSignals.Gear.R -> "후진 중이에요" to "주변을 잘 살펴보세요"
            CarSignals.Gear.N -> "중립이에요" to "P에 두면 메뉴가 열려요"
            else -> if (pending.isEmpty()) "조심히 다녀오세요" to "메시지 장소를 지나면 모아 둘게요"
                else "메시지 ${pending.size}개가 기다려요" to "차를 세우면 들려드릴게요"
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(CARD)
            minimumHeight = dp(480)
            setPadding(dp(40), dp(40), dp(40), dp(40))
            addView(text(head, 48f, INK, bold = true).apply { gravity = Gravity.CENTER })
            if (sub.isNotEmpty()) addView(text(sub, 30f, MUTED).apply { gravity = Gravity.CENTER })
            addView(space(24))
            addView(text(nearestText(), 20f, MUTED).apply { gravity = Gravity.CENTER })
        }, LinearLayout.LayoutParams(-1, -2))
    }

    /**
     * 처음 화면: 이 차가 아직 모임에 들어가지 않았을 때 (처음 켠 차, 또는 이 차를 모임에서 뺐을 때).
     * 로그인 없이 「새 모임 만들기」 또는 「초대 코드로 들어가기」. 들어가기 전에는 다른 화면을 열지 않는다.
     */
    private fun renderPair() {
        root.addView(space(24))
        root.addView(text("길 위의 안부를 시작해요", 44f, INK, bold = true))
        root.addView(space(8))
        root.addView(text("가족·친구가 각자 자기 차에서, 서로에게 남긴 목소리를 그 길 위에서 들어요", 26f, MUTED))
        root.addView(space(32))
        val busy = Engine.setupBusy
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(optionTile(R.drawable.ic_people, "새 모임 만들기", "처음이면 여기서 만들고, 초대 코드를 알려 주세요", false) { if (!busy) startCreate() },
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(optionTile(R.drawable.ic_message, "초대 코드로 들어가기", "모임 사람에게 받은 6자리 코드를 넣어요", false) { if (!busy) startJoin() },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(20) })
            alpha = if (busy) 0.4f else 1f
        })
        root.addView(space(24))
        // 서버가 안 닿거나 방금 실패했으면 이유를 계속 보여 준다
        val err = Engine.setupError
        if (!busy && (!serverOk || err != null)) root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(CARD, 2, MEMBER_COLORS["red"] ?: ACCENT)
            setPadding(dp(28), dp(18), dp(18), dp(18))
            addView(column().apply {
                addView(text(if (!serverOk) "서버에 연결되지 않았어요" else err ?: "", 28f, INK, bold = true))
                addView(label(if (!serverOk) "PC에서 서버(2_start_server.bat)가 켜져 있는지 확인해 주세요. 켜지면 저절로 사라져요. (주소 ${BuildConfig.SERVER_URL})"
                    else "다시 시도해 주세요."))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) }
        })
        root.addView(label(if (busy) "들어가는 중이에요… (서버 응답을 최대 10초 기다려요)"
            else "시작하기 전에 개인정보 처리방침을 보여 드리고 동의를 받아요. 이 차의 고유번호는 알아볼 수 없게 바꿔서(해시) 보내고, 위치는 보내지 않아요."))
        root.addView(space(24))
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(navButton("← 나중에 할게요") { openNavi() }, LinearLayout.LayoutParams(dp(320), dp(80)))
            addView(navButton("개인정보 처리방침") { showPrivacy(null) }, LinearLayout.LayoutParams(dp(320), dp(80)).apply { marginStart = dp(16) })
        })
    }

    /**
     * 새 모임: 종류 → 모임 이름 → 내 이름 → 호칭 → 얼굴 색 → 개인정보 동의. 만든 사람이 이 차의 주인이 된다.
     * 폰 웹의 「새 모임 만들기」와 같은 내용을 차 화면에서 다 넣는다.
     */
    private fun startCreate() {
        if (parked != true) return
        pickFrom("어떤 모임인가요?", CIRCLE_TYPES.map { it.second }) { i ->
            val type = CIRCLE_TYPES[i].first
            askText("모임 이름", if (type == "family") "우리집" else "우리 모임", suggestions = CIRCLE_NAME_HINTS.getValue(type)) { circleName ->
                askText("내 이름 (이 차의 주인)", "") { myName ->
                    askText("$myName 님의 호칭 (선택)", "", suggestions = TITLE_HINTS.getValue(type), allowBlank = true, okLabel = "다음") { title ->
                        pickColor("$myName 님의 얼굴 색", "blue") { color ->
                            showPrivacy("동의하고 만들기") {
                                Engine.createCircle(circleName, type, myName, title, color) { err ->
                                    if (err != null) toast(err) else toast("「$circleName」을(를) 만들었어요. 설정의 초대 코드를 알려 주세요")
                                    render()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** 초대 코드로 들어가기. 들어가면 홈에서 「나는 누구」를 고른다. */
    private fun startJoin() = askText("초대 코드 (6자리)", "") { code ->
        showPrivacy("동의하고 들어가기") {
            Engine.joinCircle(code) { err ->
                if (err != null) toast(err) else { toast("「${circle?.name}」에 들어왔어요. 이 차 주인을 골라 주세요"); pickOwner() }
                render()
            }
        }
    }

    /**
     * 개인정보 처리방침을 차 화면에서 보여 준다 (서버의 privacy.html, 폰 웹과 같은 글).
     * okLabel을 주면 동의 버튼이 생기고, 누르면 onAgree.
     */
    private fun showPrivacy(okLabel: String?, onAgree: () -> Unit = {}) {
        if (parked != true) return
        val web = android.webkit.WebView(this).apply {
            webViewClient = object : android.webkit.WebViewClient() {
                override fun onReceivedError(v: android.webkit.WebView, r: android.webkit.WebResourceRequest, e: android.webkit.WebResourceError) {
                    if (r.isForMainFrame) v.loadDataWithBaseURL(null, PRIVACY_FALLBACK, "text/html", "utf-8", null)
                }
            }
            loadUrl(BuildConfig.SERVER_URL + "/privacy.html")
        }
        AlertDialog.Builder(this, dialogTheme())
            .setTitle(if (okLabel != null) "이름·메시지·목소리를 모임 사람들과 나눠요" else "개인정보 처리방침")
            // 대화창은 바로 넣은 뷰의 높이를 무시하고 늘려서 버튼이 밀려난다. 높이를 정한 상자에 넣는다.
            .setView(column().apply { addView(web, LinearLayout.LayoutParams(-1, dp(440))) })
            .apply { if (okLabel != null) setPositiveButton(okLabel) { _, _ -> onAgree() } }
            .setNegativeButton(if (okLabel != null) "취소" else "닫기", null)
            .show().also { dialog = it }
    }

    /**
     * 여럿 중 하나 고르기 창. 기본 목록 창(setItems)은 Pleos 밝은 화면에서 글자가 흰색으로 나와 안 보여서 버튼으로 그린다.
     */
    private fun pickFrom(title: String, items: List<String>, onPick: (Int) -> Unit) {
        if (parked != true) return
        var d: AlertDialog? = null
        val list = column().apply {
            setPadding(dp(24), dp(12), dp(24), dp(4))
            items.forEachIndexed { i, s ->
                addView(button(s, rounded(SOFT), INK) { d?.dismiss(); onPick(i) }, LinearLayout.LayoutParams(-1, dp(88)).apply { bottomMargin = dp(10) })
            }
        }
        d = AlertDialog.Builder(this, dialogTheme()).setTitle(title)
            .setView(ScrollView(this).apply { addView(list) })
            .setNegativeButton("취소", null).show()
        dialog = d
    }

    /** 얼굴 색 고르기: 색 동그라미를 눌러 고른다 */
    private fun pickColor(title: String, current: String, onPick: (String) -> Unit) {
        if (parked != true) return
        var d: AlertDialog? = null
        // 창이 좁아서 네 개씩 두 줄로
        val dots = column().apply {
            setPadding(dp(24), dp(20), dp(24), dp(4))
            MEMBER_COLOR_NAMES.chunked(4).forEach { line -> addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            line.forEach { name ->
                addView(View(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(MEMBER_COLORS[name] ?: MUTED)
                        if (name == current) setStroke(dp(6), INK)
                    }
                    contentDescription = name
                    isClickable = true
                    setOnClickListener { d?.dismiss(); onPick(name) }
                }, LinearLayout.LayoutParams(dp(84), dp(84)).apply { marginStart = dp(12); marginEnd = dp(12) })
            }
            }) }
        }
        d = AlertDialog.Builder(this, dialogTheme()).setTitle(title).setView(dots).setNegativeButton("취소", null).show()
        dialog = d
    }

    /** 사진을 동그랗게 자른다 */
    private fun circleCrop(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val s = minOf(src.width, src.height)
        val out = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.BitmapShader(src, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP).apply {
                setLocalMatrix(android.graphics.Matrix().apply { setTranslate(-(src.width - s) / 2f, -(src.height - s) / 2f) })
            }
        }
        android.graphics.Canvas(out).drawCircle(s / 2f, s / 2f, s / 2f, paint)
        return out
    }

    /** a에 b를 t만큼 섞은 색 (얼굴 바탕을 프로필 색으로 옅게) */
    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt()
        return Color.rgb(ch(16), ch(8), ch(0))
    }

    private fun todayLabel() = java.text.SimpleDateFormat("M월 d일 EEEE", java.util.Locale.KOREA).format(java.util.Date())

    /**
     * 재생 화면. 화면 높이를 두 칸이 나눠 갖는다.
     *   왼쪽 「누가·어디서」: 큰 얼굴 · 이름 · 모임 · 어디에 남긴 안부인지 · (글이면 글) · 들러 가기 · 신고
     *   오른쪽 「듣기」: 큰 재생 버튼과 5초 앞뒤 · 막대 · 「들었어요」 · 처음부터 · 나중에
     */
    private fun renderPlay() {
        val m = playing ?: run { screen = Screen.HOME; renderHome(); return }
        playKey = playKey()
        val c = Engine.circles[m.circleId] ?: circle
        val replay = Engine.isReplay(m)

        // 맨 위 한 줄: 지금 상태 · 오른쪽 꼬리표
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(playState, 22f, MUTED).also { playStateView = it }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(text(when {
                replay -> "다시 듣기 · 이미 「들었어요」를 보냈어요"
                pending.isNotEmpty() -> "다음 안부 ${pending.size}개가 기다려요"
                else -> "오늘 받은 안부"
            }, 20f, ACCENT, weight = 600).apply {
                background = GradientDrawable().apply { setColor(CHIP); cornerRadius = dp(40).toFloat() }
                setPadding(dp(20), dp(8), dp(20), dp(8))
            })
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
        })

        val left = panel().apply {
            background = rounded(CARD, 1, BORDER)
            setPadding(dp(32), dp(30), dp(32), dp(28))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(avatar(m.authorName, 104, member = c?.member(m.authorId)), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(22) })
                addView(column().apply {
                    addView(text(m.authorName, 38f, INK, bold = true))
                    addView(circleTag(m.circleId))
                })
            })
            addView(space(22))
            addView(text(if (m.opensOn == "park" && m.hasPlace) "${m.placeName}에 도착하면 들려주려고 남긴 ${if (m.kind == "voice") "목소리" else "글"}"
                else if (m.isPlace) "${m.placeName}${josa(m.placeName, "을", "를")} 지날 때 들려주려고 남긴 ${if (m.kind == "voice") "목소리" else "글"}"
                else "「${m.placeName}」에 들려주려고 남긴 ${if (m.kind == "voice") "목소리" else "글"}", 28f, INK))
            heardOn(m)?.let { addView(text(it, 22f, MUTED).apply { setPadding(0, dp(6), 0, 0) }) }
            if (m.kind == "voice") {
                // 목소리면 글 대신 「어디서」를 크게: 장소 이름 또는 상황
                addView(space(22))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = rounded(SOFT)
                    setPadding(dp(26), dp(26), dp(26), dp(26))
                    addView(icon(WHEN_ICONS[m.opensOn] ?: R.drawable.ic_pin, 52, circleColor(m.circleId)), LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(20) })
                    addView(column().apply {
                        addView(text(if (m.isPlace) m.placeName else "「${m.placeName}」", 30f, INK, bold = true))
                        addView(text(if (m.isPlace) "이 근처를 지나서 열렸어요" else WHEN_HINTS[m.opensOn] ?: "", 22f, MUTED))
                    })
                }, LinearLayout.LayoutParams(-1, -2))
            }
            if (m.kind == "text") {
                addView(space(18))
                addView(text("“${m.text}”", 30f, INK, weight = 500).apply {
                    background = rounded(SOFT)
                    setPadding(dp(26), dp(22), dp(26), dp(22))
                })
            }
            addView(View(context), LinearLayout.LayoutParams(1, 0, 1f)) // 남는 높이는 여기로, 버튼은 맨 아래
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                if (m.isPlace) addView(secondary("들러 가기") {
                    toast(if (Engine.addStopover(m)) "${m.placeName}을(를) 경유지에 넣었어요" else "경유지를 넣지 못했어요")
                }, LinearLayout.LayoutParams(0, dp(88), 1f).apply { marginEnd = dp(12) })
                addView(secondary("답장") { Engine.later(); startReply(m) }, LinearLayout.LayoutParams(0, dp(88), 1f).apply { marginEnd = dp(12) })
                addView(secondary("신고") { Engine.report() }, LinearLayout.LayoutParams(dp(160), dp(88)))
            })
        }

        val right = panel().apply {
            background = rounded(CARD, 1, BORDER)
            setPadding(dp(32), dp(30), dp(32), dp(28))
            addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
            addView(playControls(m))
            addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
            addView(View(context).apply { setBackgroundColor(BORDER) }, LinearLayout.LayoutParams(-1, dp(1)).apply { bottomMargin = dp(22) })
            addView(primary("들었어요") { Engine.heard() }, LinearLayout.LayoutParams(-1, dp(116)).apply { bottomMargin = dp(14) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(if (Engine.autoHeardLeft > 0) secondary("보내지 않기") { Engine.cancelAutoHeard() }
                    else secondary("처음부터") { Engine.playCurrent() }, LinearLayout.LayoutParams(0, dp(92), 1f).apply { marginEnd = dp(14) })
                addView(secondary(if (pending.isEmpty()) "나중에 듣기" else "다음 안부") {
                    if (pending.isEmpty()) Engine.later() else Engine.next()
                }, LinearLayout.LayoutParams(0, dp(92), 1f))
            })
            addView(text(when {
                replay -> "다시 듣는 중이라 「들었어요」를 또 보내지 않아요."
                store.autoHeard -> "끝까지 들으면 누르지 않아도 ${m.authorName}님께 「들었어요」를 알려요."
                else -> "「들었어요」를 누르면 ${m.authorName}님께 알려요."
            }, 20f, MUTED).apply { setPadding(0, dp(14), 0, 0) })
        }

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(16) })
            addView(right, LinearLayout.LayoutParams(0, -1, 1.35f))
        }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    /** "11월 3일 처음 들었어요" (이 차 주인이 들은 기록이 있으면) */
    private fun heardOn(m: PlaceMessage): String? {
        val owner = Engine.ownerOf(m) ?: return null
        val at = feedHeard.firstOrNull { it.messageId == m.id && it.memberId == owner }?.at ?: return null
        return runCatching {
            val d = java.time.Instant.parse(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            "${d.monthValue}월 ${d.dayOfMonth}일 처음 들었어요"
        }.getOrNull()
    }

    /** 동그란 버튼 (재생기) */
    private fun roundButton(s: String, sizeDp: Int, filled: Boolean, onClick: () -> Unit) = text(s, if (filled) 44f else 26f, if (filled) Color.WHITE else INK, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, 0)
        background = pressable(GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (filled) setColor(ACCENT) else { setColor(CARD); setStroke(dp(2), BORDER) }
        })
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** 재생기: 목소리는 5초 전 · 재생/멈춤 · 5초 뒤 + 막대, 글은 기계음으로 듣기 (못 쓰면 안내만) */
    private fun playControls(m: PlaceMessage) = column().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        fun labeled(v: View, label: String, keep: Boolean = false) = column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(v)
            addView(text(label, 20f, MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) }.also { if (keep) playToggleLabel = it })
        }
        run {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                if (m.kind == "voice") addView(labeled(roundButton("−5", 104, false) { Engine.seekPlay(Engine.playPosition - PREVIEW_JUMP_MS) }, "5초 전"),
                    LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(40) })
                addView(labeled(roundButton("", 160, true) {
                    if (m.kind == "voice") Engine.togglePause()
                    else if (Engine.playSeekable) Engine.togglePause() else Engine.playCurrent()
                    updatePlayBar()
                }.also { playToggle = it }, "", keep = true))
                if (m.kind == "voice") addView(labeled(roundButton("+5", 104, false) { Engine.seekPlay(Engine.playPosition + PREVIEW_JUMP_MS) }, "5초 뒤"),
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(40) })
            })
            addView(android.widget.SeekBar(context).apply {
                progressTintList = android.content.res.ColorStateList.valueOf(ACCENT)
                thumbTintList = android.content.res.ColorStateList.valueOf(if (m.kind == "voice") ACCENT else Color.TRANSPARENT)
                minimumHeight = dp(56)
                setPadding(dp(24), dp(16), dp(24), dp(4))
                isEnabled = m.kind == "voice"
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: android.widget.SeekBar, v: Int, fromUser: Boolean) {
                        if (fromUser) playTime?.text = "${mmss(v)} / ${mmss(sb.max)}"
                    }
                    override fun onStartTrackingTouch(sb: android.widget.SeekBar) { playDragging = true }
                    override fun onStopTrackingTouch(sb: android.widget.SeekBar) { playDragging = false; Engine.seekPlay(sb.progress); updatePlayBar() }
                })
            }.also { playSeek = it }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(26) })
            addView(text("", 22f, MUTED).apply { gravity = Gravity.CENTER }.also { playTime = it }, LinearLayout.LayoutParams(-1, -2))
            if (m.kind != "voice") addView(text(if (Engine.ttsReady) "기계음으로 읽어요 · 길이는 어림이에요" else "이 차에 음성 엔진이 없어 개발 서버 음성으로 읽어요",
                20f, MUTED).apply { gravity = Gravity.CENTER })
        }
    }.also {
        root.removeCallbacks(playTick)
        root.post(playTick)
    }

    // 재생 막대: 목소리는 멈춤·5초 앞뒤·막대로 옮기기, 기계음은 진행만 보인다 ------------------

    private var playSeek: android.widget.SeekBar? = null
    private var playTime: TextView? = null
    private var playToggle: TextView? = null
    private var playToggleLabel: TextView? = null
    private var playDragging = false
    private val playTick = object : Runnable {
        override fun run() {
            if (screen != Screen.PLAY || playing == null) return
            updatePlayBar()
            root.postDelayed(this, 250)
        }
    }

    private fun updatePlayBar() {
        val dur = Engine.playDuration
        val pos = Engine.playPosition
        val (sym, label) = when {
            playing?.kind != "voice" && !Engine.playSeekable -> if (Engine.playPlayingNow) "…" to "읽는 중" else "▶" to "기계음으로 듣기"
            Engine.playPlayingNow -> "❚❚" to "일시정지"
            dur > 0 && pos >= dur - 300 -> "↺" to "처음부터"
            dur > 0 -> "▶" to "계속 듣기"
            playing?.kind != "voice" -> "▶" to "기계음으로 듣기"
            else -> "…" to "불러오는 중"
        }
        playToggle?.text = sym
        playToggleLabel?.text = label
        if (playing?.kind != "voice" && !Engine.ttsReady && !Engine.playSeekable && Engine.playDuration == 0) {
            // 읽지 못했어도 어림 길이로 막대를 채워 둔다
            val est = ((playing?.text?.length ?: 0) * 140).coerceAtLeast(2000)
            playSeek?.max = est; playSeek?.progress = 0
            playTime?.text = "0:00  /  ${mmss(est)}"
            return
        }
        if (!playDragging) {
            playSeek?.max = dur.coerceAtLeast(1)
            playSeek?.progress = pos
            playTime?.text = if (dur > 0) "${mmss(pos)}  /  ${mmss(dur)}" else ""
        }
    }

    /**
     * 메인: 안부 지도. 멀리 보면 시·도, 조금 가까이 보면 시·군·구로 묶어 「서울 3/25」(찾은 수/전체) 말풍선을 띄우고,
     * 더 가까이 가면 찾은 안부는 그 자리에 얼굴 말풍선, 못 찾은 안부는 「이 근처 어딘가 ?」 흐린 동그라미로 보인다.
     * 오른쪽 칸: 기다리는 메시지 → (고른 안부) → (고른 지역의 안부) → 상황별 안부(비·밤길…) → 보기(모임·사람 고르기).
     * 계산은 모두 차 안에서 한다. 운전 중에는 지도 대신 지금 상태 한 줄만.
     */
    private fun renderHome() {
        if (parked != true) { root.addView(statusBar()); root.addView(space(12)); renderDrivingHome(); return }
        Regions.load(this)
        val mv = mainMap ?: createMainMap().also { mainMap = it }
        (mv.parent as? android.view.ViewGroup)?.removeView(mv)
        val mapBox = android.widget.FrameLayout(this).apply {
            addView(mv, android.widget.FrameLayout.LayoutParams(-1, -1))
            // 왼쪽 위: 전국 보기 · 지금 위치
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(mapChip("전국 보기") { showKorea() })
                addView(mapChip("지금 위치") { showHere() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
            }, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(16), dp(16), 0, 0) })
            // 오른쪽 위: 지금 단계
            addView(text("", 20f, INK, weight = 600).apply {
                background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(30).toFloat(); setStroke(dp(1), BORDER) }
                setPadding(dp(18), dp(8), dp(18), dp(8))
            }.also { mapLevelView = it }, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(16), dp(16), 0) })
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(16).toFloat())
            }
        }
        val side = column().also { mainSide = it }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(column().apply {
                addView(mapBox, LinearLayout.LayoutParams(-1, 0, 1f))
                addView(text("얼굴 말풍선 = 찾은 안부 · ? = 아직 못 찾은 안부(이 근처 어딘가) · 「나」 = 내가 남긴 곳 · 길게 누르면 여기에 메시지 남기기", 18f, MUTED).apply { setPadding(0, dp(8), 0, 0) })
                addView(text("지도 © OpenStreetMap 기여자 · OpenFreeMap · MapLibre · 행정구역 경계 © 통계청(KOSTAT) 2013", 16f, MUTED))
            }, LinearLayout.LayoutParams(0, -1, 3f))
            addView(ScrollView(context).apply { addView(side) }, LinearLayout.LayoutParams(0, -1, 2f).apply { marginStart = dp(16) })
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        mapSig = "" // 처음 그리기
        refreshMain()
    }

    private fun mapChip(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s; textSize = 20f; setTextColor(INK); isAllCaps = false; typeface = font(600)
        background = pressable(rounded(CARD, 1, BORDER)); stateListAnimator = null; elevation = dp(2).toFloat()
        minHeight = dp(64); setPadding(dp(22), 0, dp(22), 0)
        setOnClickListener { onClick() }
    }

    /**
     * 벡터 지도 (MapLibre). 스타일은 assets/map의 단순한 지도: 땅·물·길·시와 동 이름만, 가게·건물·정류장은 그리지 않는다.
     * 지도 자료는 OpenFreeMap(OpenStreetMap)에서 받는다. 화면을 다시 그릴 때 뗐다 붙여도 되게 TextureView로 그린다.
     */
    private fun newVectorMap(): org.maplibre.android.maps.MapView {
        val opts = org.maplibre.android.maps.MapLibreMapOptions.createFromAttributes(this)
            .textureMode(true)
            .pixelRatio(MAP_PIXEL_RATIO) // 이 화면은 1dp = 1px이라 그대로 두면 지도 글자가 작다
            .localIdeographFontFamily("sans-serif") // 한글 지명은 기기 글꼴로
            .attributionEnabled(false).logoEnabled(false).compassEnabled(false)
            .rotateGesturesEnabled(false).tiltGesturesEnabled(false)
        return org.maplibre.android.maps.MapView(this, opts).apply {
            onCreate(null); onStart(); onResume()
            // 바깥 스크롤이 지도 끌기를 가로채지 않게
            setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(16).toFloat())
            }
        }
    }

    private fun mapStyle() = org.maplibre.android.maps.Style.Builder().fromUri(if (NIGHT) "asset://map/simple-night.json" else "asset://map/simple-day.json")

    private fun bounds(n: Double, e: Double, s: Double, w: Double) = org.maplibre.android.geometry.LatLngBounds.from(n, e, s, w)

    /** 메인 지도를 한 번만 만든다. 확대 수준이 단계 경계를 넘으면 그림을 바꿔 그린다. */
    private fun createMainMap(): org.maplibre.android.maps.MapView {
        val mv = newVectorMap()
        mv.getMapAsync { map ->
            mainLib = map
            map.setMinZoomPreference(4.5)
            map.setStyle(mapStyle()) { st ->
                mainStyle = st
                addOverlayLayers(st)
                // 지금 지역으로 시작 (지금 자리가 든 시·도가 꽉 차게). 위치를 모르면 전국.
                mv.post {
                    val here = lastLoc?.let { Regions.provinceOf(it.first, it.second) }
                    map.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngBounds(
                        here?.let { bounds(it.north, it.east, it.south, it.west) } ?: KOREA, dp(40)))
                    drawMainOverlays()
                }
                map.addOnCameraMoveListener { if (levelOf(map.cameraPosition.zoom) != mapLevel) drawMainOverlays() }
                map.addOnCameraIdleListener { if (levelOf(map.cameraPosition.zoom) != mapLevel) drawMainOverlays() }
                map.addOnMapClickListener { at -> onMainTap(map, at); true }
                // 빈 곳을 길게 누르면 그 자리에 메시지 남기기
                map.addOnMapLongClickListener { at ->
                    confirm("여기에 메시지를 남길까요?", "고른 자리를 모임의 다른 사람이 지나가면 그 사람 차에서 들려줘요.", "남기기") { openLeaveAt(at.latitude, at.longitude) }
                    true
                }
            }
        }
        return mv
    }

    /** 우리 그림을 올릴 층: 지역 도형 · 말풍선 · 지금 위치 */
    private fun addOverlayLayers(st: org.maplibre.android.maps.Style) {
        st.addSource(org.maplibre.android.style.sources.GeoJsonSource("regions"))
        st.addSource(org.maplibre.android.style.sources.GeoJsonSource("pins"))
        st.addSource(org.maplibre.android.style.sources.GeoJsonSource("me"))
        st.addImage("me-dot", dotBitmap(BLUE))
        val get = { k: String -> org.maplibre.android.style.expressions.Expression.get(k) }
        st.addLayer(org.maplibre.android.style.layers.FillLayer("region-fill", "regions").withProperties(
            org.maplibre.android.style.layers.PropertyFactory.fillColor(org.maplibre.android.style.expressions.Expression.toColor(get("fill")))))
        st.addLayer(org.maplibre.android.style.layers.LineLayer("region-line", "regions").withProperties(
            org.maplibre.android.style.layers.PropertyFactory.lineColor(org.maplibre.android.style.expressions.Expression.toColor(get("line"))),
            org.maplibre.android.style.layers.PropertyFactory.lineWidth(get("lw"))))
        st.addLayer(org.maplibre.android.style.layers.SymbolLayer("me", "me").withProperties(
            org.maplibre.android.style.layers.PropertyFactory.iconImage("me-dot"),
            org.maplibre.android.style.layers.PropertyFactory.iconSize(1f / MAP_PIXEL_RATIO),
            org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap(true),
            org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement(true)))
        st.addLayer(org.maplibre.android.style.layers.SymbolLayer("pins", "pins").withProperties(
            org.maplibre.android.style.layers.PropertyFactory.iconImage(get("icon")),
            // 지도 배율만큼 그림이 커지므로 되돌린다 (말풍선은 이미 화면 크기에 맞춰 그렸다)
            org.maplibre.android.style.layers.PropertyFactory.iconSize(1f / MAP_PIXEL_RATIO),
            org.maplibre.android.style.layers.PropertyFactory.iconAnchor(org.maplibre.android.style.layers.Property.ICON_ANCHOR_BOTTOM),
            org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap(true),
            org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement(true),
            org.maplibre.android.style.layers.PropertyFactory.symbolSortKey(get("z"))))
    }

    /** 지도를 눌렀다: 말풍선이면 그것, 아니면 지역 도형 */
    private fun onMainTap(map: org.maplibre.android.maps.MapLibreMap, at: org.maplibre.android.geometry.LatLng) {
        val pt = map.projection.toScreenLocation(at)
        map.queryRenderedFeatures(pt, "pins").firstOrNull()?.let { f ->
            val id = f.getStringProperty("id") ?: return
            when (f.getStringProperty("kind")) {
                "region" -> (Regions.provinces + Regions.districts).firstOrNull { it.code == id }?.let { selectRegion(it) }
                "msg" -> messages.firstOrNull { it.id == id }?.let { selMessage = it; refreshMain() }
                "spot" -> store.spots.firstOrNull { it.id == id }?.let { sp ->
                    confirm("이 자리에 안부를 남길까요?", "${sp.memo.ifBlank { spotPlaceName(sp) }} · ${spotWhen(sp)}", "남기기") { openSpot(sp) }
                }
                "fuzzy" -> toast("${messages.firstOrNull { it.id == id }?.let { Engine.circles[it.circleId]?.name } ?: "모임"}의 안부가 이 근처 어딘가에 있어요. 지나가면 들려 드려요")
            }
            return
        }
        map.queryRenderedFeatures(pt, "region-fill").firstOrNull()?.getStringProperty("code")?.let { code ->
            if (f0(code)) (Regions.provinces + Regions.districts).firstOrNull { it.code == code }?.let { selectRegion(it) }
        }
    }

    /** 안부가 있는 지역인지 */
    private fun f0(code: String) = regionStats(if (code.length > 2) 1 else 0).keys.any { it.code == code }

    /** 그 자리를 가운데 두고 부드럽게 확대·축소 */
    private fun flyTo(lat: Double, lon: Double, zoom: Double) {
        mainLib?.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(org.maplibre.android.geometry.LatLng(lat, lon), zoom), 700)
    }

    private fun levelOf(zoom: Double) = when { zoom < LEVEL_DISTRICT_ZOOM -> 0; zoom < LEVEL_PLACE_ZOOM -> 1; else -> 2 }

    private fun showKorea() {
        selRegion = null
        mainLib?.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngBounds(KOREA, dp(40)), 700)
        refreshMain()
    }

    private fun showHere() {
        val loc = lastLoc ?: return toast("아직 위치를 몰라요")
        flyTo(loc.first, loc.second, 13.0)
    }

    /** 데이터가 바뀌었으면 지도 그림을, 늘 오른쪽 칸을 고친다 */
    private fun refreshMain() {
        val sig = listOf(store.spots.joinToString(",") { it.id }, messages.joinToString(",") { it.id }, foundIds().sorted().joinToString(","), store.hiddenCircles.sorted(), store.hiddenMembers.sorted(),
            selRegion?.code, selMessage?.id, lastLoc?.let { "%.3f,%.3f".format(it.first, it.second) }, store.memberships.map { it.ownerId }).toString()
        if (sig != mapSig && mainStyle != null) drawMainOverlays()
        refreshSide()
    }

    /** 모임 색 */
    private fun circleColor(circleId: String): Int {
        val i = store.memberships.indexOfFirst { it.circleId == circleId }.coerceAtLeast(0)
        return MEMBER_COLORS[CIRCLE_COLOR_NAMES[i % CIRCLE_COLOR_NAMES.size]] ?: ACCENT
    }

    /** 보기에서 고른 모임·사람의 메시지만 */
    private fun visibleMessages(): List<PlaceMessage> {
        val hc = store.hiddenCircles; val hm = store.hiddenMembers
        // 다른 사람에게만 보낸 메시지는 이 차에서 세지 않는다 (내가 남긴 것은 보인다)
        return messages.filter { it.circleId !in hc && it.authorId !in hm && (isMine(it) || it.to.isEmpty() || Engine.ownerOf(it) in it.to) }
    }

    private fun regionsOf(m: PlaceMessage) = regionCache.getOrPut(m.id) {
        val d = Regions.districtOf(m.lat, m.lon)
        (d?.let { Regions.provinceOfDistrict(it) } ?: Regions.provinceOf(m.lat, m.lon)) to d
    }

    /** 지역 안의 (찾은 수, 전체 수, 들어 있는 모임들). 내가 남긴 것은 세지 않는다. */
    private data class RegionStat(val found: Int, val total: Int, val circles: List<String>)

    private fun regionStats(level: Int): Map<Regions.Region, RegionStat> {
        val ids = foundIds()
        return visibleMessages().filter { it.isPlace && !isMine(it) }
            .groupBy { regionsOf(it).let { r -> if (level == 0) r.first else r.second } }
            .filterKeys { it != null }
            .map { (r, list) -> r!! to RegionStat(list.count { it.id in ids }, list.size, list.map { it.circleId }.distinct()) }.toMap()
    }

    /** "서울 강남구" */
    private fun fullName(r: Regions.Region) = if (r.code.length > 2) "${Regions.provinceOfDistrict(r)?.name ?: ""} ${r.name}".trim() else r.name

    private fun hex(c: Int) = String.format("#%06X", 0xFFFFFF and c)

    /** 지도 그림: 단계에 맞게 지역 도형·말풍선, 또는 자리마다 얼굴 말풍선 */
    private fun drawMainOverlays() {
        val map = mainLib ?: return
        val st = mainStyle ?: return
        val level = levelOf(map.cameraPosition.zoom)
        mapLevel = level
        mapLevelView?.text = when (level) { 0 -> "시·도 단위 · 손가락으로 확대"; 1 -> "시·군·구 단위"; else -> "실제 자리" }
        mapSig = listOf(store.spots.joinToString(",") { it.id }, messages.joinToString(",") { it.id }, foundIds().sorted().joinToString(","), store.hiddenCircles.sorted(), store.hiddenMembers.sorted(),
            selRegion?.code, selMessage?.id, lastLoc?.let { "%.3f,%.3f".format(it.first, it.second) }, store.memberships.map { it.ownerId }).toString()
        // 시·도·시·군·구 단계는 길·지명 없이 지역 도형만 평평하게, 실제 자리 단계에서만 길과 동네 이름
        val showBase = if (level == 2) org.maplibre.android.style.layers.Property.VISIBLE else org.maplibre.android.style.layers.Property.NONE
        st.layers.filter { it.id.startsWith("road-") || it.id.startsWith("label-") }
            .forEach { it.setProperties(org.maplibre.android.style.layers.PropertyFactory.visibility(showBase)) }
        val images = HashMap<String, android.graphics.Bitmap>()
        val pins = mutableListOf<org.maplibre.geojson.Feature>()
        val regions = mutableListOf<org.maplibre.geojson.Feature>()
        if (level < 2) drawRegions(level, regions, pins, images) else drawPlaces(pins, images)
        st.addImages(images)
        st.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>("regions")?.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(regions))
        st.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>("pins")?.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(pins))
        st.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>("me")?.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(
            listOfNotNull(lastLoc?.let { org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.Point.fromLngLat(it.second, it.first)) })))
    }

    private fun pin(lat: Double, lon: Double, icon: String, kind: String, id: String, z: Int) =
        org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.Point.fromLngLat(lon, lat)).apply {
            addStringProperty("icon", icon); addStringProperty("kind", kind); addStringProperty("id", id); addNumberProperty("z", z)
        }

    private fun drawRegions(level: Int, regions: MutableList<org.maplibre.geojson.Feature>, pins: MutableList<org.maplibre.geojson.Feature>,
                            images: HashMap<String, android.graphics.Bitmap>) {
        val stats = regionStats(level)
        val list = if (level == 0) Regions.provinces else Regions.districts
        val maxLeft = stats.values.maxOfOrNull { it.total - it.found }?.coerceAtLeast(1) ?: 1
        list.forEach { r ->
            val st = stats[r]
            val left = st?.let { it.total - it.found } ?: 0
            // 땅은 옅은 회색, 안부가 있는 지역은 못 찾은 게 많을수록 진한 파랑, 다 찾으면 초록
            val fill = when {
                st == null -> SOFT
                left == 0 -> blend(SOFT, GREEN, 0.35f)
                else -> blend(SOFT, ACCENT, 0.18f + 0.5f * left / maxLeft)
            }
            r.rings.forEach { ring ->
                val pts = (ring.indices step 2).map { org.maplibre.geojson.Point.fromLngLat(ring[it + 1], ring[it]) }
                regions += org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.Polygon.fromLngLats(listOf(pts + pts.first()))).apply {
                    addStringProperty("code", r.code)
                    addStringProperty("fill", hex(fill))
                    // 경계는 바탕색 선으로 지역을 나눈다 (예시처럼 평평하게)
                    addStringProperty("line", hex(if (r == selRegion) ACCENT else BG))
                    addNumberProperty("lw", if (r == selRegion) 3 else 1.5)
                }
            }
        }
        stats.forEach { (r, st) ->
            val icon = "r_${r.code}_${st.found}_${st.total}_${r == selRegion}_${st.circles.joinToString("")}"
            images[icon] = regionBubble(r.name, st, r == selRegion)
            pins += pin(r.labelLat, r.labelLon, icon, "region", r.code, if (r == selRegion) 2 else 1)
        }
    }

    private fun drawPlaces(pins: MutableList<org.maplibre.geojson.Feature>, images: HashMap<String, android.graphics.Bitmap>) {
        val ids = foundIds()
        val localFound = store.found.associateBy { it.messageId }
        store.spots.forEach { sp ->
            images["spot"] = spotBitmap()
            pins += pin(sp.lat, sp.lon, "spot", "spot", sp.id, 4)
        }
        visibleMessages().forEach { m ->
            val color = circleColor(m.circleId)
            val letter = m.authorName.take(1)
            val (lat, lon, kind) = when {
                isMine(m) && m.isPlace -> Triple(m.lat, m.lon, "mine")
                m.id in ids && m.isPlace -> Triple(m.lat, m.lon, "msg")
                // 비·밤길 같은 조건 안부는 들었던 자리(이 차 안에만 저장)에
                m.id in ids -> localFound[m.id]?.let { Triple(it.lat, it.lon, "msg") } ?: return@forEach
                m.isPlace && !isMine(m) -> fuzzy(m).let { Triple(it.first, it.second, "fuzzy") }
                else -> return@forEach
            }
            val icon = when (kind) {
                "mine" -> "m_me_$color".also { images[it] = faceBubble("나", color, mine = true) }
                "msg" -> "m_${letter}_${color}_${m.id == selMessage?.id}".also { images[it] = faceBubble(letter, color, mine = false) }
                else -> "f_$color".also { images[it] = fuzzyBitmap(color) }
            }
            pins += pin(lat, lon, icon, if (kind == "mine") "msg" else kind, m.id, if (kind == "fuzzy") 0 else if (m.id == selMessage?.id) 3 else 2)
        }
    }

    /** 못 찾은 안부는 정확한 자리를 숨기고, 반경 안쪽 어딘가(메시지마다 늘 같은 곳)에 흐린 동그라미로 */
    private fun fuzzy(m: PlaceMessage): Pair<Double, Double> {
        val h = m.id.hashCode()
        val angle = (h and 0xFFFF) / 65535.0 * 2 * Math.PI
        val d = m.radiusM.coerceAtLeast(200.0) * (0.3 + 0.4 * ((h ushr 16) and 0xFF) / 255.0)
        val dLat = d * cos(angle) / 111_000.0
        val dLon = d * sin(angle) / (111_000.0 * cos(Math.toRadians(m.lat)))
        return m.lat + dLat to m.lon + dLon
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    /** 지역을 고르면 그 지역이 꽉 차게 확대하고, 오른쪽 칸 위에 그 지역 안부를 보여 준다 */
    private fun selectRegion(r: Regions.Region) {
        selRegion = r
        selMessage = null
        val map = mainLib ?: return
        val box = bounds(r.north, r.east, r.south, r.west)
        // 시·도는 그 시·도가 꽉 차게(시·군·구 단계), 시·군·구는 실제 자리 단계까지
        val fit = map.getCameraForLatLngBounds(box, intArrayOf(dp(40), dp(80), dp(40), dp(40)))?.zoom ?: map.cameraPosition.zoom
        val zoom = if (r.code.length > 2) maxOf(fit, LEVEL_PLACE_ZOOM + 0.4) else fit.coerceIn(LEVEL_DISTRICT_ZOOM + 0.1, LEVEL_PLACE_ZOOM - 0.1)
        flyTo(box.center.latitude, box.center.longitude, zoom)
        refreshMain()
    }

    /** 고른 안부가 보이게 지도를 그 자리로 */
    private fun focusMessage(m: PlaceMessage) {
        selMessage = m
        val at = if (m.isPlace) m.lat to m.lon else store.found.firstOrNull { it.messageId == m.id }?.let { it.lat to it.lon }
        at?.let { flyTo(it.first, it.second, maxOf(14.5, mainLib?.cameraPosition?.zoom ?: 0.0)) }
        refreshMain()
    }

    /** 지도에서 길게 누른 자리로 메시지 남기기 */
    private fun openLeaveAt(lat: Double, lon: Double) {
        goTab(Screen.LEAVE)
        leaveWhen = "place"
        leaveKey = MAP_PICK
        leavePlace = Place("고른 곳", lat, lon)
        leaveMapCenter = org.maplibre.android.geometry.LatLng(lat, lon)
        leaveStep = 1
        render()
    }

    // 메인 오른쪽 칸 -----------------------------------------------------------------

    private fun refreshSide() {
        val side = mainSide ?: return
        side.removeAllViews()
        // 이 차 주인을 아직 안 정한 모임
        store.memberships.filter { it.ownerId == null }.forEach { ms ->
            val c = Engine.circles[ms.circleId] ?: return@forEach
            side.addView(panel().apply {
                background = rounded(CARD, 3, ACCENT)
                addView(text("${c.name}에서 이 차는 누구 차인가요?", 26f, INK, bold = true))
                addView(label("주인을 정해야 그 모임 안부를 들려 드려요"))
                addView(primary("차 주인 고르기") { Engine.switchCircle(c.id); pickOwner() }, LinearLayout.LayoutParams(-1, dp(88)).apply { topMargin = dp(10) })
            }, panelParams())
        }
        if (store.spots.isNotEmpty()) side.addView(spotsCard(), panelParams())
        side.addView(waitingPanel(), panelParams())
        selMessage?.takeIf { m -> visibleMessages().any { it.id == m.id } }?.let { side.addView(selectedCard(it), panelParams()) }
        selRegion?.let { side.addView(regionCard(it), panelParams()) }
        side.addView(conditionCard(), panelParams())
        side.addView(filterCard(), panelParams())
    }

    /** 주행 중 말로 저장한 곳: 하나씩 「안부 남기기」 또는 지우기 */
    private fun spotsCard() = panel().apply {
        background = rounded(CARD, 3, ACCENT)
        val list = store.spots.sortedByDescending { it.at }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(icon(R.drawable.ic_mic, 40, ACCENT), LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) })
            addView(column().apply {
                addView(text("달리면서 저장한 곳 ${list.size}곳", 28f, INK, bold = true))
                addView(label("「여기 저장해 줘」라고 말한 자리예요. 안부로 남겨 보세요"))
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        list.take(4).forEach { sp ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, 0)
                addView(column().apply {
                    addView(text(sp.memo.ifBlank { spotPlaceName(sp) }, 24f, INK, weight = 600).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    addView(text(spotWhen(sp) + spotConditions(sp), 20f, MUTED).apply { maxLines = 1 })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                // 줄을 누르면 지도가 그 자리로
                isClickable = true
                background = pressable(GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.TRANSPARENT) })
                setOnClickListener { flyTo(sp.lat, sp.lon, 14.5) }
                addView(button("남기기", rounded(ACCENT), Color.WHITE) { openSpot(sp) }.apply { textSize = 22f; minHeight = dp(72) },
                    LinearLayout.LayoutParams(dp(130), dp(72)).apply { marginStart = dp(8) })
                addView(navButton("지우기") {
                    confirm("이 자리를 지울까요?", "${sp.memo.ifBlank { spotPlaceName(sp) }} · ${spotWhen(sp)}", "지우기") { Engine.removeSpot(sp.id) }
                }, LinearLayout.LayoutParams(dp(120), dp(72)).apply { marginStart = dp(8) })
            })
        }
        if (list.size > 4) addView(label("그 밖에 ${list.size - 4}곳 · 지도에 「저장」 핀으로 보여요"))
    }

    /** 저장한 곳의 이름: 들어 있는 시·군·구 ("서울 서초구 어딘가") */
    private fun spotPlaceName(sp: TripStore.Spot) = sp.address.ifBlank { Regions.districtOf(sp.lat, sp.lon)?.let { fullName(it) } ?: "저장한 곳" }

    private fun spotWhen(sp: TripStore.Spot): String {
        val t = java.time.Instant.ofEpochMilli(sp.at).atZone(java.time.ZoneId.systemDefault())
        val today = java.time.LocalDate.now()
        val day = when (t.toLocalDate()) { today -> "오늘"; today.minusDays(1) -> "어제"; else -> "${t.monthValue}월 ${t.dayOfMonth}일" }
        return "$day ${t.hour}:${"%02d".format(t.minute)}"
    }

    private fun spotConditions(sp: TripStore.Spot) = sp.conditions.split(",").filter { it.isNotBlank() }
        .mapNotNull { mapOf("rain" to "비", "night" to "밤길", "together" to "동승")[it] }.joinToString("") { " · $it" }

    /** 저장한 곳으로 안부 남기기: 장소를 채운 채 「내용」 단계로 바로 */
    private fun openSpot(sp: TripStore.Spot) {
        goTab(Screen.LEAVE)
        leaveWhen = "place"
        leaveKey = MAP_PICK
        leavePlace = Place(sp.memo.ifBlank { sp.address.ifBlank { Regions.districtOf(sp.lat, sp.lon)?.name ?: "저장한 곳" } }, sp.lat, sp.lon)
        leaveMapCenter = org.maplibre.android.geometry.LatLng(sp.lat, sp.lon)
        leaveSpotId = sp.id
        leaveStep = 2
        render()
    }

    /** 고른 안부 하나: 누가 · 어느 모임 · 어디서 · 다시 듣기 */
    private fun selectedCard(m: PlaceMessage) = panel().apply {
        val c = Engine.circles[m.circleId]
        val mine = isMine(m)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(avatar(m.authorName, 64, member = c?.member(m.authorId)), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(16) })
            addView(column().apply {
                addView(text(if (mine) "내가 ${m.occasion} 남긴 안부" else "${m.authorName}님이 ${m.occasion} 남긴 안부", 26f, INK, bold = true))
                addView(circleTag(m.circleId))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(navButton("닫기") { selMessage = null; refreshSide(); mapSig = "" ; refreshMain() }, LinearLayout.LayoutParams(dp(130), dp(72)))
        })
        if (m.kind == "text" && !discreet()) addView(text("“${m.text}”", 24f, INK).apply { setPadding(0, dp(10), 0, dp(6)) })
        if (!mine) addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(primary("▶ 다시 듣기") { Engine.replay(m) }, LinearLayout.LayoutParams(0, dp(88), 2f).apply { marginEnd = dp(10) })
            addView(secondary("답장") { startReply(m) }, LinearLayout.LayoutParams(0, dp(88), 1f))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        })
        if (m.isPlace) addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(secondary("내비에서 보기") { if (!Engine.showInNavi(m.lat, m.lon, m.placeName)) toast("내비에 연결하지 못했어요") },
                LinearLayout.LayoutParams(0, dp(80), 1f).apply { marginEnd = dp(10) })
            addView(secondary("여기로 안내") { if (!Engine.routeTo(m.lat, m.lon, m.placeName)) toast("내비에 연결하지 못했어요") },
                LinearLayout.LayoutParams(0, dp(80), 1f))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        })
    }

    /** 모임 이름 꼬리표 (모임 색 점 + 이름) */
    private fun circleTag(circleId: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(circleColor(circleId)) } },
            LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(8) })
        addView(text(Engine.circles[circleId]?.name ?: "모임", 20f, MUTED))
    }

    /** 고른 지역의 안부: 찾은 것·내가 남긴 것은 하나씩, 못 찾은 것은 개수만 */
    private fun regionCard(r: Regions.Region) = panel().apply {
        val ids = foundIds()
        val inRegion = visibleMessages().filter { it.isPlace && regionsOf(it).let { x -> x.first == r || x.second == r } }
        val others = inRegion.filter { !isMine(it) }
        val found = others.filter { it.id in ids }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(column().apply {
                addView(text(fullName(r), 30f, INK, bold = true))
                addView(text("찾은 안부 ${found.size} / ${others.size}", 22f, ACCENT, weight = 600))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(navButton("닫기") { selRegion = null; refreshMain() }, LinearLayout.LayoutParams(dp(130), dp(72)))
        })
        addView(progressBar(found.size, others.size))
        val left = others.size - found.size
        addView(label(if (left > 0) "아직 ${left}개가 ${r.name} 어딘가에 있어요. 지나가면 들려 드려요" else if (others.isNotEmpty()) "이 지역 안부를 다 찾았어요!" else "모임 사람들이 남긴 안부가 없어요"))
        (found + inRegion.filter { isMine(it) }).forEach { m -> addView(messageRow(m)) }
    }

    /** 목록 한 줄: 얼굴 · 누가 어디서 · 모임. 누르면 지도가 그 자리로 */
    private fun messageRow(m: PlaceMessage) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(10))
        val mine = isMine(m)
        addView(avatar(if (mine) "나" else m.authorName, 52, member = Engine.circles[m.circleId]?.member(m.authorId)), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(14) })
        addView(column().apply {
            addView(text((if (mine) "내가 남긴 곳 · " else "${m.authorName} · ") + m.placeName, 24f, INK).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            addView(circleTag(m.circleId))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        isClickable = true
        background = pressable(GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.TRANSPARENT) })
        setOnClickListener { focusMessage(m) }
    }

    /** 상황별 안부: 장소 없이 차 상태로 열리는 안부(비·밤길·옆자리·차에 탈 때). 조건이 늘어도 목록이 저절로 는다. */
    private fun conditionCard() = panel().apply {
        val ids = foundIds()
        val list = visibleMessages().filter { !it.isPlace && !isMine(it) }
        addView(text("상황별 안부", 28f, INK, bold = true))
        addView(label("지도에 자리가 없는 안부예요. 그 상황이 되면 들려 드려요"))
        WHEN_OPTIONS.filter { it.first != "place" }.forEach { (key, name) ->
            val ms = list.filter { it.opensOn == key }
            val f = ms.count { it.id in ids }
            val open = openCondition == key
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(14), dp(4), dp(14))
                addView(icon(WHEN_ICONS.getValue(key), 40, if (ms.isNotEmpty()) ACCENT else MUTED), LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(16) })
                addView(column().apply {
                    addView(text(name, 24f, INK, weight = 600))
                    addView(label(WHEN_HINTS.getValue(key)))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(text(if (ms.isEmpty()) "없음" else "$f / ${ms.size}", 24f, if (ms.isEmpty()) MUTED else ACCENT, bold = true))
                addView(text(if (open) "  ▲" else "  ▼", 20f, MUTED))
                isClickable = true
                background = pressable(GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(if (open) CHIP else Color.TRANSPARENT) })
                setOnClickListener { openCondition = if (open) null else key; refreshSide() }
            })
            if (open) {
                ms.filter { it.id in ids }.forEach { m -> addView(messageRow(m)) }
                val left = ms.count { it.id !in ids }
                if (left > 0) addView(label("아직 못 들은 「$name」 안부 ${left}개 · ${ms.filter { it.id !in ids }.map { Engine.circles[it.circleId]?.name }.distinct().joinToString(", ")}"))
            }
        }
    }

    /** 보기: 모임마다 켜고 끄기(모임 색), 그 안에서 사람마다 켜고 끄기 */
    private fun filterCard() = panel().apply {
        addView(text("보기", 28f, INK, bold = true))
        addView(label("고른 모임·사람의 안부만 지도와 목록에 보여요 (이 차 안에서만)"))
        val hc = store.hiddenCircles; val hm = store.hiddenMembers
        store.memberships.forEach { ms ->
            val c = Engine.circles[ms.circleId] ?: return@forEach
            val on = c.id !in hc
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, dp(4))
                addView(View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(circleColor(c.id)) } },
                    LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) })
                addView(text(c.name + if (c.id == store.activeCircleId) " (관리 중)" else "", 24f, INK, weight = 600), LinearLayout.LayoutParams(0, -2, 1f))
                addView(toggle("", on) { v ->
                    store.hiddenCircles = if (v) store.hiddenCircles - c.id else store.hiddenCircles + c.id
                    refreshMain()
                })
            })
            if (on) {
                val people = c.members.filter { it.id != ms.ownerId }
                people.chunked(4).forEach { line ->
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        line.forEach { m ->
                            val shown = m.id !in hm
                            addView(button(m.name, if (shown) rounded(CHIP, 2, circleColor(c.id)) else rounded(CARD, 1, BORDER), if (shown) INK else MUTED) {
                                store.hiddenMembers = if (shown) store.hiddenMembers + m.id else store.hiddenMembers - m.id
                                refreshMain()
                            }.apply { textSize = 20f; minHeight = dp(64) }, LinearLayout.LayoutParams(0, dp(64), 1f).apply { marginEnd = dp(8); topMargin = dp(8) })
                        }
                        repeat(4 - line.size) { addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { marginEnd = dp(8) }) }
                    })
                }
            }
        }
    }

    /** 지역 말풍선: 이름 · 찾은 수/전체 · 진행 막대 · 모임 색 점. 아래로 꼬리 */
    private fun regionBubble(name: String, st: RegionStat, selected: Boolean): android.graphics.Bitmap {
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val title = "$name ${st.found}/${st.total}"
        p.textSize = dp(24).toFloat(); p.typeface = font(700)
        val tw = p.measureText(title)
        val dotsW = st.circles.size * dp(16)
        val w = (tw + dotsW + dp(40)).toInt()
        val bodyH = dp(64)
        val tail = dp(14)
        val bmp = android.graphics.Bitmap.createBitmap(w, bodyH + tail, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp)
        val done = st.found == st.total
        val bg = if (selected) ACCENT else CARD
        val fg = if (selected) Color.WHITE else INK
        p.color = bg
        cv.drawRoundRect(0f, 0f, w.toFloat(), bodyH.toFloat(), dp(18).toFloat(), dp(18).toFloat(), p)
        if (!selected) { p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = dp(2).toFloat(); p.color = if (done) GREEN else ACCENT
            cv.drawRoundRect(dp(1).toFloat(), dp(1).toFloat(), w - dp(1).toFloat(), bodyH - dp(1).toFloat(), dp(18).toFloat(), dp(18).toFloat(), p); p.style = android.graphics.Paint.Style.FILL }
        val path = android.graphics.Path().apply { moveTo(w / 2f - tail, bodyH.toFloat() - 1); lineTo(w / 2f + tail, bodyH.toFloat() - 1); lineTo(w / 2f, (bodyH + tail).toFloat()); close() }
        p.color = if (selected) ACCENT else if (done) GREEN else ACCENT
        cv.drawPath(path, p)
        p.color = fg
        cv.drawText(title, dp(20).toFloat(), dp(34).toFloat(), p)
        // 모임 색 점
        st.circles.forEachIndexed { i, id ->
            p.color = circleColor(id)
            cv.drawCircle(dp(20) + tw + dp(12) + i * dp(16).toFloat(), dp(26).toFloat(), dp(6).toFloat(), p)
        }
        // 진행 막대
        val bx = dp(20).toFloat(); val bw = w - dp(40).toFloat(); val by = dp(46).toFloat()
        p.color = if (selected) withAlpha(Color.WHITE, 90) else SOFT
        cv.drawRoundRect(bx, by, bx + bw, by + dp(8), dp(4).toFloat(), dp(4).toFloat(), p)
        p.color = if (selected) Color.WHITE else if (done) GREEN else ACCENT
        if (st.total > 0 && st.found > 0) cv.drawRoundRect(bx, by, bx + bw * st.found / st.total, by + dp(8), dp(4).toFloat(), dp(4).toFloat(), p)
        return bmp
    }

    /** 얼굴 말풍선: 모임 색 동그라미에 이름 첫 글자, 아래로 꼬리. 내가 남긴 곳은 흰 바탕에 「나」 */
    private fun faceBubble(letter: String, color: Int, mine: Boolean): android.graphics.Bitmap {
        val s = dp(60); val tail = dp(12)
        val bmp = android.graphics.Bitmap.createBitmap(s, s + tail, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.color = color
        cv.drawPath(android.graphics.Path().apply { moveTo(s / 2f - tail, s - dp(8).toFloat()); lineTo(s / 2f + tail, s - dp(8).toFloat()); lineTo(s / 2f, (s + tail).toFloat()); close() }, p)
        p.color = Color.WHITE
        cv.drawCircle(s / 2f, s / 2f, s / 2f, p)
        p.color = if (mine) Color.WHITE else color
        cv.drawCircle(s / 2f, s / 2f, s / 2f - dp(4), p)
        if (mine) { p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = dp(4).toFloat(); p.color = color; cv.drawCircle(s / 2f, s / 2f, s / 2f - dp(5), p); p.style = android.graphics.Paint.Style.FILL }
        p.color = if (mine) color else Color.WHITE; p.textSize = s * 0.42f; p.typeface = font(700); p.textAlign = android.graphics.Paint.Align.CENTER
        cv.drawText(letter, s / 2f, s / 2f - (p.descent() + p.ascent()) / 2, p)
        return bmp
    }

    /** 주행 중 저장한 곳: 회색 말풍선에 마이크 */
    private fun spotBitmap(): android.graphics.Bitmap {
        val w = dp(92); val h = dp(56); val tail = dp(12)
        val bmp = android.graphics.Bitmap.createBitmap(w, h + tail, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.color = INK
        cv.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), dp(16).toFloat(), dp(16).toFloat(), p)
        cv.drawPath(android.graphics.Path().apply { moveTo(w / 2f - tail, h - 1f); lineTo(w / 2f + tail, h - 1f); lineTo(w / 2f, (h + tail).toFloat()); close() }, p)
        p.color = BG; p.textSize = dp(24).toFloat(); p.typeface = font(700); p.textAlign = android.graphics.Paint.Align.CENTER
        cv.drawText("저장", w / 2f, h / 2f - (p.descent() + p.ascent()) / 2, p)
        return bmp
    }

    /** 못 찾은 안부: 흐린 동그라미에 「?」 */
    private fun fuzzyBitmap(color: Int): android.graphics.Bitmap {
        val s = dp(84)
        val bmp = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.color = withAlpha(color, 70); cv.drawCircle(s / 2f, s / 2f, s / 2f, p)
        p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = dp(2).toFloat(); p.color = withAlpha(color, 160)
        p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(6).toFloat(), dp(5).toFloat()), 0f)
        cv.drawCircle(s / 2f, s / 2f, s / 2f - dp(2), p)
        p.style = android.graphics.Paint.Style.FILL; p.pathEffect = null
        p.color = withAlpha(color, 230); p.textSize = s * 0.42f; p.typeface = font(700); p.textAlign = android.graphics.Paint.Align.CENTER
        cv.drawText("?", s / 2f, s / 2f - (p.descent() + p.ascent()) / 2, p)
        return bmp
    }

    /** 지도 핀: 색 동그라미에 흰 글자 */
    private fun pinBitmap(color: Int, letter: String): android.graphics.Bitmap {
        val s = dp(56)
        val bmp = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE; cv.drawCircle(s / 2f, s / 2f, s / 2f, p)
        p.color = color; cv.drawCircle(s / 2f, s / 2f, s / 2f - dp(4), p)
        p.color = Color.WHITE; p.textSize = s * 0.42f; p.typeface = font(700); p.textAlign = android.graphics.Paint.Align.CENTER
        cv.drawText(letter, s / 2f, s / 2f - (p.descent() + p.ascent()) / 2, p)
        return bmp
    }

    /** 지금 위치 점 */
    private fun dotBitmap(color: Int): android.graphics.Bitmap {
        val s = dp(32)
        val bmp = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE; cv.drawCircle(s / 2f, s / 2f, s / 2f, p)
        p.color = color; cv.drawCircle(s / 2f, s / 2f, s / 2f - dp(5), p)
        return bmp
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    /** 선 아이콘 (res/drawable, Feather Icons). 색은 tint로 입힌다. */
    private fun icon(res: Int, sizeDp: Int, color: Int) = android.widget.ImageView(this).apply {
        setImageDrawable(getDrawable(res)!!.mutate().apply { setTint(color) })
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun renderSettings() {
        root.addView(title("설정"))

        // 들어간 모임: 가족·친구 등 여러 모임에 함께 들어갈 수 있다. 아래 설정은 「관리 중」인 모임 것이다.
        root.addView(section(R.drawable.ic_people, "들어간 모임 ${store.memberships.size}개"))
        store.memberships.forEach { ms ->
            val c = Engine.circles[ms.circleId]
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(CARD, if (ms.circleId == store.activeCircleId) 2 else 1, if (ms.circleId == store.activeCircleId) ACCENT else BORDER)
                setPadding(dp(24), dp(12), dp(12), dp(12))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
                addView(View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(circleColor(ms.circleId)) } },
                    LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(16) })
                addView(column().apply {
                    addView(big((c?.name ?: "불러오는 중…") + if (ms.circleId == store.activeCircleId) "  (관리 중)" else ""))
                    addView(label("${CIRCLE_TYPES.firstOrNull { it.first == c?.type }?.second ?: ""} · 이 차 주인: ${ms.ownerId?.let { c?.nameOf(it) } ?: "아직 없음"} · ${ms.carName}"))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                if (ms.circleId != store.activeCircleId) addView(navButton("이 모임 관리") { Engine.switchCircle(ms.circleId); inviteCode = null; render() })
            })
        }
        root.addView(row(secondary("+ 다른 모임 더하기") {
            pickFrom("다른 모임 더하기", listOf("새 모임 만들기", "초대 코드로 들어가기")) { i -> if (i == 0) startCreate() else startJoin() }
        }))

        val owner = store.driverId?.let { circle?.nameOf(it) }
        root.addView(section(R.drawable.ic_car, "이 차"))
        root.addView(label("${circle?.name ?: "모임"}에 연결된 ${owner?.let { "${it}님의 " } ?: ""}차예요. 이 차에서는 주인에게 온 메시지를 들려주고, 주인이 남긴 메시지는 틀지 않아요."))
        root.addView(row(
            secondary("차 주인: ${owner ?: "고르기"}") { pickOwner() },
            secondary("차 이름: ${store.carName.ifBlank { "우리 차" }}") {
                askText("이 차 이름", store.carName) { name -> Engine.renameCar(name) { ok -> if (!ok) toast("바꾸지 못했어요") } }
            },
        ))
        root.addView(row(
            secondary("모임 관리") { openMembers() },
            secondary("이 차 연결 끊기") {
                confirm("이 차를 ${circle?.name ?: "모임"}에서 뺄까요?", "다시 쓰려면 초대 코드로 들어오면 돼요. 모임 사람들이 남긴 메시지는 지워지지 않아요.", "연결 끊기") {
                    Engine.unlink { ok -> if (!ok) toast("서버에 연결하지 못했어요") else { history.clear(); screen = Screen.HOME; render() } }
                }
            },
        ))

        root.addView(section(R.drawable.ic_people, "초대"))
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(CARD, 1, BORDER)
            setPadding(dp(28), dp(16), dp(16), dp(16))
            addView(column().apply {
                addView(label("이 코드를 알려 주면 그 사람 차나 폰에서 ${circle?.name ?: "모임"}에 들어와요"))
                addView(text(inviteCode?.let { if (it.length == 6) "${it.take(3)} ${it.drop(3)}" else it } ?: "불러오는 중…", 56f, INK, bold = true).apply { letterSpacing = 0.08f })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(navButton("새 코드 만들기") {
                confirm("새 초대 코드를 만들까요?", "예전 코드로는 더 들어올 수 없어요. 이미 들어온 사람은 그대로예요.", "새로 만들기") {
                    Api.invite(reset = true) { code -> if (code != null) { inviteCode = code; render() } else toast("서버에 연결하지 못했어요") }
                }
            }, LinearLayout.LayoutParams(dp(260), dp(80)))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        })
        if (inviteCode == null) Api.invite { code -> if (code != null) { inviteCode = code; if (screen == Screen.SETTINGS) render() } }

        root.addView(section(R.drawable.ic_shield, "옆자리 배려"))
        root.addView(toggle("옆자리에 누가 있으면 먼저 물어보기", store.askWithPassenger) { store.askWithPassenger = it; Engine.maybeAutoPlay() })
        root.addView(label("조수석 센서로 동승을 알아채면 바로 틀지 않고 「메시지가 도착했어요. 지금 들을까요?」라고만 물어요. 보낸 사람과 내용은 화면·알림에 나오지 않고, 「다음에」를 고르면 옆자리가 빈 뒤 들려줘요. 「옆자리에 누가 탄 날」 메시지는 함께 들으라고 남긴 것이라 바로 들려줘요."))

        root.addView(section(R.drawable.ic_people, "모임에 보일 내용"))
        root.addView(label("기본은 공유 안 함이에요. 내가 켠 것만 모임에 보여요. 장소 이름과 동선은 보내지 않아요."))
        root.addView(toggle("이동 거리", store.shareDistance) { store.shareDistance = it; Engine.uploadDiary() })
        root.addView(toggle("쉬어간 횟수", store.shareRests) { store.shareRests = it; Engine.uploadDiary() })
        root.addView(toggle("들은 메시지", store.shareHeard) { store.shareHeard = it; Engine.uploadDiary() })
        root.addView(toggle("비 · 밤길 (옆자리에 누가 탔는지는 보내지 않아요)", store.shareWeather) { store.shareWeather = it; Engine.uploadDiary() })
        root.addView(row(
            secondary("공유 모두 끄고 기록 삭제") {
                store.stopSharing()
                val c = circle; val d = store.driverId
                if (c != null && d != null) Api.deleteDiaries(c.id, d) { render() }
                render()
            },
        ))

        root.addView(section(R.drawable.ic_headphones, "받는 메시지"))
        root.addView(toggle("운전 중에도 소리로 들려주기 (화면은 안 띄워요, 동승자가 있으면 안 들려줘요)", store.playWhileDriving) { store.playWhileDriving = it })
        root.addView(toggle("글 메시지를 기계음으로 듣기", store.acceptTts) { store.acceptTts = it })
        root.addView(toggle("끝까지 들으면 버튼 없이 「들었어요」 보내기", store.autoHeard) { store.autoHeard = it })

        root.addView(section(R.drawable.ic_sun, "화면 밝기"))
        root.addView(row(*THEMES.map { (key, name) ->
            choice(name, store.theme == key) {
                store.theme = key
                recreate() // 색을 다시 정해 화면을 새로 그린다
            }
        }.toTypedArray()))

        root.addView(section(R.drawable.ic_shield, "개인정보"))
        root.addView(row(
            secondary("개인정보 처리방침 보기") { showPrivacy(null) },
            button("모임 통째로 지우기", rounded(MEMBER_COLORS["red"] ?: ACCENT), Color.WHITE) { deleteCircle() },
        ))
        root.addView(label("모임을 지우면 모든 프로필·메시지·목소리·사진·일기와 연결된 차·폰이 지워지고 되돌릴 수 없어요."))
    }

    /** 모임 통째로 지우기. 되돌릴 수 없어서 두 번 묻는다. */
    private fun deleteCircle() {
        val name = circle?.name ?: return
        confirm("「$name」을(를) 통째로 지울까요?", "모든 프로필·메시지·목소리·사진·일기와 연결된 차·폰이 지워지고 되돌릴 수 없어요.", "지우기") {
            confirm("정말 지울까요?", "모임 사람 모두의 차와 폰에서도 「$name」이(가) 사라져요.", "통째로 지우기") {
                Engine.deleteCircle { err ->
                    if (err != null) toast(err) else { toast("모임을 지웠어요"); history.clear(); screen = Screen.HOME }
                    render()
                }
            }
        }
    }

    /** 설정 묶음 제목: 선 아이콘 + 글 */
    private fun section(res: Int, s: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(icon(res, 34, ACCENT), LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(14) })
        addView(text(s, 28f, INK, bold = true))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24); bottomMargin = dp(4) }
    }

    // 메시지 남기기 (폰 웹과 같은 일을 차에서, 정차 중에만) ------------------------------

    private fun openLeave() {
        prepareLeave()
        go(Screen.LEAVE)
    }

    private fun prepareLeave() {
        leaveStep = 0
        leaveTo = emptyList(); leaveReplyTo = null
        leaveHours = "any"; leaveDays = "any"; leaveFrom = "now"; leaveRepeat = "once"
        if (leaveCircleId == null || store.keyOf(leaveCircleId!!) == null) leaveCircleId = store.activeCircleId
        leaveMapCenter = null
        val lc = leaveCircle()
        if (leaveAuthor == null || lc?.members?.none { it.id == leaveAuthor } == true) leaveAuthor = lc?.id?.let { store.ownerOf(it) } ?: store.driverId
        if (leavePlace == null) lastLoc?.let { leavePlace = Place(HERE, it.first, it.second); leaveKey = HERE }
    }

    /** 남길 모임 (고른 것이 없으면 지금 관리하는 모임) */
    private fun leaveCircle(): Circle? = leaveCircleId?.let { Engine.circles[it] } ?: circle

    /** 남기기·소식 화면에서 같이 쓰는 "누구로" 고르기 */
    private fun pickAuthor(c: Circle) {
        pickFrom("누구인가요?", c.members.map { it.name }) { i -> leaveAuthor = c.members[i].id; render() }
    }

    /**
     * 메시지 남기기. 한 화면에 다 늘어놓지 않고 단계로 나눈다: 언제 → (장소) → 내용 → 확인.
     * 장소 단계는 「이 장소를 지나면」을 골랐을 때만 나온다.
     */
    private fun renderLeave() {
        val c = leaveCircle()
        if (c == null) { root.addView(title("메시지 남기기")); root.addView(label("서버에 연결되면 남길 수 있어요.")); return }
        val steps = leaveSteps()
        leaveStep = leaveStep.coerceIn(0, steps.lastIndex)
        val step = steps[leaveStep]
        // 내용 단계를 벗어나면 들어 보기 소리를 멈춘다
        if (step != "content") stopPreview()

        // 맨 위: 단계 표시
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            steps.forEachIndexed { i, s ->
                val done = i < leaveStep
                val now = i == leaveStep
                addView(text(if (done) "✓" else "${i + 1}", 20f, if (now || done) Color.WHITE else MUTED, bold = true).apply {
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (now || done) ACCENT else SOFT) }
                }, LinearLayout.LayoutParams(dp(44), dp(44)))
                addView(text(STEP_NAMES.getValue(s), 22f, if (now) INK else MUTED, bold = now).apply { setPadding(dp(10), 0, dp(10), 0) })
                if (i < steps.lastIndex) addView(View(context).apply { setBackgroundColor(BORDER) }, LinearLayout.LayoutParams(dp(40), dp(2)).apply { marginEnd = dp(10) })
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) }
        })

        when (step) {
            "when" -> leaveWhenStep()
            "place" -> leavePlaceStep()
            "content" -> leaveContentStep()
            "confirm" -> leaveConfirmStep(c)
        }

        // 맨 아래: 이전 · 다음 (확인 단계의 「남기기」는 그 단계 안에 있다)
        val canNext = when (step) {
            "place" -> leavePlace != null
            "content" -> !recorder.recording && !dictation.listening && (recorder.hasRecording || leaveText.isNotBlank())
            else -> true
        }
        root.addView(space(8))
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            if (leaveStep > 0) addView(secondary("← 이전") { leaveStep--; render() }, LinearLayout.LayoutParams(0, dp(96), 1f))
            else addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            if (step != "confirm") addView(primary("다음 →") { if (canNext) { leaveStep++; render() } }.apply { alpha = if (canNext) 1f else 0.4f },
                LinearLayout.LayoutParams(0, dp(96), 1f).apply { marginStart = dp(14) })
        })
    }

    private fun leaveSteps() = if (leaveWhen in WITH_PLACE) listOf("when", "place", "content", "confirm") else listOf("when", "content", "confirm")

    /** 1단계: 언제 들려줄지. 고르면 바로 다음으로. */
    private fun leaveWhenStep() {
        root.addView(title("언제 들려줄까요?"))
        root.addView(label("받는 사람에게 그 순간이 오면 들려줘요. 운전 중이면 소리로만, 옆자리에 누가 있으면 다음에 혼자일 때 들려줘요."))
        root.addView(space(12))
        WHEN_OPTIONS.chunked(3).forEach { group ->
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                group.forEachIndexed { i, (key, name) ->
                    addView(optionTile(WHEN_ICONS.getValue(key), name, WHEN_HINTS.getValue(key), leaveWhen == key) {
                        leaveWhen = key; leaveStep++; render()
                    }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(14) })
                }
                repeat(3 - group.size) { addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(14) }) }
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
            })
        }
    }

    /** 고르기 카드: 아이콘 · 이름 · 한 줄 설명 */
    private fun optionTile(res: Int, name: String, hint: String, selected: Boolean, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = pressable(if (selected) rounded(CHIP, 2, ACCENT) else rounded(CARD, 1, BORDER))
        setPadding(dp(28), dp(28), dp(28), dp(24))
        minimumHeight = dp(180)
        addView(icon(res, 48, if (selected) ACCENT else INK), LinearLayout.LayoutParams(dp(48), dp(48)).apply { bottomMargin = dp(14) })
        addView(text(name, 28f, if (selected) ACCENT else INK, bold = true))
        addView(text(hint, 22f, MUTED))
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** 2단계: 장소와 범위 */
    private fun leavePlaceStep() {
        root.addView(title(when (leaveWhen) {
            "region" -> "어느 지역에 들어가면 들려줄까요?"
            "park" -> "어디에 도착해 세우면 들려줄까요?"
            else -> "어디를 지날 때 들려줄까요?"
        }))
        if (leaveWhen == "region") leavePlace?.let { p ->
            root.addView(label("고른 곳이 든 지역: ${Regions.districtOf(p.lat, p.lon)?.let { fullName(it) } ?: "알 수 없음"} (그 시·군·구에 들어가면)"))
        }
        root.addView(label(if (Engine.naviPlaces.isNotEmpty()) "내비의 집·회사·최근 목적지에서 고를 수 있어요. 이 근처를 모임의 다른 사람이 지나가면 그 사람 차에서 들려줘요."
            else "이 근처를 모임의 다른 사람이 지나가면 그 사람 차에서 들려줘요."))
        root.addView(space(12))
        val here = lastLoc?.let { Place(HERE, it.first, it.second) }
        // 기본 내비의 집·회사·즐겨찾기·최근 목적지가 있으면 그것을, 없으면 예시 장소를
        val navi = Engine.naviPlaces.map { Place(it.name, it.lat, it.lon) }
        val places = listOfNotNull(here) + navi.ifEmpty { PRESETS }.take(5 - 1)
        val buttons = places.map { p ->
            // 좌표가 아니라 고른 버튼으로 표시한다 (지금 자리가 예시 장소 근처여도 하나만 ✓)
            choice(if (leaveKey == p.name) leavePlace?.name ?: p.name else p.name, leaveKey == p.name) { leavePlace = p; leaveKey = p.name; render() }
        } + choice("지도에서 고르기", leaveKey == MAP_PICK) {
            if (leaveKey != MAP_PICK) { leaveKey = MAP_PICK; leavePlace = null; leaveMapCenter = null }
            render()
        }
        buttons.chunked(3).forEach { group -> root.addView(row(*group.toTypedArray())) }
        if (leaveKey == MAP_PICK) {
            root.addView(label(leavePlace?.let { "고른 곳: %.5f, %.5f · 다른 곳을 누르면 옮겨요".format(it.lat, it.lon) } ?: "지도를 눌러 장소를 골라 주세요"))
            root.addView(pickMap(), LinearLayout.LayoutParams(-1, dp(560)).apply { bottomMargin = dp(12) })
        }
        leavePlace?.let { p ->
            root.addView(row(secondary("✎ 장소 이름 바꾸기") {
                askText("장소 이름", p.name) { leavePlace = p.copy(name = it); render() }
            }))
        }
        root.addView(space(8))
        root.addView(label("들려줄 범위"))
        root.addView(row(*listOf(300, 500, 1000).map { r ->
            choice(if (r < 1000) "${r}m" else "${r / 1000}km", r == leaveRadius) { leaveRadius = r; render() }
        }.toTypedArray()))
    }

    /** 「지도에서 고르기」: 누른 곳에 핀을 꽂고 그 자리를 장소로 한다. 남기기 화면에 있는 동안 같은 지도를 쓴다. */
    private fun pickMap(): org.maplibre.android.maps.MapView {
        pickShown = true
        val mv = pickMapView ?: newVectorMap().also { v ->
            pickMapView = v
            v.getMapAsync { map ->
                pickLib = map
                map.setStyle(mapStyle()) { st ->
                    st.addImage("pick", pinBitmap(ACCENT, "나"))
                    st.addSource(org.maplibre.android.style.sources.GeoJsonSource("pick"))
                    st.addLayer(org.maplibre.android.style.layers.SymbolLayer("pick", "pick").withProperties(
                        org.maplibre.android.style.layers.PropertyFactory.iconImage("pick"),
                        org.maplibre.android.style.layers.PropertyFactory.iconSize(1f / MAP_PIXEL_RATIO),
                        org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap(true)))
                    updatePickPin()
                    val c = leaveMapCenter ?: leavePlace?.let { org.maplibre.android.geometry.LatLng(it.lat, it.lon) }
                        ?: lastLoc?.let { org.maplibre.android.geometry.LatLng(it.first, it.second) } ?: org.maplibre.android.geometry.LatLng(37.5, 127.03)
                    leaveMapCenter = null
                    map.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(c, PICK_ZOOM))
                }
                map.addOnMapClickListener { at ->
                    leavePlace = Place(leavePlace?.name ?: "고른 곳", at.latitude, at.longitude)
                    render()
                    true
                }
            }
        }
        // 지도에서 길게 눌러 다시 왔으면 그 자리로
        leaveMapCenter?.let { c -> pickLib?.let { it.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(c, PICK_ZOOM)); leaveMapCenter = null } }
        updatePickPin()
        (mv.parent as? android.view.ViewGroup)?.removeView(mv)
        return mv
    }

    private fun updatePickPin() {
        val st = pickLib?.style ?: return
        st.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>("pick")?.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(
            listOfNotNull(leavePlace?.takeIf { leaveKey == MAP_PICK }?.let { org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.Point.fromLngLat(it.lon, it.lat)) })))
    }

    private fun dropPickMap() {
        pickMapView?.let { it.onPause(); it.onStop(); it.onDestroy() }
        pickMapView = null; pickLib = null
    }

    /** 3단계: 내용 (말로 적기 · 녹음 · 글) */
    private fun leaveContentStep() {
        root.addView(title("무엇을 남길까요?"))
        root.addView(label("목소리는 ${Recorder.MAX_SECONDS}초까지, 글은 ${MAX_TEXT}자까지 남길 수 있어요. 글은 기계음으로 읽어 줘요."))
        root.addView(space(12))
        // 세 가지 방법을 같은 크기 카드로. 화면 키보드는 차에서 쓰기 힘들어서 말로 적기를 맨 앞에 둔다.
        val hasText = leaveText.isNotBlank() && !recorder.hasRecording
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val tiles = listOf(
                if (dictation.listening) optionTile(R.drawable.ic_square, "다 말했어요", "누르면 듣기를 멈춰요", true) { dictation.stop() }
                else optionTile(R.drawable.ic_message, if (hasText) "말로 이어 쓰기" else "말로 적기", "말하면 글로 적어 줘요", false) { startDictation() },
                if (recorder.recording) optionTile(R.drawable.ic_square, "녹음 끝", "누르면 녹음을 마쳐요", true) { if (recordTapOk()) { recorder.stop(); render() } }
                else optionTile(R.drawable.ic_mic, if (recorder.hasRecording) "다시 녹음" else "목소리 녹음", "내 목소리 그대로 ${Recorder.MAX_SECONDS}초까지", recorder.hasRecording) { if (recordTapOk()) startRecording() },
                optionTile(R.drawable.ic_edit, if (hasText) "글 고치기" else "글로 쓰기", "화면 키보드로 적어요", hasText) {
                    askText("메시지 (${MAX_TEXT}자까지)", leaveText, multiline = true, maxLength = MAX_TEXT) { leaveText = it; recorder.clear(); render() }
                },
            )
            tiles.forEachIndexed { i, t -> addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(14) }) }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
        })
        // 지금 남길 내용 미리 보기
        when {
            dictation.listening -> root.addView(card(listOf(label("듣고 있어요 · 말을 마치면 저절로 적혀요"), big(listOf(leaveText, dictation.partial).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "…" }))))
            recorder.recording -> root.addView(card(listOf(big("● 녹음 중이에요…"))))
            recorder.hasRecording -> root.addView(previewCard())
            leaveText.isNotBlank() -> root.addView(card(listOf(label("남길 글"), big("“$leaveText”"))))
            else -> root.addView(label("셋 중 하나를 골라 남겨 주세요."))
        }
    }

    /** 4단계: 한눈에 확인하고 남기기 */
    private fun leaveConfirmStep(c: Circle) {
        val author = leaveAuthor?.takeIf { id -> c.members.any { it.id == id } }
        root.addView(title("이대로 남길까요?"))
        root.addView(space(12))
        val whenName = WHEN_OPTIONS.first { it.first == leaveWhen }.second
        fun line(k: String, v: String) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(text(k, 24f, MUTED), LinearLayout.LayoutParams(dp(200), -2))
            addView(text(v, 28f, INK, weight = 600), LinearLayout.LayoutParams(0, -2, 1f))
        }
        val reply = leaveReplyTo
        root.addView(card(listOfNotNull(
            reply?.let { line("답장", "${it.authorName}님의 「${it.placeName}」 메시지에") },
            line("언제", whenName + condSummary()),
            leavePlace?.takeIf { leaveWhen in WITH_PLACE }?.let { p ->
                if (leaveWhen == "region") line("지역", Regions.districtOf(p.lat, p.lon)?.let { fullName(it) } ?: p.name)
                else line("장소", "${p.name} · ${if (leaveRadius < 1000) "${leaveRadius}m" else "${leaveRadius / 1000}km"} 안")
            },
            line("받는 사람", if (leaveTo.isEmpty()) "모임 모두" else leaveTo.joinToString(", ") { c.nameOf(it) }),
            line("내용", if (recorder.hasRecording) "목소리 메시지" else "“$leaveText”"),
        )))
        // 받는 사람 · 시간대 · 요일 · 언제부터 · 횟수
        root.addView(row(
            secondary("받는 사람") {
                val others = c.members.filter { it.id != author }
                pickFrom("누구에게 남길까요?", listOf("모임 모두") + others.map { it.name }) { i -> leaveTo = if (i == 0) emptyList() else listOf(others[i - 1].id); render() }
            },
            secondary("시간대: ${WHEN_HOURS.first { it.first == leaveHours }.second}") {
                pickFrom("몇 시에 들려줄까요?", WHEN_HOURS.map { it.second }) { i -> leaveHours = WHEN_HOURS[i].first; render() }
            },
            secondary("요일: ${WHEN_DAYS.first { it.first == leaveDays }.second}") {
                pickFrom("무슨 요일에 들려줄까요?", WHEN_DAYS.map { it.second }) { i -> leaveDays = WHEN_DAYS[i].first; render() }
            },
        ))
        root.addView(row(
            secondary("언제부터: ${WHEN_FROM.first { it.first == leaveFrom }.second}") {
                pickFrom("언제부터 들려줄까요?", WHEN_FROM.map { it.second }) { i -> leaveFrom = WHEN_FROM[i].first; render() }
            },
            secondary("횟수: ${REPEATS.first { it.first == leaveRepeat }.second}") {
                pickFrom("몇 번 들려줄까요?", REPEATS.map { it.second }) { i -> leaveRepeat = REPEATS[i].first; render() }
            },
        ))
        root.addView(row(
            secondary("모임: ${c.name}") {
                val list = store.memberships.mapNotNull { Engine.circles[it.circleId] }
                if (list.size < 2) toast("다른 모임에도 들어가면 여기서 고를 수 있어요")
                else pickFrom("어느 모임에 남길까요?", list.map { it.name }) { i -> leaveCircleId = list[i].id; leaveAuthor = store.ownerOf(list[i].id); render() }
            },
            secondary("보내는 사람: ${if (author != null) c.nameOf(author) else "고르기"}") { pickAuthor(c) },
        ))
        val ready = author != null && !sending
        root.addView(primary(if (sending) "보내는 중…" else "남기기") { if (ready) sendLeave(c, author!!) }.apply { alpha = if (ready) 1f else 0.4f },
            LinearLayout.LayoutParams(-1, dp(120)).apply { bottomMargin = dp(8) })
        root.addView(label(if (author == null) "보내는 사람을 골라 주세요."
            else "${if (leaveTo.isEmpty()) "모임의 다른 사람" else leaveTo.joinToString(", ") { c.nameOf(it) } + "님"} 차에서 들려줘요. ${c.nameOf(author)}님 차에서는 재생되지 않아요."))
    }

    /** 「 · 밤 23~5시 · 금요일 · 1년 뒤부터 · 매번」 */
    private fun condSummary(): String = listOfNotNull(
        WHEN_HOURS.first { it.first == leaveHours }.second.takeIf { leaveHours != "any" },
        WHEN_DAYS.first { it.first == leaveDays }.second.takeIf { leaveDays != "any" },
        WHEN_FROM.first { it.first == leaveFrom }.second.takeIf { leaveFrom != "now" },
        REPEATS.first { it.first == leaveRepeat }.second,
    ).joinToString("") { " · $it" }

    private fun fromDate(key: String): String? {
        val d = java.time.LocalDate.now()
        return when (key) {
            "tomorrow" -> d.plusDays(1); "week" -> d.plusWeeks(1); "year" -> d.plusYears(1); else -> null
        }?.toString()
    }

    /**
     * 답장 남기기: 받는 사람은 원래 보낸 사람. 장소 메시지면 그 장소를 지날 때, 아니면 다음 시동 때 (시나리오 §4-1).
     */
    private fun startReply(m: PlaceMessage) {
        if (m.circleId.isNotEmpty()) leaveCircleId = m.circleId
        goTab(Screen.LEAVE)
        leaveReplyTo = m
        leaveTo = listOf(m.authorId)
        leaveAuthor = Engine.ownerOf(m)
        if (m.hasPlace && m.opensOn in WITH_PLACE) {
            leaveWhen = "place"; leaveKey = MAP_PICK
            leavePlace = Place(m.placeName, m.lat, m.lon)
            leaveMapCenter = org.maplibre.android.geometry.LatLng(m.lat, m.lon)
        } else leaveWhen = "start"
        leaveStep = leaveSteps().indexOf("content")
        render()
    }

    /**
     * 녹음 시작·끝 버튼은 같은 자리에 번갈아 그려진다. 「녹음 끝」을 두 번 누르면(또는 터치가 두 번 들어오면)
     * 두 번째 터치가 바로 「다시 녹음」이 되어 방금 녹음이 지워졌다. 바뀐 직후 잠깐은 무시한다.
     */
    private var lastRecordTap = 0L
    private fun recordTapOk(): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastRecordTap < RECORD_TAP_GAP_MS) return false
        lastRecordTap = now
        return true
    }

    /** 마이크 권한은 녹음 버튼을 누를 때만 묻고, 허락하면 바로 녹음한다. */
    private fun startRecording() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (parked != true || screen != Screen.LEAVE) return
        stopPreview()
        previewFailed = false
        // 최대 길이에 닿으면 저절로 멈춘다
        if (recorder.start { toast("${Recorder.MAX_SECONDS}초가 되어 녹음을 마쳤어요"); render() }) leaveText = "" else toast("마이크를 쓸 수 없어요")
        render()
    }

    /** 말하면 글로 적기. 적힌 글은 이어 붙이고, 고칠 땐 「글 고치기」. */
    private fun startDictation() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_STT)
            return
        }
        if (parked != true || screen != Screen.LEAVE) return
        recorder.stop(); stopPreview()
        val ok = dictation.start(
            onChange = { render() },
            onText = { t ->
                recorder.clear()
                leaveText = listOf(leaveText, t).filter { it.isNotBlank() }.joinToString(" ").take(MAX_TEXT)
            },
            onEnd = { err -> err?.let { toast(it) }; render() },
        )
        if (!ok) toast("이 차에서는 음성 인식을 쓸 수 없어요. 녹음이나 글로 남겨 주세요")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC && requestCode != REQ_STT) return
        if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (requestCode == REQ_MIC) startRecording() else startDictation()
        } else toast("마이크를 허락하지 않으면 글로만 남길 수 있어요")
    }

    // 녹음 들어 보기: 재생·일시정지, 5초 앞뒤로, 막대로 옮기기 --------------------

    private var previewSeek: android.widget.SeekBar? = null
    private var previewTime: TextView? = null
    private var previewPlayButton: Button? = null
    /** 막대를 손으로 끄는 중이면 진행 표시가 막대를 덮어쓰지 않게 */
    private var previewDragging = false
    /** 녹음 파일을 열지 못했다. 화면을 다시 그릴 때마다 같은 안내가 뜨지 않게 새로 녹음할 때까지 다시 열지 않는다. */
    private var previewFailed = false
    private val previewTick = object : Runnable {
        override fun run() {
            updatePreviewUi()
            if (preview?.isPlaying == true) root.postDelayed(this, 250)
        }
    }

    private fun previewCard() = card(listOf(
        big("목소리 메시지가 준비됐어요"),
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(navButton("⏪ 5초") { seekPreviewBy(-PREVIEW_JUMP_MS) })
            addView(navButton("") { togglePreview() }.also { previewPlayButton = it },
                LinearLayout.LayoutParams(dp(220), dp(72)).apply { marginStart = dp(14) })
            addView(navButton("5초 ⏩") { seekPreviewBy(PREVIEW_JUMP_MS) },
                LinearLayout.LayoutParams(dp(220), dp(72)).apply { marginStart = dp(14) })
            addView(text("", 24f, MUTED).apply { gravity = Gravity.END }.also { previewTime = it },
                LinearLayout.LayoutParams(0, -2, 1f))
            setPadding(0, dp(8), 0, dp(4))
        },
        android.widget.SeekBar(this).apply {
            progressTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            thumbTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            minimumHeight = dp(56)
            setPadding(dp(24), dp(16), dp(24), dp(16))
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) previewTime?.text = "${mmss(p)} / ${mmss(s.max)}"
                }
                override fun onStartTrackingTouch(s: android.widget.SeekBar) { previewDragging = true }
                override fun onStopTrackingTouch(s: android.widget.SeekBar) {
                    previewDragging = false
                    openPreview()?.seekTo(s.progress)
                    updatePreviewUi()
                }
            })
        }.also { previewSeek = it },
    )).also { openPreview(); updatePreviewUi() }

    /** 녹음 파일을 열어 둔다. 끝까지 들어도 닫지 않고 처음으로 돌려 두어 다시 듣거나 옮길 수 있게. */
    private fun openPreview(): android.media.MediaPlayer? {
        preview?.let { return it }
        if (previewFailed) return null
        val p = android.media.MediaPlayer()
        return try {
            p.setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            p.setDataSource(recorder.path)
            p.prepare()
            p.setOnCompletionListener { it.seekTo(0); updatePreviewUi() }
            preview = p
            p
        } catch (e: Exception) {
            Log.d("들어 보기 실패: $e")
            p.release()
            previewFailed = true
            toast("재생하지 못했어요")
            null
        }
    }

    private fun togglePreview() {
        if (playing != null) return // 안부가 나오는 중에는 겹쳐 틀지 않는다
        val p = openPreview() ?: return
        if (p.isPlaying) p.pause() else p.start()
        root.removeCallbacks(previewTick)
        root.post(previewTick)
    }

    private fun seekPreviewBy(ms: Int) {
        val p = openPreview() ?: return
        p.seekTo((p.currentPosition + ms).coerceIn(0, p.duration))
        updatePreviewUi()
    }

    private fun updatePreviewUi() {
        val p = preview
        val pos = p?.currentPosition ?: 0
        val dur = p?.duration ?: 0
        previewPlayButton?.text = when {
            p?.isPlaying == true -> "⏸ 일시정지"
            pos > 0 -> "▶ 계속 듣기"
            else -> "▶ 들어 보기"
        }
        if (!previewDragging) {
            previewSeek?.max = dur.coerceAtLeast(1)
            previewSeek?.progress = pos
            previewTime?.text = "${mmss(pos)} / ${mmss(dur)}"
        }
    }

    private fun mmss(ms: Int) = (ms / 1000).let { "%d:%02d".format(it / 60, it % 60) }

    private fun stopPreview() {
        if (::root.isInitialized) root.removeCallbacks(previewTick)
        preview?.release(); preview = null
    }

    private fun sendLeave(c: Circle, author: String) {
        val p = leavePlace
        if ((leaveWhen == "place" || leaveWhen == "region") && p == null) return
        val voice = recorder.hasRecording
        val body = org.json.JSONObject().put("authorId", author).put("when", leaveWhen)
            .put("kind", if (voice) "voice" else "text").put("text", if (voice) "" else leaveText)
        if (leaveWhen in WITH_PLACE && p != null) {
            val region = if (leaveWhen == "region") Regions.districtOf(p.lat, p.lon) else null
            body.put("placeName", region?.let { fullName(it) } ?: p.name).put("lat", p.lat).put("lon", p.lon).put("radiusM", leaveRadius)
            region?.let { body.put("regionCode", it.code) }
        }
        if (leaveTo.isNotEmpty()) body.put("to", org.json.JSONArray(leaveTo))
        leaveReplyTo?.let { body.put("replyTo", it.id) }
        WHEN_HOUR_RANGE[leaveHours]?.let { (f, t) -> body.put("hourFrom", f).put("hourTo", t) }
        WHEN_DAY_SET[leaveDays]?.let { body.put("days", org.json.JSONArray(it.toList())) }
        fromDate(leaveFrom)?.let { body.put("notBefore", it) }
        body.put("repeat", leaveRepeat)
        val doneName = if (leaveWhen == "place") "${p?.name}에" else "「${WHEN_OPTIONS.first { it.first == leaveWhen }.second}」에"
        if (voice) body.put("audioBase64", recorder.base64()).put("audioExt", "m4a")
        sending = true
        render()
        Api.postMessage(c.id, body, store.keyOf(c.id) ?: Api.key) { err ->
            sending = false
            if (err != null) { toast(err); render(); return@postMessage }
            toast("$doneName 남겼어요")
            leaveSpotId?.let { Engine.removeSpot(it) }
            leaveSpotId = null
            recorder.clear(); leaveText = ""
            leaveReplyTo = null; leaveTo = emptyList()
            refreshMessages()
            home()
        }
    }

    // 서클 소식: 「들었어요」, 일기, 내가 남긴 메시지 ------------------------------

    private fun openFeed() {
        go(Screen.FEED)
        loadFeed()
    }

    private fun loadFeed() = Engine.loadFeed()

    /** 모임 소식: 사람마다 카드 하나 (오늘 들은 수 · 최근 들은 것 · 오늘 일기), 아래에 내가 남긴 메시지 */
    private fun renderFeed() {
        val c = circle
        val me = store.driverId
        root.addView(title("모임 소식"))
        val list = people()
        if (list.isEmpty()) root.addView(label("아직 소식이 없어요"))
        list.chunked(2).forEach { pair ->
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                pair.forEachIndexed { i, p ->
                    addView(panel().apply {
                        addView(LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            addView(avatar(p.member.name, 72, member = p.member), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(18) })
                            addView(column().apply {
                                addView(text(p.member.name + if (p.member.title.isNotBlank() && p.member.title != p.member.name) " · ${p.member.title}" else "", 28f, INK, bold = true))
                                addView(text(if (p.todayCount > 0) "오늘 메시지 ${p.todayCount}개를 들었어요" else "오늘은 아직 조용해요", 22f, if (p.todayCount > 0) ACCENT else MUTED, weight = 600))
                            }, LinearLayout.LayoutParams(0, -2, 1f))
                        })
                        p.recent.forEach { h ->
                            val whose = if (h.authorId == me) "내" else "${c?.nameOf(h.authorId) ?: ""}님의"
                            addView(LinearLayout(context).apply {
                                orientation = LinearLayout.HORIZONTAL
                                setPadding(0, dp(8), 0, 0)
                                addView(text("$whose ${h.placeLabel} 메시지를 들었어요", 22f, if (h.authorId == me) INK else MUTED, bold = h.authorId == me), LinearLayout.LayoutParams(0, -2, 1f))
                                addView(text(whenLabel(h.lastAt), 20f, MUTED))
                            })
                        }
                        p.diary?.let { addView(text("${it.text.lines().first()}", 22f, MUTED).apply { setPadding(0, dp(10), 0, 0) }) }
                    }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(16) })
                }
                if (pair.size == 1) addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(16) })
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) }
            })
        }

        if (c != null && me != null) {
            val mine = messages.filter { it.authorId == me }
            root.addView(section(R.drawable.ic_message, "내가 남긴 메시지 ${mine.size}개"))
            mine.forEach { m ->
                val heardBy = feedHeard.filter { it.messageId == m.id }.joinToString(", ") { it.memberName }
                root.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = rounded(CARD, 1, BORDER)
                    setPadding(dp(24), dp(8), dp(12), dp(8))
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
                    val replies = messages.filter { it.replyTo == m.id }
                    val status = when {
                        replies.isNotEmpty() -> "답장 있음 (${replies.joinToString(", ") { it.authorName }})"
                        heardBy.isNotEmpty() -> "$heardBy 들음"
                        else -> "아직"
                    }
                    addView(big("${m.placeName} · ${if (m.kind == "voice") "목소리" else "글"} · $status"), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(navButton("지우기") {
                        confirm("${m.placeName} 메시지를 지울까요?", "들은 기록도 함께 지워져요.", "지우기") {
                            Api.deleteMessage(m.id, me) { ok -> if (ok) { refreshMessages(); loadFeed() } else toast("지우지 못했어요") }
                        }
                    }.apply { layoutParams = LinearLayout.LayoutParams(dp(170), dp(72)) })
                })
            }
        }
        root.addView(row(secondary("새로 고침") { refreshMessages(); loadFeed() }))
    }

    /** 가장 가까운 메시지 장소까지 거리. 계산은 차 안에서만 한다. */
    private fun nearestText(): String {
        val loc = lastLoc ?: return "위치 확인 중"
        val found = foundIds()
        val near = messages.filter { it.isPlace && !isMine(it) && it.id !in found }
            .minByOrNull { distanceM(loc.first, loc.second, it.lat, it.lon) } ?: return "위치는 이 차 안에서만 써요"
        val d = distanceM(loc.first, loc.second, near.lat, near.lon)
        val dist = if (d < 1000) "${d.toInt()}m" else "%.1fkm".format(d / 1000)
        return "가장 가까운 메시지 장소: ${near.placeName} $dist · 위치는 이 차 안에서만 써요"
    }

    private fun openMembers() {
        cars = null
        go(Screen.MEMBERS)
        loadCars()
    }

    private fun loadCars() = Api.cars { list ->
        cars = list ?: emptyList()
        if (list == null) toast("차 목록을 불러오지 못했어요")
        if (screen == Screen.MEMBERS) render()
    }

    /**
     * 모임 관리: 모임 이름·종류, 구성원(이름·호칭·얼굴 색·순서·빼기), 모임의 차(주인·이름·빼기).
     * 폰 웹의 「모임」 탭과 같은 일을 차에서 한다. 정차 중에만 열린다.
     */
    private fun renderMembers() {
        val c = circle
        root.addView(title("모임 관리"))
        if (c == null) {
            root.addView(label("서버에 연결되면 관리할 수 있어요."))
            return
        }
        root.addView(row(
            secondary("모임 이름: ${c.name}") {
                askText("모임 이름", c.name, suggestions = CIRCLE_NAME_HINTS.getValue(c.type)) { name -> Api.updateCircle(c.id, name = name, done = ::onCircleChanged) }
            },
        ))
        root.addView(label("모임 종류 · 연인·친구로 바꾸면 일기 공유가 꺼지고 일기에 장소 이름이 빠져요"))
        root.addView(row(*CIRCLE_TYPES.map { (type, name) ->
            choice(name, c.type == type) {
                Api.updateCircle(c.id, type = type) { updated, err ->
                    if (updated != null && type != "family" && store.sharingAnything) {
                        store.stopSharing()
                        store.driverId?.let { d -> Api.deleteDiaries(c.id, d) {} }
                    }
                    onCircleChanged(updated, err)
                }
            }
        }.toTypedArray()))

        root.addView(section(R.drawable.ic_people, "구성원 ${c.members.size}명"))
        root.addView(label("이 순서대로 홈 얼굴 줄에 나와요. 폰이 없는 사람도 여기서 만들 수 있어요."))
        c.members.forEachIndexed { i, m ->
            val ids = c.members.map { it.id }
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(CARD, 1, BORDER)
                setPadding(dp(24), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
                addView(avatar(m.name, 56, member = m), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(16) })
                addView(column().apply {
                    addView(big("${i + 1}. ${m.name}" + if (m.title.isNotBlank() && m.title != m.name) " · ${m.title}" else ""))
                    if (m.id == store.driverId) addView(label("이 차 주인"))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                fun small(s: String, enabled: Boolean = true, onClick: () -> Unit) = navButton(s, onClick).apply {
                    isEnabled = enabled; alpha = if (enabled) 1f else 0.35f
                    layoutParams = LinearLayout.LayoutParams(dp(150), dp(72)).apply { marginStart = dp(10) }
                }
                addView(small("▲", i > 0) { Api.reorderMembers(c.id, ids.toMutableList().apply { add(i - 1, removeAt(i)) }, ::onCircleChanged) })
                addView(small("▼", i < ids.lastIndex) { Api.reorderMembers(c.id, ids.toMutableList().apply { add(i + 1, removeAt(i)) }, ::onCircleChanged) })
                addView(small("이름") { askText("${m.name} 이름 바꾸기", m.name) { name -> Api.renameMember(c.id, m.id, name, ::onCircleChanged) } })
                addView(small("호칭") {
                    askText("${m.name} 님의 호칭 (비우면 빼요)", m.title, suggestions = TITLE_HINTS.getValue(c.type), allowBlank = true) { t -> Api.setMemberTitle(c.id, m.id, t, ::onCircleChanged) }
                })
                addView(small("색") { pickColor("${m.name} 님의 얼굴 색", m.color) { color -> Api.setMemberColor(c.id, m.id, color, ::onCircleChanged) } })
                if (m.photoUrl != null) addView(small("사진 빼기") {
                    confirm("${m.name} 님 사진을 뺄까요?", "얼굴은 이름 첫 글자와 색으로 보여요.", "빼기") { Api.deletePhoto(c.id, m.id, ::onCircleChanged) }
                })
                addView(small("빼기", c.members.size > 1) {
                    confirm("${m.name}님을 모임에서 뺄까요?", "${m.name}님이 남긴 메시지·목소리·사진·일기도 함께 지워져요.") {
                        Api.deleteMember(c.id, m.id) { updated, err ->
                            if (updated != null && store.driverId == m.id) Engine.setDriver(null)
                            onCircleChanged(updated, err)
                            refreshMessages()
                            loadCars()
                        }
                    }
                })
            })
        }
        root.addView(primary("+ 구성원 추가") { askText("새 구성원 이름", "") { name -> Api.addMember(c.id, name, ::onCircleChanged) } })

        root.addView(section(R.drawable.ic_car, "모임의 차"))
        root.addView(label("차는 한 사람의 차예요. 그 차에서는 주인에게 온 메시지가 나오고, 주인이 남긴 메시지는 나오지 않아요. 새 차는 그 차 화면에서 초대 코드로 들어와요."))
        val list = cars
        when {
            list == null -> root.addView(label("불러오는 중…"))
            list.isEmpty() -> root.addView(label("아직 들어온 차가 없어요"))
        }
        list?.forEach { car ->
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(CARD, 1, BORDER)
                setPadding(dp(24), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
                addView(icon(R.drawable.ic_car, 44, if (car.me) ACCENT else MUTED), LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(18) })
                addView(column().apply {
                    addView(big(car.name.ifBlank { "이름 없는 차" } + if (car.me) "  (이 차)" else ""))
                    addView(label("주인: ${car.ownerId?.let { c.nameOf(it) } ?: "아직 없음"}"))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                fun small(s: String, onClick: () -> Unit) = navButton(s, onClick).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(170), dp(72)).apply { marginStart = dp(10) }
                }
                addView(small("주인") {
                    if (car.me) pickOwner()
                    else pickFrom("${car.name}의 주인", c.members.map { it.name }) { i ->
                        Api.setCarOwner(car.id, c.members[i].id) { ok -> if (!ok) toast("바꾸지 못했어요"); loadCars() }
                    }
                })
                addView(small("이름") {
                    askText("차 이름", car.name) { name ->
                        if (car.me) Engine.renameCar(name) { ok -> if (!ok) toast("바꾸지 못했어요"); loadCars() }
                        else Api.renameOtherCar(car.id, name) { ok -> if (!ok) toast("바꾸지 못했어요"); loadCars() }
                    }
                })
                addView(small("빼기") {
                    if (car.me) confirm("이 차를 ${c.name}에서 뺄까요?", "다시 쓰려면 초대 코드로 들어오면 돼요. 모임 사람들이 남긴 메시지는 지워지지 않아요.", "연결 끊기") {
                        Engine.unlink { ok -> if (!ok) toast("서버에 연결하지 못했어요") else { history.clear(); screen = Screen.HOME; render() } }
                    }
                    else confirm("${car.name}을(를) 모임에서 뺄까요?", "그 차는 처음 화면으로 돌아가고, 다시 쓰려면 초대 코드로 들어와야 해요.", "빼기") {
                        Api.removeCar(car.id) { ok -> if (!ok) toast("빼지 못했어요"); loadCars() }
                    }
                })
            })
        }
    }

    private fun onCircleChanged(updated: Circle?, error: String?) {
        if (updated != null) Engine.circle = updated else toast(error ?: "바꾸지 못했어요")
        render()
    }

    private fun refreshMessages() = Engine.refreshMessages()

    /**
     * 정차 중에만 쓰는 글자 입력 창. suggestions를 주면 아래에 누르기만 하면 되는 보기를 둔다
     * (차 화면 키보드로 한글을 치기 번거로워서). allowBlank면 비워 두고 넘어갈 수 있다.
     */
    private fun askText(title: String, initial: String, multiline: Boolean = false, maxLength: Int = 40,
                        suggestions: List<String> = emptyList(), allowBlank: Boolean = false, okLabel: String = "확인", onOk: (String) -> Unit) {
        if (parked != true) return
        val input = EditText(this).apply {
            filters = arrayOf(android.text.InputFilter.LengthFilter(maxLength))
            setText(initial.take(maxLength)); setSelection(text.length); textSize = 24f; setTextColor(INK)
            if (multiline) { minLines = 3; maxLines = 5 } else isSingleLine = true
        }
        val view = if (suggestions.isEmpty()) input else column().apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
            addView(android.widget.HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                setPadding(0, dp(12), 0, dp(4))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    suggestions.forEach { s ->
                        addView(navButton(s) { input.setText(s); input.setSelection(input.text.length) },
                            LinearLayout.LayoutParams(-2, dp(72)).apply { marginEnd = dp(10) })
                    }
                })
            })
        }
        AlertDialog.Builder(this, dialogTheme()).setTitle(title).setView(view)
            .setPositiveButton(okLabel) { _, _ -> input.text.toString().trim().let { if (it.isNotEmpty() || allowBlank) onOk(it) } }
            .setNegativeButton("취소", null).show().also { dialog = it }
    }

    private fun confirm(title: String, message: String, ok: String = "빼기", onOk: () -> Unit) {
        if (parked != true) return
        AlertDialog.Builder(this, dialogTheme()).setTitle(title).setMessage(message)
            .setPositiveButton(ok) { _, _ -> onOk() }
            .setNegativeButton("취소", null).show().also { dialog = it }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun sharedItems() = listOfNotNull(
        "이동 거리".takeIf { store.shareDistance },
        "쉬어간 횟수".takeIf { store.shareRests },
        "들은 메시지".takeIf { store.shareHeard },
        "비 · 밤길".takeIf { store.shareWeather },
    )

    // 뷰 도우미 ------------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Asta Sans(Pleos 시스템 UI 글꼴, OFL). 가변 글꼴이라 굵기별로 만들어 둔다. */
    private fun font(weight: Int): Typeface = FONTS.getOrPut(weight) {
        runCatching { Typeface.Builder(assets, "fonts/AstaSans.ttf").setFontVariationSettings("'wght' $weight").build() }
            .getOrDefault(if (weight >= 600) Typeface.DEFAULT_BOLD else Typeface.DEFAULT)
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false, weight: Int = if (bold) 700 else 400) = TextView(this).apply {
        text = s; textSize = sp; setTextColor(color); typeface = font(weight)
        setLineSpacing(0f, 1.2f)
        setPadding(0, dp(4), 0, dp(4))
    }

    // 글자 크기는 Pleos 글자 단계를 따른다: Title 36·32 / Body 28·26 / Label 24·20 (이 화면은 1sp = 1px)
    private fun title(s: String) = text(s, 36f, INK, bold = true)
    private fun big(s: String) = text(s, 28f, INK)
    private fun label(s: String) = text(s, 22f, MUTED)
    private fun space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    private fun rounded(color: Int, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(16).toFloat()
        if (stroke > 0) setStroke(dp(stroke), strokeColor)
    }

    /** 누르면 살짝 어두워지는 바탕 */
    private fun pressable(bg: android.graphics.drawable.Drawable) =
        android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(PRESSED), bg, null)

    private fun card(children: List<View>) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD, 1, BORDER)
        setPadding(dp(28), dp(20), dp(28), dp(20))
        children.forEach { addView(it) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
    }

    private fun button(s: String, bg: android.graphics.drawable.Drawable, fg: Int, onClick: () -> Unit) = Button(this).apply {
        text = s; textSize = 26f; setTextColor(fg); isAllCaps = false; typeface = font(600)
        background = pressable(bg); minHeight = dp(88)
        stateListAnimator = null; elevation = 0f // 기본 버튼 그림자 없이 평평하게
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(96)).apply { bottomMargin = dp(14) }
    }

    private fun primary(s: String, onClick: () -> Unit) = button(s, rounded(ACCENT), Color.WHITE, onClick)
    private fun secondary(s: String, onClick: () -> Unit) = button(s, rounded(SOFT), INK, onClick)

    /** 여럿 중 하나 고르기. 고른 것은 강조색 테두리, 나머지는 테두리만 옅게. */
    private fun choice(s: String, selected: Boolean, onClick: () -> Unit) =
        if (selected) button("✓ $s", rounded(CHIP, 2, ACCENT), ACCENT) {}
        else button(s, rounded(CARD, 1, BORDER), INK, onClick)

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, dp(96), 1f).apply { if (i > 0) marginStart = dp(14); bottomMargin = dp(14) })
        }
    }

    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun toggle(s: String, on: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = s; textSize = 26f; setTextColor(INK); isChecked = on; typeface = font(400)
        gravity = Gravity.CENTER_VERTICAL; minHeight = dp(80)
        // 켜짐은 강조색 바탕에 흰 손잡이, 꺼짐은 회색 (Pleos switch 색, 기기 기본 보라색 대신)
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = android.content.res.ColorStateList(states, intArrayOf(Color.WHITE, MUTED))
        trackTintList = android.content.res.ColorStateList(states, intArrayOf(ACCENT, SOFT))
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    private fun dialogTheme() =
        if (NIGHT) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

    /** 잠그면 안의 화면을 보이는 높이에 딱 맞추고 스크롤하지 않는 ScrollView */
    class PageScroll(context: android.content.Context) : ScrollView(context) {
        var locked = false
            set(v) { if (field != v) { field = v; requestLayout() } }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (!locked) return super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            setMeasuredDimension(w, h)
            getChildAt(0)?.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        }

        override fun onInterceptTouchEvent(ev: android.view.MotionEvent) = if (locked) false else super.onInterceptTouchEvent(ev)
        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: android.view.MotionEvent) = if (locked) false else super.onTouchEvent(ev)
    }

    companion object {
        const val EXTRA_AUTO = "auto"
        private const val NAVI_PACKAGE = "ai.umos.maps.android.navigation.app"
        private const val REQ_NOTI = 2
        private const val KEY_SCREEN = "screen"
        /** 들어 보기에서 앞뒤로 옮기는 길이 */
        private const val PREVIEW_JUMP_MS = 5000
        /** 녹음 버튼이 바뀐 뒤 이 시간 안의 터치는 무시 */
        private const val RECORD_TAP_GAP_MS = 1000L
        /** 남기기 장소: 「지도에서 고르기」를 골랐다는 표시 (leaveKey) */
        private const val MAP_PICK = "_map_pick"
        /** 모임 이름 보기 */
        private val CIRCLE_NAME_HINTS = mapOf(
            "family" to listOf("우리집", "우리 가족", "본가", "외가"),
            "friends" to listOf("우리 모임", "친구들", "동창 모임", "동네 친구"),
            "couple" to listOf("우리 둘", "우리"),
        )
        /** 호칭 보기 */
        private val TITLE_HINTS = mapOf(
            "family" to listOf("아버지", "어머니", "아들", "딸", "할아버지", "할머니", "형", "누나", "오빠", "언니", "동생"),
            "friends" to listOf("친구", "형", "누나", "오빠", "언니", "동생"),
            "couple" to listOf("자기", "여보"),
        )
        /** 서버에 닿지 않아 처리방침을 못 받았을 때 보여 줄 요약 */
        private const val PRIVACY_FALLBACK = "<meta name='viewport' content='width=device-width'><body style='font-family:sans-serif;font-size:20px;line-height:1.6'>" +
            "<h3>개인정보 처리방침 요약</h3><ul>" +
            "<li>받는 것: 모임·구성원 이름과 호칭·얼굴 색, 메시지 글과 목소리, 메시지 장소, 「들었어요」 기록, 공유를 켠 일기 항목, 차 고유번호의 해시값과 차 이름</li>" +
            "<li>받지 않는 것: 차의 현재 위치와 다닌 길, 옆자리에 누가 탔는지, 전화번호·이메일·비밀번호</li>" +
            "<li>같은 모임 사람만 볼 수 있고, 다른 사람에게 주거나 광고에 쓰지 않아요</li>" +
            "<li>차 설정과 모임 관리에서 언제든 지울 수 있어요</li></ul>" +
            "<p>서버에 연결되면 전체 내용을 볼 수 있어요.</p></body>"
        private val THEMES =listOf("auto" to "차 설정 따르기", "day" to "밝게", "night" to "어둡게")
        private val CIRCLE_TYPES =listOf("family" to "가족", "couple" to "연인", "friends" to "친구")
        private const val REQ_MIC = 1
        private const val REQ_STT = 3
        private const val HERE = "지금 이 자리"
        /** 전국 보기 */
        private val KOREA = org.maplibre.android.geometry.LatLngBounds.from(38.7, 130.0, 33.0, 124.6)
        /** 서버도 글을 300자에서 자른다 */
        private const val MAX_TEXT = 300
        /** 메시지가 열리는 조건 (폰 웹·서버와 같은 키) */
        private val WHEN_OPTIONS = listOf(
            "place" to "이 장소를 지나면",
            "rain" to "비 오는 날",
            "night" to "밤길",
            "together" to "옆자리에 누가 탄 날",
            "door" to "차에 탈 때",
            "start" to "시동 걸 때",
            "park" to "도착해 차를 세울 때",
            "long" to "오래 운전할 때",
            "region" to "그 지역에 들어가면",
            "warning" to "경고등이 켜질 때",
        )
        /** 장소를 함께 고르는 조건 */
        private val WITH_PLACE = setOf("place", "park", "region")
        private val WHEN_HOURS = listOf("any" to "언제든", "morning" to "아침 6~10시", "evening" to "저녁 17~21시", "night" to "밤 23~5시")
        private val WHEN_HOUR_RANGE = mapOf("morning" to (6 to 10), "evening" to (17 to 21), "night" to (23 to 5))
        private val WHEN_DAYS = listOf("any" to "매일", "weekday" to "평일", "weekend" to "주말", "mon" to "월요일", "fri" to "금요일")
        private val WHEN_DAY_SET = mapOf("weekday" to setOf(1, 2, 3, 4, 5), "weekend" to setOf(6, 7), "mon" to setOf(1), "fri" to setOf(5))
        private val WHEN_FROM = listOf("now" to "지금부터", "tomorrow" to "내일부터", "week" to "다음 주부터", "year" to "1년 뒤부터")
        private val REPEATS = listOf("once" to "한 번", "always" to "매번", "weekly" to "주 1회까지")
        private val WHEN_ICONS = mapOf("place" to R.drawable.ic_pin, "rain" to R.drawable.ic_rain, "night" to R.drawable.ic_moon, "together" to R.drawable.ic_people, "door" to R.drawable.ic_door,
            "start" to R.drawable.ic_play, "park" to R.drawable.ic_home, "long" to R.drawable.ic_car, "region" to R.drawable.ic_map, "warning" to R.drawable.ic_shield)
        private val WHEN_HINTS = mapOf(
            "place" to "고른 장소 근처를 지날 때",
            "rain" to "와이퍼가 움직이는 날",
            "night" to "전조등을 켠 밤길",
            "together" to "조수석 탑승이 감지될 때",
            "door" to "운전석 문을 열고 탈 때",
            "start" to "차가 켜지고 처음 기어를 넣을 때",
            "park" to "그곳(또는 어디서든)에서 P로 세울 때",
            "long" to "쉬지 않고 2시간 넘게 달릴 때",
            "region" to "고른 곳의 시·군·구에 들어갈 때",
            "warning" to "계기판 경고등이 켜질 때",
        )
        private val STEP_NAMES =mapOf("when" to "언제", "place" to "어디서", "content" to "내용", "confirm" to "확인")
        /** 폰 웹과 같은 예시 장소 */
        private val PRESETS = listOf(
            Place("양평 국수집길", 37.4917, 127.4873),
            Place("양재 사거리", 37.48544722, 127.03636666),
            Place("강남역", 37.497952, 127.027619),
            Place("해운대 바닷길", 35.1587, 129.1604),
        )
        // 색: Pleos Connect 디자인 가이드의 basic 회색 단계 + 강조는 현대 블루(#002C5F, 야간은 Pleos informative_positive).
        // 주간·야간은 차(시스템) 설정을 따르고, 설정 화면에서 밝게·어둡게로 고정할 수 있다 (applyTheme).
        private var NIGHT = false
        private var BG = 0
        private var CARD = 0
        private var SOFT = 0
        private var INK = 0
        private var MUTED = 0
        private var ACCENT = 0
        private var DIARY_BG = 0
        private var CHIP = 0
        private var BORDER = 0
        /** 프로필 색 (서버·폰 웹과 같은 이름) */
        private val MEMBER_COLOR_NAMES = listOf("blue", "green", "orange", "purple", "pink", "teal", "red", "gray")
        private var MEMBER_COLORS: Map<String, Int> = emptyMap()
        private var GREEN = 0
        private var BLUE = 0
        private var PRESSED = 0
        private val FONTS = HashMap<Int, Typeface>()

        private fun applyTheme(night: Boolean) {
            NIGHT = night
            fun c(day: String, dark: String) = Color.parseColor(if (night) dark else day)
            BG = c("#F7F8FA", "#131417")       // background_primary (basic_50 / basic_00)
            CARD = c("#FFFFFF", "#1D1E21")     // surface
            SOFT = c("#EDEEF2", "#313236")     // 보조 버튼 (basic_100)
            INK = c("#131417", "#F7F8FA")      // 본문 (basic_900 / basic_800)
            MUTED = c("#686A72", "#A4A8AE")    // 보조 글 (basic_500 / basic_400)
            ACCENT = c("#002C5F", "#0082FF")   // 현대 블루 / 야간 informative_positive
            DIARY_BG = c("#FFFFFF", "#1D1E21")
            CHIP = c("#E5ECF5", "#1B2B42")     // 현대 블루를 옅게
            BORDER = c("#E0E1E6", "#313236")   // 카드 테두리 (basic_200 / basic_100)
            GREEN = c("#02C265", "#00E074")    // informative_active
            MEMBER_COLORS = mapOf(
                "blue" to c("#0064FF", "#4D94FF"), "green" to c("#02A15A", "#3DD68C"), "orange" to c("#E06A00", "#FF9F45"),
                "purple" to c("#6B4EE6", "#A08CFF"), "pink" to c("#D63A7C", "#FF7FB2"), "teal" to c("#008C8C", "#33CCCC"),
                "red" to c("#D93636", "#FF7070"), "gray" to c("#686A72", "#A4A8AE"),
            )
            BLUE = c("#0064FF", "#0082FF")     // informative_positive
            PRESSED = c("#1F000000", "#33FFFFFF")
        }

        /** 왼쪽 메뉴 줄: 화면 · 선 아이콘 · 이름. 메인은 안부 지도. 설정은 맨 아래로 떨어진다. */
        private val RAIL = listOf(
            Triple(Screen.HOME, R.drawable.ic_map, "메인"),
            Triple(Screen.TODAY, R.drawable.ic_home, "오늘"),
            Triple(Screen.LEAVE, R.drawable.ic_edit, "메시지"),
            Triple(Screen.FEED, R.drawable.ic_people, "소식"),
            Triple(Screen.SETTINGS, R.drawable.ic_settings, "설정"),
        )
        /** 모임 색 (모임마다 하나, 들어간 순서대로) */
        private val CIRCLE_COLOR_NAMES = listOf("blue", "orange", "green", "purple", "pink", "teal", "red")
        /** 지도 단계 경계 (확대 수준) */
        /** 지도 글자 크기 배율 */
        private const val MAP_PIXEL_RATIO = 1.5f
        /** 「지도에서 고르기」를 열 때 확대 (동네 길이 보이는 정도) */
        private const val PICK_ZOOM = 12.5
        private const val LEVEL_DISTRICT_ZOOM = 7.6
        private const val LEVEL_PLACE_ZOOM = 10.2

        fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * r * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}
