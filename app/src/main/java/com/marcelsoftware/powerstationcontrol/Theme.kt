package com.marcelsoftware.powerstationcontrol

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Zora design tokens (from zora/design/tokens.css, light theme; Zora defines no dark theme). */
object Zora {
    val Canvas = Color(0xFFF4F5FA)          // --color-bg-canvas (neutral-50)
    val Surface = Color(0xFFFFFFFF)         // --color-bg-surface
    val Tint = Color(0xFFEEF1FC)            // --color-bg-tint (dusk-50)
    val TintStrong = Color(0xFFDFE5F9)      // --color-bg-tint-strong (dusk-100)
    val Border = Color(0xFFE6E9F3)          // --color-border-default (neutral-100)
    val TextPrimary = Color(0xFF121A33)     // neutral-900
    val TextSecondary = Color(0xFF76809E)   // neutral-500
    val TextTertiary = Color(0xFFA9B0C8)    // neutral-300
    val Accent = Color(0xFF6B7FF0)          // dusk-500
    val AccentStrong = Color(0xFF3F4FC4)    // dusk-700
    val AccentSoft = Color(0xFFC4CEF7)      // dusk-200
    val Success = Color(0xFF1FA77A)
    val SuccessBg = Color(0xFFE3F6EC)
    val Danger = Color(0xFFE0506A)
    val DangerBg = Color(0xFFFDE9EC)
    val Sky = Color(0xFFBFE0FA)             // gradient-sky
    val Rose = Color(0xFFFFC2D8)            // gradient-rose

    val RadiusSm = 12.dp
    val RadiusMd = 16.dp
    val RadiusLg = 24.dp
    val RadiusXl = 28.dp
}

private val ZoraColors = lightColorScheme(
    primary = Zora.TextPrimary, onPrimary = Zora.Surface,                 // --color-action-primary
    primaryContainer = Zora.TintStrong, onPrimaryContainer = Zora.AccentStrong,
    secondary = Zora.Accent, onSecondary = Zora.Surface,
    secondaryContainer = Zora.Tint, onSecondaryContainer = Zora.AccentStrong,
    background = Zora.Canvas, onBackground = Zora.TextPrimary,
    surface = Zora.Surface, onSurface = Zora.TextPrimary,
    surfaceVariant = Zora.Tint, onSurfaceVariant = Zora.TextSecondary,
    outline = Zora.TextTertiary, outlineVariant = Zora.Border,
    error = Zora.Danger, onError = Zora.Surface, errorContainer = Zora.DangerBg, onErrorContainer = Zora.Danger,
)

@Composable
fun ZoraTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = ZoraColors, content = content)

/** Flat white card with the Zora border; optional soft pastel gradient. */
@Composable
fun ZoraCard(modifier: Modifier = Modifier, gradient: Brush? = null, onClick: (() -> Unit)? = null,
             content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(Zora.RadiusLg)
    val colors = CardDefaults.cardColors(containerColor = if (gradient != null) Color.Transparent else Zora.Surface)
    val border = BorderStroke(1.dp, Zora.Border)
    val inner: @Composable ColumnScope.() -> Unit = {
        if (gradient != null) Box(Modifier.fillMaxWidth().background(gradient)) { Column(content = content) } else content()
    }
    if (onClick != null) Card(onClick = onClick, modifier = modifier, shape = shape, colors = colors, border = border, content = inner)
    else Card(modifier = modifier, shape = shape, colors = colors, border = border, content = inner)
}
