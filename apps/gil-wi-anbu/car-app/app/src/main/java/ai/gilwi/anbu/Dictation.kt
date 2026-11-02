package ai.gilwi.anbu

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 말하면 글로 적어 주기(받아쓰기). 차 화면에서 글자를 치기 어려워서 쓴다.
 * 안드로이드 기본 음성 인식(SpeechRecognizer)을 쓴다. Pleos 에뮬레이터는 ai.umos.ambientai가 인식 서비스다.
 * Pleos SpeechToText SDK는 아직 신청할 수 없어서(클라이언트 ID 필요) 열리면 바꿀 수 있게 여기만 고치면 된다.
 */
class Dictation(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())
    /** 인식 서비스가 아무 응답도 없을 때(에뮬레이터의 ambientai가 그렇다) 대신 끝내기 */
    private var onSilent: (() -> Unit)? = null
    private val silentTimeout = Runnable { onSilent?.invoke() }
    var listening = false
        private set
    /** 듣는 동안 중간 결과 */
    var partial = ""
        private set

    val available get() = SpeechRecognizer.isRecognitionAvailable(context)

    /** onText: 최종 글, onEnd: 끝(오류면 사람이 읽을 문구, 정상이면 null). 둘 다 메인 스레드. */
    fun start(onChange: () -> Unit, onText: (String) -> Unit, onEnd: (String?) -> Unit): Boolean {
        if (!available) return false
        cancel()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        onSilent = {
            Log.d("받아쓰기: 인식 서비스 응답 없음")
            cancel()
            onEnd("이 차의 음성 인식이 응답하지 않아요. 녹음이나 글로 남겨 주세요")
        }
        main.postDelayed(silentTimeout, READY_TIMEOUT_MS)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d("받아쓰기 준비")
                main.removeCallbacks(silentTimeout) // 응답이 오면 말하는 동안은 기다린다
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                partial = best(partialResults) ?: return
                onChange()
            }

            override fun onResults(results: Bundle?) {
                val text = best(results)
                Log.d("받아쓰기 결과: $text")
                finish()
                if (!text.isNullOrBlank()) onText(text)
                onEnd(if (text.isNullOrBlank()) "말소리를 알아듣지 못했어요" else null)
            }

            override fun onError(error: Int) {
                Log.d("받아쓰기 오류: $error")
                finish()
                onEnd(errorText(error))
            }
        })
        listening = true
        partial = ""
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        })
        onChange()
        return true
    }

    /** 말을 멈췄다고 알려 지금까지 들은 것으로 결과를 받는다. 결과가 안 오면 잠시 뒤 끝낸다. */
    fun stop() {
        recognizer?.stopListening()
        main.removeCallbacks(silentTimeout)
        main.postDelayed(silentTimeout, RESULT_TIMEOUT_MS)
    }

    /** 결과 없이 그만둔다 (출발했을 때 등) */
    fun cancel() {
        recognizer?.cancel()
        finish()
    }

    private fun finish() {
        main.removeCallbacks(silentTimeout)
        onSilent = null
        listening = false
        partial = ""
        recognizer?.destroy()
        recognizer = null
    }

    private fun best(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()

    private fun errorText(error: Int) = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "말소리를 알아듣지 못했어요. 다시 말해 주세요"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> "음성 인식 서버에 연결하지 못했어요"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크를 허락해야 말로 쓸 수 있어요"
        SpeechRecognizer.ERROR_AUDIO -> "마이크를 쓸 수 없어요"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "음성 인식이 다른 곳에서 쓰이고 있어요"
        else -> "말로 쓰기를 하지 못했어요 ($error)"
    }

    companion object {
        /** 시작하고 이 시간 안에 "준비됐어요"가 없으면 인식 서비스가 없는 것으로 본다 */
        const val READY_TIMEOUT_MS = 5_000L
        /** 「다 말했어요」 뒤 결과를 기다리는 시간 */
        const val RESULT_TIMEOUT_MS = 4_000L
    }
}
