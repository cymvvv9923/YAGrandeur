package ai.gilwi.anbu

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Engine을 살려 두는 포그라운드 서비스. 앱 화면을 닫거나 내비를 띄워도 계속 돈다. */
class AnbuService : Service() {

    override fun onCreate() {
        super.onCreate()
        Notifier.init(this)
        if (Build.VERSION.SDK_INT >= 34) startForeground(Notifier.STATUS_ID, Notifier.status(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(Notifier.STATUS_ID, Notifier.status())
        Engine.start(this)
    }

    // 알림 버튼
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HEARD -> Engine.heard()
            ACTION_REPLAY -> Engine.playCurrent()
            ACTION_LATER -> Engine.later()
            ACTION_DONT_SEND -> Engine.cancelAutoHeard()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Engine.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_HEARD = "ai.gilwi.anbu.HEARD"
        const val ACTION_REPLAY = "ai.gilwi.anbu.REPLAY"
        const val ACTION_LATER = "ai.gilwi.anbu.LATER"
        const val ACTION_DONT_SEND = "ai.gilwi.anbu.DONT_SEND"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AnbuService::class.java))
        }
    }
}

/**
 * 시연용 위치 주입. 에뮬레이터는 차가 실제로 움직이지 않아 거리·근처 메시지를 볼 수 없어서,
 * 경로를 따라 위치를 보내 운행을 흉내 낸다. 화면을 앞으로 띄우지 않으니 내비는 그대로 앞에 있다.
 *   adb shell am broadcast -n ai.gilwi.anbu/.DemoReceiver --ef lat 37.49 --ef lon 127.03
 *   adb shell am broadcast -n ai.gilwi.anbu/.DemoReceiver --ez clear true
 */
class DemoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        Notifier.init(context)
        Engine.start(context)
        if (intent.getBooleanExtra("clear", false)) Engine.setDemoLocation(null)
        else if (intent.hasExtra("lat") && intent.hasExtra("lon"))
            Engine.setDemoLocation(intent.getFloatExtra("lat", 0f).toDouble() to intent.getFloatExtra("lon", 0f).toDouble())
    }
}

/** 시동(부팅) 때 앱을 열지 않아도 서비스가 켜지게 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) AnbuService.start(context)
    }
}
