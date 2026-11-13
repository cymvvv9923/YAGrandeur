package ai.daon.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * 시연·검증용: 시나리오에 필요한 플랫폼 기능이 이 차에서 되는지 하나씩 시험하고 로그(DAON "PROBE")로 남긴다.
 * 디버그 빌드에서만 동작한다.
 *   adb shell am broadcast --user current -n ai.daon.car/.ProbeReceiver --es probe stt_android
 * probe: stt_android · stt_pleos · mic · audio · belt · telltale · ignition · all
 */
class ProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val app = context.applicationContext
        val what = intent.getStringExtra("probe") ?: "all"
        Engine.start(app)
        val list = if (what == "all") listOf("stt_android", "stt_pleos", "mic", "audio", "belt", "telltale", "ignition") else listOf(what)
        // 하나씩 겹치지 않게 4초 간격으로
        list.forEachIndexed { i, p -> main.postDelayed({ run(app, p) }, i * 4000L) }
    }

    private fun run(c: Context, p: String) {
        out("--- $p 시작")
        runCatching {
            when (p) {
                "stt_android" -> Probes.sttAndroid(c)
                "stt_pleos" -> Probes.sttPleos(c)
                "stt_pleos_server" -> Probes.sttPleos(c, ai.pleos.playground.stt.constant.Mode.SERVER)
                "stt_pleos_hybrid" -> Probes.sttPleos(c, ai.pleos.playground.stt.constant.Mode.HYBRID)
                "mic" -> Probes.mic(c)
                "audio" -> Probes.audio(c)
                "belt" -> Probes.belt(c)
                "telltale" -> Probes.telltale(c)
                "ignition" -> Probes.ignition(c)
                else -> out("모르는 probe: $p")
            }
        }.onFailure { out("$p 예외: $it") }
    }

    companion object {
        private val main = Handler(Looper.getMainLooper())
        fun out(s: String) = Log.d("PROBE $s")
    }
}

object Probes {
    private val main = Handler(Looper.getMainLooper())
    private fun out(s: String) = ProbeReceiver.out(s)
    private fun state() = "기어=${Engine.gear} 화면보임=${Engine.uiVisible}"

    /** 안드로이드 표준 음성 인식 (차에서는 Gleo의 인식 서비스로 연결된다) */
    fun sttAndroid(c: Context) {
        val ok = android.speech.SpeechRecognizer.isRecognitionAvailable(c)
        out("stt_android 사용 가능=$ok 기본 서비스=${android.provider.Settings.Secure.getString(c.contentResolver, "voice_recognition_service")} ${state()}")
        if (!ok) return
        val r = android.speech.SpeechRecognizer.createSpeechRecognizer(c)
        r.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(b: android.os.Bundle?) { out("stt_android 듣기 시작됨 (마이크 열림)") }
            override fun onBeginningOfSpeech() { out("stt_android 말소리 감지") }
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { out("stt_android 말 끝") }
            override fun onError(e: Int) { out("stt_android 오류 코드 $e (${sttErr(e)})"); r.destroy() }
            override fun onResults(b: android.os.Bundle?) {
                out("stt_android 결과: ${b?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)}"); r.destroy()
            }
            override fun onPartialResults(b: android.os.Bundle?) {}
            override fun onEvent(t: Int, b: android.os.Bundle?) {}
        })
        r.startListening(Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
        main.postDelayed({ runCatching { r.cancel(); r.destroy() } }, 8000)
    }

    private fun sttErr(e: Int) = when (e) {
        1 -> "네트워크 시간 초과"; 2 -> "네트워크"; 3 -> "오디오(마이크)"; 4 -> "서버"; 5 -> "클라이언트"; 6 -> "말 없음(시간 초과)"
        7 -> "알아듣지 못함"; 8 -> "인식기 사용 중"; 9 -> "권한 없음"; 10 -> "요청 너무 많음"; 11 -> "서버 끊김"; 12 -> "언어 미지원"; 13 -> "언어 사용 불가"
        else -> "?"
    }

    /** Pleos SpeechToText SDK (차 안 엔진, ON_DEVICE) */
    fun sttPleos(c: Context, mode: ai.pleos.playground.stt.constant.Mode = ai.pleos.playground.stt.constant.Mode.ON_DEVICE) {
        out("stt_pleos 방식=$mode")
        val stt = ai.pleos.playground.stt.SpeechToText(c, mode)
        stt.initialize(object : ai.pleos.playground.stt.listener.OnInitializeListener {
            override fun onSuccess() {
                out("stt_pleos 준비 성공 ${state()}")
                runCatching { stt.setLocale(java.util.Locale.KOREA) }.onFailure { out("stt_pleos 언어 설정 실패: $it") }
                stt.addListener(object : ai.pleos.playground.stt.listener.ResultListener {
                    override fun onUpdated(text: String, isFinal: Boolean) { out("stt_pleos 인식: \"$text\" 끝=$isFinal") }
                    override fun onUpdatedEpdData(a: Long, b: Long) {}
                    override fun onStartedRecognition() { out("stt_pleos 인식 시작 (마이크 열림)") }
                    override fun onEndedRecognition() { out("stt_pleos 인식 끝") }
                    override fun onError(e: ai.pleos.playground.stt.constant.ErrorType) { out("stt_pleos 오류: $e") }
                    override fun onReady() { out("stt_pleos 듣기 준비") }
                    override fun onUpdatedRms(v: Double) {}
                })
                runCatching { stt.request(); out("stt_pleos 듣기 요청 보냄") }.onFailure { out("stt_pleos 듣기 실패: $it") }
                main.postDelayed({ runCatching { stt.stop(); stt.release() } }, 8000)
            }
            override fun onFailure(e: ai.pleos.playground.stt.constant.ErrorType) { out("stt_pleos 준비 실패: $e"); runCatching { stt.release() } }
        })
    }

    /** 뒤에서(내비가 앞에 있을 때) 마이크로 녹음할 수 있는지. 안드로이드가 소리를 막으면(silenced) 0만 들어온다. */
    fun mic(c: Context) {
        if (c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) { out("mic 권한 없음"); return }
        val rate = 16000
        val size = android.media.AudioRecord.getMinBufferSize(rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
        @Suppress("MissingPermission")
        val rec = android.media.AudioRecord(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, size)
        out("mic 준비 state=${rec.state} ${state()}")
        rec.startRecording()
        Thread {
            val buf = ShortArray(size / 2)
            var peak = 0; var n = 0
            val end = System.currentTimeMillis() + 1500
            while (System.currentTimeMillis() < end) {
                val r = rec.read(buf, 0, buf.size)
                if (r > 0) { n += r; for (i in 0 until r) peak = maxOf(peak, kotlin.math.abs(buf[i].toInt())) }
            }
            val am = c.getSystemService(android.media.AudioManager::class.java)
            val silenced = am.activeRecordingConfigurations.firstOrNull { it.clientAudioSessionId == rec.audioSessionId }?.isClientSilenced
            out("mic 녹음 상태=${rec.recordingState} 받은 샘플=$n 최대 소리=$peak 안드로이드가 막음(silenced)=$silenced")
            rec.stop(); rec.release()
        }.start()
    }

    /** 뒤에서 소리를 낼 수 있는지 (오디오 포커스) */
    fun audio(c: Context) {
        val am = c.getSystemService(android.media.AudioManager::class.java)
        val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build()).build()
        val r = am.requestAudioFocus(req)
        out("audio 포커스 결과=${when (r) { 1 -> "받음"; 0 -> "거절"; 2 -> "나중에"; else -> "$r" }} ${state()}")
        main.postDelayed({ am.abandonAudioFocusRequest(req); Unit }, 1500)
    }

    private val vehicle by lazy { ai.pleos.playground.vehicle.Vehicle(Engine.appContext).also { it.initialize() } }

    /** 안전벨트 (동승자 판단을 좌석 센서 말고 벨트로도) */
    fun belt(c: Context) {
        main.postDelayed({
            val seat = vehicle.getSeat()
            seat.checkSeatBeltBuckledCapabilities({ areas -> out("belt 지원 좌석: $areas") }, { out("belt 지원 확인 실패: $it") })
            listOf(ai.pleos.playground.vehicle.constant.area.SeatValue.ROW_1_LEFT, ai.pleos.playground.vehicle.constant.area.SeatValue.ROW_1_RIGHT,
                ai.pleos.playground.vehicle.constant.area.SeatValue.ROW_2_LEFT, ai.pleos.playground.vehicle.constant.area.SeatValue.ROW_2_RIGHT).forEach { v ->
                val a = ai.pleos.playground.vehicle.api.model.area.SeatArea(v)
                seat.getCurrentSeatBeltBuckledState(a, { b -> out("belt $v 매었음=$b") }, { out("belt $v 실패: $it") })
                seat.getCurrentSeatOccupancy(a, { b -> out("seat $v 앉음(원래 값)=$b") }, { out("seat $v 실패: $it") })
            }
        }, 1500)
    }

    /** 경고등 (E4) */
    fun telltale(c: Context) {
        main.postDelayed({
            vehicle.getSafety().checkTelltalesCapabilities({ cfg -> out("telltale 지원: $cfg") }, { out("telltale 지원 확인 실패: $it") })
            vehicle.getSafety().getTelltalesInfo({ t -> out("telltale 값: $t") }, { out("telltale 실패: $it") })
        }, 1500)
    }

    /** 시동 상태 (AAOS 표준 IGNITION_STATE, CAR_POWERTRAIN 권한) */
    fun ignition(c: Context) {
        val car = android.car.Car.createCar(c) ?: run { out("ignition Car 연결 실패"); return }
        val pm = car.getCarManager(android.car.Car.PROPERTY_SERVICE) as android.car.hardware.property.CarPropertyManager
        runCatching {
            val v = pm.getProperty<Int>(android.car.VehiclePropertyIds.IGNITION_STATE, 0)
            out("ignition 값=${v.value} (0 모름 1 잠김 2 꺼짐 3 ACC 4 켜짐 5 시동 중)")
        }.onFailure { out("ignition 실패: $it") }
        car.disconnect()
    }
}
