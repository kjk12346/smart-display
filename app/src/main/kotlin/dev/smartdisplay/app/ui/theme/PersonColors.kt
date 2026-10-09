package dev.smartdisplay.app.ui.theme

import androidx.compose.ui.graphics.Color
import dev.smartdisplay.app.R
import dev.smartdisplay.app.kiosk.PersonColor

// People's colors on the family calendar: the theme's three pastels first, then more in the same soft range, all
// light enough for dark text on top.

private val Mint = Color(0xFF9ADBC1)
private val Peach = Color(0xFFF7BE96)
private val Butter = Color(0xFFF2DC8C)
private val Coral = Color(0xFFF29B93)
private val Sky = Color(0xFF9DB8F2)

/** The family's own calendars: a soft neutral, so people's colors stand out. */
val FamilyColor: Color = CloudGrey

/** Text on a person's color. */
val OnPersonColor: Color = Ink

fun PersonColor.color(): Color = when (this) {
    PersonColor.Pink -> Pink500
    PersonColor.Blue -> Blue500
    PersonColor.Lavender -> Lavender500
    PersonColor.Mint -> Mint
    PersonColor.Peach -> Peach
    PersonColor.Butter -> Butter
    PersonColor.Coral -> Coral
    PersonColor.Sky -> Sky
}

/** The color's name, for screen readers and Settings. */
fun PersonColor.label(): Int = when (this) {
    PersonColor.Pink -> R.string.color_pink
    PersonColor.Blue -> R.string.color_blue
    PersonColor.Lavender -> R.string.color_lavender
    PersonColor.Mint -> R.string.color_mint
    PersonColor.Peach -> R.string.color_peach
    PersonColor.Butter -> R.string.color_butter
    PersonColor.Coral -> R.string.color_coral
    PersonColor.Sky -> R.string.color_sky
}
