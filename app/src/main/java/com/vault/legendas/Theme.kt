package com.vault.legendas

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Mesmas cores do Cofre (índigo sobre fundo escuro). */
private val scheme = darkColorScheme(
    primary = Color(0xFF8FA8FF), onPrimary = Color(0xFF0B1330),
    primaryContainer = Color(0xFF2B3A78), onPrimaryContainer = Color(0xFFDCE3FF),
    secondaryContainer = Color(0xFF2A3042), onSecondaryContainer = Color(0xFFDDE1F2),
    background = Color(0xFF0B0D12), onBackground = Color(0xFFE6E8EB),
    surface = Color(0xFF12151C), onSurface = Color(0xFFE6E8EB),
    surfaceVariant = Color(0xFF1B1F29), onSurfaceVariant = Color(0xFFA9AFBD),
    surfaceContainer = Color(0xFF161A22), surfaceContainerHigh = Color(0xFF1B1F29), surfaceContainerHighest = Color(0xFF242936),
    error = Color(0xFFFF8A80), errorContainer = Color(0xFF5C1F1F),
    outline = Color(0xFF3A4152),
)

@Composable
fun LegendasTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)
