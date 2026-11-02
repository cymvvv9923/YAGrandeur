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

    private enum class Screen { HOME, DRIVER, PLAY, DIARY, SETTINGS, MEMBERS, LEAVE, FEED }

    /** 메시지 남기기 화면에서 고르는 값 */
    private data class Place(val name: String, val lat: Double, val lon: Double)
    private var leaveAuthor: String? = null
    private var leavePlace: Place? = null
    /** 어느 장소 버튼을 골랐는지 (이름을 바꿔도 유지) */
    private var leaveKey: String? = null
    private var leaveRadius = 500
    /** 언제 열릴지: place 장소 · rain 비 · night 밤길 · together 옆자리 · door 차에 탈 때 */
    private var leaveWhen = "place"
    private var leaveText = ""
    private var sending = false
    private lateinit var recorder: Recorder
    private lateinit var dictation: Dictation
    private var preview: android.media.MediaPlayer? = null

    private lateinit var root: LinearLayout
    private var screen = Screen.HOME
    /** 뒤로 가기용 화면 기록 */
    private val history = ArrayDeque<Screen>()
    /** 정차 때 Engine이 앞으로 띄운 화면이면, 재생이 끝나거나 출발하면 내비로 돌아간다 */
    private var autoOpened = false
    /** 열려 있는 입력·확인 창. 출발하면 닫는다. */
    private var dialog: AlertDialog? = null

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
        recorder = Recorder(this)
        dictation = Dictation(this)
        Notifier.init(this)
        Engine.start(this)
        AnbuService.start(this)
        Engine.ui = this
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTI)
        }

        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(40), dp(28), dp(40), dp(28)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(root) })

        handleIntent(intent)
        if (playing != null) screen = Screen.PLAY
        else if (parked == true && store.driverId == null) screen = Screen.DRIVER
        render()
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
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onStop() {
        Engine.uiVisible = false
        super.onStop()
    }

    override fun onDestroy() {
        if (Engine.ui === this) Engine.ui = null
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
            if (screen == Screen.DRIVER) screen = Screen.HOME
            Engine.setDriver(it)
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

    override fun changed() = render()

    override fun onNeedDriver() {
        Log.d("운전자 필요 screen=$screen")
        if (screen == Screen.HOME) go(Screen.DRIVER)
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
        if (screen != Screen.PLAY) { history.addLast(screen); screen = Screen.PLAY }
        render()
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
        root.removeAllViews()
        if (screen != Screen.HOME) root.addView(navBar())
        when (screen) {
            Screen.HOME -> renderHome()
            Screen.DRIVER -> renderDriver()
            Screen.PLAY -> renderPlay()
            Screen.DIARY -> renderDiary()
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

    private fun navBar() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(navButton("← 뒤로") { back() })
        addView(navButton("⌂ 홈") { home() }, LinearLayout.LayoutParams(dp(220), dp(72)).apply { marginStart = dp(14) })
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
    }

    private fun navButton(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s; textSize = 22f; setTextColor(INK); isAllCaps = false
        background = rounded(SOFT)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(220), dp(72))
    }

    private fun renderHome() {
        val c = circle
        // 기어를 모르거나 P가 아니면 조작을 막는다
        val driving = parked != true
        val driver = store.driverId?.let { c?.nameOf(it) }
        val status = when (Engine.gear) {
            null -> "차 상태를 확인하는 중"
            CarSignals.Gear.P -> "정차 중"
            CarSignals.Gear.R -> "후진 중"
            CarSignals.Gear.N -> "중립 · P에 두면 설정할 수 있어요"
            CarSignals.Gear.D -> "운전 중 · 메시지는 차를 세우면 들려드려요"
        }
        val carState = listOfNotNull("비".takeIf { Engine.rainNow }, "밤길".takeIf { Engine.nightNow }, "옆자리 탑승".takeIf { Engine.passengerNow })
        root.addView(text("길 위의 안부  ·  ${c?.name ?: if (serverOk) "" else "서버에 연결하는 중…"}  ·  $status" + (driver?.let { "  ·  운전 $it" } ?: "") +
            (if (carState.isEmpty()) "" else "  ·  " + carState.joinToString(" · ")), 20f, MUTED))
        root.addView(space(8))

        // 왼쪽: 오늘의 일기를 가장 크게. 오른쪽: 받은 메시지와 서클 소식.
        val left = column()
        val right = column()
        left.addView(diaryCard(clickable = !driving))
        right.addView(card(listOf(
            label("지나온 길에서 받은 메시지"),
            text(if (pending.isEmpty()) "아직 없어요" else "${pending.size}개 · ${pending.joinToString(", ") { "${it.authorName}(${it.placeName})" }}", 26f, INK, bold = pending.isNotEmpty()),
            label(nearestText()),
        )))
        if (!driving && pending.isNotEmpty() && store.driverId != null) {
            right.addView(primary("▶ 지금 듣기") { Engine.startPlayback(announce = false) })
        }
        // 들러 가기: 오늘 들은 장소 메시지 중 최근 2곳. 재생 화면은 자동 「들었어요」로 금방 넘어가서 홈에도 둔다.
        if (!driving) {
            val heardIds = store.heard.map { it.messageId }
            messages.filter { it.isPlace && it.id in heardIds }.sortedByDescending { heardIds.indexOf(it.id) }.take(2).forEach { m ->
                right.addView(secondary("📍 ${m.placeName} 들러 가기") {
                    toast(if (Engine.addStopover(m)) "${m.placeName}을(를) 경유지에 넣었어요" else "경유지를 넣지 못했어요")
                })
            }
        }
        right.addView(card(listOf(label("서클 소식")) + feedPreview()).apply { if (!driving) setOnClickListener { openFeed() } })
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, -2, 3f))
            addView(right, LinearLayout.LayoutParams(0, -2, 2f).apply { marginStart = dp(20) })
        })

        if (!driving) {
            root.addView(row(
                secondary("메시지 남기기") { openLeave() },
                secondary("서클 소식") { openFeed() },
                secondary("오늘의 일기") { openDiary() },
            ))
            root.addView(row(
                secondary("공유 설정") { go(Screen.SETTINGS) },
                secondary("운전자 바꾸기") { go(Screen.DRIVER) },
                secondary("사용자 관리") { go(Screen.MEMBERS) },
            ))
        }
    }

    private fun renderDriver() {
        val c = circle
        root.addView(title("오늘 누가 운전하세요?"))
        root.addView(label("고른 사람이 남긴 메시지는 이 차에서 재생하지 않아요."))
        root.addView(space(16))
        if (c == null) {
            root.addView(label("서클 정보를 불러오는 중이에요."))
            root.addView(secondary("나중에") { back() })
            return
        }
        c.members.chunked(3).forEach { group ->
            root.addView(row(*group.map { m ->
                primary(m.name) {
                    history.clear()
                    screen = Screen.HOME
                    Engine.setDriver(m.id) // 들을 메시지가 있으면 바로 재생
                    render()
                }
            }.toTypedArray()))
        }
    }

    private fun renderPlay() {
        val m = playing ?: run { screen = Screen.HOME; renderHome(); return }
        root.addView(label(playState))
        root.addView(title("${m.authorName}님이 ${m.occasion} 남긴 메시지"))
        if (m.kind == "text") root.addView(card(listOf(big("“${m.text}”"))))
        root.addView(space(12))
        root.addView(primary("들었어요") { Engine.heard() }, LinearLayout.LayoutParams(-1, dp(120)))
        root.addView(row(
            if (Engine.autoHeardLeft > 0) secondary("보내지 않기") { Engine.cancelAutoHeard() }
            else secondary("다시 듣기") { Engine.playCurrent() },
            secondary(if (pending.isEmpty()) "나중에" else "다음 메시지") {
                if (pending.isEmpty()) Engine.later() else Engine.next()
            },
            secondary("신고") { Engine.report() },
        ))
        // 들러 가기: 장소 메시지면 그곳을 내비 경유지로 (정차 중에만 보이는 화면)
        if (m.isPlace) root.addView(row(secondary("📍 ${m.placeName} 들러 가기") {
            toast(if (Engine.addStopover(m)) "${m.placeName}을(를) 경유지에 넣었어요" else "경유지를 넣지 못했어요")
        }))
        root.addView(label("「들었어요」를 누르면 ${m.authorName}님에게 알려요." + if (store.autoHeard) " 끝까지 들으면 누르지 않아도 알려요 (공유 설정에서 끌 수 있어요)." else " 누르지 않으면 아무것도 전해지지 않아요."))
    }

    private fun renderDiary() {
        val type = circle?.type ?: "family"
        val left = column()
        val right = column()
        left.addView(diaryCard(clickable = false))
        right.addView(card(listOf(
            label("서클에 보이는 내용"),
            big(store.diary(full = false, circleType = type).ifBlank { "공유하지 않아요. 나만 봐요." }),
        )))
        right.addView(secondary("공유 설정") { go(Screen.SETTINGS) })
        val others = feedDiaries.filter { it.date == store.today && it.memberName != circle?.nameOf(store.driverId) }
        right.addView(card(listOf(label("오늘 다른 가족 차의 일기")) +
            (if (others.isEmpty()) listOf(label("아직 없어요")) else others.take(3).flatMap { listOf(text("${it.memberName}의 차", 20f, ACCENT, bold = true), big(it.text)) })))
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, -2, 3f))
            addView(right, LinearLayout.LayoutParams(0, -2, 2f).apply { marginStart = dp(20) })
        })
    }

    private fun openDiary() {
        go(Screen.DIARY)
        loadFeed()
    }

    /** 오늘의 일기 카드: 날짜, 숫자 세 개, 문장 한 줄씩, 공유 상태. 홈과 일기 화면에서 같이 쓴다. */
    private fun diaryCard(clickable: Boolean): View {
        val type = circle?.type ?: "family"
        val date = java.text.SimpleDateFormat("M월 d일 EEEE", java.util.Locale.KOREA).format(java.util.Date())
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(DIARY_BG); cornerRadius = dp(24).toFloat(); setStroke(dp(2), ACCENT) }
            setPadding(dp(32), dp(24), dp(32), dp(24))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
            addView(text("$date · 차가 쓴 오늘의 일기", 22f, ACCENT, bold = true))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(12), 0, dp(12))
                addView(statTile("%.1f".format(store.todayKm), "km 달렸어요"), LinearLayout.LayoutParams(0, -2, 1f))
                addView(statTile("${store.rests}", "번 쉬었어요"), LinearLayout.LayoutParams(0, -2, 1f))
                addView(statTile("${store.heard.size}", "개 들었어요"), LinearLayout.LayoutParams(0, -2, 1f))
            })
            store.diaryLines(full = true, circleType = type).forEach { addView(text(it, 30f, INK)) }
            addView(space(8))
            addView(text(if (store.sharingAnything) "서클에 보이는 것: " + sharedItems().joinToString(" · ") else "🔒 나만 봐요 · 공유 안 함", 20f, MUTED))
            if (clickable) setOnClickListener { openDiary() }
        }
    }

    private fun statTile(value: String, unit: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(value, 56f, INK, bold = true))
        addView(text(unit, 20f, MUTED))
    }

    /** 홈에 보이는 최근 서클 소식 3개 */
    private fun feedPreview(): List<View> {
        val c = circle
        val items = feedHeard.take(3).map { "${it.memberName}님이 ${c?.nameOf(it.authorId) ?: ""}님의 ${it.placeLabel} 메시지를 들었어요" } +
            feedDiaries.filter { it.date == store.today }.take(3 - minOf(3, feedHeard.size)).map { "${it.memberName}의 차: ${it.text}" }
        return if (items.isEmpty()) listOf(big("아직 소식이 없어요")) else items.map { text(it, 22f, INK) }
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun renderSettings() {
        root.addView(title("서클에 보일 내용"))
        root.addView(label("기본은 공유 안 함이에요. 내가 켠 것만 서클에 보여요. 장소 이름과 동선은 보내지 않아요."))
        root.addView(toggle("이동 거리", store.shareDistance) { store.shareDistance = it; Engine.uploadDiary() })
        root.addView(toggle("쉬어간 횟수", store.shareRests) { store.shareRests = it; Engine.uploadDiary() })
        root.addView(toggle("들은 메시지", store.shareHeard) { store.shareHeard = it; Engine.uploadDiary() })
        root.addView(toggle("비 · 밤길 (옆자리에 누가 탔는지는 보내지 않아요)", store.shareWeather) { store.shareWeather = it; Engine.uploadDiary() })
        root.addView(space(12))
        root.addView(label("받는 메시지"))
        root.addView(toggle("글 메시지를 기계음으로 듣기", store.acceptTts) { store.acceptTts = it })
        root.addView(toggle("끝까지 들으면 버튼 없이 「들었어요」 보내기", store.autoHeard) { store.autoHeard = it })
        root.addView(space(12))
        root.addView(row(
            secondary("공유 모두 끄고 기록 삭제") {
                store.stopSharing()
                val c = circle; val d = store.driverId
                if (c != null && d != null) Api.deleteDiaries(c.id, d) { render() }
                render()
            },
        ))
    }

    // 메시지 남기기 (폰 웹과 같은 일을 차에서, 정차 중에만) ------------------------------

    private fun openLeave() {
        if (leaveAuthor == null || circle?.members?.none { it.id == leaveAuthor } == true) leaveAuthor = store.driverId
        if (leavePlace == null) lastLoc?.let { leavePlace = Place(HERE, it.first, it.second); leaveKey = HERE }
        go(Screen.LEAVE)
    }

    /** 남기기·소식 화면에서 같이 쓰는 "누구로" 고르기 */
    private fun pickAuthor(c: Circle) {
        AlertDialog.Builder(this).setTitle("누구인가요?")
            .setItems(c.members.map { it.name }.toTypedArray()) { _, i -> leaveAuthor = c.members[i].id; render() }.show().also { dialog = it }
    }

    private fun renderLeave() {
        val c = circle
        root.addView(title("메시지 남기기"))
        if (c == null) { root.addView(label("서버에 연결되면 남길 수 있어요.")); return }
        val author = leaveAuthor?.takeIf { id -> c.members.any { it.id == id } }

        root.addView(row(secondary("보내는 사람: ${if (author != null) c.nameOf(author) else "고르기"}") { pickAuthor(c) }))

        root.addView(label("언제 들려줄까요? · 받는 사람이 차를 세웠을 때 재생돼요"))
        WHEN_OPTIONS.chunked(3).forEach { group ->
            root.addView(row(*group.map { (key, name) ->
                if (leaveWhen == key) primary("✓ $name") {} else secondary(name) { leaveWhen = key; render() }
            }.toTypedArray()))
        }

        if (leaveWhen == "place") {
            root.addView(label("장소 · 이 근처를 서클의 다른 사람이 지나가면 차에서 들려줘요"))
            val here = lastLoc?.let { Place(HERE, it.first, it.second) }
            val places = listOfNotNull(here) + PRESETS
            places.chunked(3).forEach { group ->
                root.addView(row(*group.map { p ->
                    // 좌표가 아니라 고른 버튼으로 표시한다 (지금 자리가 예시 장소 근처여도 하나만 ✓)
                    if (leaveKey == p.name) primary("✓ ${leavePlace?.name ?: p.name}") {}
                    else secondary(p.name) { leavePlace = p; leaveKey = p.name; render() }
                }.toTypedArray()))
            }
            leavePlace?.let { p ->
                root.addView(row(secondary("장소 이름 바꾸기: ${p.name}") {
                    askText("장소 이름", p.name) { leavePlace = p.copy(name = it); render() }
                }))
            }

            root.addView(label("들려줄 범위"))
            root.addView(row(*listOf(300, 500, 1000).map { r ->
                val name = if (r < 1000) "${r}m" else "${r / 1000}km"
                if (r == leaveRadius) primary("✓ $name") {} else secondary(name) { leaveRadius = r; render() }
            }.toTypedArray()))
        }

        root.addView(label("내용 · 목소리는 ${Recorder.MAX_SECONDS}초까지, 글은 ${MAX_TEXT}자까지 (글은 기계음으로 읽어요)"))
        // 화면 키보드는 차에서 쓰기 힘들어서, 말하면 글로 적어 주는 버튼을 가장 크게 둔다
        root.addView(
            if (dictation.listening) primary("■ 다 말했어요") { dictation.stop() }
            else primary(if (leaveText.isBlank()) "🎤 말하면 글로 적어요" else "🎤 말로 이어 쓰기") { startDictation() },
            LinearLayout.LayoutParams(-1, dp(96)).apply { bottomMargin = dp(12) },
        )
        root.addView(row(
            if (recorder.recording) primary("■ 녹음 끝") { recorder.stop(); render() }
            else secondary(if (recorder.hasRecording) "● 다시 녹음" else "● 녹음 시작") { startRecording() },
            secondary("▶ 들어 보기") { playPreview() }.apply { isEnabled = recorder.hasRecording && !recorder.recording; alpha = if (isEnabled) 1f else 0.35f },
            secondary(if (leaveText.isBlank()) "글로 쓰기" else "글 고치기") {
                askText("메시지 (${MAX_TEXT}자까지)", leaveText, multiline = true, maxLength = MAX_TEXT) { leaveText = it; recorder.clear(); render() }
            },
        ))
        when {
            dictation.listening -> root.addView(card(listOf(label("듣고 있어요 · 말을 마치면 저절로 적혀요"), big(listOf(leaveText, dictation.partial).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "…" }))))
            recorder.recording -> root.addView(card(listOf(big("녹음 중이에요…"))))
            recorder.hasRecording -> root.addView(card(listOf(big("목소리 메시지가 준비됐어요"))))
            leaveText.isNotBlank() -> root.addView(card(listOf(big("“$leaveText”"))))
        }

        val ready = author != null && (leaveWhen != "place" || leavePlace != null) && !recorder.recording && !dictation.listening && (recorder.hasRecording || leaveText.isNotBlank()) && !sending
        root.addView(primary(if (sending) "보내는 중…" else "남기기") { sendLeave(c, author!!) }.apply { isEnabled = ready; alpha = if (ready) 1f else 0.4f })
        root.addView(label("남긴 사람 본인 차에서는 재생되지 않아요."))
    }

    /** 마이크 권한은 녹음 버튼을 누를 때만 묻고, 허락하면 바로 녹음한다. */
    private fun startRecording() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (parked != true || screen != Screen.LEAVE) return
        stopPreview()
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

    private fun playPreview() {
        stopPreview()
        preview = android.media.MediaPlayer().apply {
            runCatching { setDataSource(recorder.path); prepare(); start() }.onFailure { toast("재생하지 못했어요") }
            setOnCompletionListener { stopPreview() }
        }
    }

    private fun stopPreview() { preview?.release(); preview = null }

    private fun sendLeave(c: Circle, author: String) {
        val p = leavePlace
        if (leaveWhen == "place" && p == null) return
        val voice = recorder.hasRecording
        val body = org.json.JSONObject().put("authorId", author).put("when", leaveWhen)
            .put("kind", if (voice) "voice" else "text").put("text", if (voice) "" else leaveText)
        if (leaveWhen == "place" && p != null) body.put("placeName", p.name).put("lat", p.lat).put("lon", p.lon).put("radiusM", leaveRadius)
        val doneName = if (leaveWhen == "place") "${p?.name}에" else "「${WHEN_OPTIONS.first { it.first == leaveWhen }.second}」에"
        if (voice) body.put("audioBase64", recorder.base64()).put("audioExt", "m4a")
        sending = true
        render()
        Api.postMessage(c.id, body) { err ->
            sending = false
            if (err != null) { toast(err); render(); return@postMessage }
            toast("$doneName 남겼어요")
            recorder.clear(); leaveText = ""
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

    private fun renderFeed() {
        val c = circle
        // 남기기 화면에서 고른 사람 기준, 없으면 운전자
        val me = leaveAuthor?.takeIf { id -> c?.members?.any { it.id == id } == true } ?: store.driverId
        root.addView(title("서클 소식"))
        root.addView(label("「들었어요」"))
        if (feedHeard.isEmpty()) root.addView(label("아직 없어요"))
        feedHeard.take(8).forEach { h ->
            val author = c?.nameOf(h.authorId) ?: ""
            root.addView(card(listOf(big("${h.memberName}님이 ${author}님의 ${h.placeLabel} 메시지를 들었어요"))))
        }
        root.addView(space(8))
        root.addView(label("차의 일기 (본인이 허락한 내용만)"))
        if (feedDiaries.isEmpty()) root.addView(label("아직 없어요"))
        feedDiaries.take(6).forEach { d -> root.addView(card(listOf(label("${d.date} · ${d.memberName}의 차"), big(d.text)))) }

        if (c != null) root.addView(space(8))
        if (c != null && me == null) root.addView(row(secondary("내가 남긴 메시지 보기 · 누구인지 고르기") { pickAuthor(c) }))
        if (c != null && me != null) {
            val mine = messages.filter { it.authorId == me }
            root.addView(label("${c.nameOf(me)}님이 남긴 메시지 ${mine.size}개"))
            root.addView(row(secondary("다른 사람 메시지 보기") { pickAuthor(c) }))
            mine.forEach { m ->
                val heardBy = feedHeard.filter { it.messageId == m.id }.joinToString(", ") { it.memberName }
                root.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = rounded(CARD)
                    setPadding(dp(24), dp(8), dp(12), dp(8))
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
                    addView(big("${m.placeName} · ${if (m.kind == "voice") "목소리" else "글"}" + if (heardBy.isNotEmpty()) " · $heardBy 들음" else ""), LinearLayout.LayoutParams(0, -2, 1f))
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
        val near = messages.filter { it.isPlace && it.authorId != store.driverId }
            .minByOrNull { distanceM(loc.first, loc.second, it.lat, it.lon) } ?: return "위치는 이 차 안에서만 써요"
        val d = distanceM(loc.first, loc.second, near.lat, near.lon)
        val dist = if (d < 1000) "${d.toInt()}m" else "%.1fkm".format(d / 1000)
        return "가장 가까운 메시지 장소: ${near.placeName} $dist · 위치는 이 차 안에서만 써요"
    }

    /** 사용자 관리: 서클 이름·종류, 구성원 추가·이름 바꾸기·순서·빼기. 정차 중에만 열린다. */
    private fun renderMembers() {
        val c = circle
        root.addView(title("사용자 관리"))
        if (c == null) {
            root.addView(label("서버에 연결되면 관리할 수 있어요."))
            return
        }
        root.addView(row(
            secondary("서클 이름: ${c.name}") {
                askText("서클 이름", c.name) { name -> Api.updateCircle(c.id, name = name, done = ::onCircleChanged) }
            },
        ))
        root.addView(label("서클 종류 · 연인·친구로 바꾸면 일기 공유가 꺼지고 일기에 장소 이름이 빠져요"))
        root.addView(row(*CIRCLE_TYPES.map { (type, name) ->
            (if (c.type == type) primary("✓ $name") {} else secondary(name) {
                Api.updateCircle(c.id, type = type) { updated, err ->
                    if (updated != null && type != "family" && store.sharingAnything) {
                        store.stopSharing()
                        store.driverId?.let { d -> Api.deleteDiaries(c.id, d) {} }
                    }
                    onCircleChanged(updated, err)
                }
            })
        }.toTypedArray()))

        root.addView(space(8))
        root.addView(label("구성원 ${c.members.size}명 · 이 순서대로 운전자 선택 화면에 나와요"))
        c.members.forEachIndexed { i, m ->
            val ids = c.members.map { it.id }
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(CARD)
                setPadding(dp(24), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
                addView(big("${i + 1}. ${m.name}" + if (m.id == store.driverId) "  (운전 중)" else ""), LinearLayout.LayoutParams(0, -2, 1f))
                fun small(s: String, enabled: Boolean = true, onClick: () -> Unit) = navButton(s, onClick).apply {
                    isEnabled = enabled; alpha = if (enabled) 1f else 0.35f
                    layoutParams = LinearLayout.LayoutParams(dp(150), dp(72)).apply { marginStart = dp(10) }
                }
                addView(small("▲", i > 0) { Api.reorderMembers(c.id, ids.toMutableList().apply { add(i - 1, removeAt(i)) }, ::onCircleChanged) })
                addView(small("▼", i < ids.lastIndex) { Api.reorderMembers(c.id, ids.toMutableList().apply { add(i + 1, removeAt(i)) }, ::onCircleChanged) })
                addView(small("이름") { askText("${m.name} 이름 바꾸기", m.name) { name -> Api.renameMember(c.id, m.id, name, ::onCircleChanged) } })
                addView(small("빼기", c.members.size > 1) {
                    confirm("${m.name}님을 서클에서 뺄까요?", "${m.name}님이 남긴 메시지와 일기도 함께 지워져요.") {
                        Api.deleteMember(c.id, m.id) { updated, err ->
                            if (updated != null && store.driverId == m.id) Engine.setDriver(null)
                            onCircleChanged(updated, err)
                            refreshMessages()
                        }
                    }
                })
            })
        }
        root.addView(primary("+ 구성원 추가") { askText("새 구성원 이름", "") { name -> Api.addMember(c.id, name, ::onCircleChanged) } })
    }

    private fun onCircleChanged(updated: Circle?, error: String?) {
        if (updated != null) Engine.circle = updated else toast(error ?: "바꾸지 못했어요")
        render()
    }

    private fun refreshMessages() = Engine.refreshMessages()

    /** 정차 중에만 쓰는 글자 입력 창 */
    private fun askText(title: String, initial: String, multiline: Boolean = false, maxLength: Int = 40, onOk: (String) -> Unit) {
        if (parked != true) return
        val input = EditText(this).apply {
            filters = arrayOf(android.text.InputFilter.LengthFilter(maxLength))
            setText(initial.take(maxLength)); setSelection(text.length); textSize = 24f; setTextColor(Color.BLACK)
            if (multiline) { minLines = 3; maxLines = 5 } else isSingleLine = true
        }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton("확인") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onOk) }
            .setNegativeButton("취소", null).show().also { dialog = it }
    }

    private fun confirm(title: String, message: String, ok: String = "빼기", onOk: () -> Unit) {
        if (parked != true) return
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
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

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = sp; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun title(s: String) = text(s, 34f, INK, bold = true)
    private fun big(s: String) = text(s, 26f, INK)
    private fun label(s: String) = text(s, 20f, MUTED)
    private fun space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    private fun rounded(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(18).toFloat() }

    private fun card(children: List<View>) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD)
        setPadding(dp(24), dp(16), dp(24), dp(16))
        children.forEach { addView(it) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
    }

    private fun button(s: String, bg: Int, fg: Int, onClick: () -> Unit) = Button(this).apply {
        text = s; textSize = 24f; setTextColor(fg); isAllCaps = false
        background = rounded(bg); minHeight = dp(88)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(96)).apply { bottomMargin = dp(14) }
    }

    private fun primary(s: String, onClick: () -> Unit) = button(s, ACCENT, Color.WHITE, onClick)
    private fun secondary(s: String, onClick: () -> Unit) = button(s, SOFT, INK, onClick)

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, dp(96), 1f).apply { if (i > 0) marginStart = dp(14); bottomMargin = dp(14) })
        }
    }

    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun toggle(s: String, on: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = s; textSize = 24f; setTextColor(INK); isChecked = on
        gravity = Gravity.CENTER_VERTICAL; minHeight = dp(80)
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    companion object {
        const val EXTRA_AUTO = "auto"
        private const val NAVI_PACKAGE = "ai.umos.maps.android.navigation.app"
        private const val REQ_NOTI = 2
        private val CIRCLE_TYPES = listOf("family" to "가족", "couple" to "연인", "friends" to "친구")
        private const val REQ_MIC = 1
        private const val REQ_STT = 3
        private const val HERE = "지금 이 자리"
        /** 서버도 글을 300자에서 자른다 */
        private const val MAX_TEXT = 300
        /** 메시지가 열리는 조건 (폰 웹·서버와 같은 키) */
        private val WHEN_OPTIONS = listOf(
            "place" to "이 장소를 지나면",
            "rain" to "비 오는 날",
            "night" to "밤길",
            "together" to "옆자리에 누가 탄 날",
            "door" to "차에 탈 때",
        )
        /** 폰 웹과 같은 예시 장소 */
        private val PRESETS = listOf(
            Place("양평 국수집길", 37.4917, 127.4873),
            Place("양재 사거리", 37.48544722, 127.03636666),
            Place("강남역", 37.497952, 127.027619),
            Place("해운대 바닷길", 35.1587, 129.1604),
        )
        private val BG = Color.parseColor("#1F1B18")
        private val CARD = Color.parseColor("#2C2622")
        private val SOFT = Color.parseColor("#3A322C")
        private val INK = Color.parseColor("#F4EDE4")
        private val MUTED = Color.parseColor("#B5A89B")
        private val ACCENT = Color.parseColor("#D9774A")
        private val DIARY_BG = Color.parseColor("#3A2A20")

        fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * r * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}
