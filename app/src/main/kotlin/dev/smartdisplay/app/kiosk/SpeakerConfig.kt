package dev.smartdisplay.app.kiosk

import androidx.annotation.StringRes
import dev.smartdisplay.app.R

/** The display as a speaker Home Assistant can play to (see `speaker/Speaker.kt`). Off until the owner turns it on. */
data class SpeakerConfig(
    val enabled: Boolean = false,
    /** The name Home Assistant shows; null for one from the display's type ([defaultSpeakerName]). */
    val name: String? = null,
)

/** A speaker's name when the owner hasn't given one: what the display is for. */
@StringRes
fun defaultSpeakerName(mode: DisplayMode): Int = when (mode) {
    DisplayMode.Owner -> R.string.speaker_default_owner
    DisplayMode.Guest -> R.string.speaker_default_guest
    DisplayMode.Kitchen -> R.string.speaker_default_kitchen
}
