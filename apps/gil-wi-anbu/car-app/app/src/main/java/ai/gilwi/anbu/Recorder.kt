package ai.gilwi.anbu

import android.content.Context
import android.media.MediaRecorder
import android.util.Base64
import java.io.File

/** 차에서 목소리 메시지 녹음 (m4a, 최대 MAX_SECONDS초). 정차 중에만 쓴다. */
/** fileName: 화면 남기기(leave.m4a)와 운전 중 「녹음해 줘」(voice_note.m4a)가 서로 덮어쓰지 않게 따로 쓴다 */
class Recorder(private val context: Context, private val fileName: String = "leave.m4a") {

    private var rec: MediaRecorder? = null
    private val file get() = File(context.cacheDir, fileName)
    var hasRecording = false
        private set
    val recording get() = rec != null

    /** onAutoStop: 최대 길이에 닿아 저절로 멈췄을 때 */
    fun start(onAutoStop: () -> Unit): Boolean {
        stop()
        hasRecording = false
        return try {
            @Suppress("DEPRECATION")
            rec = (if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(64_000) // 60초에 약 480KB
                setMaxDuration(MAX_SECONDS * 1000)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) { stop(); onAutoStop() }
                }
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            true
        } catch (e: Exception) {
            Log.d("녹음 시작 실패: $e")
            rec?.release(); rec = null
            false
        }
    }

    fun stop() {
        val r = rec ?: return
        rec = null
        // 최대 길이로 이미 멈춘 뒤에는 stop()이 예외를 낼 수 있다. 파일이 있으면 녹음된 것으로 본다.
        try { r.stop() } catch (e: Exception) { Log.d("녹음 정지: $e") }
        r.release()
        hasRecording = file.length() > 0
    }

    /** 지난번 부른 뒤로 가장 큰 소리 (0~32767). 말이 끝났는지 볼 때 쓴다 */
    fun amplitude(): Int = runCatching { rec?.maxAmplitude ?: 0 }.getOrDefault(0)

    fun clear() { stop(); hasRecording = false; file.delete() }

    fun base64(): String? = if (hasRecording) Base64.encodeToString(file.readBytes(), Base64.NO_WRAP) else null

    val path get() = file.absolutePath

    companion object {
        const val MAX_SECONDS = 60
    }
}
