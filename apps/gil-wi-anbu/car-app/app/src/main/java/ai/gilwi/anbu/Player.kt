package ai.gilwi.anbu

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Handler
import android.os.Looper
import java.util.Locale

/** 메시지 재생: 목소리는 MediaPlayer, 글은 TTS(Pleos 음성 엔진 우선). */
class Player(context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var media: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    var ttsReady = false
        private set

    init {
        initTts(context, PLEOS_TTS_ENGINE)
    }

    // Pleos 음성 엔진이 안 되면 기본 엔진으로 한 번 더 시도한다
    private fun initTts(context: Context, engine: String?) {
        tts = TextToSpeech(context, { status ->
            ttsReady = status == TextToSpeech.SUCCESS && tts?.setLanguage(Locale.KOREAN)?.let { it >= 0 } == true
            Log.d("TTS 준비($engine): $ttsReady (status=$status)")
            if (!ttsReady && engine != null) {
                tts?.shutdown()
                initTts(context, null)
            }
        }, engine)
    }

    /** 재생이 끝나면(또는 실패하면) done(성공 여부). */
    fun play(msg: PlaceMessage, done: (Boolean) -> Unit) {
        stop()
        if (msg.kind == "voice" && msg.audioUrl != null) playVoice(Api.mediaUrl(msg.audioUrl), done) else speak(msg.text, done)
    }

    fun speak(text: String, done: (Boolean) -> Unit) {
        val engine = tts
        if (!ttsReady || engine == null) {
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
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
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
        tts?.stop()
    }

    fun release() {
        stop()
        tts?.shutdown()
    }

    companion object {
        const val PLEOS_TTS_ENGINE = "ai.pleos.playground.caas"
    }
}
