package ai.gilwi.anbu

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * 운전 중 「녹음해 줘」: 글레오는 시작 신호만 주고, 남길 말은 이 앱이 직접 받는다.
 * 글레오 음성 인식이 내용을 잘못 알아듣거나 전화·문자로 넘기는 문제를 피하려고, 내용은 글레오를 거치지 않는다.
 *
 *   안내("가족에 남길 말을 말씀하세요") → 삐 → 받기 → 남기기 → 결과 안내 ("가족에 목소리로 남겼어요")
 *
 * 운전 중에는 내 목소리 그대로만 남긴다 (말이 끝나고 잠시 조용하면 저절로 멈춘다).
 * 글(기계음)로 남기기는 정차 중 메시지 화면에서만 한다 (앱 받아쓰기가 이 차에서 응답하지 않을 때가 있어 운전 중엔 쓰지 않는다).
 * 화면은 쓰지 않으므로 운전 중에도 된다 (마이크는 AnbuService의 마이크 형식 포그라운드 서비스로 쓴다).
 */
object VoiceNote {
    private val main = Handler(Looper.getMainLooper())
    /** 받는 중이면 다음 「녹음해 줘」는 무시한다 */
    var busy = false
        private set
    private var recorder: Recorder? = null
    private var dictation: Dictation? = null

    /** 글레오가 대답을 마치고 마이크를 놓을 시간 */
    private const val START_DELAY_MS = 1200L
    /** 이 소리보다 크면 말하는 중으로 본다 (MediaRecorder 최대 소리 0~32767) */
    private const val SPEECH_AMP = 1500
    /** 말한 뒤 이만큼 조용하면 다 말한 것으로 본다 */
    private const val SILENCE_MS = 1800L
    /** 시작하고 이만큼 아무 말이 없으면 그만둔다 */
    private const val NO_SPEECH_MS = 7000L
    /** 한 번에 받는 가장 긴 말 */
    private const val MAX_MS = 20_000L

    /**
     * group: 앞에 정확히 말한 그룹 이름 (「우리집 녹음해 줘」). 비면 지금 관리하는 그룹 (「녹음해 줘」).
     * 그룹 이름이 정확히 맞지 않으면 짐작해서 남기지 않고, 있는 그룹 이름을 알려 준다.
     * useSpot: 방금 저장한 곳에 남긴다.
     */
    fun start(context: Context, group: String, useSpot: Boolean) {
        if (busy) { Log.d("녹음해 줘: 이미 받는 중"); return }
        val c = if (group.isBlank()) Engine.circle else Engine.findGroupExact(group)
        if (c == null) {
            val names = Engine.groupNames().joinToString(", ")
            Log.d("녹음해 줘: 그룹 이름 안 맞음 「$group」 (있는 그룹: $names)")
            Engine.say(if (group.isBlank()) "아직 그룹에 연결되지 않았어요."
                else "「$group」이라는 그룹이 없어요. $names 중에서 이름을 정확히 말해 주세요.")
            return
        }
        busy = true
        Log.d("녹음해 줘: ${c.name} · 목소리 그대로")
        main.postDelayed({
            Engine.speakThen("${c.name}에 남길 말을 말씀하세요.") {
                beep()
                main.postDelayed({
                    recordVoice(context.applicationContext, c, useSpot)
                }, 350)
            }
        }, START_DELAY_MS)
    }

    // 목소리 그대로 ---------------------------------------------------------

    private fun recordVoice(context: Context, c: Circle, useSpot: Boolean) {
        val r = Recorder(context, "voice_note.m4a")
        recorder = r
        if (!r.start { finishVoice(r, c, useSpot) }) {
            recorder = null
            done("마이크를 쓸 수 없어서 남기지 못했어요.")
            return
        }
        val startedAt = SystemClock.uptimeMillis()
        var heard = false
        var quietSince = startedAt
        val tick = object : Runnable {
            override fun run() {
                if (recorder !== r) return
                val now = SystemClock.uptimeMillis()
                if (r.amplitude() > SPEECH_AMP) { heard = true; quietSince = now }
                when {
                    !heard && now - startedAt > NO_SPEECH_MS -> {
                        recorder = null; r.clear()
                        done("말씀이 들리지 않아 남기지 않았어요.")
                    }
                    heard && now - quietSince > SILENCE_MS -> finishVoice(r, c, useSpot)
                    now - startedAt > MAX_MS -> finishVoice(r, c, useSpot)
                    else -> main.postDelayed(this, 200)
                }
            }
        }
        main.postDelayed(tick, 200)
    }

    private fun finishVoice(r: Recorder, c: Circle, useSpot: Boolean) {
        if (recorder !== r) return
        recorder = null
        r.stop()
        val audio = r.base64()
        r.clear()
        if (audio == null) { done("녹음이 되지 않아 남기지 못했어요."); return }
        Log.d("녹음해 줘: 목소리 ${audio.length * 3 / 4 / 1024}KB")
        Engine.leaveMessage("", emptyList(), "", useSpot, c.name, audioBase64 = audio) { o -> report(o, voice = true) }
    }

    // 결과 ------------------------------------------------------------------

    private fun report(o: Engine.LeaveOutcome, voice: Boolean) {
        val whenKo = if (o.place.isNotEmpty()) "${o.place}에서" else Engine.LEAVE_WHEN[o.whenKey].orEmpty()
        done(when (o.result) {
            Engine.LeaveResult.SENT -> if (voice) "「${o.group}」에 목소리로 남겼어요. $whenKo 들려줄게요."
                else "「${o.group}」에 「${o.text}」라고 남겼어요."
            Engine.LeaveResult.NO_LOCATION -> "지금 위치를 몰라서 남기지 못했어요."
            Engine.LeaveResult.NOT_READY -> "아직 그룹에 연결되지 않아 남기지 못했어요."
            Engine.LeaveResult.NO_AUTHOR -> "보내는 사람이 정해지지 않아 남기지 못했어요."
            else -> "남기지 못했어요. ${o.detail}".trim()
        })
    }

    private fun done(say: String) {
        Log.d("녹음해 줘 끝: $say")
        busy = false
        Engine.say(say)
    }

    /** 이제 말하라는 짧은 삐 소리 */
    private fun beep() {
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
            main.postDelayed({ tg.release() }, 400)
        }
    }
}
