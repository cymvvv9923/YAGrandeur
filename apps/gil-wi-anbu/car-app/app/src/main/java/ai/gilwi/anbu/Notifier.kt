package ai.gilwi.anbu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * 내비가 앞에 있을 때 쓰는 알림.
 * - 상태 알림(서비스 유지용, 조용히)
 * - 메시지 알림: 운전 중 도착 알림 / 정차 중 재생 알림(「들었어요」「다시 듣기」「나중에」 버튼)
 */
object Notifier {

    const val STATUS_ID = 1
    private const val MESSAGE_ID = 2
    private const val DRIVER_ID = 3
    private const val CH_STATUS = "status"
    private const val CH_MESSAGE = "message"

    private lateinit var app: Context
    private val nm get() = app.getSystemService(NotificationManager::class.java)
    private var lastStatus = ""

    fun init(context: Context) {
        app = context.applicationContext
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "동작 상태", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_MESSAGE, "길 위의 메시지", NotificationManager.IMPORTANCE_HIGH))
    }

    private val ready get() = ::app.isInitialized

    private fun openApp(auto: Boolean = false) = PendingIntent.getActivity(
        app, if (auto) 1 else 0,
        Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_AUTO, auto),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun action(act: String, title: String) = Notification.Action.Builder(
        null, title,
        PendingIntent.getService(app, act.hashCode(), Intent(app, AnbuService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE),
    ).build()

    private fun builder(channel: String) = Notification.Builder(app, channel)
        .setSmallIcon(android.R.drawable.ic_dialog_email)
        .setContentIntent(openApp())

    private fun statusText(): String {
        val e = Engine
        val wait = e.pending.size
        return when (e.gear) {
            CarSignals.Gear.R -> "후진 중"
            CarSignals.Gear.N -> "중립"
            CarSignals.Gear.D -> if (wait > 0) "운전 중 · 받은 메시지 ${wait}개 · 차를 세우면 들려드려요" else "운전 중 · 지나는 길의 메시지를 찾고 있어요"
            CarSignals.Gear.P -> if (wait > 0) "정차 중 · 들을 메시지 ${wait}개" else "정차 중"
            null -> "차 상태를 확인하는 중"
        }
    }

    fun status(): Notification = builder(CH_STATUS).setContentTitle("다온").setContentText(statusText().also { lastStatus = it }).setOngoing(true).build()

    fun updateStatus() {
        if (!ready) return
        val text = statusText()
        if (text == lastStatus) return
        nm.notify(STATUS_ID, status())
    }

    /** 운전 중 근처를 지났을 때: 소리 한 번과 한 줄. discreet=true(옆자리에 누가 있음)면 누가 보냈는지 쓰지 않는다. */
    fun arrived(m: PlaceMessage, total: Int, discreet: Boolean = false) {
        if (!ready) return
        nm.notify(MESSAGE_ID, builder(CH_MESSAGE)
            .setContentTitle(when {
                discreet -> "메시지가 도착했어요"
                m.isPlace -> "${m.placeName}에 ${m.authorName}님의 메시지가 있어요"
                else -> "「${m.placeName}」 ${m.authorName}님의 메시지가 열렸어요"
            })
            .setContentText(if (total > 1) "받은 메시지 ${total}개 · 차를 세우면 들려드릴게요" else "차를 세우면 들려드릴게요")
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setTimeoutAfter(15_000)
            .build())
    }

    /** 정차 중 재생: 화면이 내비에 가려도 알림에서 「들었어요」를 누를 수 있게. */
    fun playing(m: PlaceMessage, state: String, countdown: Boolean = false) {
        if (!ready) return
        // 운전 중에는 화면에 아무것도 띄우지 않는다 (소리만)
        if (Engine.parked != true) { nm.cancel(MESSAGE_ID); return }
        // 화면이 보이면 재생 화면에 같은 버튼이 있다. Pleos는 알림을 화면 위에 띄워 재생 막대를 가리므로 화면이 보이는 동안엔 띄우지 않는다.
        // 화면이 가려지면(MainActivity.onStop) 다시 부른다.
        if (Engine.uiVisible) { nm.cancel(MESSAGE_ID); return }
        nm.notify(MESSAGE_ID, builder(CH_MESSAGE)
            .setContentTitle("${m.authorName}님이 ${m.occasion} 남긴 메시지")
            .setContentText(if (m.kind == "text") "$state · “${m.text}”" else state)
            .setStyle(Notification.BigTextStyle().bigText(if (m.kind == "text") "$state\n“${m.text}”" else state))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(action(AnbuService.ACTION_HEARD, "들었어요"))
            .addAction(if (countdown) action(AnbuService.ACTION_DONT_SEND, "보내지 않기") else action(AnbuService.ACTION_REPLAY, "다시 듣기"))
            .addAction(action(AnbuService.ACTION_LATER, "나중에"))
            .build())
    }

    fun clearPlaying() { if (ready) nm.cancel(MESSAGE_ID) }

    fun needDriver() {
        if (!ready) return
        nm.notify(DRIVER_ID, builder(CH_MESSAGE).setContentTitle("이 차는 누구 차인가요?").setContentText("눌러서 차 주인을 골라 주세요")
            .setContentIntent(openApp(auto = true)).setAutoCancel(true).build())
    }

    fun clearNeedDriver() { if (ready) nm.cancel(DRIVER_ID) }

    /**
     * 정차했을 때 앱을 잠깐 앞으로 띄운다. 안드로이드가 막으면(백그라운드 실행 제한) 알림만 남는다.
     * 앞으로 뜬 화면은 재생이 끝나면 스스로 뒤로 물러나 내비로 돌아간다.
     */
    fun bringToFront() {
        if (!ready) return
        runCatching {
            app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_AUTO, true))
        }.onFailure { Log.d("앱 앞으로 띄우기 실패: $it") }
    }
}
