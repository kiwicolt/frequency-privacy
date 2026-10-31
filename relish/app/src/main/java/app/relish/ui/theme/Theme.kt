package app.relish.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.relish.data.prefs.AppSettings
import app.relish.data.prefs.ThemeMode

/** Colours Material 3 has no slot for. */
@Immutable
data class RelishColors(
    val upvote: Color = Color(0xFFFF8B60),
    val downvote: Color = Color(0xFF9494FF),
    val nsfw: Color = Color(0xFFD32F2F),
    val spoiler: Color = Color(0xFF616161),
    val op: Color = Color(0xFF2196F3),
    val mod: Color = Color(0xFF4CAF50),
    val stickied: Color = Color(0xFF388E3C),
    val readTitle: Color = Color.Gray,
    /** RIF link-blue titles (purple once visited) and light-blue header bar. */
    val title: Color = Color(0xFF1A1AE6),
    val titleRead: Color = Color(0xFF6A3DA8),
    val appBar: Color = Color(0xFFCEE3F8),
    val onAppBar: Color = Color(0xFF1A1A1A),
    val flairBackground: Color = Color(0xFFE4E4E4),
    val voteArrow: Color = Color(0xFFB0B0B0),
    val subtle: Color = Color.Gray,
    val actionBar: Color = Color(0x14000000),
    val selectedRow: Color = Color(0x0F000000),
    val depth: List<Color> = AppSettings.DEFAULT_DEPTH_COLORS.map { Color(it) },
) {
    fun depthColor(level: Int): Color = if (depth.isEmpty()) subtle else depth[level % depth.size]
}

/** Spacing that the Compact Mode preference tightens to RIF's maximum density. */
@Immutable
data class RelishSpacing(
    val rowVertical: Dp = 6.dp,
    val rowHorizontal: Dp = 8.dp,
    val thumbnail: Dp = 64.dp,
    val actionHeight: Dp = 40.dp,
    val commentVertical: Dp = 5.dp,
    val depthIndent: Dp = 8.dp,
)

val LocalRelishColors = staticCompositionLocalOf { RelishColors() }
val LocalSpacing = staticCompositionLocalOf { RelishSpacing() }

object Relish {
    val colors: RelishColors @Composable get() = LocalRelishColors.current
    val spacing: RelishSpacing @Composable get() = LocalSpacing.current
}

private val CompactTypography = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontSize = 19.sp, lineHeight = 24.sp),
        titleMedium = t.titleMedium.copy(fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.Bold),
        titleSmall = t.titleSmall.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold),
        bodyLarge = t.bodyLarge.copy(fontSize = 15.sp, lineHeight = 21.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 14.sp, lineHeight = 19.sp),
        bodySmall = t.bodySmall.copy(fontSize = 12.sp, lineHeight = 15.sp),
        labelSmall = t.labelSmall.copy(fontSize = 11.sp, lineHeight = 13.sp, letterSpacing = 0.2.sp),
    )
}

/** Meta text under titles ("37 comments  subreddit"). */
val MetaTextStyle = TextStyle(fontSize = 13.sp, lineHeight = 16.sp)

/** RIF's dense title: ~1.2x line spacing instead of Material's ~1.4x. */
val RifTitleStyle = TextStyle(fontSize = 16.sp, lineHeight = 19.sp)

/** Comment and selftext bodies, RIF density. */
val RifBodyStyle = TextStyle(fontSize = 15.sp, lineHeight = 19.sp)

@Composable
fun RelishTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val mode = settings.theme
    val dark = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.PURE_BLACK -> true
    }
    val pureBlack = mode == ThemeMode.PURE_BLACK || (mode == ThemeMode.SYSTEM && systemDark && settings.lastDarkTheme == ThemeMode.PURE_BLACK)
    val accent = Color(settings.accentColor)
    val scheme = colorScheme(dark, pureBlack, accent)

    val extra = RelishColors(
        readTitle = if (dark) Color(0xFF8A8A8A) else Color(0xFF8C8C8C),
        title = if (dark) Color(0xFF8AB4F8) else Color(0xFF1A1AE6),
        titleRead = if (dark) Color(0xFFB39DDB) else Color(0xFF6A3DA8),
        appBar = when {
            pureBlack -> Color.Black
            dark -> Color(0xFF263238)
            else -> Color(0xFFCEE3F8)
        },
        onAppBar = if (dark) Color(0xFFE6E6E6) else Color(0xFF1A1A1A),
        flairBackground = if (dark) Color(0xFF3A3A3A) else Color(0xFFE4E4E4),
        voteArrow = if (dark) Color(0xFF7A7A7A) else Color(0xFFB0B0B0),
        subtle = if (dark) Color(0xFF9E9E9E) else Color(0xFF6F6F6F),
        actionBar = if (dark) Color(0x22FFFFFF) else Color(0x12000000),
        selectedRow = if (dark) Color(0x14FFFFFF) else Color(0x0A000000),
        depth = settings.depthColors.map { Color(it) },
    )
    val spacing = if (settings.compactMode) {
        RelishSpacing(rowVertical = 4.dp, rowHorizontal = 6.dp, thumbnail = 52.dp, actionHeight = 34.dp, commentVertical = 3.dp, depthIndent = 6.dp)
    } else RelishSpacing()

    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalRelishColors provides extra,
        LocalSpacing provides spacing,
        LocalDensity provides Density(density.density, density.fontScale * settings.fontSize.scale),
    ) {
        MaterialTheme(colorScheme = scheme, typography = CompactTypography, content = content)
    }
}

private fun colorScheme(dark: Boolean, pureBlack: Boolean, accent: Color): ColorScheme = when {
    pureBlack -> darkColorScheme(
        primary = accent, secondary = accent, tertiary = accent,
        background = Color.Black, surface = Color.Black, surfaceVariant = Color(0xFF121212),
        surfaceContainer = Color(0xFF0A0A0A), surfaceContainerHigh = Color(0xFF121212),
        surfaceContainerLow = Color.Black, surfaceContainerLowest = Color.Black, surfaceContainerHighest = Color(0xFF1A1A1A),
        onBackground = Color(0xFFE6E6E6), onSurface = Color(0xFFE6E6E6), outlineVariant = Color(0xFF262626),
    )
    dark -> darkColorScheme(
        primary = accent, secondary = accent, tertiary = accent,
        background = Color(0xFF1B1B1B), surface = Color(0xFF1B1B1B), surfaceVariant = Color(0xFF2A2A2A),
        surfaceContainer = Color(0xFF232323), surfaceContainerHigh = Color(0xFF2A2A2A),
        surfaceContainerLow = Color(0xFF1F1F1F), surfaceContainerLowest = Color(0xFF161616), surfaceContainerHighest = Color(0xFF303030),
        onBackground = Color(0xFFE3E3E3), onSurface = Color(0xFFE3E3E3), outlineVariant = Color(0xFF333333),
    )
    else -> lightColorScheme(
        primary = accent, secondary = accent, tertiary = accent,
        background = Color.White, surface = Color.White, surfaceVariant = Color(0xFFF1F1F1),
        surfaceContainer = Color(0xFFF5F5F5), surfaceContainerHigh = Color(0xFFEFEFEF),
        surfaceContainerLow = Color(0xFFFAFAFA), surfaceContainerLowest = Color.White, surfaceContainerHighest = Color(0xFFE8E8E8),
        onBackground = Color(0xFF1A1A1A), onSurface = Color(0xFF1A1A1A), outlineVariant = Color(0xFFE0E0E0),
    )
}
