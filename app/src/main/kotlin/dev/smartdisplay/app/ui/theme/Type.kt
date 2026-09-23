package dev.smartdisplay.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// System font (Roboto on most tablets) for now; a bundled typeface can come with the visual design.
internal val SmartDisplayTypography = Typography()

/** Small tracked caps shown above a title, in a highlight colour. */
val Typography.eyebrow: TextStyle
    get() = labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
