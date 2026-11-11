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
 * 다온(DAON) 차량 앱 화면. 정차 중 설정·입력과 재생 화면만 맡는다.
 * - 기어·위치·근처 계산·재생은 Engine(AnbuService)이 뒤에서 한다. 운전 중엔 내비가 앞에 있어도 된다.
 * - 운전 중에는 정보만 보여 주고 조작 화면은 열지 않는다.
 */
class MainActivity : Activity(), Engine.Ui {

    /** HOME은 메인 지도. TODAY는 예전 홈(모임 얼굴 줄·모아 듣기·기다리는 메시지·소식 요약) */
    private enum class Screen { HOME, TODAY, PLAY, SETTINGS, MEMBERS, LEAVE, FEED, DEMO, GROUPS }

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
    /** 시간대 (WHEN_HOURS의 키) · 요일 (WHEN_DAYS) · 언제부터 (WHEN_FROM) · 언제까지 (WHEN_UNTIL) · 횟수 (once·always·weekly) */
    private var leaveHours = "any"
    private var leaveDays = "any"
    private var leaveFrom = "now"
    private var leaveUntil = "forever"
    private var leaveRepeat = "once"
    private var leaveText = ""
    /** 남기기 단계 (leaveSteps의 몇 번째인지) */
    private var leaveStep = 0
    /** 한 화면 남기기에서 장소 고르기를 열어 둔 동안 */
    private var leavePickingPlace = false
    /** 한 화면 남기기의 「자세히」(시간·요일·기간·횟수)를 펼쳤는지 */
    private var leaveMoreOpen = false
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
    /** 설정의 「초대」: 사람마다 내 코드 */
    private var invitees: List<Api.Invitee>? = null
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
    /** 정차 중 메인을 하늘 배경·유리 지도(시안 5안)로 그린다 */
    private val SKY_HOME = true
    /** 메인 외 화면의 하늘 바탕 위 막 (메인은 투명) */
    private var skyVeil: View? = null
    /** 상황별 안부 칸 전체를 펼쳤는지 (처음엔 한 줄 요약만) */
    private var conditionsExpanded = false
    /** 들은 안부: 고른 칸 (all 전체 · cond 상황별 · place 장소) · 다 펼쳤는지 */
    private var heardTab = "all"
    private var heardMore = false
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
        // 알림·위치 권한 (위치는 근처 안부 판단에만 쓰고 서버로 보내지 않는다)
        val need = listOfNotNull(
            android.Manifest.permission.POST_NOTIFICATIONS.takeIf { Build.VERSION.SDK_INT >= 33 && checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED },
            android.Manifest.permission.ACCESS_FINE_LOCATION.takeIf { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED },
        )
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), REQ_NOTI)

        val systemNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        applyTheme(when (store.theme) { "day" -> false; "night" -> true; else -> systemNight })
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(40), dp(28), dp(40), dp(28)) }
        rail =LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (SKY_SKIN) background = GradientDrawable().apply {
                setColor(if (NIGHT) Color.argb(150, 14, 20, 50) else Color.argb(150, 255, 255, 255))
                setStroke(1, if (NIGHT) Color.argb(40, 255, 255, 255) else Color.argb(200, 255, 255, 255))
            } else setBackgroundColor(CARD)
            setPadding(dp(12), dp(28), dp(12), dp(28))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            if (!SKY_SKIN) setBackgroundColor(BG)
            addView(rail, LinearLayout.LayoutParams(dp(150), -1))
            // 내용이 짧으면 화면 높이를 꽉 채운다 (홈은 카드가 남은 높이를 나눠 갖는다)
            addView(PageScroll(this@MainActivity).apply { isFillViewport = true; isScrollbarFadingEnabled = false; isVerticalScrollBarEnabled = true; addView(root, android.widget.FrameLayout.LayoutParams(-1, -1)) }.also { page = it },
                LinearLayout.LayoutParams(0, -1, 1f))
        }
        // 하늘 스킨: 모든 화면이 메인과 같은 하늘(주간·야간) 위에 유리 카드로 올라간다
        setContentView(if (!SKY_SKIN) content else android.widget.FrameLayout(this).apply {
            addView(android.widget.ImageView(context).apply {
                setImageResource(if (NIGHT) R.drawable.home_sky_night else R.drawable.home_sky_day); scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            }, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(View(context).also { skyVeil = it }, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(content, android.widget.FrameLayout.LayoutParams(-1, -1))
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
        stopMine()
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
        // 화면 띄우기를 꺼 두면 소리만. 메인 지도는 새로 그리지 않고 오른쪽 칸만 고친다 (확대해 둔 지도가 풀리지 않게)
        if (Engine.quietPlay) { changed(); return }
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
        Engine.uiBusy = screen != Screen.HOME && screen != Screen.PLAY && screen != Screen.DEMO // 시연 메뉴에서는 열리면 바로 들려준다
        playKey = null // renderPlay가 다시 정한다
        pickShown = false
        if (screen != Screen.LEAVE) dropPickMap()
        mainSide = null; mapLevelView = null
        if (playing != null) stopPreview()
        root.removeAllViews()
        sky = null
        skyVeil?.setBackgroundColor(when {
            screen == Screen.HOME && parked == true && !Engine.needPairing -> Color.TRANSPARENT
            NIGHT -> Color.argb(110, 8, 12, 32)
            else -> Color.argb(120, 255, 255, 255)
        })
        root.setPadding(dp(40), dp(28), dp(40), dp(28))
        // 메인 지도는 아래로 스크롤하지 않는다 (지도·오른쪽 칸이 화면 높이를 나눠 갖고, 오른쪽 칸만 따로 스크롤)
        page.locked = (screen == Screen.HOME || screen == Screen.PLAY) && parked == true && !Engine.needPairing
        if (page.locked) page.scrollTo(0, 0)
        if (Engine.needPairing) { rail.visibility = View.GONE; renderPair(); return }
        val railShown = renderRail()
        // 홈이 아닌 화면에는 늘 맨 위에 뒤로 가기를 둔다 (메뉴 줄이 있어도). 입력 중인 화면엔 취소도.
        val tabScreen = screen in setOf(Screen.TODAY, Screen.FEED, Screen.SETTINGS, Screen.DEMO, Screen.GROUPS) || (SKY_SKIN && screen == Screen.LEAVE)
        if (screen != Screen.HOME && !(SKY_SKIN && railShown && tabScreen)) root.addView(navBar(withHome = !railShown))
        when (screen) {
            Screen.HOME -> renderHome()
            Screen.TODAY -> renderHome() // 「오늘」은 메인(오늘 온 안부)과 소식(발견한 안부)으로 나눴다
            Screen.PLAY -> renderPlay()
            Screen.SETTINGS -> renderSettings()
            Screen.MEMBERS -> renderMembers()
            Screen.LEAVE -> renderLeave()
            Screen.FEED -> renderFeed()
            Screen.DEMO -> renderDemo()
            Screen.GROUPS -> renderGroups()
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
        if (withHome) addView(navButton("⌂ 홈") { home() }, (if (SKY_SKIN) LinearLayout.LayoutParams(-2, dp(64)) else LinearLayout.LayoutParams(dp(220), dp(72))).apply { marginStart = dp(14) })
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
        // 예전 모임 관리 화면은 그룹 메뉴에 속한다
        val current = if (screen == Screen.MEMBERS) Screen.GROUPS else screen
        (if (BuildConfig.STANDALONE) RAIL.dropLast(1) + DEMO_RAIL + RAIL.last() else RAIL).forEach { (s, icon, name) ->
            if (s == Screen.SETTINGS) rail.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f)) // 설정은 맨 아래
            rail.addView(railItem(icon, name, s == current) { goTab(s) })
        }
        return true
    }

    private fun railItem(res: Int, name: String, selected: Boolean, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        if (selected) background = if (SKY_SKIN) GradientDrawable().apply {
            setColor(if (NIGHT) Color.argb(200, 40, 54, 110) else Color.WHITE); cornerRadius = dp(24).toFloat()
        } else rounded(SOFT)
        if (selected && SKY_SKIN) { elevation = dp(4).toFloat(); if (Build.VERSION.SDK_INT >= 28) { outlineSpotShadowColor = Color.parseColor("#5A6E96"); outlineAmbientShadowColor = Color.parseColor("#5A6E96") } }
        setPadding(0, dp(18), 0, dp(14))
        val color = if (selected) ACCENT else MUTED
        addView(icon(res, 44, color), LinearLayout.LayoutParams(dp(44), dp(44)).apply { bottomMargin = dp(6) })
        addView(text(name, 24f, color, bold = selected).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, 0) })
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
            Screen.GROUPS -> { invitees = null; cars = null; loadCars() }
            else -> {}
        }
        screen = next
        render()
        if (next == Screen.HOME) Engine.maybeAutoPlay()
    }

    private fun navButton(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s; textSize = if (SKY_SKIN) 20f else 22f; setTextColor(INK); isAllCaps = false; typeface = font(600)
        background = pressable(if (SKY_SKIN) GradientDrawable().apply {
            setColor(if (NIGHT) Color.argb(190, 22, 31, 72) else Color.argb(215, 255, 255, 255)); cornerRadius = dp(40).toFloat()
            setStroke(1, if (NIGHT) Color.argb(50, 255, 255, 255) else Color.WHITE)
        } else rounded(SOFT)); stateListAnimator = null; elevation = 0f
        if (SKY_SKIN) { minWidth = 0; minimumWidth = 0; setPadding(dp(30), 0, dp(34), 0) }
        setOnClickListener { onClick() }
        layoutParams = if (SKY_SKIN) LinearLayout.LayoutParams(-2, dp(64)) else LinearLayout.LayoutParams(dp(220), dp(72))
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
        if (SKY_SKIN) { renderTodaySky(); return }

        root.addView(greeting())
        root.addView(space(16))
        // 가족 얼굴 줄은 두지 않는다 (얻는 정보가 없고, 모임이 여럿일 수 있다). 오늘 들을 것 → 지도 → 모임 소식 순서.
        val left = column().apply {
            addView(inboxCard(), panelParams())
            addView(waitingPanel(), panelParams())
            addView(mapCard(), LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, -2, 3f))
            addView(feedCard(), LinearLayout.LayoutParams(0, -1, 2f).apply { marginStart = dp(16) })
        }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun panelParams() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) }

    /** 홈 카드 바탕 */
    private fun panel(orientation: Int = LinearLayout.VERTICAL) = LinearLayout(this).apply {
        this.orientation = orientation
        background = pressable(rounded(CARD, 1, BORDER))
        setPadding(dp(28), dp(22), dp(28), dp(22))
        if (SKY_SKIN) cardShadow(this)
    }

    /** 하늘 스킨의 카드 그림자: 메인과 같은 푸른 그림자를 옅게 */
    private fun cardShadow(v: View) {
        v.elevation = dp(5).toFloat()
        if (Build.VERSION.SDK_INT >= 28) { val c = if (NIGHT) Color.BLACK else Color.parseColor("#5A6E96"); v.outlineSpotShadowColor = c; v.outlineAmbientShadowColor = c }
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
            addView(text(if (driver != null) "$driver, $hello" else "안녕하세요", 36f, INK, bold = true))
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

    /** 옆자리에 누가 있어 보낸 사람을 가리는 중인지 */
    private fun discreet() = Engine.passengerNow && store.askWithPassenger && pending.isNotEmpty()

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
        addView(primary("▶ 지금 듣기") { Engine.startPlayback(announce = false) }, LinearLayout.LayoutParams(dp(260), dp(100)).apply { marginStart = dp(16) })
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
        val others = messages.filter { !isMine(it) && (it.to.isEmpty() || Engine.ownerOf(it) in it.to) && (viewCircle == null || it.circleId == viewCircle) }
        val ids = foundIds()
        return others.count { it.id in ids } to others.size
    }
    private fun myPlaces() = messages.filter { it.isPlace && isMine(it) && (viewCircle == null || it.circleId == viewCircle) }

    /** 사람별 요약 한 줄 (소식 카드·소식 화면) */
    private data class Person(val member: Member, val todayCount: Int, val line: String, val whenText: String, val key: String, val recent: List<Api.Heard>, val diary: Api.Diary?)

    /** cc: 사람을 보여 줄 모임. 소식(들은 기록·일기)은 관리 중인 모임 것만 받아 두므로, 다른 모임이면 사람만 보인다 */
    private fun people(cc: Circle? = circle): List<Person> {
        val c = cc ?: return emptyList()
        val active = c.id == circle?.id
        val me = if (active) store.driverId else store.ownerOf(c.id)
        return c.members.filter { it.id != me }.map { m ->
            val hs = if (!active) emptyList() else feedHeard.filter { it.memberId == m.id }.sortedByDescending { it.lastAt }
            val diary = if (!active) null else feedDiaries.firstOrNull { it.memberName == m.name && it.date == store.today }
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
        addView(text(driver ?: store.carName.ifBlank { "이 차" }, 22f, ACCENT, bold = true).apply {
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
        // 달리는 중에도 말로 남기는 법은 늘 보인다 (후진·중립일 때는 빼고)
        if (Engine.gear == CarSignals.Gear.D) root.addView(voiceGuide(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
    }

    /**
     * 처음 화면: 이 차가 아직 모임에 들어가지 않았을 때 (처음 켠 차, 또는 이 차를 모임에서 뺐을 때).
     * 로그인 없이 「새 모임 만들기」 또는 「초대 코드로 들어가기」. 들어가기 전에는 다른 화면을 열지 않는다.
     */
    private fun renderPair() {
        root.addView(space(24))
        root.addView(text("다온을 시작해요", 44f, INK, bold = true))
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
            addView(navButton("← 나중에 할게요") { if (BuildConfig.STANDALONE) store.demoSkipped = true; openNavi() }, LinearLayout.LayoutParams(dp(320), dp(80)))
            if (BuildConfig.STANDALONE) addView(navButton("시연 가족으로 시작") { store.demoSkipped = false; Engine.joinDemo { render() } },
                LinearLayout.LayoutParams(dp(320), dp(80)).apply { marginStart = dp(16) })
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
                    askText("$myName 님의 호칭 (선택)", "", suggestions = TITLE_HINTS.getValue(type), allowBlank = true, okLabel = "다음", skipLabel = "건너뛰기") { title ->
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

    /** 초대 코드로 들어가기: 모임 사람에게 받은 「내 코드」를 넣으면 바로 내 차로 들어온다 (주인을 고르지 않는다) */
    private fun startJoin() = askText("내 코드 (모임 사람에게 받은 6자리)", "") { code ->
        showPrivacy("동의하고 들어가기") {
            // 다른 사람이 타면 앞 사람 시연 안내(「○○의 안부가 열렸어요」)도 걷는다
            stopScenario(silent = true)
            Engine.joinCircle(code) { err ->
                if (err != null) toast(err) else toast("「${circle?.name}」에 ${store.driverId?.let { circle?.nameOf(it) }?.let { "${it}님 차로 " } ?: ""}들어왔어요")
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
            loadUrl(if (BuildConfig.STANDALONE) "file:///android_asset/privacy.html" else BuildConfig.SERVER_URL + "/privacy.html")
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
                addView(avatar(m.authorName, if (SKY_SKIN) 120 else 104, member = c?.member(m.authorId)), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(22) })
                addView(column().apply {
                    addView(text(m.authorName, if (SKY_SKIN) 46f else 38f, INK, weight = if (SKY_SKIN) 800 else 700))
                    addView(circleTag(m.circleId))
                })
            })
            addView(space(22))
            if (!SKY_SKIN) addView(text(if (m.opensOn == "park" && m.hasPlace) "${m.placeName}에 도착하면 들려주려고 남긴 ${if (m.kind == "voice") "목소리" else "글"}"
                else if (m.isPlace) "${m.placeName}${josa(m.placeName, "을", "를")} 지날 때 들려주려고 남긴 ${if (m.kind == "voice") "목소리" else "글"}"
                else "「${m.placeName}」에 들려주려고 남긴 ${if (m.kind == "voice") "목소리" else "글"}", 28f, INK))
            heardOn(m)?.let { addView(text(it, 22f, MUTED).apply { setPadding(0, dp(6), 0, 0) }) }
            if (SKY_SKIN) {
                // 어디서·언제 열렸는지를 메인과 같은 3D 그림 칸으로
                addView(space(20))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = rounded(SOFT)
                    setPadding(dp(22), dp(20), dp(26), dp(20))
                    val key = if (m.isPlace) "place" else m.opensOn
                    addView(tile3d(condArt[key] ?: R.drawable.ic3_pin, if (NIGHT) skyTile else Color.parseColor(condTint[key] ?: "#EEF2FF"), 72, 52),
                        LinearLayout.LayoutParams(dp(72), dp(72)).apply { marginEnd = dp(18) })
                    addView(column().apply {
                        addView(text(if (m.isPlace) m.placeName else "「${m.placeName}」", 28f, INK, bold = true))
                        addView(text(if (m.isPlace) "이 근처를 지나서 열렸어요" else WHEN_HINTS[m.opensOn] ?: "", 21f, MUTED))
                    })
                }, LinearLayout.LayoutParams(-1, -2))
                // 답장이면 무엇에 대한 답장인지 (내가 남긴 안부의 글, 목소리면 받아 적은 글)
                m.replyTo?.let { rid -> messages.firstOrNull { it.id == rid } }?.let { orig ->
                    val what = orig.text.takeIf { it.isNotBlank() }?.let { "「${it.take(16)}${if (it.length > 16) "…" else ""}」" } ?: if (orig.kind == "voice") "목소리 안부" else "안부"
                    addView(text("↩ ${if (isMine(orig)) "내가 남긴" else "${orig.authorName}님의"} ${what}에 대한 답장", 21f, ACCENT, weight = 600).apply {
                        setPadding(dp(4), dp(14), 0, 0); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                }
                if (m.kind == "text") {
                    addView(View(context), LinearLayout.LayoutParams(1, 0, 0.7f)) // 인용은 남는 공간 가운데쯤
                    addView(text("“${m.text}”", 40f, INK, weight = 700).apply { letterSpacing = -0.015f; setLineSpacing(0f, 1.3f) })
                }
            }
            if (m.kind == "voice" && !SKY_SKIN) {
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
            if (m.kind == "text" && !SKY_SKIN) {
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
            if (m.kind != "voice") addView(text(if (Engine.ttsReady) "기계음으로 읽어요 · 길이는 어림이에요" else if (BuildConfig.STANDALONE) "이 차에 음성 엔진이 없어 글로 보여 드려요 (실차는 기계음으로 읽어요)" else "이 차에 음성 엔진이 없어 개발 서버 음성으로 읽어요",
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
        if (SKY_HOME) { renderSkyHome(); return }
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

    // 하늘 메인 (시안 5안) -----------------------------------------------------------------
    // 정차 중 메인: 하늘 배경 · 큰 제목 · 지역 칩 · 유리 지도(GlassMap) · 오른쪽 패널. 주행 중 화면은 그대로.

    private class SkyViews(val title: TextView, val chips: LinearLayout, val map: GlassMap, val hereName: TextView, val hereSub: TextView,
                           val weekNum: TextView, val side: LinearLayout)
    private var sky: SkyViews? = null
    private var skyNextAll = false
    private var skyHeardAll = false

    private class SkyTone(val top: Int, val bottom: Int, val glow: Int, val tint: Int)
    private val skyTones get() = if (NIGHT) skyTonesNight else skyTonesDay
    /** 야간: 짙은 남색 윗면에 도시 불빛 같은 빛 (호박·하늘·민트·보라·주황·파랑) */
    private val skyTonesNight = listOf(
        SkyTone(Color.parseColor("#2E3F8A"), Color.parseColor("#1E2B66"), Color.parseColor("#FFC46B"), Color.parseColor("#2A356A")),
        SkyTone(Color.parseColor("#24477A"), Color.parseColor("#172E55"), Color.parseColor("#6FD3FF"), Color.parseColor("#213E5E")),
        SkyTone(Color.parseColor("#22505A"), Color.parseColor("#163741"), Color.parseColor("#7BE3B0"), Color.parseColor("#1F4440")),
        SkyTone(Color.parseColor("#3A3480"), Color.parseColor("#251F59"), Color.parseColor("#C3A6FF"), Color.parseColor("#33295F")),
        SkyTone(Color.parseColor("#4A3A62"), Color.parseColor("#2C2340"), Color.parseColor("#FFB07A"), Color.parseColor("#4A2E28")),
        SkyTone(Color.parseColor("#2C3D78"), Color.parseColor("#1B2754"), Color.parseColor("#9DB8FF"), Color.parseColor("#26315E")),
    )
    private val skyTonesDay = listOf(
        SkyTone(Color.parseColor("#9DBBFF"), Color.parseColor("#5F8BF5"), Color.parseColor("#5F8BF5"), Color.parseColor("#E7EEFF")),
        SkyTone(Color.parseColor("#E5F6FF"), Color.parseColor("#B8E3FA"), Color.parseColor("#93D7FF"), Color.parseColor("#E3F4FD")),
        SkyTone(Color.parseColor("#EAF8E8"), Color.parseColor("#C3E9C0"), Color.parseColor("#A4E3A3"), Color.parseColor("#EAF7E8")),
        SkyTone(Color.parseColor("#F0E9FF"), Color.parseColor("#D2C1FB"), Color.parseColor("#C6A8FF"), Color.parseColor("#F1EBFF")),
        SkyTone(Color.parseColor("#FFF0E3"), Color.parseColor("#FBD1B0"), Color.parseColor("#FFB98A"), Color.parseColor("#FFF1E6")),
        SkyTone(Color.parseColor("#E6EFFF"), Color.parseColor("#C5D7FB"), Color.parseColor("#A9C4FF"), Color.parseColor("#EEF2FF")),
    )
    // 하늘 메인 색: 주간·야간 (앱의 NIGHT를 따른다)
    private fun sky(day: String, night: String) = Color.parseColor(if (NIGHT) night else day)
    private val skyInk get() = sky("#131A26", "#EEF2FF")
    private val skySub get() = sky("#4A5466", "#AEB8D9")
    private val skyFaint get() = sky("#687386", "#8792B8")
    private val skyBlue get() = sky("#2D5FD6", "#9DBCFF")
    private val skyLine get() = sky("#E6EBF2", "#2A3563")
    /** 진한 버튼 위 글자 (주간 흰색, 야간 남색) */
    private val skyOnInk get() = sky("#FFFFFF", "#0E1630")
    /** 작은 둥근 바탕 (아이콘 칸·개수) */
    private val skyTile get() = sky("#E7EEFF", "#26315E")
    private val skyPillBg get() = if (NIGHT) Color.argb(235, 22, 31, 72) else Color.argb(240, 255, 255, 255)
    private val skyPillStroke get() = if (NIGHT) Color.argb(60, 255, 255, 255) else Color.WHITE

    private val regionArt = mapOf(
        "서울" to R.drawable.rg_seoul, "부산" to R.drawable.rg_busan, "대구" to R.drawable.rg_daegu, "인천" to R.drawable.rg_incheon,
        "광주" to R.drawable.rg_gwangju, "대전" to R.drawable.rg_daejeon, "울산" to R.drawable.rg_ulsan, "세종" to R.drawable.rg_sejong,
        "경기" to R.drawable.rg_gyeonggi, "강원" to R.drawable.rg_gangwon, "충북" to R.drawable.rg_chungbuk, "충남" to R.drawable.rg_chungnam,
        "전북" to R.drawable.rg_jeonbuk, "전남" to R.drawable.rg_jeonnam, "경북" to R.drawable.rg_gyeongbuk, "경남" to R.drawable.rg_gyeongnam,
        "제주" to R.drawable.rg_jeju)
    private val condArt = mapOf(
        "rain" to R.drawable.cd_rain, "night" to R.drawable.cd_night, "together" to R.drawable.cd_together, "door" to R.drawable.cd_door,
        "start" to R.drawable.cd_start, "park" to R.drawable.cd_park, "long" to R.drawable.cd_long, "region" to R.drawable.cd_region,
        "warning" to R.drawable.cd_warning, "place" to R.drawable.ic3_pin)
    private val condTint = mapOf(
        "rain" to "#E6F0FF", "night" to "#ECEEFF", "together" to "#FFF1E6", "door" to "#FFF1E6", "start" to "#FFF6DB",
        "park" to "#E9F1FF", "long" to "#EAF7E8", "region" to "#E3F4FD", "warning" to "#FFF6DB", "place" to "#FDECEE")

    private fun art(res: Int, size: Int) = android.widget.ImageView(this).apply {
        setImageResource(res); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(pxu(size), pxu(size))
    }

    private fun glass(radius: Int, alpha: Float) = GradientDrawable().apply {
        if (NIGHT) { setColor(Color.argb((alpha * 215).toInt(), 18, 26, 62)); setStroke(1, Color.argb(55, 255, 255, 255)) }
        else { setColor(Color.argb((alpha * 255).toInt(), 255, 255, 255)); setStroke(1, Color.argb(240, 255, 255, 255)) }
        cornerRadius = pxu(radius).toFloat()
    }

    private fun skyShadow(v: View, elev: Int) {
        v.elevation = pxu(elev).toFloat()
        if (Build.VERSION.SDK_INT >= 28) { val c = if (NIGHT) Color.BLACK else Color.parseColor("#5A6E96"); v.outlineAmbientShadowColor = c; v.outlineSpotShadowColor = c }
    }

    private fun skyText(s: String, sp: Float, color: Int, weight: Int = 400) = text(s, sp, color, weight = weight).apply { setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, maxOf(sp * SKY_K, SKY_MIN_PX)); setPadding(0, 0, 0, 0); setLineSpacing(0f, 1.15f) }
    /** 하늘 메인은 시안의 px 단위로 그리고 SKY_K배 키운다. 시안 그대로(1배)면 글자가 13~20px라 차 화면의 다른 글자(약 26px)보다 너무 작다 */
    private fun pxu(v: Int) = Math.round(v * SKY_K)

    private fun renderSkyHome() {
        Regions.load(this)
        val frame = android.widget.FrameLayout(this)
        if (!SKY_SKIN) frame.addView(android.widget.ImageView(this).apply { setImageResource(if (NIGHT) R.drawable.home_sky_night else R.drawable.home_sky_day); scaleType = android.widget.ImageView.ScaleType.CENTER_CROP },
            android.widget.FrameLayout.LayoutParams(-1, -1))
        val content = column().apply { setPadding(pxu(40), pxu(24), pxu(30), pxu(26)) }
        frame.addView(content, android.widget.FrameLayout.LayoutParams(-1, -1))

        // 머리: 차 이름 · 큰 제목 · 한 줄 · 내 위치로
        val title = skyText("", 52f, skyInk, 800).apply { letterSpacing = -0.03f }
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(column().apply {
                // 차 이름: 레일과 같은 선 아이콘 + 지금 상태, 작은 유리 캡슐 · 그 옆에 모임 고르기
                val capRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                // 모임이 셋이면 오른쪽 안내 칸에 닿을 수 있어 옆으로 밀어 볼 수 있게 둔다
                addView(android.widget.HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(capRow) }, LinearLayout.LayoutParams(-1, -2))
                capRow.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = glass(22, if (NIGHT) 0.75f else 0.7f); setPadding(pxu(14), pxu(8), pxu(18), pxu(8))
                    addView(icon(R.drawable.ic_car, 20, skyInk), LinearLayout.LayoutParams(pxu(20), pxu(20)).apply { marginEnd = pxu(9) })
                    addView(skyText(store.carName.ifBlank { "내 차" }, 18f, skyInk, 600))
                    addView(View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(skyFaint) } },
                        LinearLayout.LayoutParams(pxu(4), pxu(4)).apply { marginStart = pxu(10); marginEnd = pxu(10) })
                    addView(skyText("주차 중", 17f, skySub, 500))
                }, LinearLayout.LayoutParams(-2, -2))
                // 모임 고르기. 바꾸면 refreshSky가 다시 그린다
                skySwitchBox = android.widget.FrameLayout(context)
                capRow.addView(skySwitchBox, LinearLayout.LayoutParams(-2, -2).apply { marginStart = pxu(12) })
                addView(title, LinearLayout.LayoutParams(-2, -2).apply { topMargin = pxu(14) })
                addView(skyText("지역을 누르면 그곳에 남겨 둔 안부가 보여요", 20f, skySub), LinearLayout.LayoutParams(-2, -2).apply { topMargin = pxu(6) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            // 오른쪽: 내 위치로 · 그 아래 말로 남기는 법 (늘 떠 있다)
            addView(column().apply {
                gravity = Gravity.END
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = pressable(GradientDrawable().apply { setColor(skyInk); cornerRadius = pxu(28).toFloat() })
                    setPadding(pxu(20), 0, pxu(24), 0)
                    addView(icon(R.drawable.ic_nav, 20, skyOnInk), LinearLayout.LayoutParams(pxu(20), pxu(20)).apply { marginEnd = pxu(10) })
                    addView(skyText("내 위치로", 18f, skyOnInk, 600))
                    isClickable = true; setOnClickListener { skyGoHere() }
                    skyShadow(this, 6)
                }, LinearLayout.LayoutParams(-2, pxu(56)))
                addView(voiceGuide(), LinearLayout.LayoutParams(pxu(370), -2).apply { topMargin = pxu(12) })
            }, LinearLayout.LayoutParams(-2, -2))
        })

        // 지역 칩
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, pxu(4), 0, pxu(10)) }
        content.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false; addView(chips) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = pxu(14); bottomMargin = pxu(4) })

        // 본문: 지도 + 오른쪽 패널
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val mapBox = android.widget.FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        // 아래층: 실제 지도(MapLibre). 평소엔 투명, 지역을 고르면 유리 지도가 확대되며 겹쳐 나타난다.
        val realMap = mainMap ?: createMainMap().also { mainMap = it }
        (realMap.parent as? android.view.ViewGroup)?.removeView(realMap)
        realMap.alpha = 0f
        mapBox.addView(realMap, android.widget.FrameLayout.LayoutParams(-1, -1).apply { setMargins(pxu(70), pxu(30), 0, 0) })
        // 하늘 배경과 어울리게: 오른쪽 패널과 같은 둥근 유리판 (모서리 · 흰 테두리 · 그림자)
        realMap.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) = outline.setRoundRect(0, 0, view.width, view.height, pxu(30).toFloat())
        }
        realMap.clipToOutline = true
        skyBorderV = View(this).apply {
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = pxu(30).toFloat(); setStroke(pxu(3), if (NIGHT) Color.argb(70, 157, 188, 255) else Color.argb(230, 255, 255, 255)) }
            alpha = 0f; isClickable = false
        }
        mapBox.addView(skyBorderV, android.widget.FrameLayout.LayoutParams(-1, -1).apply { setMargins(pxu(70), pxu(30), 0, 0) })
        val map = GlassMap(this).apply { night = NIGHT; onMarkTap = { m -> skyGroups.firstOrNull { it.key == m.key }?.let { skySelect(it) } } }
        mapBox.addView(map, android.widget.FrameLayout.LayoutParams(-1, -1).apply { setMargins(pxu(70), pxu(30), 0, 0) })
        skyZoomed = false; skyAnim?.cancel()
        skyBackV = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = pressable(glass(22, 0.92f)); setPadding(pxu(14), pxu(10), pxu(20), pxu(10))
            addView(skyText("‹", 26f, skyInk, 600), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = pxu(8) })
            addView(skyText("전국 보기", 18f, skyInk, 600))
            isClickable = true; setOnClickListener { skyBack() }
            visibility = View.GONE
            skyShadow(this, 6)
        }
        mapBox.addView(skyBackV, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = pxu(4) })
        // 출처 표기(OpenStreetMap 조건)는 지우지 않고 ⓘ 버튼으로 줄여 둔다. 누르면 펼쳐지고 다시 누르면 접힌다.
        val creditText = skyText("지도 © OpenStreetMap 기여자 · OpenFreeMap · MapLibre", 13f, skySub).apply { visibility = View.GONE }
        skyCreditV = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = glass(16, 0.85f); setPadding(pxu(10), pxu(4), pxu(10), pxu(4))
            addView(creditText, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = pxu(8) })
            addView(skyText("ⓘ", 18f, skySub, 600))
            isClickable = true
            setOnClickListener { creditText.visibility = if (creditText.visibility == View.GONE) View.VISIBLE else View.GONE }
            visibility = View.GONE
        }
        mapBox.addView(skyCreditV, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, pxu(10), pxu(10)) })
        skyMapV = realMap
        val hereName = skyText("", 15f, skyInk, 700)
        val hereSub = skyText("", 12.5f, skySub)
        mapBox.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            // 지금 여기 · 가장 가까운 안부: 지도를 덜 가리게 작게
            background = pressable(glass(18, 0.9f)); setPadding(pxu(8), pxu(7), pxu(14), pxu(7))
            addView(art(R.drawable.ic3_pin, 30), LinearLayout.LayoutParams(pxu(30), pxu(30)).apply { marginEnd = pxu(7) })
            addView(column().apply { addView(hereName); addView(hereSub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = pxu(2) }) })
            isClickable = true; setOnClickListener { skyGoHere() }
            skyShadow(this, 6)
        }, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { topMargin = pxu(4) })
        // 이번 주 들은 안부: 지도를 가리지 않게 한 줄짜리 작은 알약
        val weekNum = skyText("", 20f, skyInk, 800)
        mapBox.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = glass(20, 0.9f); setPadding(pxu(16), pxu(8), pxu(18), pxu(8))
            addView(skyText("이번 주 들은 안부", 14f, skySub, 600).apply { isSingleLine = true }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = pxu(8) })
            addView(weekNum)
            skyShadow(this, 4)
        }, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { bottomMargin = pxu(4) })
        // 시연 시나리오 안내 (달리는 중 · 결과)
        scenHud = android.widget.FrameLayout(this).also {
            mapBox.addView(it, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = pxu(70); leftMargin = pxu(100); rightMargin = pxu(30) })
        }
        drawScenHud()
        body.addView(mapBox, LinearLayout.LayoutParams(0, -1, 1f))

        val side = column().apply { setPadding(pxu(20), pxu(16), pxu(20), pxu(12)) }
        body.addView(ScrollView(this).apply {
            background = glass(30, 0.95f); clipToOutline = true; isVerticalScrollBarEnabled = false
            addView(side); skyShadow(this, 10)
        }, LinearLayout.LayoutParams(pxu(400), -1).apply { marginStart = pxu(18) })
        content.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))

        root.setPadding(0, 0, 0, 0)
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        sky = SkyViews(title, chips, map, hereName, hereSub, weekNum, side)
        mainSide = side // 엔진이 바뀌면 changed() → refreshMain() → refreshSky()
        refreshSky()
    }

    /** 메인 지도는 17개 시·도를 6개 권역으로 묶어 보여 준다. 세부는 확대한 실제 지도에서 본다. */
    private class SkyGroup(val key: String, val name: String, val codes: Set<String>, val art: Int, val lat: Double, val lon: Double)
    private val skyGroups = listOf(
        SkyGroup("cap", "수도권", setOf("11", "23", "31"), R.drawable.rg_seoul, 37.50, 127.05),
        SkyGroup("gw", "강원", setOf("32"), R.drawable.rg_gangwon, 37.75, 128.30),
        SkyGroup("cc", "충청", setOf("25", "29", "33", "34"), R.drawable.rg_chungnam, 36.55, 127.30),
        SkyGroup("hn", "호남", setOf("24", "35", "36"), R.drawable.rg_jeonbuk, 35.45, 126.95),
        SkyGroup("yn", "영남", setOf("21", "22", "26", "37", "38"), R.drawable.rg_busan, 35.80, 128.85),
        SkyGroup("jj", "제주", setOf("39"), R.drawable.rg_jeju, 33.40, 126.55),
    )
    private var selGroup: SkyGroup? = null
    private var skySwitchBox: android.widget.FrameLayout? = null
    private fun groupOfProvince(code: String) = skyGroups.firstOrNull { code in it.codes }

    private fun skySelect(g: SkyGroup) {
        skyZoom(g)
    }

    private fun skyGoHere() {
        val r = lastLoc?.let { Regions.provinceOf(it.first, it.second) }
        val g = r?.let { groupOfProvince(it.code) }
        if (g == null) { toast("지금 위치를 아직 몰라요"); return }
        skyZoom(g, r) // 권역 안의 지금 시·도까지 (예: 수도권 · 경기)
    }

    // 유리 지도 → 실제 지도 전환 ------------------------------------------------------------
    private var skyZoomed = false
    private var skyAnim: android.animation.ValueAnimator? = null
    private var skyMapV: View? = null
    private var skyBackV: View? = null
    private var skyCreditV: View? = null
    private var skyBorderV: View? = null

    /** 실제 지도의 색을 하늘 배경에 맞춘다 (바탕 연한 하늘색 · 물 · 길 · 지명) */
    private fun skyTintMap(st: org.maplibre.android.maps.Style) {
        (st.getLayer("bg") as? org.maplibre.android.style.layers.BackgroundLayer)?.setProperties(org.maplibre.android.style.layers.PropertyFactory.backgroundColor((if (NIGHT) "#111A3A" else "#EAF1FB")))
        (st.getLayer("water") as? org.maplibre.android.style.layers.FillLayer)?.setProperties(org.maplibre.android.style.layers.PropertyFactory.fillColor((if (NIGHT) "#0A1230" else "#C4D9F3")))
        (st.getLayer("road-major") as? org.maplibre.android.style.layers.LineLayer)?.setProperties(org.maplibre.android.style.layers.PropertyFactory.lineColor((if (NIGHT) "#2A3768" else "#D3DEEE")))
        listOf("label-city", "label-suburb").forEach { id ->
            (st.getLayer(id) as? org.maplibre.android.style.layers.SymbolLayer)?.setProperties(org.maplibre.android.style.layers.PropertyFactory.textHaloColor((if (NIGHT) "#111A3A" else "#EAF1FB")),
                org.maplibre.android.style.layers.PropertyFactory.textColor(if (id == "label-city") (if (NIGHT) "#C9D3F2" else "#4A5466") else (if (NIGHT) "#8C97BD" else "#7C879A")))
        }
    }
    private var skyRect: android.graphics.RectF? = null
    private var skyScale = 1f

    private fun smooth(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    /** t=0: 유리 지도 전체, t=1: 그 지역이 화면을 채우고 실제 지도가 비쳐 나옴 */
    private fun skyFrame(t: Float) {
        val g = sky?.map ?: return
        val rect = skyRect ?: return
        g.pivotX = rect.centerX(); g.pivotY = rect.centerY()
        val s = 1 + (skyScale - 1) * t
        g.scaleX = s; g.scaleY = s
        g.translationX = (g.width / 2f - rect.centerX()) * t
        g.translationY = (g.height / 2f - rect.centerY()) * t
        g.alpha = 1 - smooth((t - 0.4f) / 0.45f)
        skyMapV?.alpha = smooth((t - 0.3f) / 0.5f)
        skyBackV?.alpha = smooth((t - 0.5f) / 0.5f)
        skyBorderV?.alpha = smooth((t - 0.3f) / 0.5f)
    }

    private fun skyRun(from: Float, to: Float, onEnd: () -> Unit) {
        skyAnim?.cancel()
        skyAnim = android.animation.ValueAnimator.ofFloat(from, to).apply {
            duration = 800
            interpolator = android.view.animation.PathInterpolator(0.25f, 0.1f, 0.1f, 1f)
            addUpdateListener { skyFrame(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                var cancelled = false
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) { if (!cancelled) onEnd() }
            })
            start()
        }
    }

    private fun skyZoom(grp: SkyGroup, prov: Regions.Region? = null) {
        val v = sky ?: return
        val mv = mainMap
        selGroup = grp; selRegion = prov; selMessage = null
        val g = v.map
        val rect = g.regionRect(prov?.let { setOf(it.code) } ?: grp.codes)
        val provs = Regions.provinces.filter { if (prov != null) it.code == prov.code else it.code in grp.codes }
        if (mv == null || rect == null || g.width == 0 || provs.isEmpty()) { refreshSky(); return }
        // 서해 5도 같은 먼 섬은 빼고 본토 기준으로 맞춘다
        val mains = provs.mapNotNull { Regions.mainRing(it) }
        fun lat(ring: DoubleArray) = ring.filterIndexed { i, _ -> i % 2 == 0 }
        fun lon(ring: DoubleArray) = ring.filterIndexed { i, _ -> i % 2 == 1 }
        val box = if (mains.isEmpty()) bounds(provs.maxOf { it.north }, provs.maxOf { it.east }, provs.minOf { it.south }, provs.minOf { it.west })
            else bounds(mains.maxOf { lat(it).max() }, mains.maxOf { lon(it).max() }, mains.minOf { lat(it).min() }, mains.minOf { lon(it).min() })
        val pad = (g.width * 0.08f).toInt()
        // 시·도 하나로 들어갈 때는 시·군·구(구) 단위가 보이는 배율까지, 권역은 시·도 단위
        fun camera(m: org.maplibre.android.maps.MapLibreMap): org.maplibre.android.camera.CameraUpdate {
            val fit = m.getCameraForLatLngBounds(box, intArrayOf(pad, pad, pad, pad))?.zoom
            return if (prov != null && fit != null)
                org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(box.center, fit.coerceIn(LEVEL_DISTRICT_ZOOM + 0.1, LEVEL_PLACE_ZOOM - 0.1))
            else org.maplibre.android.camera.CameraUpdateFactory.newLatLngBounds(box, pad)
        }
        if (skyZoomed) { // 이미 실제 지도: 다른 지역으로 날아간다
            refreshSky()
            mainLib?.let { it.animateCamera(camera(it), 700) }
            skyRect = rect
            return
        }
        skyZoomed = true
        skyRect = rect
        skyScale = minOf(g.width * 0.8f / rect.width(), g.height * 0.8f / rect.height()).coerceIn(1.3f, 6f)
        skyBackV?.visibility = View.VISIBLE; skyCreditV?.visibility = View.VISIBLE
        mv.getMapAsync { m ->
            m.moveCamera(camera(m))
            mainStyle?.let { skyTintMap(it) }
            drawMainOverlays()
        }
        refreshSky()
        skyRun(0f, 1f) { g.visibility = View.INVISIBLE }
    }

    /** 전국 보기로 돌아간다 (확대의 반대 방향) */
    private fun skyBack() {
        val g = sky?.map
        selRegion = null; selGroup = null; selMessage = null
        if (!skyZoomed || g == null) { refreshSky(); return }
        skyZoomed = false
        g.visibility = View.VISIBLE
        refreshSky()
        skyRun(1f, 0f) {
            skyBackV?.visibility = View.GONE; skyCreditV?.visibility = View.GONE
            g.scaleX = 1f; g.scaleY = 1f; g.translationX = 0f; g.translationY = 0f; g.alpha = 1f
        }
    }

    private fun skyChip(label: String, on: Boolean, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = pressable(GradientDrawable().apply { setColor(if (on) skyInk else if (NIGHT) Color.argb(190, 24, 34, 80) else Color.argb(225, 255, 255, 255)); cornerRadius = pxu(24).toFloat()
            if (NIGHT && !on) setStroke(1, Color.argb(50, 255, 255, 255)) })
        setPadding(pxu(16), 0, pxu(20), 0)
        addView(icon(R.drawable.ic_pin, 18, if (on) skyOnInk else skyInk), LinearLayout.LayoutParams(pxu(18), pxu(18)).apply { marginEnd = pxu(6) })
        addView(skyText(label, 18f, if (on) skyOnInk else skyInk, 600))
        isClickable = true; setOnClickListener { onClick() }
        skyShadow(this, if (on) 4 else 2)
    }

    private fun skyRegionPill(g: SkyGroup, found: Int, total: Int, tone: SkyTone, on: Boolean, compact: Boolean = false): View {
        val r = g; val st = RegionStat(found, total, emptyList())
        val left = st.total - st.found
        if (compact) return LinearLayout(this).apply {
            // 지역이 많을 때: 3D 그림 + 이름만 (못 들은 수는 모서리 배지)
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = pressable(GradientDrawable().apply {
                setColor(skyPillBg); cornerRadius = pxu(16).toFloat()
                if (on) setStroke(pxu(2), skyBlue) else setStroke(1, skyPillStroke)
            })
            setPadding(pxu(7), pxu(6), pxu(7), pxu(6))
            addView(android.widget.FrameLayout(context).apply {
                background = GradientDrawable().apply { setColor(tone.tint); cornerRadius = pxu(12).toFloat() }
                addView(art(r.art, 34), android.widget.FrameLayout.LayoutParams(pxu(34), pxu(34), Gravity.CENTER))
                if (left > 0) addView(skyText("$left", 12f, Color.WHITE, 700).apply {
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply { setColor(skyBlue); cornerRadius = pxu(10).toFloat() }
                    minWidth = pxu(20); minHeight = pxu(20); setPadding(pxu(4), 0, pxu(4), 0)
                }, android.widget.FrameLayout.LayoutParams(-2, pxu(20), Gravity.TOP or Gravity.END))
            }, LinearLayout.LayoutParams(pxu(44), pxu(44)))
            addView(skyText(r.name, 14f, skyInk, 700), LinearLayout.LayoutParams(-2, -2).apply { topMargin = pxu(3) })
            isClickable = true; setOnClickListener { skySelect(g) }
            skyShadow(this, 6)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = pressable(GradientDrawable().apply {
                setColor(skyPillBg); cornerRadius = pxu(20).toFloat()
                if (on) setStroke(pxu(2), skyBlue) else setStroke(1, skyPillStroke)
            })
            setPadding(pxu(8), pxu(8), pxu(16), pxu(8))
            addView(android.widget.FrameLayout(context).apply {
                background = GradientDrawable().apply { setColor(tone.tint); cornerRadius = pxu(14).toFloat() }
                addView(art(r.art, 40), android.widget.FrameLayout.LayoutParams(pxu(40), pxu(40), Gravity.CENTER))
            }, LinearLayout.LayoutParams(pxu(52), pxu(52)).apply { marginEnd = pxu(12) })
            addView(column().apply {
                addView(skyText(r.name, 19f, skyInk, 700))
                addView(skyText(if (left > 0) "못 들은 안부 ${left}개 ›" else "안부 ${st.total}개 모두 들음 ›", 15f, skyBlue, 600),
                    LinearLayout.LayoutParams(-2, -2).apply { topMargin = pxu(2) })
            })
            isClickable = true; setOnClickListener { skySelect(g) }
            skyShadow(this, 8)
        }
    }

    private fun refreshSky() {
        val v = sky ?: return
        selRegion?.takeIf { it.code.length >= 2 }?.let { r -> groupOfProvince(r.code.take(2))?.let { selGroup = it } }
        if (skyZoomed) drawMainOverlays() // 실제 지도가 비쳐 있으면 거기 그림도 같이 고친다
        skySwitchBox?.let { box -> box.removeAllViews(); circleSwitch { refreshSky() }?.let { box.addView(it) } }
        val ids = foundIds()
        val vis = visibleMessages().filter { !isMine(it) }
        val unheard = vis.count { it.id !in ids }
        val head = "못 들은 안부 "
        v.title.text = android.text.SpannableString("$head${unheard}개").apply {
            setSpan(android.text.style.ForegroundColorSpan(skyBlue), head.length, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // 칩과 지도: 장소 안부가 있는 권역만
        val stats = regionStats(0)
        val gStats = skyGroups.mapNotNull { g ->
            val ss = stats.filterKeys { it.code in g.codes }.values
            if (ss.isEmpty()) null else Triple(g, ss.sumOf { it.found }, ss.sumOf { it.total })
        }.sortedBy { skyGroups.indexOf(it.first) } // 지역 칩은 늘 같은 순서(수도권 · 강원 · 충청 · 호남 · 영남 · 제주): 듣다 보면 순서가 바뀌어 헷갈렸다
        val selKey = selGroup?.key
        v.chips.removeAllViews()
        v.chips.addView(skyChip("전체", selKey == null) { skyBack() }, LinearLayout.LayoutParams(-2, pxu(48)))
        gStats.forEach { (g, _, _) -> v.chips.addView(skyChip(g.name, selKey == g.key) { skySelect(g) }, LinearLayout.LayoutParams(-2, pxu(48)).apply { marginStart = pxu(8) }) }
        // 못 들은 안부가 가장 많은 권역을 1로: 밤엔 불빛, 낮엔 색 진하기
        val maxLeft = gStats.maxOfOrNull { it.third - it.second }?.coerceAtLeast(1) ?: 1
        val placed = vis.filter { it.isPlace && it.hasPlace }
        v.map.marks = gStats.mapIndexed { i, (g, fnd, tot) ->
            val tone = skyTones[i % skyTones.size]
            val level = (tot - fnd).toFloat() / maxLeft
            // 점(알약 자리)은 권역 대표 위치가 아니라 그 권역의 실제 안부 자리에: 안부들 가운데에 가장 가까운 안부
            val inG = placed.filter { m -> regionsOf(m).first?.code?.let { it in g.codes } == true }
                .let { l -> l.filter { it.id !in ids }.ifEmpty { l } }
            val anchor = if (inG.isEmpty()) g.lat to g.lon else {
                val cLat = inG.map { it.lat }.average(); val cLon = inG.map { it.lon }.average()
                inG.minByOrNull { (it.lat - cLat) * (it.lat - cLat) + (it.lon - cLon) * (it.lon - cLon) }!!.let { it.lat to it.lon }
            }
            GlassMap.Mark(g.key, g.codes, anchor.first, anchor.second, tone.top, tone.bottom, tone.glow, skyRegionPill(g, fnd, tot, tone, selKey == g.key), level)
        }
        // 밤 불빛은 안부가 남겨진 자리에서만 (못 들은 것은 밝게, 들은 것은 은은하게)
        v.map.lights = placed.map { Triple(it.lat, it.lon, it.id !in ids) }
        v.map.selected = selKey
        v.map.here = lastLoc

        // 지금 여기
        val loc = lastLoc
        val inKorea = loc?.let { l -> Regions.provinceOf(l.first, l.second) } != null
        v.hereName.text = when {
            loc == null -> "위치 확인 중"
            !inKorea -> "국내 지도 밖"
            else -> Regions.districtOf(loc.first, loc.second)?.let { fullName(it) } ?: Regions.provinceOf(loc.first, loc.second)?.name ?: ""
        }
        val near = loc?.takeIf { inKorea }?.let { l -> vis.filter { it.isPlace && it.id !in ids }.minOfOrNull { distanceM(l.first, l.second, it.lat, it.lon) } }
        v.hereSub.text = when {
            near == null -> if (loc != null && !inKorea) "가까운 안부를 찾지 못했어요" else "근처에 남은 안부가 없어요"
            near < 1000 -> "가장 가까운 안부까지 ${near.toInt()}m"
            else -> "가장 가까운 안부까지 ${"%.0f".format(near / 1000)}km"
        }

        // 이번 주 들은 안부 (이 차 주인 기준, 최근 7일)
        val owners = store.memberships.mapNotNull { it.ownerId }.toSet()
        val today = java.time.LocalDate.now()
        val days = (6 downTo 0).map { today.minusDays(it.toLong()).toString() }
        val perDay = days.map { d -> feedHeard.filter { it.memberId in owners && it.at.take(10) == d }.map { it.messageId }.distinct().size }
        v.weekNum.text = "${perDay.sum()}개"

        // 오른쪽 패널
        val side = v.side
        side.removeAllViews()
        selGroup?.let { g -> skyRegionSection(side, g, selRegion?.takeIf { it.code.length == 2 && it.code in g.codes }, vis, ids); skyDivider(side) }
        skyTodaySection(side)
        // 주행 중 말로 남기기가 되어 「달리면서 저장한 곳」 대신 내가 남긴 안부를 보여 준다
        skyMineSection(side); skyDivider(side)

        val whenName = WHEN_OPTIONS.toMap()
        val orderKeys = WHEN_OPTIONS.map { it.first }
        val waiting = vis.filter { !it.isPlace && it.id !in ids }.groupBy { it.opensOn }.entries.sortedBy { orderKeys.indexOf(it.key) }
        skyHeader(side, "다음에 들을 안부", skyBlue, if (waiting.size > 3) (if (skyNextAll) "접기" else "전체 보기") else null) { skyNextAll = !skyNextAll; refreshSky() }
        if (waiting.isEmpty()) side.addView(skyNote("기다리는 상황별 안부가 없어요"))
        (if (skyNextAll) waiting else waiting.take(3)).forEach { (key, list) ->
            side.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, pxu(6), 0, pxu(6))
                addView(android.widget.FrameLayout(context).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (NIGHT) skyTile else Color.parseColor(condTint[key] ?: "#EEF2FF")) }
                    addView(art(condArt[key] ?: R.drawable.ic3_pin, 28), android.widget.FrameLayout.LayoutParams(pxu(28), pxu(28), Gravity.CENTER))
                }, LinearLayout.LayoutParams(pxu(44), pxu(44)).apply { marginEnd = pxu(12) })
                addView(column().apply {
                    addView(skyText(whenName[key] ?: key, 16.5f, skyInk, 700))
                    // 설명 · 그룹을 한 줄로 (넘치면 말줄임)
                    addView(skyText((WHEN_HINTS[key] ?: "") + " · " + list.map { Engine.circles[it.circleId]?.name ?: "모임" }.distinct().joinToString(", "), 13f, skySub).apply {
                        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = pxu(2) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(skyText("${list.size}개", 13.5f, skyBlue, 700).apply {
                    gravity = Gravity.CENTER; setPadding(pxu(11), 0, pxu(11), 0)
                    background = GradientDrawable().apply { setColor(if (NIGHT) skyTile else Color.parseColor("#EDF2FF")); cornerRadius = pxu(16).toFloat() }
                }, LinearLayout.LayoutParams(-2, pxu(30)).apply { marginStart = pxu(8) })
            })
        }
        skyDivider(side)

        val heard = vis.filter { it.id in ids }.sortedByDescending { m -> feedHeard.filter { it.messageId == m.id }.maxOfOrNull { it.at } ?: "" }
        skyHeader(side, "들은 안부", Color.parseColor("#A98BFA"), if (heard.size > 3) (if (skyHeardAll) "접기" else "더 보기") else null) { skyHeardAll = !skyHeardAll; refreshSky() }
        if (heard.isEmpty()) side.addView(skyNote("아직 들은 안부가 없어요"))
        (if (skyHeardAll) heard else heard.take(3)).forEach { m -> side.addView(skyHeardRow(m, whenName)) }
    }

    /** 달리면서 「다온, 여기 저장해 줘」로 저장한 곳: 어느 모임 자리인지와 함께, 남기기 · 지우기 */
    /**
     * 오늘 온 안부 (예전 「오늘」 메뉴에서 옮김): 얼굴 · 개수 · 모아 듣기, 기다리는 안부가 있으면 지금 듣기.
     * 오늘 온 것도 기다리는 것도 없으면 칸을 두지 않는다.
     */
    private fun skyTodaySection(side: LinearLayout) {
        skyNowPlaying(side)
        val heardToday = viewHeardToday()
        val waiting = viewPending()
        val total = heardToday.size + waiting.size
        if (total == 0) return
        val hide = discreet()
        val px = { dpv: Int -> (dpv / resources.displayMetrics.density).toInt() } // avatar는 dp로 받는다
        val who = (heardToday.map { it.authorName to circle?.memberByName(it.authorName) } + waiting.map { it.authorName to memberOf(it) }).distinctBy { it.first }
        skyHeader(side, "오늘 받은 안부", Color.parseColor("#34C3A0"), null) {}
        side.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, pxu(8), 0, pxu(10))
            if (!hide) addView(android.widget.FrameLayout(context).apply {
                who.take(4).forEachIndexed { i, (n, mem) ->
                    addView(avatar(n, px(38), member = mem).apply {
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(sky("#FFFFFF", "#1C2550")) }
                        setPadding(pxu(3), pxu(3), pxu(3), pxu(3))
                    }, android.widget.FrameLayout.LayoutParams(pxu(44), pxu(44)).apply { leftMargin = pxu(i * 28) })
                }
            }, LinearLayout.LayoutParams(pxu(44 + (who.take(4).size - 1).coerceAtLeast(0) * 28), pxu(44)).apply { marginEnd = pxu(12) })
            addView(column().apply {
                addView(skyText("${total}개", 24f, skyInk, 800))
                addView(skyText(if (hide) "옆자리에 누가 있어 보낸 사람은 가렸어요" else who.joinToString(", ") { "${it.first}님" } + "이 남겼어요", 15f, skySub).apply {
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val can = store.driverId != null
            addView(skyText("▶  모아 듣기", 14.5f, skyOnInk, 700).apply {
                gravity = Gravity.CENTER; setPadding(pxu(16), 0, pxu(18), 0)
                background = pressable(GradientDrawable().apply { setColor(skyInk); cornerRadius = pxu(20).toFloat() })
                alpha = if (can) 1f else 0.35f
                isClickable = true; setOnClickListener { if (can) Engine.playToday(viewCircle) }
            }, LinearLayout.LayoutParams(-2, pxu(40)))
        })
        val m = waiting.firstOrNull()
        if (m != null) side.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(sky("#EEF7F3", "#1F3550")); cornerRadius = pxu(18).toFloat() }
            setPadding(pxu(14), pxu(10), pxu(10), pxu(10))
            val key = if (m.isPlace) "place" else m.opensOn
            addView(art(if (hide) R.drawable.cd_together else condArt[key] ?: R.drawable.ic3_pin, 34), LinearLayout.LayoutParams(pxu(34), pxu(34)).apply { marginEnd = pxu(12) })
            addView(column().apply {
                addView(skyText("기다리는 안부 ${waiting.size}개", 14f, skySub, 600))
                addView(skyText(if (hide) "안부가 도착했어요" else "${m.authorName}님이 ${m.occasion} 남겼어요", 17f, skyInk, 700).apply {
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(skyText("▶ 지금 듣기", 16f, Color.WHITE, 700).apply {
                gravity = Gravity.CENTER; setPadding(pxu(16), 0, pxu(16), 0)
                background = pressable(GradientDrawable().apply { setColor(skyBlue); cornerRadius = pxu(20).toFloat() })
                isClickable = true; setOnClickListener { Engine.playNow(viewCircle) }
            }, LinearLayout.LayoutParams(-2, pxu(40)))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = pxu(6) })
        skyDivider(side)
    }

    /** 소리로만 듣는 중: 화면은 원할 때만 (「화면 보기」), 그만 듣기는 「나중에」 */
    private fun skyNowPlaying(side: LinearLayout) {
        val m = playing ?: return
        if (parked != true) return
        val hide = discreet()
        side.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(sky("#EAF1FF", "#22305E")); cornerRadius = pxu(18).toFloat() }
            setPadding(pxu(16), pxu(12), pxu(10), pxu(12))
            addView(icon(R.drawable.ic_play, 26, skyBlue), LinearLayout.LayoutParams(pxu(28), pxu(28)).apply { marginEnd = pxu(12) })
            addView(column().apply {
                addView(skyText("지금 듣는 중", 14f, skySub, 600))
                addView(skyText(if (hide) "안부를 들려주고 있어요" else "${m.authorName}의 안부", 18f, skyInk, 700).apply {
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            // 다 듣고 「들었어요」를 보내기 전 잠깐은 「보내지 않기」
            val counting = Engine.autoHeardLeft > 0
            addView(skyText(if (counting) "보내지 않기" else "나중에", 16f, skyInk, 600).apply {
                gravity = Gravity.CENTER; setPadding(pxu(14), 0, pxu(14), 0)
                isClickable = true; setOnClickListener { if (counting) Engine.cancelAutoHeard() else Engine.later() }
            }, LinearLayout.LayoutParams(-2, pxu(40)))
            addView(skyText("화면 보기", 16f, Color.WHITE, 700).apply {
                gravity = Gravity.CENTER; setPadding(pxu(16), 0, pxu(16), 0)
                background = pressable(GradientDrawable().apply { setColor(skyBlue); cornerRadius = pxu(20).toFloat() })
                isClickable = true; setOnClickListener { Engine.showPlayback() }
            }, LinearLayout.LayoutParams(-2, pxu(40)))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = pxu(10) })
    }

    /** 고른 모임의 저장한 곳 (모임을 정하지 않고 저장한 곳은 늘 보인다) */
    private fun viewSpots() = store.spots.filter { sp -> viewCircle == null || sp.circleId.isEmpty() || sp.circleId == viewCircle }

    /** Gleo에게 말로 남기는 법: 예시 문장이 몇 초마다 바뀐다 (운전 중에도 화면을 보지 않고 쓸 수 있게) */
    private fun voiceGuide(): View {
        // 예시는 한 줄 (「글레오」는 위 단계 표시로 빠져서 짧다). 길이가 달라도 칸 높이가 출렁이지 않게 늘 한 줄
        val say = skyText("", 16f, skyInk, 700).apply { minLines = 1; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setLineSpacing(0f, 1.1f)
            // 한글은 글자 단위로 끊기므로 어절 단위로 (Android 13+)
            if (android.os.Build.VERSION.SDK_INT >= 33) lineBreakWordStyle = android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE }
        val result = skyText("", 14f, skyBlue, 600).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        var i = 0
        fun show() {
            val ex = voiceExamples()
            val (line, group) = ex[i % ex.size]
            val cond = VOICE_WHEN_PHRASE[store.voiceWhen] ?: "이 자리를 지날 때"
            say.text = "“$line”"
            result.text = "→ 삐 소리 뒤 말하면 ${group}에 내 목소리 그대로 남아 $cond 들려줘요"
        }
        show()
        val tick = object : Runnable {
            override fun run() {
                if (!say.isAttachedToWindow) return
                say.animate().alpha(0f).setDuration(220).withEndAction {
                    i++; show()
                    say.animate().alpha(1f).setDuration(220).start()
                }.start()
                result.animate().alpha(0f).setDuration(220).withEndAction { result.animate().alpha(1f).setDuration(220).start() }.start()
                say.postDelayed(this, 4500)
            }
        }
        say.postDelayed(tick, 4500)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = glass(22, 0.9f); setPadding(pxu(12), pxu(10), pxu(18), pxu(10))
            skyShadow(this, 4)
            addView(android.widget.FrameLayout(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (NIGHT) skyTile else Color.parseColor("#EAF1FF")) }
                addView(icon(R.drawable.ic_mic, 22, skyBlue), android.widget.FrameLayout.LayoutParams(pxu(24), pxu(24), Gravity.CENTER))
            }, LinearLayout.LayoutParams(pxu(46), pxu(46)).apply { marginEnd = pxu(12) })
            addView(column().apply {
                // ① 「글레오」라고 부르고 → 띠링 → ② 이렇게 말한다
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    fun chip(t: String) = skyText(t, 13f, skyBlue, 700).apply {
                        background = GradientDrawable().apply { setColor(if (NIGHT) Color.argb(60, 157, 188, 255) else Color.parseColor("#E6EEFF")); cornerRadius = pxu(10).toFloat() }
                        setPadding(pxu(8), pxu(2), pxu(8), pxu(2))
                    }
                    addView(chip("① “글레오”"))
                    addView(skyText("띠링 소리 뒤", 13f, skySub, 500), LinearLayout.LayoutParams(-2, -2).apply { marginStart = pxu(6); marginEnd = pxu(6) })
                    addView(chip("② 이렇게 말해요"))
                })
                addView(say, LinearLayout.LayoutParams(-2, -2).apply { topMargin = pxu(2) })
                addView(result, LinearLayout.LayoutParams(-2, -2).apply { topMargin = pxu(1) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    /**
     * 메인 안내에 번갈아 보일 말: 「녹음해 줘」(지금 모임) + 들어간 모임마다 「우리집 녹음해 줘」.
     * 그룹은 모임 이름을 정확히 말해야 그 모임에 남는다 (VoiceNote · Engine.findGroupExact). (글레오에게 하는 말, 받는 모임)
     */
    private fun voiceExamples(): List<Pair<String, String>> =
        // 모임 이름 없이 「녹음해 줘」만 해도 지금 모임에 남지만, 화면에는 모임 이름을 말하는 꼴만 보여 준다
        Engine.groupNames().map { "$it 녹음해 줘" to it }.ifEmpty { listOf("(모임 이름) 녹음해 줘" to "그 모임") }

    /** 마지막 글자에 받침이 있는지 (이/가 고르기) */
    private fun hasBatchim(w: String): Boolean { val c = w.trim().lastOrNull() ?: return false; return c in '가'..'힣' && (c - '가') % 28 != 0 }

    /** 내가 남긴 안부: 최근 3개 · 누가 들었는지 · 답장. 전체는 소식에서 (고치기·지우기도 거기서) */
    private fun skyMineSection(side: LinearLayout) {
        val mine = visibleMessages().filter { isMine(it) }.asReversed()
        skyHeader(side, "내가 남긴 안부", Color.parseColor("#F2A23A"), if (mine.isNotEmpty()) "전체 ${mine.size}개" else null) { goTab(Screen.FEED) }
        if (mine.isEmpty()) {
            side.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, pxu(4), 0, pxu(6))
                addView(skyText("아직 남긴 안부가 없어요", 14f, skySub), LinearLayout.LayoutParams(0, -2, 1f))
                addView(skyText("남기기", 14f, Color.WHITE, 700).apply {
                    gravity = Gravity.CENTER; setPadding(pxu(16), 0, pxu(16), 0)
                    background = pressable(GradientDrawable().apply { setColor(sky("#131A26", "#4D7BEA")); cornerRadius = pxu(18).toFloat() })
                    isClickable = true; setOnClickListener { goTab(Screen.LEAVE) }
                }, LinearLayout.LayoutParams(-2, pxu(36)))
            })
            return
        }
        mine.take(3).forEach { m ->
            val c = Engine.circles[m.circleId]
            val heardBy = feedHeard.filter { it.messageId == m.id }.map { it.memberName }.distinct()
            val replies = messages.count { it.replyTo == m.id }
            val toNames = if (m.to.isEmpty()) "모두" else m.to.joinToString(", ") { c?.nameOf(it) ?: "누군가" }
            side.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, pxu(6), 0, pxu(6))
                isClickable = true; setOnClickListener { goTab(Screen.FEED) }
                val key = if (m.isPlace) "place" else m.opensOn
                addView(android.widget.FrameLayout(context).apply {
                    background = GradientDrawable().apply { setColor(if (NIGHT) skyTile else Color.parseColor(condTint[key] ?: "#EEF2FF")); cornerRadius = pxu(14).toFloat() }
                    addView(art(condArt[key] ?: R.drawable.ic3_pin, 28), android.widget.FrameLayout.LayoutParams(pxu(28), pxu(28), Gravity.CENTER))
                }, LinearLayout.LayoutParams(pxu(44), pxu(44)).apply { marginEnd = pxu(12) })
                addView(column().apply {
                    // 목소리 안부에 받아 적은 글이 있으면 그 글을 보여 준다 (없으면 「목소리 안부」)
                    addView(skyText(if (m.kind == "voice") (if (m.text.isNotBlank()) "목소리 · 「${m.text}」" else "목소리 안부") else "「${m.text}」", 16f, skyInk, 700).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    addView(skyText("${toNames}에게 · ${m.occasion}", 13f, skySub).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                        LinearLayout.LayoutParams(-2, -2).apply { topMargin = pxu(3) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                val heardText = if (heardBy.size == 1) "${heardBy[0]} 들음" else "${heardBy.size}명 들음"
                val (badge, color) = when {
                    replies > 0 && heardBy.isNotEmpty() -> "$heardText · 답장 $replies" to skyBlue
                    replies > 0 -> "답장 $replies" to skyBlue
                    heardBy.isNotEmpty() -> heardText to GREEN
                    else -> "안 들음" to skySub
                }
                // 메인에서도 바로 들어 보기 (줄을 누르면 소식으로)
                addView(skyText(if (minePlayingId == m.id) "■" else "▶", 15f, skyBlue, 700).apply {
                    gravity = Gravity.CENTER
                    background = pressable(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(blend(if (NIGHT) Color.parseColor("#1C2550") else Color.WHITE, skyBlue, 0.14f)) })
                    isClickable = true; setOnClickListener { playMine(m) }
                }, LinearLayout.LayoutParams(pxu(36), pxu(36)).apply { marginStart = pxu(8) })
                addView(skyText(badge, 14f, color, 700).apply {
                    gravity = Gravity.CENTER; setPadding(pxu(12), pxu(4), pxu(12), pxu(4))
                    background = GradientDrawable().apply { setColor(blend(if (NIGHT) Color.parseColor("#1C2550") else Color.WHITE, color, 0.14f)); cornerRadius = pxu(20).toFloat() }
                }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = pxu(8) })
            })
        }
    }

    private fun skySpotsSection(side: LinearLayout) {
        val list = viewSpots().sortedByDescending { it.at }
        skyHeader(side, "달리면서 저장한 곳", Color.parseColor("#F2A23A"), null) {}
        list.take(3).forEach { sp ->
            side.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, pxu(9), 0, pxu(9))
                isClickable = true
                setOnClickListener { openSpot(sp) }
                addView(android.widget.FrameLayout(context).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (NIGHT) skyTile else Color.parseColor("#FFF1DC")) }
                    addView(icon(R.drawable.ic_mic, 26, Color.parseColor("#E08A1E")), android.widget.FrameLayout.LayoutParams(pxu(28), pxu(28), Gravity.CENTER))
                }, LinearLayout.LayoutParams(pxu(56), pxu(56)).apply { marginEnd = pxu(16) })
                addView(column().apply {
                    addView(skyText(sp.memo.ifBlank { spotPlaceName(sp) }, 19f, skyInk, 700).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    addView(skyText(listOfNotNull(Engine.circles[sp.circleId]?.name, spotWhen(sp)).joinToString(" · ") + spotConditions(sp), 16f, skySub).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                        LinearLayout.LayoutParams(-2, -2).apply { topMargin = pxu(3) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(skyText("지우기", 15f, skySub, 600).apply {
                    gravity = Gravity.CENTER; setPadding(pxu(10), 0, pxu(10), 0); isClickable = true
                    setOnClickListener { confirm("이 자리를 지울까요?", "${sp.memo.ifBlank { spotPlaceName(sp) }} · ${spotWhen(sp)}", "지우기") { Engine.removeSpot(sp.id) } }
                }, LinearLayout.LayoutParams(-2, pxu(44)))
                addView(skyText("남기기", 16f, Color.WHITE, 700).apply {
                    gravity = Gravity.CENTER; setPadding(pxu(18), 0, pxu(18), 0)
                    background = pressable(GradientDrawable().apply { setColor(sky("#131A26", "#4D7BEA")); cornerRadius = pxu(22).toFloat() })
                    isClickable = true; setOnClickListener { openSpot(sp) }
                }, LinearLayout.LayoutParams(-2, pxu(44)).apply { marginStart = pxu(4) })
            })
        }
        if (list.size > 3) side.addView(skyNote("그 밖에 ${list.size - 3}곳"))
    }

    private fun skyHeader(side: LinearLayout, s: String, bar: Int, more: String?, onMore: () -> Unit) {
        side.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(View(context).apply { background = GradientDrawable().apply { setColor(bar); cornerRadius = pxu(2).toFloat() } },
                LinearLayout.LayoutParams(pxu(4), pxu(16)).apply { marginEnd = pxu(9) })
            addView(skyText(s, 18f, skyInk, 700), LinearLayout.LayoutParams(0, -2, 1f))
            if (more != null) addView(skyText("$more ›", 14f, skySub, 600).apply {
                gravity = Gravity.CENTER_VERTICAL; isClickable = true; setOnClickListener { onMore() }
            }, LinearLayout.LayoutParams(-2, pxu(38)))
        }, LinearLayout.LayoutParams(-1, pxu(38)))
    }

    private fun skyDivider(side: LinearLayout) = side.addView(View(this).apply { setBackgroundColor(skyLine) },
        LinearLayout.LayoutParams(-1, 1).apply { topMargin = pxu(6); bottomMargin = pxu(8) })

    private fun skyNote(s: String) = skyText(s, 14.5f, skySub).apply { setPadding(0, pxu(8), 0, pxu(8)) }

    private fun skyHeardRow(m: PlaceMessage, whenName: Map<String, String>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, pxu(6), 0, pxu(6))
        addView(avatar(m.authorName, (44 / resources.displayMetrics.density).toInt(), member = Engine.circles[m.circleId]?.member(m.authorId)), LinearLayout.LayoutParams(pxu(44), pxu(44)).apply { marginEnd = pxu(12) })
        addView(column().apply {
            addView(skyText(m.authorName, 16.5f, skyInk, 700))
            addView(skyText("${if (m.isPlace) m.placeName else whenName[m.opensOn] ?: m.placeName} · ${Engine.circles[m.circleId]?.name ?: "모임"}", 13f, skySub).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = pxu(2) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(skyText("다시 듣기", 13.5f, skyInk, 600).apply {
            gravity = Gravity.CENTER; setPadding(pxu(12), 0, pxu(12), 0)
            background = pressable(GradientDrawable().apply { setColor(sky("#FFFFFF", "#25305C")); cornerRadius = pxu(22).toFloat(); setStroke(1, sky("#DDE3EC", "#3A467E")) })
            isClickable = true; setOnClickListener { Engine.replay(m) }
        }, LinearLayout.LayoutParams(-2, pxu(34)))
    }

    /** 고른 시·도의 안부: 들은 것은 하나씩, 못 들은 것은 개수만 */
    private fun skyRegionSection(side: LinearLayout, r: SkyGroup, sel: Regions.Region?, vis: List<PlaceMessage>, ids: Set<String>) {
        val inRegion = vis.filter { it.isPlace && (if (sel != null) regionsOf(it).first?.code == sel.code else regionsOf(it).first?.code in r.codes) }
        skyHeader(side, if (sel != null) "${r.name} · ${sel.name}의 안부" else "${r.name}의 안부", skyBlue, "닫기") { skyBack() }
        val left = inRegion.count { it.id !in ids }
        if (left > 0) side.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, pxu(11), 0, pxu(11))
            addView(android.widget.FrameLayout(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(skyTile) }
                addView(art(sel?.let { regionArt[it.name] } ?: r.art, 40), android.widget.FrameLayout.LayoutParams(pxu(40), pxu(40), Gravity.CENTER))
            }, LinearLayout.LayoutParams(pxu(62), pxu(62)).apply { marginEnd = pxu(16) })
            addView(column().apply {
                addView(skyText("못 들은 안부 ${left}개", 20f, skyInk, 700))
                addView(skyText("그 자리를 지나면 열려요", 17f, skySub), LinearLayout.LayoutParams(-2, -2).apply { topMargin = pxu(3) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        val whenName = WHEN_OPTIONS.toMap()
        inRegion.filter { it.id in ids }.forEach { m -> side.addView(skyHeardRow(m, whenName)) }
        if (inRegion.isEmpty()) side.addView(skyNote("이 지역에 남겨 둔 안부가 없어요"))
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
                    if (skyZoomed) { drawMainOverlays(); return@post } // 하늘 메인에서 이미 확대했으면 그 자리를 지킨다
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
                    if (DEMO_MOVE) pickFrom("이 자리에서", listOf("여기로 이동 (시연)", "여기에 메시지 남기기")) { i ->
                        if (i == 0) demoMove(at.latitude, at.longitude, Regions.districtOf(at.latitude, at.longitude)?.let { fullName(it) } ?: "고른 곳")
                        else openLeaveAt(at.latitude, at.longitude)
                    }
                    else confirm("여기에 메시지를 남길까요?", "고른 자리를 모임의 다른 사람이 지나가면 그 사람 차에서 들려줘요.", "남기기") { openLeaveAt(at.latitude, at.longitude) }
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
        st.addSource(org.maplibre.android.style.sources.GeoJsonSource("scen-route"))
        st.addImage("me-dot", dotBitmap(BLUE))
        st.addImage("me-car", carBitmap())
        val get = { k: String -> org.maplibre.android.style.expressions.Expression.get(k) }
        st.addLayer(org.maplibre.android.style.layers.FillLayer("region-fill", "regions").withProperties(
            org.maplibre.android.style.layers.PropertyFactory.fillColor(org.maplibre.android.style.expressions.Expression.toColor(get("fill")))))
        st.addLayer(org.maplibre.android.style.layers.LineLayer("region-line", "regions").withProperties(
            org.maplibre.android.style.layers.PropertyFactory.lineColor(org.maplibre.android.style.expressions.Expression.toColor(get("line"))),
            org.maplibre.android.style.layers.PropertyFactory.lineWidth(get("lw"))))
        // 시연 시나리오의 길: 출발점에서 목적지까지 점선
        st.addLayer(org.maplibre.android.style.layers.LineLayer("scen-route", "scen-route").withProperties(
            org.maplibre.android.style.layers.PropertyFactory.lineColor(skyBlue),
            org.maplibre.android.style.layers.PropertyFactory.lineWidth(6f),
            org.maplibre.android.style.layers.PropertyFactory.lineOpacity(0.75f),
            org.maplibre.android.style.layers.PropertyFactory.lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND),
            org.maplibre.android.style.layers.PropertyFactory.lineDasharray(arrayOf(1.5f, 1.8f))))
        st.addLayer(org.maplibre.android.style.layers.SymbolLayer("me", "me").withProperties(
            org.maplibre.android.style.layers.PropertyFactory.iconImage(if (scenCar != null) "me-car" else "me-dot"),
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
        if (sky != null && screen == Screen.HOME) { refreshSky(); return }
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
            .filter { m -> viewCircle?.let { it == m.circleId } ?: true }
    }

    /** 보는 모임: null이면 모든 모임. 메인·오늘의 모임 고르기에서 바꾼다 */
    private var viewCircle: String? = null

    /** 오늘 들은 것 · 기다리는 것 중 고른 모임 것 */
    /** 오늘 이 차에서 들은 안부 중 지금 타는 사람 것만 (바꿔 타면 앞 사람이 받은 안부는 보이지 않는다) */
    private fun viewHeardToday() = store.heard.filter { h ->
        val m = messages.firstOrNull { it.id == h.messageId } ?: return@filter false
        (m.to.isEmpty() || Engine.ownerOf(m) in m.to) && (viewCircle == null || m.circleId == viewCircle)
    }
    private fun viewPending() = pending.filter { viewCircle == null || it.circleId == viewCircle }

    /**
     * 모임 고르기: 「모든 모임 · 우리집 · 대학 동기」 유리 알약 하나. 모임마다 색 점과 못 들은 수.
     * 모임이 하나뿐이면 보이지 않는다.
     */
    private fun circleSwitch(onChange: () -> Unit): View? {
        val joined = store.liveMemberships.mapNotNull { ms -> Engine.circles[ms.circleId] }
        if (joined.size < 2) return null
        if (viewCircle != null && joined.none { it.id == viewCircle }) viewCircle = null
        val ids = foundIds()
        val keep = viewCircle; viewCircle = null
        val unheard = visibleMessages().filter { !isMine(it) && it.id !in ids }.groupingBy { it.circleId }.eachCount()
        viewCircle = keep
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = glass(26, if (NIGHT) 0.75f else 0.72f); setPadding(pxu(5), pxu(5), pxu(5), pxu(5))
            val items = listOf<Pair<String?, String>>(null to "모두") + joined.map { it.id to it.name }
            items.forEach { (id, name) ->
                val on = viewCircle == id
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(pxu(13), 0, pxu(13), 0)
                    if (on) { background = GradientDrawable().apply { setColor(sky("#FFFFFF", "#33427F")); cornerRadius = pxu(22).toFloat() }; skyShadow(this, 3) }
                    if (id != null) addView(View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(circleColor(id)) } },
                        LinearLayout.LayoutParams(pxu(10), pxu(10)).apply { marginEnd = pxu(8) })
                    addView(skyText(name, 17f, if (on) skyInk else skySub, if (on) 700 else 500).apply { isSingleLine = true })
                    val n = if (id == null) unheard.values.sum() else unheard[id] ?: 0
                    if (n > 0) addView(skyText("$n", 15f, if (on) skyBlue else skyFaint, 700).apply { isSingleLine = true }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = pxu(7) })
                    isClickable = true
                    setOnClickListener { if (viewCircle != id) { viewCircle = id; mapSig = ""; onChange() } }
                }, LinearLayout.LayoutParams(-2, pxu(42)))
            }
        }
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
            listOfNotNull((scenCar ?: lastLoc)?.let { org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.Point.fromLngLat(it.second, it.first)) })))
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
            val base = if (sky != null) (if (NIGHT) 0xFF1A2452.toInt() else 0xFFE4EDFA.toInt()) else SOFT // 하늘 메인에서는 연한 하늘색 땅
            val fill = when {
                st == null -> base
                left == 0 -> blend(base, GREEN, 0.35f)
                else -> blend(base, if (sky != null) (if (NIGHT) Color.parseColor("#4766D6") else skyBlue) else ACCENT, 0.18f + 0.5f * left / maxLeft)
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
        // 고른 안부 카드는 오른쪽 칸 위쪽에 생긴다: 아래 목록에서 골랐어도 보이게 맨 위로
        (mainSide?.parent as? ScrollView)?.post { (mainSide?.parent as? ScrollView)?.smoothScrollTo(0, 0) }
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
        // 「이 차는 누구 차인가요?」 카드는 두지 않는다 (주인은 Engine.ensureOwners가 저절로 정한다)
        if (store.spots.isNotEmpty()) side.addView(spotsCard(), panelParams())
        side.addView(waitingPanel(), panelParams())
        if (DEMO_MOVE && !BuildConfig.STANDALONE) side.addView(demoCard(), panelParams())
        selMessage?.takeIf { m -> visibleMessages().any { it.id == m.id } }?.let { side.addView(selectedCard(it), panelParams()) }
        selRegion?.let { side.addView(regionCard(it), panelParams()) }
        side.addView(conditionCard(), panelParams())
        side.addView(heardCard(), panelParams())
        side.addView(filterCard(), panelParams())
    }

    /**
     * 시연: 실제로 달리지 않고 차를 옮기거나 시동·주차한 것처럼. 앱 안의 위치만 바뀐다(기본 내비 화면은 그대로).
     * 안부 장소로 옮기면 그 안부가 열려 재생 화면이 뜨고, 그 자리에서 바로 남길 수도 있다.
     */
    private fun demoCard() = panel().apply {
        background = rounded(CARD, 2, BORDER)
        val at = Engine.demoLocation
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(icon(R.drawable.ic_car, 40, ACCENT), LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) })
            addView(column().apply {
                addView(text("시연 · 차 움직여 보기", 28f, INK, bold = true))
                addView(label(if (at != null) "시간·요일 조건은 건너뛰어요 · 지금 시연 위치: ${demoPlaceName ?: Regions.districtOf(at.first, at.second)?.let { fullName(it) } ?: "고른 곳"}"
                    else "달리지 않고 안부 장소로 옮겨 볼 수 있어요. 지도를 길게 눌러도 돼요"))
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        addView(row(
            primary("안부 장소로 이동") { pickDemoPlace() },
            secondary(if (at != null) "실제 위치로" else "지도 가운데로") {
                if (at != null) { Engine.setDemoLocation(null); demoPlaceName = null; toast("실제 위치(GPS)로 돌아왔어요"); refreshMain() }
                else mainLib?.cameraPosition?.target?.let { t -> demoMove(t.latitude, t.longitude, Regions.districtOf(t.latitude, t.longitude)?.let { fullName(it) } ?: "지도 가운데") }
            },
        ).apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) } })
        addView(row(
            secondary("시동 걸기") { demoResult(Engine.demoStart(), "시동 걸 때") },
            secondary("도착해 세우기") { demoResult(Engine.demoPark(), "도착해 차를 세울 때") },
        ))
    }

    private fun demoResult(n: Int, name: String) {
        toast(if (n > 0) "「$name」 안부 ${n}개가 열렸어요" else "지금 열 「$name」 안부가 없어요 (한 번만 듣는 안부는 이미 들었으면 「들은 안부」에서 다시 들어요)")
        demoShowPlayback()
    }

    /** 시연 버튼은 직접 누른 것이라 소리로만 시작했어도 재생 화면을 연다 */
    private fun demoShowPlayback() { if (Engine.quietPlay) Engine.showPlayback() }

    /** 시연 위치 이름 (고른 안부 장소 이름) */
    private var demoPlaceName: String? = null
    /** 「가족에게 가기」로 옮겨 간 사람 (옮긴 뒤 「여기서 ○○에게 남기기」의 받는 사람) */
    private var demoTarget: Member? = null

    /** "지연(누나)" · "아빠" */
    private fun who(m: Member) = if (m.title.isNotBlank() && m.title != m.name) "${m.name}(${m.title})" else m.name

    /** 그 사람이 남긴 장소 안부 중 가장 최근 것 (그 사람이 있는 쪽으로 보는 자리). 위치는 서버로 보내지 않으므로 안부 장소를 쓴다. */
    private fun familySpot(m: Member): PlaceMessage? =
        visibleMessages().filter { it.isPlace && it.authorId == m.id && it.circleId == circle?.id }.lastOrNull()

    /** 시연: 그 사람의 자리로 옮긴다. 그 자리 안부가 아직 안 들은 것이면 Engine이 바로 들려준다. */
    private fun demoGoTo(m: Member, spot: PlaceMessage) {
        demoTarget = m
        demoMove(spot.lat, spot.lon, spot.placeName)
        render()
    }

    /** 시연: 지금 자리 근처의 나에게 온 안부를 (이미 들었어도) 다시 들려준다 */
    private fun demoListenHere(at: Pair<Double, Double>) {
        val here = visibleMessages().filter { it.isPlace && !isMine(it) && distanceM(at.first, at.second, it.lat, it.lon) <= maxOf(it.radiusM, 300.0) }
            .sortedBy { distanceM(at.first, at.second, it.lat, it.lon) }
        if (here.isEmpty()) { toast("이 자리에 남은 안부가 없어요"); return }
        Engine.replay(here.first())
    }

    /** 시연: 지금 자리에 그 사람에게 남기기. 장소·받는 사람을 채운 채 「내용」 단계로 바로 간다. */
    private fun demoLeaveHere(m: Member, at: Pair<Double, Double>) {
        goTab(Screen.LEAVE)
        leaveWhen = "place"
        leaveKey = MAP_PICK
        leavePlace = Place(demoPlaceName ?: "여기", at.first, at.second)
        leaveMapCenter = org.maplibre.android.geometry.LatLng(at.first, at.second)
        leaveTo = listOf(m.id)
        leaveStep = 2
        render()
    }

    /** 시연: 나에게 온 장소 안부 중에서 골라 그 자리로 */
    private fun pickDemoPlace() {
        val places = visibleMessages().filter { it.isPlace && !isMine(it) }.distinctBy { it.placeName }.sortedBy { it.placeName }
        if (places.isEmpty()) { toast("나에게 온 장소 안부가 없어요"); return }
        val ids = foundIds()
        pickFrom("어디로 가 볼까요?", places.map { m -> m.placeName + if (m.id in ids) " · 들음" else "" }) { i -> demoMove(places[i].lat, places[i].lon, places[i].placeName) }
    }

    private fun demoMove(lat: Double, lon: Double, name: String) {
        demoPlaceName = name
        Engine.setDemoLocation(lat to lon)
        demoShowPlayback()
        flyTo(lat, lon, maxOf(14.5, mainLib?.cameraPosition?.zoom ?: 0.0))
        toast("「$name」(으)로 옮겼어요 · 이 근처 안부가 있으면 들려 드려요")
        if (screen == Screen.HOME) refreshMain()
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
                    addView(text(listOfNotNull(Engine.circles[sp.circleId]?.name, spotWhen(sp)).joinToString(" · ") + spotConditions(sp), 20f, MUTED).apply { maxLines = 1 })
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
        // 저장할 때 고른 그룹으로 남긴다 (보내는 사람은 그 그룹에서의 이 차 주인)
        Engine.circles[sp.circleId]?.takeIf { store.keyOf(it.id) != null }?.let { g ->
            leaveCircleId = g.id
            leaveAuthor = store.ownerOf(g.id) ?: leaveAuthor
            leaveTo = emptyList()
        }
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
            if (DEMO_MOVE) addView(secondary("여기로 이동") { demoMove(m.lat, m.lon, m.placeName) },
                LinearLayout.LayoutParams(0, dp(80), 1f).apply { marginStart = dp(10) })
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

    /**
     * 상황별 안부: 아직 못 들은 것만. 장소 없이 차 상태로 열리는 안부(비·밤길·옆자리·차에 탈 때…).
     * 내용과 보낸 사람은 숨기고(그 상황이 됐을 때 깜짝) 개수와 모임만. 들은 것은 「들은 안부」에.
     */
    private fun conditionCard() = panel().apply {
        val ids = foundIds()
        val waiting = visibleMessages().filter { !it.isPlace && !isMine(it) && it.id !in ids }
        val groups = WHEN_OPTIONS.filter { it.first != "place" }.map { (key, name) -> Triple(key, name, waiting.filter { it.opensOn == key }) }
            .filter { it.third.isNotEmpty() }
        // 처음엔 한 줄 요약. 누르면 안부가 있는 상황만 펼친다 (없는 상황은 숨긴다).
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(column().apply {
                addView(text(if (waiting.isEmpty()) "기다리는 상황별 안부 없음" else "기다리는 상황별 안부 ${waiting.size}개", 28f, INK, bold = true))
                addView(label(if (waiting.isEmpty()) "들은 안부는 아래 「들은 안부」에 있어요" else "그 상황이 되면 들려 드려요"))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (waiting.isNotEmpty()) addView(text(if (conditionsExpanded) "▲" else "▼", 24f, MUTED))
            if (waiting.isNotEmpty()) {
                isClickable = true
                setOnClickListener { conditionsExpanded = !conditionsExpanded; if (!conditionsExpanded) openCondition = null; refreshSide() }
            }
        })
        if (!conditionsExpanded) return@apply
        groups.forEach { (key, name, ms) ->
            val open = openCondition == key
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(12), dp(4), dp(12))
                addView(icon(WHEN_ICONS.getValue(key), 36, ACCENT), LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(14) })
                addView(text(name, 24f, INK, weight = 600), LinearLayout.LayoutParams(0, -2, 1f))
                addView(text("${ms.size}개", 24f, ACCENT, bold = true))
                addView(text(if (open) "  ▲" else "  ▼", 20f, MUTED))
                isClickable = true
                background = pressable(GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(if (open) CHIP else Color.TRANSPARENT) })
                setOnClickListener { openCondition = if (open) null else key; refreshSide() }
            })
            if (open) {
                val byCircle = ms.groupBy { Engine.circles[it.circleId]?.name ?: "모임" }.map { (c, l) -> "$c ${l.size}개" }.joinToString(" · ")
                addView(label("${WHEN_HINTS.getValue(key)} · $byCircle").apply { setPadding(dp(50), 0, 0, dp(10)) })
            }
        }
    }

    /** 들은 안부: 이 차가 이미 들은 안부를 모아 다시 듣기 (전체 · 상황별 · 장소) */
    private fun heardCard() = panel().apply {
        val ids = foundIds()
        val heard = visibleMessages().filter { !isMine(it) && it.id in ids }
        val list = when (heardTab) { "cond" -> heard.filter { !it.isPlace }; "place" -> heard.filter { it.isPlace }; else -> heard }
            .sortedByDescending { m -> feedHeard.filter { it.messageId == m.id }.maxOfOrNull { it.at } ?: "" }
        addView(text("들은 안부 ${heard.size}개", 28f, INK, bold = true))
        addView(label("누르면 지도와 위 칸에서 다시 듣고 답장할 수 있어요"))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf("all" to "전체 ${heard.size}", "cond" to "상황별 ${heard.count { !it.isPlace }}", "place" to "장소 ${heard.count { it.isPlace }}").forEachIndexed { i, (k, n) ->
                val on = heardTab == k
                addView(button(n, if (on) rounded(ACCENT) else rounded(SOFT), if (on) Color.WHITE else INK) { heardTab = k; heardMore = false; refreshSide() }
                    .apply { textSize = 20f; minHeight = dp(64) },
                    LinearLayout.LayoutParams(0, dp(64), 1f).apply { if (i > 0) marginStart = dp(8) })
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(4) }
        })
        if (list.isEmpty()) addView(label("아직 들은 안부가 없어요"))
        val shown = if (heardMore) list else list.take(HEARD_PREVIEW)
        shown.forEach { m -> addView(messageRow(m)) }
        if (list.size > HEARD_PREVIEW) addView(secondary(if (heardMore) "접기" else "${list.size - HEARD_PREVIEW}개 더 보기") { heardMore = !heardMore; refreshSide() },
            LinearLayout.LayoutParams(-1, dp(72)).apply { topMargin = dp(6) })
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

        // 그룹(이름·구성원·초대 코드·그룹의 차)은 왼쪽 메뉴 「그룹」으로 옮겼다. 여기는 이 차와 듣기 설정만.
        root.addView(section(R.drawable.ic_car, "이 차"))
        root.addView(label("이 차에서는 주인에게 온 안부를 들려주고, 주인이 남긴 안부는 틀지 않아요. 그룹마다 이 차의 주인은 「그룹」 메뉴에서 볼 수 있어요."))
        root.addView(row(
            secondary("차 이름: ${store.carName.ifBlank { "우리 차" }}") {
                askText("이 차 이름", store.carName) { name -> Engine.renameCar(name) { ok -> if (!ok) toast("바꾸지 못했어요") } }
            },
            secondary("그룹 관리로 가기") { goTab(Screen.GROUPS) },
        ))

        root.addView(section(R.drawable.ic_play, "안부가 열리면"))
        root.addView(toggle("차를 세웠을 때 재생 화면 띄우기", store.popUpScreen) { store.popUpScreen = it })
        root.addView(label("꺼 두면 보던 화면은 그대로 두고 소리로만 들려줘요. 화면으로 보고 싶을 때는 메인의 「화면 보기」를 누르세요."))

        root.addView(section(R.drawable.ic_shield, "옆자리 배려"))
        root.addView(toggle("옆자리에 누가 있으면 먼저 물어보기", store.askWithPassenger) { store.askWithPassenger = it; Engine.maybeAutoPlay() })
        root.addView(label("조수석 센서로 동승을 알아채면 바로 틀지 않고 「메시지가 도착했어요. 지금 들을까요?」라고만 물어요. 보낸 사람과 내용은 화면·알림에 나오지 않고, 「다음에」를 고르면 옆자리가 빈 뒤 들려줘요."))

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
            View(this),
        ))

        if (BuildConfig.STANDALONE) root.addView(label("시연 동작과 「시연 데이터로 되돌리기」는 왼쪽 메뉴의 「시연」에 있어요."))
    }

    /**
     * 앱만 버전의 「시연」 메뉴: 실제로 달리지 않고 차를 옮기고, 차 신호(시동·문·와이퍼·전조등·조수석·경고등)를 받은 것처럼 한다.
     * 나머지 메뉴는 서버 버전과 똑같이 쓰고, 시연에 필요한 것은 모두 여기에만 둔다.
     */
    private fun renderDemo() {
        if (SKY_SKIN && !demoManual) { renderDemoScenarios(); return }
        if (SKY_SKIN) root.addView(LinearLayout(this).apply {
            addView(miniPill("← 시나리오로") { demoManual = false; render() }.apply { (layoutParams as? LinearLayout.LayoutParams)?.marginStart = 0 }, LinearLayout.LayoutParams(-2, dp(64)))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        root.addView(title("시연"))
        root.addView(label("실제로 달리지 않고 차를 옮기거나 차 상태를 바꿔 봐요. 실차에서는 차 신호로 저절로 돼요. 시연에서는 안부의 시간·요일 조건을 건너뛰어요."))
        val ids = foundIds()
        val at = Engine.demoLocation

        // 주행 없이 시연: 가족에게 가기 → 그 자리 안부 듣기 또는 그 자리에 남기기
        val c = circle
        val me = store.driverId
        root.addView(section(R.drawable.ic_people, "가족에게 가기"))
        root.addView(label("달리지 않고 그 사람이 안부를 남긴 자리로 바로 옮겨요. 옮긴 뒤 그 자리 안부를 듣거나, 그 사람에게 남길 수 있어요."))
        val family = c?.members?.filter { it.id != me }?.mapNotNull { m -> familySpot(m)?.let { m to it } }.orEmpty()
        if (family.isEmpty()) root.addView(label("가족이 남긴 장소 안부가 없어요"))
        family.chunked(3).forEach { line ->
            root.addView(row(*(line.map { (m, spot) ->
                val label = "${who(m)} · ${spot.placeName}"
                if (demoTarget?.id == m.id && demoPlaceName == spot.placeName) primary("● $label") { demoGoTo(m, spot) }
                else secondary(label) { demoGoTo(m, spot) }
            } + List(3 - line.size) { View(this) }).toTypedArray()))
        }
        val target = demoTarget
        if (at != null && target != null) {
            root.addView(card(listOf(
                label("지금 시연 위치"),
                big("${who(target)} 쪽 · ${demoPlaceName ?: "고른 곳"}"),
            )))
            root.addView(row(
                primary("▶ 이 자리 안부 듣기") { demoListenHere(at) },
                secondary("✎ 여기서 ${who(target)}에게 남기기") { demoLeaveHere(target, at) },
            ))
        }

        root.addView(section(R.drawable.ic_pin, "어디로 갈까요"))
        root.addView(label(if (at != null) "지금 시연 위치: ${demoPlaceName ?: Regions.districtOf(at.first, at.second)?.let { fullName(it) } ?: "고른 곳"} · 누르면 그 자리로 옮기고, 그곳 안부가 있으면 바로 들려 드려요"
            else "지금은 실제 위치(GPS)예요 · 누르면 그 자리로 옮기고, 그곳 안부가 있으면 바로 들려 드려요"))
        val places = visibleMessages().filter { it.isPlace && !isMine(it) }.distinctBy { it.placeName }.sortedBy { it.placeName }
        if (places.isEmpty()) root.addView(label("나에게 온 장소 안부가 없어요"))
        places.chunked(3).forEach { line ->
            root.addView(row(*(line.map { m ->
                (if (demoPlaceName == m.placeName) primary("● ${m.placeName}") { demoTarget = null; demoMove(m.lat, m.lon, m.placeName); render() }
                else secondary(m.placeName + if (m.id in ids) " · 들음" else "") { demoTarget = null; demoMove(m.lat, m.lon, m.placeName); render() })
            } + List(3 - line.size) { View(this) }).toTypedArray()))
        }
        root.addView(row(
            secondary("메인 지도에서 고르기") { goTab(Screen.HOME); toast("지도를 길게 누르면 그 자리로 옮길 수 있어요") },
            secondary("실제 위치로") { Engine.setDemoLocation(null); demoPlaceName = null; demoTarget = null; toast("실제 위치(GPS)로 돌아왔어요"); render() }.apply { alpha = if (at != null) 1f else 0.4f },
        ))

        root.addView(section(R.drawable.ic_car, "차 상태"))
        root.addView(row(
            secondary("시동 걸기") { demoResult(Engine.demoStart(), "시동 걸 때") },
            secondary("차에 타기 (차 켜기)") { demoResult(Engine.demoSignal("door"), "차에 탈 때") },
            secondary("도착해 세우기") { demoResult(Engine.demoPark(), "도착해 차를 세울 때") },
        ))
        fun onOff(name: String, on: Boolean, kind: String, cond: String) =
            (if (on) primary("$name · 켜짐") { Engine.demoSignal(kind, false); render() }
            else secondary("$name · 꺼짐") { demoResult(Engine.demoSignal(kind, true), cond); render() })
        root.addView(row(
            onOff("와이퍼", Engine.rainNow, "rain", "비 오는 날"),
            onOff("전조등", Engine.nightNow, "night", "밤길"),
            // 동승은 안부를 여는 조건이 아니다: 옆자리 배려(재생 전에 묻기)를 시험하는 스위치
            if (Engine.passengerNow) primary("옆자리 동승 · 켜짐") { Engine.demoSignal("together", false); render() }
            else secondary("옆자리 동승 · 꺼짐") { Engine.demoSignal("together", true); toast("옆자리에 누가 탄 것처럼 해요. 안부는 들려주기 전에 먼저 물어요"); render() },
        ))
        root.addView(row(
            secondary("2시간 넘게 달린 것처럼") { demoResult(Engine.demoSignal("long"), "오래 운전할 때") },
            secondary("경고등 켜기") { demoResult(Engine.demoSignal("warning"), "경고등이 켜질 때") },
        ))

        root.addView(section(R.drawable.ic_mic, "운전 중 말로 (Gleo)"))
        root.addView(label("글레오에게 말한 것처럼 해 봐요. 「여기 저장해 줘」는 지금 자리를 저장하고, 「(모임 이름) 녹음해 줘」는 삐 소리 뒤 말한 것을 그 모임에 남겨요."))
        root.addView(row(secondary("「여기 저장해 줘」") {
            toast(when (Engine.saveSpot("")) {
                Engine.SpotResult.SAVED -> "지금 자리를 저장했어요"
                Engine.SpotResult.MERGED -> "방금 저장한 자리와 합쳤어요"
                Engine.SpotResult.NO_LOCATION -> "아직 위치를 몰라요. 위에서 장소로 먼저 옮겨 주세요"
                Engine.SpotResult.NOT_READY -> "모임에 들어간 뒤에 저장할 수 있어요"
                Engine.SpotResult.NO_GROUP -> "그 모임을 찾지 못했어요"
            })
        }, secondary("「${circle?.name ?: "모임"} 녹음해 줘」") {
            // 글레오에게 「다온으로 녹음해 줘」라고 한 것처럼: 안내 → 삐 → 녹음 → 지금 모임에 남기기
            if (VoiceNote.busy) toast("받는 중이에요") else VoiceNote.start(this, "", false)
        }))

        root.addView(section(R.drawable.ic_settings, "시연 데이터"))
        root.addView(label("앱만으로 동작하는 시연 버전이에요. 가족·친구와 안부는 이 차 안에만 있어요. 남기기의 「보내는 사람」을 엄마·아빠 등으로 바꾸면 원우에게 온 안부처럼 시험할 수 있어요."))
        root.addView(row(secondary("시연 데이터로 되돌리기") {
            confirm("시연 데이터로 되돌릴까요?", "남긴 안부·들은 기록·설정한 모임이 모두 처음 시연 상태로 돌아가요.", "되돌리기") {
                toast("처음 상태로 되돌리는 중이에요…")
                history.clear(); screen = Screen.HOME; demoPlaceName = null
                Engine.resetDemo { toast("시연 데이터로 되돌렸어요"); render() }
            }
        }, View(this)))
    }

    // 시연: 시나리오 ------------------------------------------------------
    // 시나리오를 고르면 메인의 실제 지도로 가서, 차가 길 위를 달려 그 자리·그 순간에 닿는다.
    // 장소·지역 안부는 엔진이 실제처럼 위치로 알아채서 열고, 차 상태 안부는 다 달린 뒤 그 신호를 넣는다.

    /**
     * mode: move = 목적지까지 달린다(위치로 열림) · drive = 잠깐 달린 뒤 fire · still = 서 있다가 fire.
     * to: move의 목적지, zoom: 달릴 때 지도 배율
     */
    private class Scen(val key: String, val title: String, val sub: String, val ready: Boolean, val mode: String,
                       val goal: String, val to: Pair<Double, Double>? = null, val regionCode: String? = null,
                       val fire: () -> Unit = {})

    private var scenRun: Scen? = null
    /** go 지도로 가는 중 · drive 달리는 중 · done 끝 */
    private var scenPhase = ""
    private var scenResult = ""
    private var scenOpened = 0
    /** 이번 시나리오로 열린 안부 (하나면 지도 위 안내에서 바로 「답장」) */
    private var scenOpenedList: List<PlaceMessage> = emptyList()
    private var scenBefore: Set<String> = emptySet()
    /** 지도 위 차 자리 (시나리오 중에만). 지도 그림의 「지금 위치」가 이것을 따른다 */
    private var scenCar: Pair<Double, Double>? = null
    private var scenAnim: android.animation.ValueAnimator? = null
    private val scenJobs = mutableListOf<Runnable>()
    private var scenHud: android.widget.FrameLayout? = null
    private var scenStatus: TextView? = null
    /** 시연 화면에서 예전 버튼 모음(직접 조작)을 보는지 */
    private var demoManual = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun scenLater(ms: Long, f: () -> Unit) {
        val r = Runnable { f() }
        scenJobs += r
        mainHandler.postDelayed(r, ms)
    }

    private fun scenarios(): List<Scen> {
        val heard = foundIds()
        val mine = visibleMessages().filter { !isMine(it) }
        fun waiting(key: String) = mine.filter { it.opensOn == key && !it.isPlace && it.id !in heard }
        fun who(list: List<PlaceMessage>) = if (list.isEmpty()) "열 안부가 없어요 (이미 들었어요)"
            else list.map { it.authorName }.distinct().joinToString(", ") + "의 안부 ${list.size}개"
        val out = mutableListOf<Scen>()
        // 들은 장소 카드도 자리를 지킨다 (시연 중에 카드가 사라지면 다음에 누를 자리가 밀린다)
        mine.filter { it.isPlace && it.opensOn != "region" }.groupBy { it.placeName }.forEach { (name, list) ->
            val left = list.filter { it.id !in heard }
            val m = left.firstOrNull() ?: list.first()
            out += Scen("place", "「$name」 지나가기", if (left.isEmpty()) "이미 들었어요" else "${m.authorName}의 안부", left.isNotEmpty(), "move", name, m.lat to m.lon)
        }
        mine.filter { it.opensOn == "region" }.groupBy { it.placeName }.entries.take(1).forEach { (name, list) ->
            val left = list.filter { it.id !in heard }
            val m = left.firstOrNull() ?: list.first()
            out += Scen("region", "「$name」에 들어서기", if (left.isEmpty()) "이미 들었어요" else "${m.authorName}의 안부", left.isNotEmpty(), "move", name, m.lat to m.lon, m.regionCode)
        }
        waiting("rain").let { out += Scen("rain", "달리다 비가 오면", who(it), it.isNotEmpty(), "drive", "비가 와요 · 와이퍼가 켜졌어요") { Engine.demoSignal("rain", true) } }
        waiting("night").let { out += Scen("night", "해 지고 전조등을 켜면", who(it), it.isNotEmpty(), "drive", "해가 졌어요 · 전조등이 켜졌어요") { Engine.demoSignal("night", true) } }
        waiting("long").let { out += Scen("long", "2시간 넘게 달리면", who(it), it.isNotEmpty(), "drive", "쉬지 않고 2시간을 달렸어요") { Engine.demoSignal("long") } }
        waiting("warning").let { out += Scen("warning", "경고등이 켜지면", who(it), it.isNotEmpty(), "drive", "계기판에 경고등이 켜졌어요") { Engine.demoSignal("warning") } }
        waiting("park").let { out += Scen("park", "도착해 차를 세우면", who(it), it.isNotEmpty(), "drive", "목적지에 도착해 P로 세웠어요") { Engine.demoPark() } }
        waiting("door").let { out += Scen("door", "차에 타면", who(it), it.isNotEmpty(), "still", "운전석 문이 열렸어요") { Engine.demoSignal("door") } }
        waiting("start").let { out += Scen("start", "시동을 걸면", who(it), it.isNotEmpty(), "still", "시동이 걸렸어요") { Engine.demoStart() } }
        // 지도로 가지 않고 바로 하는 것 (mode now)
        Engine.groupNames().forEach { g ->
            out += Scen("record:$g", "「$g 녹음해 줘」", "글레오에게 말하듯 · 삐 소리 뒤에 말하기", true, "now", g) {
                if (VoiceNote.busy) toast("받는 중이에요") else VoiceNote.start(this, g, false)
            }
        }
        out += Scen("save", "「여기 저장해 줘」", "지금 자리만 먼저 저장 · 세우면 안부로", true, "now", "여기") {
            toast(when (Engine.saveSpot("")) {
                Engine.SpotResult.SAVED -> "지금 자리를 저장했어요"
                Engine.SpotResult.MERGED -> "방금 저장한 자리와 합쳤어요"
                Engine.SpotResult.NO_LOCATION -> "아직 위치를 몰라요. 장소 카드로 먼저 옮겨 주세요"
                Engine.SpotResult.NOT_READY -> "모임에 들어간 뒤에 저장할 수 있어요"
                Engine.SpotResult.NO_GROUP -> "그 모임을 찾지 못했어요"
            })
        }
        val riding = Engine.passengerNow
        out += Scen("together", if (riding) "조수석에서 내리기" else "조수석에 누가 타면", if (riding) "옆자리가 비면 기다리던 안부를 들려줘요" else "가족 안부는 보낸 사람을 가리고 먼저 물어요",
            true, "now", "옆자리") { Engine.demoSignal("together", !riding); render() }
        return out
    }

    /** 시연 묶음: (제목, 순서 안내, 카드 고르는 열쇠). 열쇠는 Scen.key 또는 "place:장소 이름" */
    private val DEMO_FLOW = listOf(
        Triple("메인 ① 받기 · 금요일 귀성길", "시동 → 2시간 → 천안 휴게소(지도 아래 「답장」) → 도착",
            listOf("start", "long", "place:천안 휴게소", "park")),
        Triple("메인 ② 남기기 · 토요일 강릉", "우리 둘 녹음해 줘 → 강릉 → 정동진 → 세운 뒤 「메시지」에서 1년 뒤 남기기",
            listOf("record:우리 둘", "region", "place:정동진 전망대")),
        Triple("질문이 나오면", "옆자리 배려 · 연인 · 다른 조건 · 여기 저장 · 경유지",
            listOf("together", "place:성수동 카페 앞", "door", "rain", "night", "warning", "save", "place:우리 동네", "place:잠수교")),
    )

    /** 이 시나리오의 출발점과 도착점 */
    private fun scenRoute(s: Scen): Pair<Pair<Double, Double>, Pair<Double, Double>> {
        val here = lastLoc ?: (37.5665 to 126.9780)
        return when (s.mode) {
            "move" -> {
                val to = s.to!!
                if (s.regionCode != null) {
                    // 지역: 그 지역 밖(서쪽)에서 출발해 경계를 넘어 들어간다
                    var d = 0.08
                    while (d < 0.8 && Regions.districtOf(to.first - d * 0.3, to.second - d)?.code == s.regionCode) d += 0.06
                    (to.first - (d + 0.04) * 0.3 to to.second - (d + 0.04)) to to
                } else (to.first - 0.008 to to.second - 0.03) to to // 장소: 서쪽 3km쯤에서 동쪽으로
            }
            "drive" -> here to (here.first + 0.004 to here.second + 0.028)
            else -> here to here
        }
    }

    private fun runScenario(s: Scen) {
        if (scenRun != null) stopScenario(silent = true)
        if (s.mode == "now") { s.fire(); return }
        if (!s.ready) { toast("이 시나리오로 열 안부가 없어요. 「처음 상태로」를 누르면 다시 들을 수 있어요"); return }
        if (Engine.rainNow) Engine.demoSignal("rain", false)
        if (Engine.nightNow) Engine.demoSignal("night", false)
        val (from, to) = scenRoute(s)
        scenRun = s; scenPhase = "go"; scenResult = ""; scenOpened = 0
        scenBefore = (pending.map { it.id } + listOfNotNull(playing?.id)).toSet()
        // 출발점으로 옮겨 두고 메인 지도로
        demoPlaceName = null
        Engine.setDemoLocation(from)
        scenCar = from
        history.clear()
        goTab(Screen.HOME)
        // 지도가 자리 잡으면 출발점으로 확대 → 달리기
        scenLater(300) {
            val prov = Regions.provinceOf(from.first, from.second)
            val g = prov?.let { groupOfProvince(it.code) }
            if (g != null && !skyZoomed) skyZoom(g, prov)
            scenLater(if (g != null) 700 else 100) {
                val zoom = if (s.regionCode != null) 10.6 else 13.6
                mainLib?.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(org.maplibre.android.geometry.LatLng(from.first, from.second), zoom), 600)
                scenLater(650) { scenDrive(s, from, to) }
            }
        }
        drawScenHud()
    }

    private fun scenDrive(s: Scen, from: Pair<Double, Double>, to: Pair<Double, Double>) {
        if (scenRun !== s) return
        scenPhase = "drive"
        mainStyle?.getLayerAs<org.maplibre.android.style.layers.SymbolLayer>("me")?.setProperties(org.maplibre.android.style.layers.PropertyFactory.iconImage("me-car"))
        drawScenRoute(from, to)
        drawScenHud()
        val total = MainActivity.distanceM(from.first, from.second, to.first, to.second)
        var pushed = 0L
        scenAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = when (s.mode) { "move" -> if (s.regionCode != null) SCEN_REGION_MS else SCEN_MOVE_MS; "drive" -> SCEN_DRIVE_MS; else -> SCEN_STILL_MS }
            interpolator = if (s.key == "park") android.view.animation.DecelerateInterpolator(1.6f) else android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { a ->
                if (screen != Screen.HOME) { stopScenario(silent = true); return@addUpdateListener }
                val f = a.animatedValue as Float
                val p = (from.first + (to.first - from.first) * f) to (from.second + (to.second - from.second) * f)
                scenCar = p
                mainStyle?.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>("me")?.setGeoJson(org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.Point.fromLngLat(p.second, p.first)))
                if (s.mode != "still") mainLib?.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLng(org.maplibre.android.geometry.LatLng(p.first, p.second)))
                // 엔진에는 0.4초마다 위치를 준다: 장소·지역 안부는 지나가는 순간 엔진이 스스로 연다
                val now = System.currentTimeMillis()
                if (s.mode == "move" && now - pushed > 400) { pushed = now; Engine.setDemoLocation(p) }
                val left = (total * (1 - f)) / 1000
                val kmh = when { s.mode == "still" -> 0; s.key == "park" -> (60 * (1 - f)).toInt(); else -> (54 + 10 * Math.sin(f * 7.0)).toInt() }
                scenStatus?.text = when {
                    s.mode == "still" -> if (s.key == "start") "시동 거는 중…" else "차 문을 여는 중…"
                    s.mode == "move" -> "주행 중 · ${kmh}km/h · 「${s.goal}」까지 ${String.format("%.1f", left)}km"
                    s.key == "long" -> "주행 중 · ${kmh}km/h · 달린 시간 ${(f * 125).toInt() / 60}시간 ${(f * 125).toInt() % 60}분"
                    else -> "주행 중 · ${kmh}km/h"
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                var cancelled = false
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) { if (!cancelled) scenArrive(s, to) }
            })
            start()
        }
    }

    private fun scenArrive(s: Scen, to: Pair<Double, Double>) {
        if (scenRun !== s) return
        if (s.mode == "move") { demoPlaceName = s.goal; Engine.setDemoLocation(to) } else s.fire()
        scenLater(700) {
            if (scenRun !== s) return@scenLater
            val now = pending.map { it.id } + listOfNotNull(playing?.id)
            val opened = now.filter { it !in scenBefore }.mapNotNull { id -> messages.firstOrNull { it.id == id } }
            scenOpened = opened.size
            scenOpenedList = opened
            scenPhase = "done"
            // 옆자리 배려 중에는 시연 안내에도 보낸 사람을 쓰지 않는다
            val hide = Engine.passengerNow && store.askWithPassenger
            scenResult = if (opened.isEmpty()) "열린 안부가 없어요"
                else if (hide) "안부가 도착했어요 · 옆자리에 누가 있어 보낸 사람을 가렸어요"
                else opened.map { it.authorName }.distinct().joinToString(", ") + "의 안부가 열렸어요"
            if (hide) scenOpenedList = emptyList()
            drawScenHud()
        }
    }

    /** 시나리오를 끝낸다. 지도의 차 그림과 길 선을 걷는다 */
    private fun stopScenario(silent: Boolean = false) {
        scenAnim?.cancel(); scenAnim = null
        scenJobs.forEach { mainHandler.removeCallbacks(it) }; scenJobs.clear()
        scenRun = null; scenPhase = ""; scenCar = null
        mainStyle?.getLayerAs<org.maplibre.android.style.layers.SymbolLayer>("me")?.setProperties(org.maplibre.android.style.layers.PropertyFactory.iconImage("me-dot"))
        mainStyle?.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>("scen-route")?.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(emptyList()))
        drawScenHud()
        if (!silent && screen == Screen.HOME) refreshMain()
    }

    /** 출발점에서 목적지까지 점선 */
    private fun drawScenRoute(from: Pair<Double, Double>, to: Pair<Double, Double>) {
        mainStyle?.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>("scen-route")?.setGeoJson(
            org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.LineString.fromLngLats(listOf(
                org.maplibre.geojson.Point.fromLngLat(from.second, from.first), org.maplibre.geojson.Point.fromLngLat(to.second, to.first)))))
    }

    /** 지도 위 시나리오 안내: 무엇을 하는 중인지 · 그만 / 끝나면 결과와 「화면 보기」 */
    private fun drawScenHud() {
        val box = scenHud ?: return
        box.removeAllViews(); scenStatus = null
        val s = scenRun ?: return
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = glass(24, 0.95f); setPadding(pxu(12), pxu(10), pxu(12), pxu(10))
            skyShadow(this, 8)
            addView(android.widget.FrameLayout(context).apply {
                background = GradientDrawable().apply { setColor(if (NIGHT) skyTile else Color.parseColor(condTint[s.key] ?: "#EEF2FF")); cornerRadius = pxu(14).toFloat() }
                addView(art(condArt[s.key] ?: R.drawable.ic3_pin, 38), android.widget.FrameLayout.LayoutParams(pxu(38), pxu(38), Gravity.CENTER))
            }, LinearLayout.LayoutParams(pxu(52), pxu(52)).apply { marginEnd = pxu(12) })
            addView(column().apply {
                addView(skyText(when {
                    scenPhase != "done" -> s.title
                    s.key == "region" -> "「${s.goal}」에 들어섰어요"
                    s.key == "place" -> "「${s.goal}」에 왔어요"
                    else -> s.goal
                }, 18f, skyInk, 700).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                val st = skyText(when (scenPhase) { "go" -> "출발 준비 중…"; "done" -> scenResult; else -> "주행 중" }, 15f, if (scenPhase == "done" && scenOpened > 0) skyBlue else skySub, 600)
                addView(st, LinearLayout.LayoutParams(-2, -2).apply { topMargin = pxu(2) })
                if (scenPhase != "done") scenStatus = st
            }, LinearLayout.LayoutParams(0, -2, 1f))
            fun btn(label: String, dark: Boolean, onClick: () -> Unit) = skyText(label, 16f, if (dark) Color.WHITE else skyInk, 700).apply {
                gravity = Gravity.CENTER; setPadding(pxu(16), 0, pxu(16), 0)
                background = pressable(GradientDrawable().apply { setColor(if (dark) skyBlue else if (NIGHT) Color.argb(60, 255, 255, 255) else Color.parseColor("#EEF2F8")); cornerRadius = pxu(20).toFloat() })
                isClickable = true; setOnClickListener { onClick() }
            }
            if (scenPhase == "done") {
                if (scenOpened > 0) addView(btn("화면 보기", true) {
                    stopScenario(silent = true)
                    if (playing != null) Engine.showPlayback() else Engine.playNow()
                }, LinearLayout.LayoutParams(-2, pxu(40)).apply { marginStart = pxu(18) })
                // 글 안부는 몇 초면 다 읽혀 재생 화면이 닫히므로, 안부가 하나 열렸으면 여기서 바로 답장한다
                scenOpenedList.singleOrNull()?.let { m ->
                    addView(btn("답장", false) { stopScenario(silent = true); startReply(m) }, LinearLayout.LayoutParams(-2, pxu(40)).apply { marginStart = pxu(8) })
                }
                addView(btn("닫기", false) { stopScenario() }, LinearLayout.LayoutParams(-2, pxu(40)).apply { marginStart = pxu(8) })
            } else addView(btn("그만", false) { stopScenario() }, LinearLayout.LayoutParams(-2, pxu(40)).apply { marginStart = pxu(18) })
        }, android.widget.FrameLayout.LayoutParams(-1, -2))
    }

    /** 지도 위 차 그림 (오른쪽을 본다) */
    private fun carBitmap(): android.graphics.Bitmap {
        val src = android.graphics.BitmapFactory.decodeResource(resources, R.drawable.ic3_car)
        val s = (dp(64) * MAP_PIXEL_RATIO).toInt()
        val m = android.graphics.Matrix().apply { postScale(-s.toFloat() / src.width, s.toFloat() / src.height); postTranslate(s.toFloat(), 0f) }
        return android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888).also { android.graphics.Canvas(it).drawBitmap(src, m, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)) }
    }

    private fun renderDemoScenarios() {
        root.addView(pageHead(null, "시연", "시나리오를 고르면 지도에서 차가 달려가 그 순간을 만나요", LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(miniPill("직접 조작") { demoManual = true; render() }, LinearLayout.LayoutParams(-2, dp(64)))
            addView(miniPill("처음 상태로") {
                confirm("시연 데이터로 되돌릴까요?", "남긴 안부·들은 기록이 처음 시연 상태로 돌아가요. 들은 안부도 다시 들을 수 있어요.", "되돌리기") {
                    toast("처음 상태로 되돌리는 중이에요…")
                    demoPlaceName = null
                    Engine.resetDemo { toast("시연 데이터로 되돌렸어요"); render() }
                }
            }, LinearLayout.LayoutParams(-2, dp(64)).apply { marginStart = dp(10) })
        }))
        val all = scenarios()
        fun pick(k: String) = if (k.startsWith("place:")) all.firstOrNull { it.key == "place" && it.goal == k.removePrefix("place:") }
            else all.firstOrNull { it.key == k }
        DEMO_FLOW.map { (head, guide, keys) -> Triple(head, guide, keys.mapNotNull { pick(it) }) }
            .filter { it.third.isNotEmpty() }.forEach { (head, guide, list) ->
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
                setPadding(dp(4), dp(6), 0, dp(12))
                addView(text(head, 24f, INK, weight = 700))
                addView(text(guide, 19f, MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) })
            })
            list.chunked(3).forEach { line ->
                root.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    line.forEachIndexed { i, s -> addView(scenCard(s), LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(16) }) }
                    repeat(3 - line.size) { addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(16) }) }
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
            }
        }
    }

    private fun scenCard(s: Scen) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = pressable(rounded(CARD, 1, BORDER))
        if (SKY_SKIN) cardShadow(this)
        setPadding(dp(20), dp(18), dp(22), dp(18))
        alpha = if (s.ready) 1f else 0.45f
        addView(tile3d(condArt[s.key] ?: R.drawable.ic3_pin, if (NIGHT) skyTile else Color.parseColor(condTint[s.key] ?: "#EEF2FF"), 76, 54),
            LinearLayout.LayoutParams(dp(76), dp(76)).apply { marginEnd = dp(18) })
        addView(column().apply {
            addView(text(s.title, 24f, INK, weight = 700).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            addView(text(s.sub, 19f, MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(text("▶", 22f, if (s.ready) ACCENT else MUTED, weight = 700), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        isClickable = true
        setOnClickListener { runScenario(s) }
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
        leavePickingPlace = false; leaveMoreOpen = false
        leaveTo = emptyList(); leaveReplyTo = null
        leaveHours = "any"; leaveDays = "any"; leaveFrom = "now"; leaveUntil = "forever"; leaveRepeat = "once"
        if (leaveCircleId == null || store.keyOf(leaveCircleId!!) == null) leaveCircleId = store.activeCircleId
        leaveMapCenter = null
        val lc = leaveCircle()
        if (leaveAuthor == null || lc?.members?.none { it.id == leaveAuthor } == true) leaveAuthor = lc?.id?.let { store.ownerOf(it) } ?: store.driverId
        // 새로 열 때는 늘 「지금 이 자리를 지나면」부터: 답장이나 지난번에 고른 장소가 남아 있으면 엉뚱한 곳에 남는다
        // (답장은 이 함수 뒤에 startReply가 장소를 다시 정한다)
        leaveWhen = "place"
        lastLoc?.let { leavePlace = Place(HERE, it.first, it.second); leaveKey = HERE } ?: run { if (leaveKey == HERE) leavePlace = null }
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
        if (SKY_SKIN) { renderLeaveOnePage(c); return }
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
        val cols = if (SKY_SKIN) 4 else 3
        WHEN_OPTIONS.chunked(cols).forEach { group ->
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                group.forEachIndexed { i, (key, name) ->
                    addView(optionTile(WHEN_ICONS.getValue(key), name, WHEN_HINTS.getValue(key), leaveWhen == key, art = if (SKY_SKIN) condArt[key] else null, artTint = condTint[key]) {
                        leaveWhen = key; leaveStep++; render()
                    // 꽉 찬 줄은 칸 높이를 맞추고, 덜 찬 마지막 줄은 제 높이로 (모두 맞추기면 줄이 접힌다)
                    }, LinearLayout.LayoutParams(0, if (group.size == cols) -1 else -2, 1f).apply { if (i > 0) marginStart = dp(14) })
                }
                repeat(cols - group.size) { addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(14) }) }
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
            })
        }
    }

    /** 고르기 카드: 아이콘 · 이름 · 한 줄 설명 */
    private fun optionTile(res: Int, name: String, hint: String, selected: Boolean, art: Int? = null, artTint: String? = null, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = pressable(if (selected) rounded(CHIP, 2, ACCENT) else rounded(CARD, 1, BORDER))
        setPadding(dp(28), dp(28), dp(28), dp(24))
        minimumHeight = dp(180)
        if (SKY_SKIN) cardShadow(this)
        if (art != null) addView(tile3d(art, if (selected) Color.WHITE else if (NIGHT) skyTile else Color.parseColor(artTint ?: "#EEF2FF"), 72, 52),
            LinearLayout.LayoutParams(dp(72), dp(72)).apply { bottomMargin = dp(16) })
        else addView(icon(res, 48, if (selected) ACCENT else INK), LinearLayout.LayoutParams(dp(48), dp(48)).apply { bottomMargin = dp(14) })
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
        root.addView(label("이 근처를 모임의 다른 사람이 지나가면 그 사람 차에서 들려줘요."))
        root.addView(space(12))
        val here = lastLoc?.let { Place(HERE, it.first, it.second) }
        // 좌표가 아니라 고른 버튼으로 표시한다 (지금 자리가 예시 장소 근처여도 하나만 ✓). prefix는 이게 어떤 장소인지 알려 주는 표시.
        fun pick(p: Place, prefix: String = "") =
            choice(prefix + (if (leaveKey == p.name) leavePlace?.name ?: p.name else p.name), leaveKey == p.name) { leavePlace = p; leaveKey = p.name; render() }
        // 무슨 장소인지 묶음마다 이름표를 붙인다: 지금 있는 곳 · 내비에 저장된 장소(집/회사/즐겨찾기/최근) · 예시
        root.addView(section(R.drawable.ic_pin, "지금 있는 곳 · 직접 고르기"))
        root.addView(row(*listOfNotNull(here?.let { pick(it) }, choice("지도에서 고르기", leaveKey == MAP_PICK) {
            if (leaveKey != MAP_PICK) { leaveKey = MAP_PICK; leavePlace = null; leaveMapCenter = null }
            render()
        }).toTypedArray()))
        val navi = Engine.naviPlaces
        if (navi.isNotEmpty()) {
            root.addView(section(R.drawable.ic_home, "내비에 저장된 장소"))
            root.addView(label("🏠 집 · 🏢 회사 · ⭐ 즐겨찾기 · 🕘 최근 목적지"))
            navi.take(4).map { n ->
                pick(Place(n.name, n.lat, n.lon), when (n.kind) { "home" -> "🏠 "; "office" -> "🏢 "; "recent" -> "🕘 "; else -> "⭐ " })
            }.chunked(3).forEach { group -> root.addView(row(*group.toTypedArray())) }
        } else {
            root.addView(section(R.drawable.ic_map, "예시 장소"))
            root.addView(label("내비에 저장된 장소가 없어서 예시로 보여 드려요. 원하는 곳은 「지도에서 고르기」로 골라 주세요."))
            PRESETS.take(4).map { pick(it) }.chunked(3).forEach { group -> root.addView(row(*group.toTypedArray())) }
        }
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
            line("언제", whenName),
            line("조건", condSummary()),
            leavePlace?.takeIf { leaveWhen in WITH_PLACE }?.let { p ->
                if (leaveWhen == "region") line("지역", Regions.districtOf(p.lat, p.lon)?.let { fullName(it) } ?: p.name)
                else line("장소", "${p.name} · ${if (leaveRadius < 1000) "${leaveRadius}m" else "${leaveRadius / 1000}km"} 안")
            },
            line("받는 사람", if (leaveTo.isEmpty()) "모임 모두" else leaveTo.joinToString(", ") { c.nameOf(it) }),
            line("내용", if (recorder.hasRecording) "목소리 메시지" else "“$leaveText”"),
        )))
        // 어떤 조건이든 같은 8개: 받는 사람 · 시간대 · 요일 · 언제부터 / 언제까지 · 횟수 · 모임 · 보내는 사람
        val hours = hoursFor(leaveWhen)
        if (hours.none { it.first == leaveHours }) leaveHours = "any"
        root.addView(row(
            secondary("받는 사람") {
                val others = c.members.filter { it.id != author }
                pickFrom("누구에게 남길까요?", listOf("모임 모두") + others.map { it.name }) { i -> leaveTo = if (i == 0) emptyList() else listOf(others[i - 1].id); render() }
            },
            secondary("시간: ${WHEN_HOURS.first { it.first == leaveHours }.second}") {
                val why = if (leaveWhen == "night") " (밤길이라 아침은 빼요)" else ""
                pickFrom("몇 시에 들려줄까요?$why", hours.map { it.second }) { i -> leaveHours = hours[i].first; render() }
            },
            secondary("요일: ${WHEN_DAYS.first { it.first == leaveDays }.second}") {
                pickFrom("무슨 요일에 들려줄까요?", WHEN_DAYS.map { it.second }) { i -> leaveDays = WHEN_DAYS[i].first; render() }
            },
            secondary("부터: ${WHEN_FROM.first { it.first == leaveFrom }.second}") {
                pickFrom("언제부터 들려줄까요?", WHEN_FROM.map { it.second }) { i -> leaveFrom = WHEN_FROM[i].first; render() }
            },
        ))
        root.addView(row(
            secondary("까지: ${WHEN_UNTIL.first { it.first == leaveUntil }.second}") {
                pickFrom("언제까지 들려줄까요? (시작한 날부터)", WHEN_UNTIL.map { it.second }) { i -> leaveUntil = WHEN_UNTIL[i].first; render() }
            },
            secondary("횟수: ${REPEATS.first { it.first == leaveRepeat }.second}") {
                pickFrom("몇 번 들려줄까요?", REPEATS.map { it.second }) { i -> leaveRepeat = REPEATS[i].first; render() }
            },
            secondary("모임: ${c.name}") {
                val list = store.liveMemberships.mapNotNull { Engine.circles[it.circleId] }
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

    /**
     * 한 화면 남기기: 왼쪽 「무엇을」(말로 적기·녹음·키보드), 오른쪽 「언제」(조건 9개 + 장소), 아래 「남기기」.
     * 기본값으로 바로 남길 수 있게: 이 그룹 모두에게 · 지금 이 자리를 지나면 · 언제든 · 한 번.
     * 시간·요일·기간·횟수는 「자세히」 안에 접어 둔다. 장소를 바꿀 때만 장소 고르기 화면이 잠깐 열린다.
     */
    private fun renderLeaveOnePage(c: Circle) {
        if (leavePickingPlace) { renderLeavePlacePicker(); return }
        if (WHEN_OPTIONS.none { it.first == leaveWhen }) leaveWhen = "place"
        val author = leaveAuthor?.takeIf { id -> c.members.any { it.id == id } }
        val reply = leaveReplyTo
        val toName = if (leaveTo.isEmpty()) "${c.name} 모두" else leaveTo.joinToString(", ") { c.nameOf(it) }

        // 머리: 누구에게 (그룹 · 받는 사람)
        root.addView(pageHead(if (reply != null) "${reply.authorName}님에게 답장" else null, "안부 남기기", null,
            right = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                val joined = store.liveMemberships.mapNotNull { Engine.circles[it.circleId] }
                if (joined.size > 1) addView(pill("그룹 · ${c.name} ▾", dark = false, heightDp = 68) {
                    pickFrom("어느 그룹에 남길까요?", joined.map { it.name }) { i -> leaveCircleId = joined[i].id; leaveAuthor = store.ownerOf(joined[i].id); leaveTo = emptyList(); render() }
                }.apply { textSize = 21f }, LinearLayout.LayoutParams(-2, dp(68)).apply { marginEnd = dp(12) })
                addView(pill("받는 사람 · ${if (leaveTo.isEmpty()) "모두" else toName} ▾", dark = false, heightDp = 68) {
                    val others = c.members.filter { it.id != author }
                    pickFrom("누구에게 남길까요?", listOf("${c.name} 모두") + others.map { it.name }) { i -> leaveTo = if (i == 0) emptyList() else listOf(others[i - 1].id); render() }
                }.apply { textSize = 21f })
                if (author == null) addView(pill("보내는 사람 고르기", dark = true, heightDp = 68) { pickAuthor(c) }.apply { textSize = 21f },
                    LinearLayout.LayoutParams(-2, dp(68)).apply { marginStart = dp(12) })
            }))

        // 가운데: 무엇을 | 언제
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(leaveWhatCard(), LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(18) })
            addView(leaveWhenCard(), LinearLayout.LayoutParams(0, -2, 1.05f))
        }, LinearLayout.LayoutParams(-1, -2))

        // 운전 중 말로 남기기: 하는 말 · 그때 쓸 조건 (Gleo에게는 조건을 말하지 않는다)
        root.addView(voiceLeaveRow(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })

        // 아래: 한 줄 요약 + 남기기
        val hasContent = !recorder.recording && !dictation.listening && (recorder.hasRecording || leaveText.isNotBlank())
        val placeOk = leaveWhen !in WITH_PLACE || leavePlace != null
        val ready = hasContent && placeOk && author != null && !sending
        val whenName = WHEN_OPTIONS.first { it.first == leaveWhen }.second
        val whenLine = when (leaveWhen) {
            "place" -> "「${leavePlace?.name ?: "고른 곳"}」을 지나면"
            "park" -> "「${leavePlace?.name ?: "고른 곳"}」에 도착해 세우면"
            "region" -> "${leavePlace?.let { p -> Regions.districtOf(p.lat, p.lon)?.let { fullName(it) } } ?: "그 지역"}에 들어가면"
            else -> "「$whenName」"
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(20), 0, 0)
            addView(column().apply {
                addView(text("${toName}에게 · $whenLine 들려줘요", 24f, INK, weight = 700).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                addView(text(when {
                    author == null -> "보내는 사람을 골라 주세요"
                    !placeOk -> "장소를 골라 주세요"
                    !hasContent -> "남길 말을 먼저 적거나 녹음해 주세요"
                    else -> "${c.nameOf(author)}님 차에서는 들리지 않아요"
                }, 20f, MUTED))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(pill(if (sending) "보내는 중…" else "남기기", dark = true, heightDp = 96) { if (ready) sendLeave(c, author!!) }.apply {
                textSize = 28f; alpha = if (ready) 1f else 0.35f; minWidth = dp(320)
            })
        })
    }

    /** 메시지 화면의 말로 남기기 줄: 「녹음해 줘」 하는 법 · 남기는 방식(목소리/글) · 말로 남길 때 쓸 조건 고르기 */
    private fun voiceLeaveRow() = panel(LinearLayout.HORIZONTAL).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(26), dp(20), dp(26), dp(20))
        addView(android.widget.FrameLayout(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (NIGHT) skyTile else Color.parseColor("#EAF1FF")) }
            addView(icon(R.drawable.ic_mic, 34, ACCENT), android.widget.FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(72), dp(72)).apply { marginEnd = dp(20) })
        addView(column().apply {
            addView(text("운전 중엔 모임 이름을 말하고 「녹음해 줘」", 23f, INK, weight = 700))
            addView(text("① “글레오” → 띠링 → ② “${Engine.groupNames().firstOrNull() ?: "모임 이름"} 녹음해 줘” → ③ 삐 소리 뒤 남길 말", 20f, MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, 0) })
            // 운전 중에는 목소리 그대로만 남긴다. 글(기계음)로 남기기는 정차 중 이 메시지 화면에서 (말로 적기 · 글로 쓰기)
            addView(text("내 목소리 그대로 남아요 · 말을 멈추면 저절로 끝나고 소리로 알려 줘요 · 글로 남기려면 위에서", 18f, MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(2), 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val keys = WHEN_OPTIONS.map { it.first }
        val cur = store.voiceWhen.takeIf { it in keys } ?: "place"
        addView(column().apply {
            gravity = Gravity.END
            addView(text("말로 남기면 이때 들려줘요", 18f, MUTED, weight = 600))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, 0)
                // 위에서 고른 조건과 다르면 한 번에 맞출 수 있게
                if (leaveWhen != cur && leaveWhen in keys) addView(miniPill("위 조건으로") { store.voiceWhen = leaveWhen; render() }, LinearLayout.LayoutParams(-2, dp(60)).apply { marginEnd = dp(10) })
                addView(miniPill("${WHEN_SHORT[cur] ?: WHEN_OPTIONS.first { it.first == cur }.second} ▾", color = ACCENT) {
                    pickFrom("말로 남길 때 언제 들려줄까요?", WHEN_OPTIONS.map { WHEN_SHORT[it.first] ?: it.second }) { i -> store.voiceWhen = keys[i]; render() }
                }, LinearLayout.LayoutParams(-2, dp(60)))
            })
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(18) })
    }

    /** 무엇을: 미리 보기 + 말로 적기(기본) · 목소리 녹음 · 키보드 */
    private fun leaveWhatCard() = panel().apply {
        setPadding(dp(32), dp(28), dp(32), dp(28))
        addView(text("무엇을 남길까요?", 28f, INK, weight = 800))
        addView(text("목소리 ${Recorder.MAX_SECONDS}초 · 글 ${MAX_TEXT}자까지", 19f, MUTED).apply { setPadding(0, dp(2), 0, dp(16)) })
        // 미리 보기 칸: 지금 남길 것
        val box = column().apply {
            background = rounded(SOFT); setPadding(dp(28), dp(24), dp(28), dp(24)); minimumHeight = dp(300)
            gravity = Gravity.CENTER_VERTICAL
        }
        when {
            dictation.listening -> {
                box.addView(text("● 듣고 있어요", 20f, MEMBER_COLORS["red"] ?: ACCENT, weight = 700))
                box.addView(text(listOf(leaveText, dictation.partial).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "말해 보세요…" }, 30f, INK, weight = 700).apply { setPadding(0, dp(8), 0, 0) })
            }
            recorder.recording -> box.addView(text("● 녹음 중이에요… 다 말하면 「녹음 끝」", 28f, INK, weight = 700))
            recorder.hasRecording -> box.addView(previewCard().apply { background = null; setPadding(0, 0, 0, 0); elevation = 0f })
            leaveText.isNotBlank() -> {
                box.addView(text("“$leaveText”", 32f, INK, weight = 700).apply { setLineSpacing(0f, 1.25f) })
                box.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, 0)
                    val white = { v: TextView -> v.background = pressable(GradientDrawable().apply { setColor(CARD); cornerRadius = dp(28).toFloat() }) }
                    addView(miniPill("고치기") { askText("안부 (${MAX_TEXT}자까지)", leaveText, multiline = true, maxLength = MAX_TEXT) { leaveText = it; recorder.clear(); render() } }.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = 0; white(this) })
                    addView(miniPill("지우기") { leaveText = ""; render() }.apply { white(this) })
                })
            }
            else -> {
                box.gravity = Gravity.CENTER
                box.addView(text("아래 「말로 적기」를 누르고\n전하고 싶은 말을 해 보세요", 26f, MUTED).apply { gravity = Gravity.CENTER })
            }
        }
        addView(box, LinearLayout.LayoutParams(-1, 0, 1f))
        // 방법: 말로 적기(크게) · 목소리 녹음 · 키보드
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(18), 0, 0)
            val dict = when {
                dictation.listening -> pill("■  다 말했어요", dark = true, heightDp = 88) { dictation.stop() }
                else -> pill(if (leaveText.isNotBlank() && !recorder.hasRecording) "🎤  말로 이어 쓰기" else "🎤  말로 적기", dark = true, heightDp = 88) { startDictation() }
            }
            addView(dict, LinearLayout.LayoutParams(0, dp(88), 1.3f))
            val rec = if (recorder.recording) pill("■  녹음 끝", dark = false, heightDp = 88) { if (recordTapOk()) { recorder.stop(); render() } }
                else pill(if (recorder.hasRecording) "다시 녹음" else "목소리 녹음", dark = false, heightDp = 88) { if (recordTapOk()) startRecording() }
            addView(rec, LinearLayout.LayoutParams(0, dp(88), 1f).apply { marginStart = dp(12) })
            addView(pill("키보드", dark = false, heightDp = 88) {
                askText("안부 (${MAX_TEXT}자까지)", leaveText, multiline = true, maxLength = MAX_TEXT) { leaveText = it; recorder.clear(); render() }
            }, LinearLayout.LayoutParams(0, dp(88), 0.8f).apply { marginStart = dp(12) })
        })
    }

    /** 언제: 조건 9개 작은 칸 · 고른 조건 설명 · (장소 조건이면) 장소 · 자세히 */
    private fun leaveWhenCard() = panel().apply {
        setPadding(dp(30), dp(28), dp(30), dp(24))
        addView(text("언제 들려줄까요?", 28f, INK, weight = 800).apply { setPadding(0, 0, 0, dp(14)) })
        WHEN_OPTIONS.chunked(3).forEach { line ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                line.forEachIndexed { i, (key, name) ->
                    val sel = leaveWhen == key
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        background = pressable(if (sel) rounded(CHIP, 3, ACCENT) else rounded(SOFT))
                        setPadding(dp(14), dp(12), dp(12), dp(12))
                        addView(android.widget.ImageView(context).apply { setImageResource(condArt[key] ?: R.drawable.ic3_pin); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER },
                            LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(12) })
                        addView(text(WHEN_SHORT[key] ?: name, 22f, if (sel) ACCENT else INK, weight = if (sel) 800 else 600).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
                        isClickable = true
                        setOnClickListener {
                            leaveWhen = key
                            if (key in WITH_PLACE && leavePlace == null) lastLoc?.let { leavePlace = Place(HERE, it.first, it.second); leaveKey = HERE }
                            render()
                        }
                    }, LinearLayout.LayoutParams(0, dp(100), 1f).apply { if (i > 0) marginStart = dp(10) })
                }
                repeat(3 - line.size) { addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(10) }) }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        addView(text(WHEN_HINTS[leaveWhen] ?: "", 20f, MUTED).apply { setPadding(dp(4), dp(4), 0, dp(10)) })

        // 장소 조건이면: 어디 · 범위 · 바꾸기
        if (leaveWhen in WITH_PLACE) addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(SOFT); setPadding(dp(18), dp(12), dp(12), dp(12))
            addView(android.widget.ImageView(context).apply { setImageResource(R.drawable.ic3_pin) }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) })
            val p = leavePlace
            addView(column().apply {
                addView(text(when {
                    p == null -> "장소를 골라 주세요"
                    leaveWhen == "region" -> Regions.districtOf(p.lat, p.lon)?.let { fullName(it) } ?: p.name
                    else -> p.name
                }, 23f, INK, weight = 700).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                if (p != null && leaveWhen != "region") addView(text("${if (leaveRadius < 1000) "${leaveRadius}m" else "${leaveRadius / 1000}km"} 안에 들어오면", 18f, MUTED))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(miniPill("장소 바꾸기") { leavePickingPlace = true; render() }.apply { background = pressable(GradientDrawable().apply { setColor(CARD); cornerRadius = dp(28).toFloat() }) })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        // 자세히: 시간 · 요일 · 부터 · 까지 · 횟수 (기본은 접힘)
        val hours = hoursFor(leaveWhen)
        if (hours.none { it.first == leaveHours }) leaveHours = "any"
        addView(text("${if (leaveMoreOpen) "▴" else "▾"}  자세히 · ${condSummary()}", 19f, ACCENT, weight = 600).apply {
            setPadding(dp(4), dp(10), 0, dp(6)); isClickable = true; setOnClickListener { leaveMoreOpen = !leaveMoreOpen; render() }
        })
        if (leaveMoreOpen) {
            // 두 줄: 들려줄 때(시간·요일·횟수) / 남아 있는 기간(시작·끝)
            fun pillRow(vararg pills: View) = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                pills.forEachIndexed { i, p -> addView(p.apply { if (i == 0) (layoutParams as? LinearLayout.LayoutParams)?.marginStart = 0 }) }
            }
            addView(pillRow(
                miniPill("시간 · ${WHEN_HOURS.first { it.first == leaveHours }.second}") {
                    pickFrom("몇 시에 들려줄까요?${if (leaveWhen == "night") " (밤길이라 아침은 빼요)" else ""}", hours.map { it.second }) { i -> leaveHours = hours[i].first; render() }
                },
                miniPill("요일 · ${WHEN_DAYS.first { it.first == leaveDays }.second}") {
                    pickFrom("무슨 요일에 들려줄까요?", WHEN_DAYS.map { it.second }) { i -> leaveDays = WHEN_DAYS[i].first; render() }
                },
                // 잔소리가 되지 않게 「한 번만」과 「주 1회까지」 두 가지뿐
                miniPill("횟수 · ${REPEATS.first { it.first == leaveRepeat }.second}") {
                    pickFrom("한 사람에게 몇 번 들려줄까요?", REPEATS.map { it.second }) { i -> leaveRepeat = REPEATS[i].first; render() }
                },
            ), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            addView(pillRow(
                miniPill("시작 · ${WHEN_FROM.first { it.first == leaveFrom }.second}") {
                    pickFrom("이 안부를 언제부터 들을 수 있게 할까요?", WHEN_FROM.map { it.second }) { i -> leaveFrom = WHEN_FROM[i].first; render() }
                },
                miniPill("끝 · ${WHEN_UNTIL.first { it.first == leaveUntil }.second}") {
                    pickFrom("언제 사라지게 할까요? 시작한 날부터 셉니다", WHEN_UNTIL.map { it.second }) { i -> leaveUntil = WHEN_UNTIL[i].first; render() }
                },
            ), LinearLayout.LayoutParams(-1, -2))
        }
    }

    /** 장소 바꾸기: 예전 장소 단계 그대로 + 돌아가기 / 이 장소로 */
    private fun renderLeavePlacePicker() {
        leavePlaceStep()
        root.addView(space(8))
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(secondary("← 돌아가기") { leavePickingPlace = false; render() }, LinearLayout.LayoutParams(0, dp(96), 1f))
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(primary("이 장소로") { if (leavePlace != null) { leavePickingPlace = false; render() } }.apply { alpha = if (leavePlace != null) 1f else 0.4f },
                LinearLayout.LayoutParams(0, dp(96), 1f).apply { marginStart = dp(14) })
        })
    }

    /** 「언제든 · 매일 · 기한 없음 · 한 번만」: 시작은 오늘이 아닐 때만 */
    private fun condSummary(): String = listOfNotNull(
        WHEN_HOURS.first { it.first == leaveHours }.second,
        WHEN_DAYS.first { it.first == leaveDays }.second,
        WHEN_FROM.first { it.first == leaveFrom }.second.takeIf { leaveFrom != "now" }?.let { "${it}부터" },
        if (leaveUntil == "forever") "기한 없음" else "${WHEN_UNTIL.first { it.first == leaveUntil }.second} 사라짐",
        REPEATS.first { it.first == leaveRepeat }.second,
    ).joinToString(" · ")

    /** 조건과 안 맞는 시간대는 뺀다: 밤길(전조등)에 아침은 어색하다 */
    private fun hoursFor(whenKey: String) = if (whenKey == "night") WHEN_HOURS.filter { it.first != "morning" } else WHEN_HOURS

    /** 시작한 날(언제부터)에서 언제까지. 「계속」이면 null */
    private fun untilDate(fromKey: String, untilKey: String): String? {
        val start = fromDate(fromKey)?.let { java.time.LocalDate.parse(it) } ?: java.time.LocalDate.now()
        return when (untilKey) {
            "week" -> start.plusWeeks(1); "month" -> start.plusMonths(1); "year" -> start.plusYears(1); else -> null
        }?.toString()
    }

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
        if (requestCode == REQ_NOTI) { Engine.restartGps(); return }
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
            // 「지금 이 자리」는 받는 사람에게 뜻이 없다: 시연으로 온 장소 이름, 아니면 시·군·구 이름으로 남긴다
            val here = if (p.name == HERE) demoPlaceName ?: Regions.districtOf(p.lat, p.lon)?.let { fullName(it) } else null
            body.put("placeName", region?.let { fullName(it) } ?: here ?: p.name).put("lat", p.lat).put("lon", p.lon).put("radiusM", leaveRadius)
            region?.let { body.put("regionCode", it.code) }
        }
        if (leaveTo.isNotEmpty()) body.put("to", org.json.JSONArray(leaveTo))
        leaveReplyTo?.let { body.put("replyTo", it.id) }
        WHEN_HOUR_RANGE[leaveHours]?.let { (f, t) -> body.put("hourFrom", f).put("hourTo", t) }
        WHEN_DAY_SET[leaveDays]?.let { body.put("days", org.json.JSONArray(it.toList())) }
        fromDate(leaveFrom)?.let { body.put("notBefore", it) }
        untilDate(leaveFrom, leaveUntil)?.let { body.put("notAfter", it) }
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
    // 하늘 스킨 화면 (오늘 · 소식) ---------------------------------------------------------
    // 메인과 같은 말투로: 큰 제목 하나, 큰 카드 하나에 할 일 하나, 3D 그림은 칸 안에.

    /** 색 칸 안의 3D 그림 */
    private fun tile3d(res: Int, tint: Int, sizeDp: Int, artDp: Int) = android.widget.FrameLayout(this).apply {
        background = GradientDrawable().apply { setColor(tint); cornerRadius = dp(sizeDp * 3 / 10).toFloat() }
        addView(android.widget.ImageView(context).apply { setImageResource(res); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER },
            android.widget.FrameLayout.LayoutParams(dp(artDp), dp(artDp), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** 둥근 알약 버튼. dark면 메인의 「내 위치로」처럼 진한 바탕 */
    private fun pill(s: String, dark: Boolean, heightDp: Int = 84, onClick: () -> Unit) = text(s, 24f, if (dark) skyOnInk else INK, weight = 700).apply {
        gravity = Gravity.CENTER
        setPadding(dp(40), 0, dp(40), 0)
        background = pressable(GradientDrawable().apply {
            setColor(if (dark) skyInk else SOFT); cornerRadius = dp(heightDp / 2).toFloat()
        })
        if (dark) cardShadow(this)
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, dp(heightDp))
    }

    /** 작은 상태 캡슐 (메인의 「민수의 차 · 주차 중」과 같은 모양) */
    private fun capsule(s: String) = text(s, 20f, MUTED, weight = 600).apply {
        background = GradientDrawable().apply {
            setColor(if (NIGHT) Color.argb(170, 22, 31, 72) else Color.argb(190, 255, 255, 255)); cornerRadius = dp(40).toFloat()
            setStroke(1, if (NIGHT) Color.argb(50, 255, 255, 255) else Color.WHITE)
        }
        setPadding(dp(22), dp(8), dp(22), dp(8))
        layoutParams = LinearLayout.LayoutParams(-2, -2)
    }

    /** 페이지 머리: 캡슐 · 큰 제목 · 한 줄 (· 오른쪽 버튼) */
    private fun pageHead(cap: String?, head: CharSequence, sub: String?, right: View? = null) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
        addView(column().apply {
            cap?.let { addView(capsule(it)) }
            addView(text("", 46f, INK, weight = 800).apply { text = head; letterSpacing = -0.025f; setPadding(0, dp(if (cap != null) 12 else 0), 0, 0) })
            sub?.let { addView(text(it, 24f, MUTED).apply { setPadding(0, dp(2), 0, 0) }) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        right?.let { addView(it) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(22) }
    }

    /** 얼굴 겹쳐 놓기 (최대 4) */
    private fun faceStack(names: List<Pair<String, Member?>>, sizeDp: Int) = android.widget.FrameLayout(this).apply {
        val shown = names.take(4)
        shown.forEachIndexed { i, (n, mem) ->
            addView(avatar(n, sizeDp, member = mem).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(CARD) }
                setPadding(dp(3), dp(3), dp(3), dp(3))
            }, android.widget.FrameLayout.LayoutParams(-2, -2).apply { leftMargin = dp(i * (sizeDp * 2 / 3)) })
        }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp + 6 + (shown.size - 1).coerceAtLeast(0) * (sizeDp * 2 / 3)), dp(sizeDp + 6))
    }

    private fun memberOf(m: PlaceMessage) = Engine.circles[m.circleId]?.member(m.authorId) ?: circle?.member(m.authorId)

    /** 오늘: 오늘 온 안부(모아 듣기·기다리는 안부) · 가까운 안부 · 발견한 안부 · 모임 소식 */
    private fun renderTodaySky() {
        val driver = circle?.member(store.driverId)?.let { if (it.title.isNotBlank() && circle?.type == "family") it.title else "${it.name}님" }
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val hello = when (hour) { in 5..10 -> "좋은 아침이에요"; in 11..16 -> "좋은 오후예요"; in 17..21 -> "좋은 저녁이에요"; else -> "오늘도 수고 많았어요" }
        val total = viewHeardToday().size + viewPending().size
        val states = listOfNotNull(todayLabel(), "정차 중", "비".takeIf { Engine.rainNow }, "밤길".takeIf { Engine.nightNow }, "서버 연결 안 됨".takeIf { !serverOk })
        root.addView(pageHead(states.joinToString(" · "), if (driver != null) "$driver, $hello" else "안녕하세요",
            if (total == 0) "오늘은 아직 온 안부가 없어요" else "오늘 안부 ${total}개가 왔어요",
            right = circleSwitch { render() }))

        // 왼쪽: 큰 카드 하나 + 작은 카드 둘
        val left = column()
        left.addView(todayHero(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
        left.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(nearCard(), LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(18) })
            addView(foundCard(), LinearLayout.LayoutParams(0, -1, 1f))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
        left.addView(momentsCard(), LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, -1, 1.5f))
            addView(peopleCard(), LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(18) })
        }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    /** 오늘 온 안부: 얼굴 · 개수 · 모아 듣기. 기다리는 안부가 있으면 아래에 「지금 듣기」 */
    private fun todayHero() = panel().apply {
        setPadding(dp(36), dp(32), dp(36), dp(32))
        val heardToday = viewHeardToday()
        val pending = viewPending()
        val total = heardToday.size + pending.size
        val hide = discreet()
        val who = (heardToday.map { it.authorName to circle?.memberByName(it.authorName) } + pending.map { it.authorName to memberOf(it) }).distinctBy { it.first }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(column().apply {
                addView(text("오늘 받은 안부", 22f, MUTED, weight = 600))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(10), 0, dp(6))
                    if (who.isNotEmpty() && !hide) addView(faceStack(who, 64), (LinearLayout.LayoutParams(-2, -2)).apply { marginEnd = dp(18) })
                    else addView(tile3d(R.drawable.ic3_pin, skyTile, 72, 52), LinearLayout.LayoutParams(dp(72), dp(72)).apply { marginEnd = dp(18) })
                    addView(text(if (total == 0) "0개" else "${total}개", 56f, INK, weight = 800).apply { letterSpacing = -0.02f })
                })
                addView(text(when {
                    total == 0 -> "안부를 남긴 곳을 지나면 여기에 모여요"
                    hide -> "옆자리에 누가 있어 보낸 사람은 가려 두었어요"
                    else -> who.joinToString(", ") { "${it.first}님" } + "이 남겼어요"
                }, 22f, MUTED))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val can = total > 0 && store.driverId != null
            addView(pill("▶  모아 듣기", dark = true, heightDp = 96) { if (can) Engine.playToday(viewCircle) }.apply { alpha = if (can) 1f else 0.35f })
        })
        // 기다리는 안부
        val m = pending.firstOrNull() ?: return@apply
        addView(View(context).apply { setBackgroundColor(BORDER) }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(24); bottomMargin = dp(20) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (hide) {
                addView(tile3d(R.drawable.cd_together, skyTile, 64, 46), LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(18) })
                addView(column().apply {
                    addView(text("안부가 도착했어요", 26f, INK, bold = true))
                    addView(text(if (Engine.askingPassenger) "지금 들을까요? 다음에 들어도 돼요" else "${pending.size}개 · 원할 때 언제든 들을 수 있어요", 20f, MUTED))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                if (Engine.askingPassenger) addView(pill("다음에", dark = false, heightDp = 72) { Engine.passengerLater() }.apply {
                    (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(12) })
            } else {
                addView(tile3d(condArt[m.opensOn] ?: R.drawable.ic3_pin, if (NIGHT) skyTile else Color.parseColor(condTint[m.opensOn] ?: "#EEF2FF"), 64, 46),
                    LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(18) })
                addView(column().apply {
                    addView(text("기다리는 안부 ${pending.size}개", 20f, ACCENT, weight = 700))
                    addView(text("${m.authorName}님이 ${m.occasion} 남겼어요", 26f, INK, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            addView(text("▶  지금 듣기", 22f, Color.WHITE, weight = 700).apply {
                gravity = Gravity.CENTER; setPadding(dp(32), 0, dp(32), 0)
                background = pressable(GradientDrawable().apply { setColor(ACCENT); cornerRadius = dp(36).toFloat() })
                isClickable = true; setOnClickListener { Engine.playNow(viewCircle) }
            }, LinearLayout.LayoutParams(-2, dp(72)))
        })
    }

    /** 이런 순간에 열려요: 상황 조건으로 기다리는 안부를 3D 그림과 개수로 */
    private fun momentsCard() = panel().apply {
        setPadding(dp(30), dp(26), dp(30), dp(22))
        val ids = foundIds()
        val order = WHEN_OPTIONS.map { it.first }
        val waiting = visibleMessages().filter { !isMine(it) && !it.isPlace && it.id !in ids }.groupBy { it.opensOn }
            .entries.sortedBy { order.indexOf(it.key) }
        addView(text("이런 순간에 열려요", 26f, INK, bold = true))
        addView(text(if (waiting.isEmpty()) "상황에 맞춰 기다리는 안부가 없어요" else "기다리는 안부 ${waiting.sumOf { it.value.size }}개", 20f, MUTED).apply { setPadding(0, 0, 0, dp(12)) })
        val name = WHEN_OPTIONS.toMap()
        waiting.take(6).chunked(3).forEach { row ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                row.forEachIndexed { i, (key, list) ->
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = rounded(SOFT)
                        setPadding(dp(14), dp(12), dp(18), dp(12))
                        addView(tile3d(condArt[key] ?: R.drawable.ic3_pin, if (NIGHT) skyTile else Color.parseColor(condTint[key] ?: "#EEF2FF"), 56, 40),
                            LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(12) })
                        addView(column().apply {
                            addView(text(name[key] ?: key, 21f, INK, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                            addView(text("${list.size}개 · " + list.map { it.authorName }.distinct().joinToString(", "), 18f, MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                    }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(12) })
                }
                repeat(3 - row.size) { addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(12) }) }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
    }

    /** 가장 가까운 안부 (아직 못 들은 곳). 누르면 지도 */
    private fun nearCard() = panel().apply {
        setPadding(dp(30), dp(28), dp(30), dp(28))
        val loc = lastLoc
        val found = foundIds()
        val near = loc?.let { l -> visibleMessages().filter { it.isPlace && !isMine(it) && it.id !in found }.minByOrNull { distanceM(l.first, l.second, it.lat, it.lon) } }
        addView(tile3d(R.drawable.ic3_pin, if (NIGHT) skyTile else Color.parseColor("#FDECEE"), 76, 56), LinearLayout.LayoutParams(dp(76), dp(76)).apply { bottomMargin = dp(18) })
        addView(text("가장 가까운 안부", 22f, MUTED, weight = 600))
        if (near == null || loc == null) {
            addView(text(if (loc == null) "위치를 확인하는 중" else "근처에 남은 안부가 없어요", 28f, INK, bold = true).apply { setPadding(0, dp(6), 0, 0) })
        } else {
            val d = distanceM(loc.first, loc.second, near.lat, near.lon)
            addView(text(near.placeName, 28f, INK, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, 0) })
            addView(text(if (d < 1000) "${d.toInt()}m" else "%.1fkm".format(d / 1000), 40f, ACCENT, weight = 800))
        }
        addView(text("지도에서 보기 ›", 20f, MUTED).apply { setPadding(0, dp(10), 0, 0) })
        setOnClickListener { goTab(Screen.HOME) }
    }

    /** 발견한 안부: 모임 사람들이 나에게 남긴 것 중 들은 수 */
    private fun foundCard() = panel().apply {
        setPadding(dp(30), dp(28), dp(30), dp(28))
        val (found, total) = discoveryCount()
        addView(tile3d(R.drawable.cd_region, if (NIGHT) skyTile else Color.parseColor("#E3F4FD"), 76, 56), LinearLayout.LayoutParams(dp(76), dp(76)).apply { bottomMargin = dp(18) })
        addView(text("발견한 안부", 22f, MUTED, weight = 600))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, dp(4), 0, 0)
            addView(text("$found", 40f, INK, weight = 800))
            addView(text(" / ${total}개", 24f, MUTED).apply { setPadding(0, 0, 0, dp(8)) })
        })
        addView(progressBar(found, total))
        addView(text("내가 남긴 곳 ${myPlaces().size}곳", 20f, MUTED))
        setOnClickListener { goTab(Screen.HOME) }
    }

    /** 모임 소식: 사람마다 한 줄. 소식이 없어도 사람은 보인다 */
    private fun peopleCard() = panel().apply {
        setPadding(dp(30), dp(28), dp(30), dp(20))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val vc = viewCircle?.let { Engine.circles[it] }
            addView(text(vc?.name ?: "모임 소식", 26f, INK, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            // 소식 화면은 관리 중인 모임 것이라, 다른 모임을 보고 있으면 「전체 보기」를 두지 않는다
            if (vc == null || vc.id == circle?.id) addView(text("전체 보기 ›", 20f, ACCENT, weight = 600).apply { isClickable = true; setOnClickListener { openFeed() } })
            setPadding(0, 0, 0, dp(8))
        })
        val list = people(viewCircle?.let { Engine.circles[it] } ?: circle)
        if (list.isEmpty()) { addView(text("모임에 사람이 들어오면 여기에 보여요", 22f, MUTED)); return@apply }
        list.take(6).forEachIndexed { i, p ->
            if (i > 0) addView(View(context).apply { setBackgroundColor(BORDER) }, LinearLayout.LayoutParams(-1, dp(1)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(14), 0, dp(14))
                addView(avatar(p.member.name, 56, member = p.member), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(16) })
                addView(column().apply {
                    addView(text(p.member.name, 24f, INK, bold = true))
                    addView(text(if (p.key.isEmpty()) "오늘은 아직 조용해요" else p.line, 20f, if (p.todayCount > 0) ACCENT else MUTED).apply {
                        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                if (p.whenText.isNotEmpty()) addView(text(p.whenText, 18f, MUTED).apply { setPadding(dp(10), 0, 0, 0) })
            })
        }
        setOnClickListener { openFeed() }
    }

    /** 소식: 왼쪽 사람 카드, 오른쪽 내가 남긴 안부 (들었는지 · 답장 · 고치기·지우기) */
    private fun renderFeedSky() {
        val c = circle
        val me = store.driverId
        val list = people()
        val heardPeople = list.count { it.todayCount > 0 }
        root.addView(pageHead(c?.name, "모임 소식",
            if (list.isEmpty()) "아직 소식이 없어요" else if (heardPeople == 0) "오늘은 아직 아무도 안부를 듣지 않았어요" else "오늘 ${heardPeople}명이 안부를 들었어요",
            right = pill("새로 고침", dark = false, heightDp = 64) { refreshMessages(); loadFeed() }))

        val left = column()
        list.chunked(2).forEach { pair ->
            left.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                pair.forEachIndexed { i, p -> addView(personCard(p, c, me), LinearLayout.LayoutParams(0, -1, 1f).apply { if (i > 0) marginStart = dp(16) }) }
                if (pair.size == 1) addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(16) })
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        }
        if (list.isEmpty()) left.addView(panel().apply { addView(text("모임에 사람이 들어오면 여기에 보여요", 24f, MUTED)) })
        // 최근 주고받은 안부: 누가 누구의 어떤 안부를 언제 들었는지 (모든 모임, 최근 8개)
        val recent = feedHeard.sortedByDescending { it.lastAt }.take(8)
        if (recent.isNotEmpty()) left.addView(panel().apply {
            setPadding(dp(30), dp(26), dp(30), dp(14))
            addView(text("최근 주고받은 안부", 26f, INK, bold = true).apply { setPadding(0, 0, 0, dp(6)) })
            recent.forEachIndexed { i, h ->
                if (i > 0) addView(View(context).apply { setBackgroundColor(BORDER) }, LinearLayout.LayoutParams(-1, dp(1)))
                val listener = Engine.circles.values.firstNotNullOfOrNull { it.member(h.memberId) }
                val author = Engine.circles.values.firstNotNullOfOrNull { it.member(h.authorId) }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(12), 0, dp(12))
                    addView(avatar(h.memberName, 48, member = listener), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(14) })
                    val whose = if (h.authorId == me) "내" else "${author?.name ?: "누군가"}님의"
                    addView(text("${h.memberName}님이 $whose ${h.placeLabel} 안부를 들었어요", 21f, if (h.authorId == me) INK else MUTED, bold = h.authorId == me).apply {
                        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(text(whenLabel(h.lastAt), 18f, MUTED).apply { setPadding(dp(12), 0, 0, 0) })
                })
            }
        }, LinearLayout.LayoutParams(-1, -2))

        val right = panel().apply { setPadding(dp(30), dp(28), dp(30), dp(24)) }
        val mine = if (me != null) messages.filter { it.authorId == me } else emptyList()
        right.addView(text("내가 남긴 안부 ${mine.size}개", 26f, INK, bold = true).apply { setPadding(0, 0, 0, dp(10)) })
        if (mine.isEmpty()) {
            right.addView(column().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(40), 0, dp(30))
                addView(tile3d(R.drawable.cd_door, if (NIGHT) skyTile else Color.parseColor("#FFF1E6"), 120, 88))
                addView(text("아직 남긴 안부가 없어요", 26f, INK, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(22), 0, 0) })
                addView(text("가족이 지나갈 곳이나 비 오는 날에\n들려줄 말을 남겨 보세요", 21f, MUTED).apply { gravity = Gravity.CENTER })
                addView(space(22))
                addView(pill("안부 남기기", dark = true) { goTab(Screen.LEAVE) })
            }, LinearLayout.LayoutParams(-1, -2))
        } else if (c != null && me != null) mine.forEachIndexed { i, m ->
            if (i > 0) right.addView(View(this).apply { setBackgroundColor(BORDER) }, LinearLayout.LayoutParams(-1, dp(1)))
            right.addView(myNoteRow(m, c, me))
        }

        // 오른쪽 위: 발견한 안부 (예전 「오늘」 메뉴에서 옮김)
        val rightCol = column()
        rightCol.addView(discoveryPanel(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
        rightCol.addView(right, LinearLayout.LayoutParams(-1, -2))

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, -2, 1.35f))
            addView(rightCol, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(18) })
        }, LinearLayout.LayoutParams(-1, -2))
    }

    /** 발견한 안부: 나에게 온 안부 중 들은 수 · 진행 막대 · 내가 남긴 곳. 메인에서 고른 그룹을 따른다 */
    private fun discoveryPanel() = panel().apply {
        setPadding(dp(30), dp(26), dp(30), dp(24))
        val (found, total) = discoveryCount()
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(tile3d(R.drawable.cd_region, if (NIGHT) skyTile else Color.parseColor("#E3F4FD"), 72, 52), LinearLayout.LayoutParams(dp(72), dp(72)).apply { marginEnd = dp(20) })
            addView(column().apply {
                addView(text("발견한 안부" + (viewCircle?.let { Engine.circles[it]?.name }?.let { " · $it" } ?: ""), 22f, MUTED, weight = 600))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
                    addView(text("$found", 40f, INK, weight = 800))
                    addView(text(" / ${total}개", 24f, MUTED).apply { setPadding(0, 0, 0, dp(8)) })
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(column().apply {
                gravity = Gravity.END
                addView(text("내가 남긴 곳", 20f, MUTED))
                addView(text("${myPlaces().size}곳", 32f, INK, weight = 800))
            })
        })
        addView(space(14))
        addView(progressBar(found, total))
    }

    private fun personCard(p: Person, c: Circle?, me: String?) = panel().apply {
        setPadding(dp(28), dp(26), dp(28), dp(24))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(avatar(p.member.name, 76, member = p.member), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(18) })
            addView(column().apply {
                addView(text(p.member.name + if (p.member.title.isNotBlank() && p.member.title != p.member.name) " · ${p.member.title}" else "", 28f, INK, bold = true))
                addView(text(if (p.todayCount > 0) "오늘 안부 ${p.todayCount}개를 들었어요" else "오늘은 아직 조용해요", 21f, if (p.todayCount > 0) ACCENT else MUTED, weight = 600))
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        if (p.recent.isNotEmpty()) addView(space(10))
        p.recent.forEach { h ->
            val mineH = h.authorId == me
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, 0)
                addView(View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (mineH) ACCENT else BORDER) } },
                    LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(12) })
                addView(text("${if (mineH) "내" else "${c?.nameOf(h.authorId) ?: ""}님의"} ${h.placeLabel} 안부", 20f, if (mineH) INK else MUTED, bold = mineH).apply {
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(text(whenLabel(h.lastAt), 18f, MUTED))
            })
        }
        p.diary?.let { addView(text(it.text.lines().first(), 20f, MUTED).apply { setPadding(0, dp(12), 0, 0); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }) }
    }

    // 내가 남긴 안부 들어 보기 ------------------------------------------------------

    /** 지금 들어 보는 내 안부 (다시 누르면 멈춘다) */
    private var minePlayingId: String? = null
    private var minePlayer: android.media.MediaPlayer? = null

    /**
     * 내가 남긴 안부를 내가 들어 본다: 목소리는 녹음 그대로, 글은 받는 차에서처럼 기계음으로.
     * 「들었어요」는 보내지 않는다. 받은 안부가 나오는 중이면 겹쳐 틀지 않는다.
     */
    private fun playMine(m: PlaceMessage) {
        if (playing != null) { toast("안부가 나오는 중이에요"); return }
        val again = minePlayingId == m.id
        stopMine()
        if (again) { render(); return }
        stopPreview()
        minePlayingId = m.id
        val url = m.audioUrl
        if (m.kind == "voice" && url != null) {
            val mp = android.media.MediaPlayer()
            minePlayer = mp
            runCatching {
                mp.setDataSource(Api.mediaUrl(url))
                mp.setOnCompletionListener { if (minePlayer === mp) { stopMine(); render() } }
                mp.setOnErrorListener { _, _, _ -> if (minePlayer === mp) { stopMine(); toast("녹음을 틀지 못했어요"); render() }; true }
                mp.setOnPreparedListener { it.start() }
                mp.prepareAsync()
            }.onFailure { Log.d("내 안부 듣기 실패: $it"); stopMine(); toast("녹음을 틀지 못했어요") }
        } else {
            Engine.speakThen(m.text) { if (minePlayingId == m.id) { minePlayingId = null; render() } }
        }
        render()
    }

    private fun stopMine() {
        minePlayer?.runCatching { stop(); release() }
        minePlayer = null
        if (minePlayingId != null) Engine.stopSpeaking()
        minePlayingId = null
    }

    private fun mineLabel(m: PlaceMessage) = if (minePlayingId == m.id) "■ 멈춤" else "▶ 듣기"

    /** 내가 남긴 안부 한 줄: 조건 그림 · 내용 · 받는 사람 · 상태 표시 · 듣기/고치기/지우기 */
    private fun myNoteRow(m: PlaceMessage, c: Circle, me: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(16), 0, dp(16))
        val heardBy = feedHeard.filter { it.messageId == m.id }.map { it.memberName }.distinct()
        val replies = messages.filter { it.replyTo == m.id }
        val toNames = if (m.to.isEmpty()) "모두" else m.to.joinToString(", ") { c.nameOf(it) }
        addView(tile3d(if (m.isPlace) R.drawable.ic3_pin else condArt[m.opensOn] ?: R.drawable.ic3_pin,
            if (NIGHT) skyTile else Color.parseColor(condTint[if (m.isPlace) "place" else m.opensOn] ?: "#EEF2FF"), 60, 44),
            LinearLayout.LayoutParams(dp(60), dp(60)).apply { marginEnd = dp(16) })
        addView(column().apply {
            addView(text(if (m.kind == "voice") "목소리 안부" else "「${m.text}」", 23f, INK, bold = true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            addView(text("${toNames}에게 · ${m.occasion}", 19f, MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, 0)
                val (badge, color) = when {
                    replies.isNotEmpty() -> "답장 ${replies.size}" to ACCENT
                    heardBy.isNotEmpty() -> "${heardBy.joinToString(", ")} 들음" to GREEN
                    else -> "아직 안 들음" to MUTED
                }
                addView(text(badge, 18f, color, weight = 700).apply {
                    background = GradientDrawable().apply { setColor(blend(CARD, color, 0.14f)); cornerRadius = dp(30).toFloat() }
                    setPadding(dp(14), dp(4), dp(14), dp(4))
                })
                addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                addView(text(mineLabel(m), 19f, ACCENT, weight = 700).apply {
                    setPadding(dp(14), dp(6), dp(14), dp(6)); isClickable = true; setOnClickListener { playMine(m) } })
                addView(text(if (m.kind == "voice") "다시 녹음" else "고치기", 19f, ACCENT, weight = 600).apply {
                    setPadding(dp(14), dp(6), dp(14), dp(6)); isClickable = true; setOnClickListener { editNote(m, toNames, me) } })
                addView(text("지우기", 19f, MUTED, weight = 600).apply {
                    setPadding(dp(14), dp(6), dp(4), dp(6)); isClickable = true
                    setOnClickListener {
                        confirm("${m.placeName} 안부를 지울까요?", "들은 기록도 함께 지워져요.", "지우기") {
                            Api.deleteMessage(m.id, me) { ok -> if (ok) { refreshMessages(); loadFeed() } else toast("지우지 못했어요") }
                        }
                    }
                })
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun renderFeed() {
        if (SKY_SKIN) { renderFeedSky(); return }
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
                    // 무엇을 누구에게 남겼는지가 먼저 보이게: 내용 → 받는 사람 · 언제/어디서 · 들었는지
                    val toNames = if (m.to.isEmpty()) "모두" else m.to.joinToString(", ") { c.nameOf(it) }
                    addView(column().apply {
                        addView(big(if (m.kind == "voice") "🎙 목소리 메시지" else "「${m.text}」"))
                        addView(label("${toNames}에게 · ${m.occasion} · $status"))
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(navButton(mineLabel(m)) { playMine(m) }
                        .apply { layoutParams = LinearLayout.LayoutParams(dp(150), dp(72)).apply { marginEnd = dp(10) } })
                    addView(navButton(if (m.kind == "voice") "다시 녹음" else "고치기") { editNote(m, toNames, me) }
                        .apply { layoutParams = LinearLayout.LayoutParams(dp(170), dp(72)).apply { marginEnd = dp(10) } })
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

    /** 모임(그룹) 관리는 왼쪽 메뉴의 「그룹」 */
    private fun openMembers() = goTab(Screen.GROUPS)

    private fun loadCars() = Api.cars { list ->
        cars = list ?: emptyList()
        if (list == null) toast("차 목록을 불러오지 못했어요")
        if (screen == Screen.MEMBERS || screen == Screen.GROUPS) render()
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

    // 그룹 관리 ------------------------------------------------------------------
    // 왼쪽: 이 차가 들어간 그룹들 (누르면 그 그룹을 관리). 오른쪽: 고른 그룹의 이름·종류 · 구성원 · 초대 코드 · 그룹의 차.
    // 서버 관리 기능은 「관리 중」인 그룹 열쇠로 부르므로, 그룹을 고르면 Engine.switchCircle로 관리 그룹을 바꾼다.

    private fun renderGroups() {
        val joined = store.memberships
        root.addView(pageHead("그룹 ${joined.size}개", "그룹 관리",
            "그룹을 고르면 이름, 구성원, 초대 코드를 바꿀 수 있어요",
            right = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(pill("초대 코드로 들어가기", dark = false, heightDp = 72) { startJoin() })
                addView(pill("+ 새 그룹", dark = true, heightDp = 72) { startCreate() }, LinearLayout.LayoutParams(-2, dp(72)).apply { marginStart = dp(14) })
            }))

        // 한 화면에 들어가게 세 칸: 그룹 목록 | 그룹 이름·종류 + 구성원 | 초대 코드 + 그룹의 차
        val left = column()
        joined.forEach { ms -> left.addView(groupCard(ms), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }) }

        val c = circle
        val mid = column()
        val right = column()
        if (c == null) mid.addView(panel().apply { addView(text("서버에 연결되면 그룹을 관리할 수 있어요", 24f, MUTED)) })
        else {
            mid.addView(groupInfoCard(c), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
            mid.addView(groupMembersCard(c), LinearLayout.LayoutParams(-1, -2))
            right.addView(groupInviteCard(c), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
            right.addView(groupCarsCard(c), LinearLayout.LayoutParams(-1, -2))
            right.addView(text("「${c.name}」 지우기", 21f, MEMBER_COLORS["red"] ?: ACCENT, weight = 700).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END; setPadding(dp(16), 0, dp(16), 0); isClickable = true
                setOnClickListener { deleteCircle() }
            }, LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(6) })
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, -2, 0.78f).apply { marginEnd = dp(14) })
            // 그룹 목록과 고른 그룹의 내용 사이를 연하게 나눈다. 내용 길이와 상관없이 화면 끝까지, 양 끝은 둥글게
            addView(View(context).apply { background = GradientDrawable().apply { setColor(withAlpha(MUTED, 60)); cornerRadius = dp(3).toFloat() } },
                LinearLayout.LayoutParams(dp(6), -1).apply { marginEnd = dp(14); topMargin = dp(4); bottomMargin = dp(4) })
            addView(mid, LinearLayout.LayoutParams(0, -2, 1.22f).apply { marginEnd = dp(18) })
            addView(right, LinearLayout.LayoutParams(0, -2, 1.3f))
        }, LinearLayout.LayoutParams(-1, 0, 1f)) // 남은 높이를 다 차지해야 나눔 선이 화면 끝까지 간다
    }

    /** 구성원 한 사람 고치기: 버튼 줄 대신 「편집」 하나 → 고를 것 목록 */
    private fun editMember(c: Circle, i: Int) {
        val m = c.members[i]
        val ids = c.members.map { it.id }
        val acts = mutableListOf<Pair<String, () -> Unit>>(
            "이름 바꾸기" to { askText("${m.name} 이름 바꾸기", m.name) { name -> Api.renameMember(c.id, m.id, name, ::onCircleChanged) } },
            "호칭 바꾸기" to { askText("${m.name} 님의 호칭 (비우면 빼요)", m.title, suggestions = TITLE_HINTS.getValue(c.type), allowBlank = true) { t -> Api.setMemberTitle(c.id, m.id, t, ::onCircleChanged) } },
            "얼굴 색 바꾸기" to { pickColor("${m.name} 님의 얼굴 색", m.color) { color -> Api.setMemberColor(c.id, m.id, color, ::onCircleChanged) } },
        )
        if (i > 0) acts += "위로 올리기" to { Api.reorderMembers(c.id, ids.toMutableList().apply { add(i - 1, removeAt(i)) }, ::onCircleChanged) }
        if (i < ids.lastIndex) acts += "아래로 내리기" to { Api.reorderMembers(c.id, ids.toMutableList().apply { add(i + 1, removeAt(i)) }, ::onCircleChanged) }
        if (m.photoUrl != null) acts += "사진 빼기" to { confirm("${m.name} 님 사진을 뺄까요?", "얼굴은 이름 첫 글자와 색으로 보여요.", "빼기") { Api.deletePhoto(c.id, m.id, ::onCircleChanged) } }
        if (c.members.size > 1) acts += "그룹에서 빼기" to {
            confirm("${m.name}님을 그룹에서 뺄까요?", "${m.name}님이 남긴 안부·목소리·사진·일기도 함께 지워져요.") {
                Api.deleteMember(c.id, m.id) { updated, err ->
                    if (updated != null && store.driverId == m.id) Engine.setDriver(null)
                    onCircleChanged(updated, err)
                    refreshMessages()
                    loadCars()
                }
            }
        }
        pickFrom(m.name, acts.map { it.first }) { k -> acts[k].second() }
    }

    /** 그룹 색 판 + 첫 글자 */
    private fun groupBadge(circleId: String, name: String, sizeDp: Int) = text(name.take(1), sizeDp * 0.42f, Color.WHITE, weight = 800).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { setColor(circleColor(circleId)); cornerRadius = dp(sizeDp * 3 / 10).toFloat() }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** 그룹 목록의 한 칸. 지금 관리 중이면 강조, 누르면 그 그룹을 관리 */
    private fun groupCard(ms: TripStore.Membership) = panel().apply {
        val c = Engine.circles[ms.circleId]
        val on = ms.circleId == store.activeCircleId
        background = pressable(if (on) rounded(CARD, 3, circleColor(ms.circleId)) else rounded(CARD, 1, BORDER))
        setPadding(dp(26), dp(24), dp(26), dp(22))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(groupBadge(ms.circleId, c?.name ?: "?", 72).apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(18) })
            addView(column().apply {
                addView(text(c?.name ?: "불러오는 중…", 28f, INK, weight = 800).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                addView(text(listOfNotNull(CIRCLE_TYPES.firstOrNull { it.first == c?.type }?.second, c?.let { "${it.members.size}명" }).joinToString(" · "), 20f, MUTED))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (on) addView(text("관리 중", 18f, ACCENT, weight = 700).apply {
                setPadding(dp(14), dp(6), dp(14), dp(6)); background = GradientDrawable().apply { setColor(CHIP); cornerRadius = dp(20).toFloat() }
            })
        })
        c?.let { cc ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(16), 0, 0)
                addView(faceStack(cc.members.map { it.name to it }, 44))
                addView(text(if (ms.circleId in store.pausedCircles) "쉬는 중 · 이 차에서 안 열려요" else "이 차 주인 · ${ms.ownerId?.let { cc.nameOf(it) } ?: "아직 없음"}", 19f, MUTED).apply { setPadding(dp(14), 0, 0, 0); maxLines = 1 },
                    LinearLayout.LayoutParams(0, -2, 1f))
            })
        }
        isClickable = true
        setOnClickListener {
            if (!on) { Engine.switchCircle(ms.circleId); invitees = null; cars = null; viewCircle = null; render(); loadCars() }
        }
    }

    /** 작은 알약 버튼 (구성원 줄의 이름·호칭·색 등) */
    private fun miniPill(s: String, enabled: Boolean = true, color: Int = INK, onClick: () -> Unit) = text(s, 20f, color, weight = 600).apply {
        gravity = Gravity.CENTER; setPadding(dp(22), 0, dp(22), 0)
        background = pressable(GradientDrawable().apply { setColor(SOFT); cornerRadius = dp(28).toFloat() })
        isClickable = enabled; alpha = if (enabled) 1f else 0.35f
        if (enabled) setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, dp(56)).apply { marginStart = dp(8) }
    }

    private fun cardHead(s: String, sub: String?, right: View? = null) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, dp(10))
        addView(column().apply {
            addView(text(s, 28f, INK, weight = 800))
            sub?.let { addView(text(it, 20f, MUTED).apply { setPadding(0, dp(2), 0, 0) }) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        right?.let { addView(it) }
    }

    private fun thinLine() = View(this).apply { setBackgroundColor(BORDER) }

    /** 그룹 이름 · 종류 */
    private fun groupInfoCard(c: Circle) = panel().apply {
        setPadding(dp(30), dp(24), dp(30), dp(22))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(groupBadge(c.id, c.name, 76).apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(20) })
            addView(column().apply {
                addView(text(c.name, 34f, INK, weight = 800).apply { letterSpacing = -0.02f })
                addView(text("구성원 ${c.members.size}명", 20f, MUTED))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(pill("이름 바꾸기", dark = false, heightDp = 64) {
                askText("그룹 이름", c.name, suggestions = CIRCLE_NAME_HINTS.getValue(c.type)) { name -> Api.updateCircle(c.id, name = name, done = ::onCircleChanged) }
            })
        })
        addView(thinLine(), LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(18); bottomMargin = dp(16) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(column().apply {
                addView(text("그룹 종류", 22f, INK, weight = 700))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            // 가족 · 연인 · 친구: 한 줄 알약 고르기
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                background = GradientDrawable().apply { setColor(SOFT); cornerRadius = dp(34).toFloat() }
                setPadding(dp(6), dp(6), dp(6), dp(6))
                CIRCLE_TYPES.forEach { (type, name) ->
                    val sel = c.type == type
                    addView(text(name, 22f, if (sel) skyOnInk else INK, weight = if (sel) 700 else 500).apply {
                        gravity = Gravity.CENTER; setPadding(dp(30), 0, dp(30), 0)
                        if (sel) background = GradientDrawable().apply { setColor(skyInk); cornerRadius = dp(28).toFloat() }
                        isClickable = true
                        setOnClickListener {
                            if (sel) return@setOnClickListener
                            val change = {
                                Api.updateCircle(c.id, type = type) { updated, err ->
                                    if (updated != null && type != "family" && store.sharingAnything) {
                                        store.stopSharing()
                                        store.driverId?.let { d -> Api.deleteDiaries(c.id, d) {} }
                                    }
                                    onCircleChanged(updated, err)
                                }
                            }
                            // 연인·친구로 바꾸면 일기 공유가 꺼지고 올린 일기가 지워진다: 공유 중일 때만 먼저 묻는다
                            if (type != "family" && store.sharingAnything)
                                confirm("「$name」 그룹으로 바꿀까요?", "연인·친구 그룹은 일기 공유가 꺼지고, 올린 일기도 지워져요.", "바꾸기") { change() }
                            else change()
                        }
                    }, LinearLayout.LayoutParams(-2, dp(56)))
                }
            })
        })
    }

    /** 구성원: 얼굴 · 이름 · 호칭 · (이 차 주인) · 편집 (이름·호칭·색·순서·사진·빼기는 편집 안에) */
    private fun groupMembersCard(c: Circle) = panel().apply {
        setPadding(dp(30), dp(24), dp(30), dp(12))
        addView(cardHead("구성원 ${c.members.size}명", "이 순서대로 얼굴 줄에 나와요",
            pill("+ 추가", dark = true, heightDp = 60) { askText("새 구성원 이름", "") { name -> Api.addMember(c.id, name, ::onCircleChanged) } }))
        c.members.forEachIndexed { i, m ->
            addView(thinLine(), LinearLayout.LayoutParams(-1, dp(1)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                addView(avatar(m.name, 56, member = m), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(16) })
                addView(text(m.name, 25f, INK, weight = 700))
                if (m.title.isNotBlank() && m.title != m.name) addView(text(m.title, 20f, MUTED).apply { setPadding(dp(10), 0, 0, 0) })
                if (m.id == store.driverId) addView(text("이 차 주인", 17f, ACCENT, weight = 700).apply {
                    setPadding(dp(12), dp(4), dp(12), dp(4)); background = GradientDrawable().apply { setColor(CHIP); cornerRadius = dp(16).toFloat() }
                }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
                addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                addView(miniPill("편집") { editMember(c, i) })
                isClickable = true
                setOnClickListener { editMember(c, i) }
            })
        }
    }

    /** 초대 코드: 사람마다 자기 코드. 그 사람 차에서 넣으면 그 사람 차로 바로 들어온다 */
    private fun groupInviteCard(c: Circle) = panel().apply {
        setPadding(dp(32), dp(28), dp(32), dp(16))
        setPadding(dp(30), dp(24), dp(30), dp(12))
        addView(cardHead("초대 코드", "그 사람 차에서 「초대 코드로 들어가기」에 넣으면 바로 들어와요"))
        val people = invitees
        if (people == null) {
            addView(text("불러오는 중…", 22f, MUTED).apply { setPadding(0, dp(8), 0, dp(16)) })
            Api.invitees { l -> if (l != null) { invitees = l; if (screen == Screen.GROUPS) render() } }
            return@apply
        }
        val list = people.filter { it.id != store.driverId }
        if (list.isEmpty()) addView(text("초대할 사람이 없어요. 구성원을 먼저 추가해 주세요", 22f, MUTED).apply { setPadding(0, dp(8), 0, dp(16)) })
        list.forEachIndexed { i, p ->
            if (i > 0) addView(thinLine(), LinearLayout.LayoutParams(-1, dp(1)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                addView(avatar(p.name, 48, member = c.member(p.id)), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(14) })
                val code = p.code.uppercase().let { if (it.length == 6) "${it.take(3)} ${it.drop(3)}" else it }
                addView(column().apply {
                    addView(text(p.name, 18f, MUTED, weight = 600))
                    addView(text(code, if (code.length > 8) 24f else 30f, INK, weight = 800).apply {
                        letterSpacing = if (code.length > 8) 0.02f else 0.08f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(miniPill("새 코드") {
                    confirm("${p.name}님 코드를 새로 만들까요?", "예전 코드로는 더 들어올 수 없어요. 이미 들어온 차는 그대로예요.", "새로 만들기") {
                        Api.invitees(resetFor = p.id) { l -> if (l != null) { invitees = l; render() } else toast("서버에 연결하지 못했어요") }
                    }
                })
            })
        }
    }

    /** 그룹의 차: 주인 · 이름 바꾸기 · 빼기 (이 차를 빼면 이 그룹 연결이 끊긴다) */
    private fun groupCarsCard(c: Circle) = panel().apply {
        setPadding(dp(32), dp(28), dp(32), dp(16))
        setPadding(dp(30), dp(24), dp(30), dp(12))
        addView(cardHead("그룹의 차", "차 한 대는 한 사람의 차예요"))
        val list = cars
        when {
            list == null -> addView(text("불러오는 중…", 22f, MUTED).apply { setPadding(0, dp(8), 0, dp(16)) })
            list.isEmpty() -> addView(text("아직 들어온 차가 없어요", 22f, MUTED).apply { setPadding(0, dp(8), 0, dp(16)) })
        }
        list?.forEach { car ->
            addView(thinLine(), LinearLayout.LayoutParams(-1, dp(1)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(14), 0, dp(14))
                addView(android.widget.FrameLayout(context).apply {
                    background = GradientDrawable().apply { setColor(if (car.me) CHIP else SOFT); cornerRadius = dp(18).toFloat() }
                    addView(icon(R.drawable.ic_car, 34, if (car.me) ACCENT else MUTED), android.widget.FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
                }, LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(18) })
                addView(column().apply {
                    addView(text(car.name.ifBlank { "이름 없는 차" }, 23f, INK, weight = 700).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    addView(text("주인 ${car.ownerId?.let { c.nameOf(it) } ?: "아직 없음"}" + if (car.me) " · 이 차" else "", 19f, if (car.me) ACCENT else MUTED))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(miniPill("이름") {
                    askText("차 이름", car.name) { name ->
                        if (car.me) Engine.renameCar(name) { ok -> if (!ok) toast("바꾸지 못했어요"); loadCars() }
                        else Api.renameOtherCar(car.id, name) { ok -> if (!ok) toast("바꾸지 못했어요"); loadCars() }
                    }
                })
                addView(miniPill(if (car.me) "연결 끊기" else "빼기", color = MEMBER_COLORS["red"] ?: INK) {
                    if (car.me) confirm("이 차를 ${c.name}에서 뺄까요?", "다시 쓰려면 초대 코드로 들어오면 돼요. 그룹 사람들이 남긴 안부는 지워지지 않아요.", "연결 끊기") {
                        Engine.unlink { ok -> if (!ok) toast("서버에 연결하지 못했어요") else { history.clear(); screen = Screen.HOME; render() } }
                    }
                    else confirm("${car.name}을(를) 그룹에서 뺄까요?", "그 차는 처음 화면으로 돌아가고, 다시 쓰려면 초대 코드로 들어와야 해요.", "빼기") {
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
                        suggestions: List<String> = emptyList(), allowBlank: Boolean = false, okLabel: String = "확인",
                        skipLabel: String? = null, onOk: (String) -> Unit) {
        if (parked != true) return
        val input = EditText(this).apply {
            filters = arrayOf(android.text.InputFilter.LengthFilter(maxLength))
            setText(initial.take(maxLength)); setSelection(text.length); textSize = INPUT_TEXT_SP; setTextColor(INK)
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
            // 선택 항목(호칭 등)은 「건너뛰기」로 비워 둔 채 다음으로. 「취소」는 이 입력 전체를 그만두는 것이라 따로 둔다.
            .apply { skipLabel?.let { setNeutralButton(it) { _, _ -> onOk("") } } }
            .setNegativeButton("취소", null).show().also { dialog = it }
    }

    /**
     * 내가 남긴 쪽지 고치기. 글은 직접 고치거나 「말로 다시 쓰기」, 목소리는 「다시 녹음」.
     * 말이나 녹음으로 다시 하면 예전 내용은 버리고 처음부터 새로 쓴다 (이어 붙이지 않는다). 「고치기」를 눌러야 바뀌고, 닫으면 그대로다.
     */
    private fun editNote(m: PlaceMessage, toNames: String, me: String) {
        if (parked != true) return
        val key = store.keyOf(m.circleId) ?: Api.key
        fun done(err: String?) { if (err != null) toast(err) else { toast("고쳤어요"); refreshMessages(); loadFeed() } }
        fun hasMic() = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (m.kind == "voice") {
            // 목소리: 새로 녹음하면 예전 녹음은 지워지고 새 녹음으로 바뀐다
            val toggle = navButton("● 녹음 시작") {}
            fun refreshLabel() { toggle.text = if (recorder.recording) "■ 녹음 끝" else if (recorder.hasRecording) "● 다시 녹음" else "● 녹음 시작" }
            toggle.setOnClickListener {
                when {
                    recorder.recording -> { recorder.stop(); refreshLabel() }
                    !hasMic() -> { requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC); toast("마이크를 허락한 뒤 다시 눌러 주세요") }
                    else -> {
                        recorder.clear()
                        if (recorder.start { toast("${Recorder.MAX_SECONDS}초가 되어 녹음을 마쳤어요"); refreshLabel() }) refreshLabel() else toast("마이크를 쓸 수 없어요")
                    }
                }
            }
            val body = column().apply {
                setPadding(dp(24), dp(8), dp(24), 0)
                addView(label("새로 녹음하면 예전 녹음은 지워지고 이 녹음으로 바뀌어요 (${Recorder.MAX_SECONDS}초까지)"))
                addView(toggle, LinearLayout.LayoutParams(-1, dp(72)).apply { topMargin = dp(12) })
            }
            val dlg = AlertDialog.Builder(this, dialogTheme()).setTitle("목소리 다시 남기기 (${toNames}에게)").setView(body)
                .setPositiveButton("바꾸기", null).setNegativeButton("취소", null)
                .setOnDismissListener { recorder.clear() }.show().also { dialog = it }
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (recorder.recording) recorder.stop()
                val audio = recorder.base64()
                if (audio == null) { toast("먼저 녹음해 주세요"); return@setOnClickListener }
                dlg.dismiss()
                Api.editMessage(m.id, me, null, audioBase64 = audio, k = key) { err -> done(err) }
            }
            return
        }

        // 글: 직접 고치기, 또는 말로 처음부터 다시 쓰기 (말하는 동안 입력칸이 새 말로 바뀐다)
        val input = EditText(this).apply {
            filters = arrayOf(android.text.InputFilter.LengthFilter(MAX_TEXT))
            setText(m.text.take(MAX_TEXT)); setSelection(text.length); textSize = INPUT_TEXT_SP; setTextColor(INK)
            minLines = 3; maxLines = 5
        }
        val speak = navButton("🎤 말로 다시 쓰기") {}
        fun idle() { speak.text = "🎤 말로 다시 쓰기" }
        speak.setOnClickListener {
            when {
                dictation.listening -> dictation.stop()
                !hasMic() -> { requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_STT); toast("마이크를 허락한 뒤 다시 눌러 주세요") }
                else -> {
                    val started = dictation.start(
                        onChange = { if (dictation.partial.isNotBlank()) input.setText(dictation.partial.take(MAX_TEXT)) },
                        onText = { t -> input.setText(GleoActions.direct(t).take(MAX_TEXT)); input.setSelection(input.text.length) },
                        onEnd = { err -> err?.let { toast(it) }; idle() },
                    )
                    if (started) speak.text = "■ 다 말했어요" else toast("이 차에서는 음성 인식을 쓸 수 없어요")
                }
            }
        }
        val body = column().apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
            addView(speak, LinearLayout.LayoutParams(-1, dp(72)).apply { topMargin = dp(12) })
            addView(label("말하면 예전 글은 지워지고 말한 내용으로 처음부터 바뀌어요"))
        }
        AlertDialog.Builder(this, dialogTheme()).setTitle("메시지 고치기 (${toNames}에게)").setView(body)
            .setPositiveButton("고치기") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isEmpty()) toast("메시지를 써 주세요") else Api.editMessage(m.id, me, t, k = key) { err -> done(err) }
            }
            .setNegativeButton("취소", null)
            .setOnDismissListener { dictation.cancel() }
            .show().also { dialog = it }
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
    private fun title(s: String) = text(s, if (SKY_SKIN) 40f else 36f, INK, weight = if (SKY_SKIN) 800 else 700).apply { if (SKY_SKIN) letterSpacing = -0.02f }
    private fun big(s: String) = text(s, 28f, INK)
    private fun label(s: String) = text(s, 22f, MUTED)
    private fun space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    private fun rounded(color: Int, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(if (SKY_SKIN) 26 else 16).toFloat()
        if (stroke > 0) setStroke(dp(stroke), strokeColor)
    }

    /** 누르면 살짝 어두워지는 바탕 */
    private fun pressable(bg: android.graphics.drawable.Drawable) =
        android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(PRESSED), bg, null)

    private fun card(children: List<View>) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD, 1, BORDER)
        setPadding(dp(28), dp(20), dp(28), dp(20))
        if (SKY_SKIN) cardShadow(this)
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
        /** 시연 시나리오: 달리는 시간 · 달리지 않는 것(시동·차에 타기)의 기다림 */
        /** 시연 카드에서 차가 달리는 시간 (3분 시연에 맞춰 짧게) */
        private const val SCEN_MOVE_MS = 3200L
        private const val SCEN_REGION_MS = 3800L
        private const val SCEN_DRIVE_MS = 2200L
        /** 말로 남길 때의 조건을 문장 안에 넣을 꼴 */
        private val VOICE_WHEN_PHRASE = mapOf(
            "place" to "이 자리를 지날 때", "rain" to "비 오는 날", "night" to "밤에 전조등을 켤 때", "door" to "차에 탈 때",
            "start" to "시동을 걸 때", "park" to "여기 도착해 세울 때", "long" to "오래 운전할 때", "region" to "이 지역에 들어올 때", "warning" to "경고등이 켜질 때",
        )
        private const val SCEN_STILL_MS = 1200L
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
            // 「옆자리에 누가 탄 날」은 뺐다: 동승자 앞에서 일부러 트는 조건이라 옆자리 배려(재생 미루기)와 어긋난다
            "door" to "차에 탈 때",
            "start" to "시동 걸 때",
            "park" to "도착해 차를 세울 때",
            "long" to "오래 운전할 때",
            "region" to "그 지역에 들어가면",
            "warning" to "경고등이 켜질 때",
        )
        /** 한 화면 남기기의 조건 칸에 쓰는 짧은 이름 (한 줄에 들어가게) */
        private val WHEN_SHORT = mapOf("place" to "이곳을 지나면", "park" to "도착해 세울 때", "region" to "지역에 들어가면", "long" to "오래 운전할 때", "warning" to "경고등 켜질 때")
        /** 장소를 함께 고르는 조건 */
        private val WITH_PLACE = setOf("place", "park", "region")
        /** 시연 이동·시동·세우기 버튼 (앱만 버전·개발 빌드에서만, 실차 배포에는 없음) */
        private val DEMO_MOVE = BuildConfig.STANDALONE || BuildConfig.DEBUG
        /** 「들은 안부」에 처음 보여 줄 개수 */
        private const val HEARD_PREVIEW = 4
        private val WHEN_HOURS = listOf("any" to "언제든", "morning" to "아침 6~10시", "evening" to "저녁 17~21시", "night" to "밤 23~5시")
        private val WHEN_HOUR_RANGE = mapOf("morning" to (6 to 10), "evening" to (17 to 21), "night" to (23 to 5))
        private val DAY_NAMES = listOf("월", "화", "수", "목", "금", "토", "일")
        private val WHEN_DAYS = listOf("any" to "매일", "weekday" to "평일", "weekend" to "주말") +
            DAY_NAMES.mapIndexed { i, n -> "d${i + 1}" to "${n}요일" }
        private val WHEN_DAY_SET = mapOf("weekday" to setOf(1, 2, 3, 4, 5), "weekend" to setOf(6, 7)) +
            (1..7).associate { "d$it" to setOf(it) }
        private val WHEN_UNTIL = listOf("forever" to "기한 없음", "week" to "1주일 뒤", "month" to "한 달 뒤", "year" to "1년 뒤")
        private val WHEN_FROM = listOf("now" to "오늘", "tomorrow" to "내일", "week" to "다음 주", "year" to "1년 뒤")
        /** 「매번」은 잔소리가 되어 뺐다 (예전에 남긴 always 안부는 Engine에서 그대로 연다) */
        private val REPEATS = listOf("once" to "한 번만", "weekly" to "주 1회까지")
        private val WHEN_ICONS = mapOf("place" to R.drawable.ic_pin, "rain" to R.drawable.ic_rain, "night" to R.drawable.ic_moon, "together" to R.drawable.ic_people, "door" to R.drawable.ic_door,
            "start" to R.drawable.ic_play, "park" to R.drawable.ic_home, "long" to R.drawable.ic_car, "region" to R.drawable.ic_map, "warning" to R.drawable.ic_shield)
        private val WHEN_HINTS = mapOf(
            "place" to "고른 장소 근처를 지날 때",
            "rain" to "와이퍼가 계속 움직이는 날",
            "night" to "해가 진 뒤 전조등을 켜고 달릴 때",
            "together" to "옆자리에 누가 앉아 안전벨트를 맸을 때",
            "door" to "차에 타서 차가 켜질 때",
            "start" to "차가 켜지고 처음 기어를 넣을 때",
            "park" to "그곳(또는 어디서든)에 도착해 P로 세울 때",
            "long" to "쉬지 않고 2시간 넘게 달릴 때",
            "region" to "고른 곳의 시·군·구에 들어갈 때",
            "warning" to "계기판에 고장·주의 경고등이 켜질 때",
        )
        /** 글자 입력 칸의 글자 크기 (차 화면에서 멀리서도 읽히게. 다른 제목은 28sp 안팎) */
        private const val INPUT_TEXT_SP = 34f
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
        /** 하늘 스킨: 메인 외 화면도 하늘 바탕 + 유리 카드 (false면 팀 원래 모습) */
        private const val SKY_SKIN = true
        /** 하늘 메인 배율: 글자·여백·칸을 함께 키운다 */
        private const val SKY_K = 1.4f
        /** 하늘 메인의 가장 작은 글자 (회색 설명 줄도 이보다 작아지지 않게) */
        private const val SKY_MIN_PX = 19f
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
            if (SKY_SKIN) {
                // 카드·버튼은 하늘이 살짝 비치는 유리, 글자·강조는 메인(하늘 메인)과 같은 색
                CARD = if (night) Color.argb(225, 20, 28, 66) else Color.argb(232, 255, 255, 255)
                DIARY_BG = CARD
                SOFT = c("#E4EBF7", "#2A3770") // 보조 버튼: 유리 위에서도 버튼으로 보이게 불투명
                BORDER = if (night) Color.argb(45, 255, 255, 255) else Color.argb(235, 255, 255, 255)
                INK = c("#131A26", "#EEF2FF")
                MUTED = c("#4A5466", "#AEB8D9")
                ACCENT = c("#2D5FD6", "#4D7BEA") // 야간도 흰 글자가 읽히는 진하기
                CHIP = c("#E3ECFF", "#26315E")
            }
        }

        /** 왼쪽 메뉴 줄: 화면 · 선 아이콘 · 이름. 메인은 안부 지도. 설정은 맨 아래로 떨어진다. */
        private val RAIL = listOf(
            Triple(Screen.HOME, R.drawable.ic_map, "메인"),
            Triple(Screen.LEAVE, R.drawable.ic_edit, "메시지"),
            Triple(Screen.FEED, R.drawable.ic_inbox, "소식"),
            Triple(Screen.GROUPS, R.drawable.ic_people, "그룹"),
            Triple(Screen.SETTINGS, R.drawable.ic_settings, "설정"),
        )
        /** 앱만 버전의 「시연」 메뉴 (설정 바로 위) */
        private val DEMO_RAIL = Triple(Screen.DEMO, R.drawable.ic_play, "시연")
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
