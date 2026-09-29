package com.zdt.stage.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = ZdtAccentYellow,
    secondary = ZdtButton,
    background = ZdtBg,
    surface = ZdtCard,
    onPrimary = ZdtBg,
    onSecondary = ZdtTextPrimary,
    onBackground = ZdtTextPrimary,
    onSurface = ZdtTextPrimary
)

@Composable
fun ZdtStageTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
