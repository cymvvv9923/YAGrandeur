package ai.daon.car

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Engine을 살려 두는 포그라운드 서비스. 앱 화면을 닫거나 내비를 띄워도 계속 돈다. */
class DaonService : Service() {

    override fun onCreate() {
        super.onCreate()
        Notifier.init(this)
        goForeground(withMic = false)
        Engine.start(this)
    }

    /**
     * 주행 중(내비가 앞에 있을 때) 말로 대답 받기·녹음을 하려면 마이크 형식의 포그라운드 서비스여야 한다.
     * 안드로이드는 앱 화면이 보일 때만 마이크 형식을 켜게 하므로, 화면이 보일 때 MainActivity가 ACTION_MIC로 다시 부른다.
     */
    private fun goForeground(withMic: Boolean) {
        if (Build.VERSION.SDK_INT >= 34) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                (if (withMic && micAllowed()) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0) or
                (if (withMic && locAllowed()) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
            runCatching { startForeground(Notifier.STATUS_ID, Notifier.status(), type) }
                .onFailure { Log.d("마이크 서비스 실패: $it"); startForeground(Notifier.STATUS_ID, Notifier.status(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }
            if (withMic) Log.d("서비스 형식: 마이크 ${if (type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0) "켬" else "못 켬"} · 위치 ${if (type and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0) "켬" else "못 켬"}")
        } else startForeground(Notifier.STATUS_ID, Notifier.status())
    }

    private fun locAllowed() = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
    private fun micAllowed() = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED

    // 알림 버튼
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HEARD -> Engine.heard()
            ACTION_REPLAY -> Engine.playCurrent()
            ACTION_LATER -> Engine.later()
            ACTION_DONT_SEND -> Engine.cancelAutoHeard()
            ACTION_MIC -> goForeground(withMic = true)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Engine.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_HEARD = "ai.daon.car.HEARD"
        const val ACTION_REPLAY = "ai.daon.car.REPLAY"
        const val ACTION_LATER = "ai.daon.car.LATER"
        const val ACTION_DONT_SEND = "ai.daon.car.DONT_SEND"
        const val ACTION_MIC = "ai.daon.car.MIC"

        /** 화면이 보일 때 불러 마이크 형식을 켠다 */
        fun enableMic(context: Context) {
            runCatching { context.startForegroundService(Intent(context, DaonService::class.java).setAction(ACTION_MIC)) }
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DaonService::class.java))
        }
    }
}

/**
 * 시연용 위치 주입. 에뮬레이터는 차가 실제로 움직이지 않아 거리·근처 메시지를 볼 수 없어서,
 * 경로를 따라 위치를 보내 운행을 흉내 낸다. 화면을 앞으로 띄우지 않으니 내비는 그대로 앞에 있다.
 *   adb shell am broadcast -n ai.daon.car/.DemoReceiver --ef lat 37.49 --ef lon 127.03
 *   adb shell am broadcast -n ai.daon.car/.DemoReceiver --ez clear true
 */
class DemoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        Notifier.init(context)
        Engine.start(context)
        // 시연용: 「오래 운전할 때」를 바로 보려고 주행 시작을 N분 전으로
        if (intent.hasExtra("driveMin")) { Engine.demoDriveMinutes(intent.getIntExtra("driveMin", 0)); return }
        if (intent.getBooleanExtra("clear", false)) Engine.setDemoLocation(null)
        else if (intent.hasExtra("lat") && intent.hasExtra("lon"))
            Engine.setDemoLocation(intent.getFloatExtra("lat", 0f).toDouble() to intent.getFloatExtra("lon", 0f).toDouble())
    }
}

/** 시동(부팅) 때 앱을 열지 않아도 서비스가 켜지게 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) DaonService.start(context)
    }
}
