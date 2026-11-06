package dev.smartdisplay.app.ui.dashboard

import android.content.Context
import android.media.RingtoneManager
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Says reminders out loud, at the media volume, with Android's text-to-speech and the notification sound. The speech
 * engine starts the first time it's needed. A tablet without one just plays the sound.
 */
internal class Announcer(context: Context) {
    private val context = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var waiting: Pair<String, Boolean>? = null

    fun announce(text: String, sound: Boolean, voice: Boolean) {
        if (sound) playSound()
        if (!voice) return
        if (ready) {
            speak(text, after = sound)
        } else {
            waiting = text to sound
            if (tts == null) {
                tts = TextToSpeech(context) { status ->
                    ready = status == TextToSpeech.SUCCESS
                    if (ready) {
                        tts?.language = Locale.getDefault()
                        waiting?.let { (words, after) -> speak(words, after) }
                    } else {
                        Log.w(TAG, "No text-to-speech engine: $status")
                        tts = null
                    }
                    waiting = null
                }
            }
        }
    }

    private fun speak(text: String, after: Boolean) {
        val engine = tts ?: return
        // A moment for the sound first.
        if (after) engine.playSilentUtterance(SOUND_MS, TextToSpeech.QUEUE_ADD, "pause")
        engine.speak(text, TextToSpeech.QUEUE_ADD, null, "reminder")
    }

    private fun playSound() {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(context, uri)?.play()
        }.onFailure { Log.w(TAG, "Couldn't play the reminder sound", it) }
    }

    fun close() {
        tts?.shutdown()
        tts = null
        ready = false
    }

    private companion object {
        const val TAG = "Announcer"
        const val SOUND_MS = 1_200L
    }
}
