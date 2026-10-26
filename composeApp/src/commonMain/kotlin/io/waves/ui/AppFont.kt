package io.waves.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/** Font of all app text: the system sans-serif, with in-between weights (350, 450) where the platform allows. */
internal expect val AppFontFamily: FontFamily

/** Material's type scale half a weight step lighter: body text 350, titles and labels 450. */
internal fun appTypography(): Typography = with(Typography()) {
    Typography(
        displayLarge = displayLarge.app(),
        displayMedium = displayMedium.app(),
        displaySmall = displaySmall.app(),
        headlineLarge = headlineLarge.app(),
        headlineMedium = headlineMedium.app(),
        headlineSmall = headlineSmall.app(),
        titleLarge = titleLarge.app(),
        titleMedium = titleMedium.app(),
        titleSmall = titleSmall.app(),
        bodyLarge = bodyLarge.app(),
        bodyMedium = bodyMedium.app(),
        bodySmall = bodySmall.app(),
        labelLarge = labelLarge.app(),
        labelMedium = labelMedium.app(),
        labelSmall = labelSmall.app(),
    )
}

private fun TextStyle.app(): TextStyle {
    val weight = fontWeight ?: FontWeight.Normal
    return copy(fontFamily = AppFontFamily, fontWeight = FontWeight((weight.weight - 50).coerceAtLeast(100)))
}
