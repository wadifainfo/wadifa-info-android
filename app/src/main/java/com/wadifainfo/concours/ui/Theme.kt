package com.wadifainfo.concours.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wadifainfo.concours.R

/*
 * Same design system as wadifa-info.com (Content/css/wf-ds.css, 2026-10-14): Moroccan green tuned for WCAG AA
 * (white on #15803D = 5:1), navy ink, amber for deadlines, IBM Plex Sans Arabic (SIL OFL, docs/licenses).
 * Screens take colours from the scheme only, never raw hex — except the per-employer avatar tints in Common.kt.
 */

val Plex = FontFamily(
    Font(R.font.plex_regular, FontWeight.Normal),
    Font(R.font.plex_semibold, FontWeight.SemiBold),
    Font(R.font.plex_semibold, FontWeight.Medium),
    Font(R.font.plex_bold, FontWeight.Bold),
)

private val Light = lightColorScheme(
    primary = Color(0xFF15803D), onPrimary = Color.White, primaryContainer = Color(0xFFDCF2E3), onPrimaryContainer = Color(0xFF0B3D22),
    secondary = Color(0xFF13233F), onSecondary = Color.White, secondaryContainer = Color(0xFFE6EBF3), onSecondaryContainer = Color(0xFF13233F),
    tertiary = Color(0xFFB45309), onTertiary = Color.White, tertiaryContainer = Color(0xFFFFF1DC), onTertiaryContainer = Color(0xFF6B3200),
    error = Color(0xFFB42318), onError = Color.White, errorContainer = Color(0xFFFDECEC), onErrorContainer = Color(0xFF7A1A12),
    background = Color(0xFFF5F7F9), onBackground = Color(0xFF0F1B2E), surface = Color(0xFFF5F7F9), onSurface = Color(0xFF0F1B2E),
    surfaceVariant = Color(0xFFE9EDF2), onSurfaceVariant = Color(0xFF566375),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White, surfaceContainer = Color(0xFFEEF2F6),
    surfaceContainerHigh = Color(0xFFE7ECF2), surfaceContainerHighest = Color(0xFFDDE3EA),
    outline = Color(0xFFC3CCD7), outlineVariant = Color(0xFFE2E7EE),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF6FD896), onPrimary = Color(0xFF00391A), primaryContainer = Color(0xFF0E4D28), onPrimaryContainer = Color(0xFFB8F0CB),
    secondary = Color(0xFFB7C6E0), onSecondary = Color(0xFF13233F), secondaryContainer = Color(0xFF263449), onSecondaryContainer = Color(0xFFD8E2F2),
    tertiary = Color(0xFFF5B66B), onTertiary = Color(0xFF4A2500), tertiaryContainer = Color(0xFF4A2A06), onTertiaryContainer = Color(0xFFFFDDB8),
    error = Color(0xFFFFB4A9), onError = Color(0xFF680003), errorContainer = Color(0xFF5C1610), onErrorContainer = Color(0xFFFFDAD4),
    background = Color(0xFF0E131A), onBackground = Color(0xFFE3E8EF), surface = Color(0xFF0E131A), onSurface = Color(0xFFE3E8EF),
    surfaceVariant = Color(0xFF232B36), onSurfaceVariant = Color(0xFFA9B4C2),
    surfaceContainerLowest = Color(0xFF0A0E13), surfaceContainerLow = Color(0xFF161C24), surfaceContainer = Color(0xFF1A212A),
    surfaceContainerHigh = Color(0xFF222A34), surfaceContainerHighest = Color(0xFF2B3440),
    outline = Color(0xFF465263), outlineVariant = Color(0xFF2C3540),
)

private fun t(size: Int, line: Int, w: FontWeight = FontWeight.Normal) = TextStyle(fontFamily = Plex, fontSize = size.sp, lineHeight = line.sp, fontWeight = w)

/** One type scale for both languages; Arabic needs the taller line heights. */
private val Type = Typography(
    headlineSmall = t(22, 32, FontWeight.Bold),
    titleLarge = t(20, 30, FontWeight.Bold),
    titleMedium = t(17, 26, FontWeight.SemiBold),
    titleSmall = t(15, 23, FontWeight.SemiBold),
    bodyLarge = t(16, 26),
    bodyMedium = t(14, 22),
    bodySmall = t(13, 20),
    labelLarge = t(14, 20, FontWeight.SemiBold),
    labelMedium = t(12, 17, FontWeight.SemiBold),
    labelSmall = t(11, 16, FontWeight.SemiBold),
)

private val Shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp), extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = Type, shapes = Shapes, content = content)

/** Brand header colour: saturated green in light mode, deep green in dark mode (a bright green slab glares at night). */
@Composable fun heroColor() = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.primary
@Composable fun heroInk() = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onPrimary
