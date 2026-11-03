package ai.gilwi.anbu

import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Handler
import android.os.Looper
import java.util.Locale
import ai.pleos.playground.tts.TextToSpeech as PleosTts

/**
 * 메시지 재생: 목소리는 MediaPlayer, 글은 TTS.
 * 글은 Pleos TTS SDK(차 안 엔진, 내비 안내 음성과 같은 것)를 먼저 쓰고, 안 되면 안드로이드 TTS로 읽는다.
 */
class Player(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val speech = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var media: MediaPlayer? = null
    private var pleos: PleosTts? = null
    private var pleosReady = false
    /** Pleos TTS로 읽는 중인 글이 끝나면 부를 것 */
    private var pleosDone: ((Boolean) -> Unit)? = null
    private var pleosText = ""
    /** Pleos에서 안드로이드 TTS로 바꾸는 중에 못 읽은 글. 안드로이드 TTS 준비가 끝나면 읽는다. */
    private var retry: (() -> Unit)? = null
    private var tts: TextToSpeech? = null
    private var androidReady = false
    val ttsReady get() = pleosReady || androidReady

    init {
        initPleos()
    }

    private fun initPleos() {
        val t = runCatching { PleosTts(context, Mode.ON_DEVICE) }.getOrElse {
            Log.d("Pleos TTS 만들기 실패: $it")
            initAndroidTts(PLEOS_TTS_ENGINE)
            return
        }
        pleos = t
        // 순서가 중요하다: initialize가 안쪽 엔진을 만들어서, 그 전에 addEventListener를 부르면 죽는다 (내비도 이 순서)
        runCatching {
            t.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    main.post {
                        // 나라까지 넣어야 한다: Locale.KOREAN("ko")은 엔진이 "unsupported locale: ko-"로 거절한다
                        runCatching { t.setLocale(Locale.KOREA) }
                        pleosReady = true
                        Log.d("TTS 준비(Pleos SDK): true")
                    }
                }
                override fun onFailure(error: ErrorType) {
                    main.post { pleosFailed("준비 실패 $error") }
                }
            })
            t.addEventListener(object : EventListener {
                override fun onStart() {}
                override fun onReady() {}
                override fun onDone() { main.post { finishPleos(true) } }
                override fun onError(error: ErrorType) {
                    Log.d("Pleos TTS 오류: $error")
                    main.post {
                        // 엔진 자체가 안 되면(에뮬레이터는 음성 모델이 없다) 안드로이드 TTS로 바꿔 같은 글을 다시 읽는다
                        if (error == ErrorType.ON_DEVICE_ENGINE_ERROR && pleosDone != null) {
                            val text = pleosText
                            val done = pleosDone!!
                            pleosDone = null
                            pleosFailed("읽기 중 $error")
                            retry = { speak(text, done) }
                        } else finishPleos(false)
                    }
                }
                override fun onStop() {}
                override fun onUpdatedRms(rms: Double) {}
            })
            t.setAudioAttributes(speech)
        }.onFailure { pleosFailed("$it") }
    }

    private fun pleosFailed(why: String) {
        if (pleos == null) return
        Log.d("Pleos TTS 안 됨($why) → 안드로이드 TTS")
        pleos?.runCatching { release() }
        pleos = null
        pleosReady = false
        initAndroidTts(PLEOS_TTS_ENGINE)
    }

    private fun finishPleos(ok: Boolean) {
        val done = pleosDone ?: return
        pleosDone = null
        done(ok)
    }

    // Pleos 음성 엔진이 안 되면 기본 엔진으로 한 번 더 시도한다
    private fun initAndroidTts(engine: String?) {
        tts = TextToSpeech(context, { status ->
            androidReady = status == TextToSpeech.SUCCESS && tts?.setLanguage(Locale.KOREAN)?.let { it >= 0 } == true
            Log.d("TTS 준비($engine): $androidReady (status=$status)")
            if (!androidReady && engine != null) {
                tts?.shutdown()
                initAndroidTts(null)
                return@TextToSpeech
            }
            main.post {
                val r = retry
                retry = null
                r?.invoke()
            }
        }, engine)
    }

    /** 재생이 끝나면(또는 실패하면) done(성공 여부). */
    fun play(msg: PlaceMessage, done: (Boolean) -> Unit) {
        stop()
        if (msg.kind == "voice" && msg.audioUrl != null) playVoice(Api.mediaUrl(msg.audioUrl), done) else speak(msg.text, done)
    }

    fun speak(text: String, done: (Boolean) -> Unit) {
        val p = pleos
        if (pleosReady && p != null) {
            pleosDone = done
            pleosText = text
            runCatching { p.speak(text) }.onFailure {
                Log.d("Pleos TTS 읽기 실패: $it")
                finishPleos(false)
            }
            return
        }
        val engine = tts
        if (!androidReady || engine == null) {
            done(false)
            return
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { main.post { done(true) } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { main.post { done(false) } }
        })
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "msg")
    }

    private fun playVoice(url: String, done: (Boolean) -> Unit) {
        val mp = MediaPlayer()
        media = mp
        try {
            mp.setAudioAttributes(speech)
            mp.setDataSource(url)
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { done(true) }
            mp.setOnErrorListener { _, what, extra -> Log.d("재생 오류 $what/$extra"); done(false); true }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.d("재생 실패: $e")
            done(false)
        }
    }

    fun stop() {
        media?.runCatching { stop(); release() }
        media = null
        // 멈춘 글은 끝난 것으로 알리지 않는다 (안드로이드 TTS의 stop도 onDone을 부르지 않는다)
        pleosDone = null
        retry = null
        pleos?.runCatching { stop() }
        tts?.stop()
    }

    fun release() {
        stop()
        pleos?.runCatching { release() }
        tts?.shutdown()
    }

    companion object {
        /** 안드로이드 TTS로 쓸 때의 Pleos 엔진 이름 (에뮬레이터에서는 초기화 실패) */
        const val PLEOS_TTS_ENGINE = "ai.pleos.playground.caas"
    }
}
