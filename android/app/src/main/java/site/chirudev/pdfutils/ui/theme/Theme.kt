package site.chirudev.pdfutils.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import site.chirudev.pdfutils.tools.Category

// Palette ported from frontend/src/index.css so the app and the website look like one product.
object Brand {
    val Red = Color(0xFFE5484D)
    val RedStrong = Color(0xFFCD2B31)
    val Organize = Color(0xFFE5484D)
    val Optimize = Color(0xFF30A46C)
    val Convert = Color(0xFFF5A524)
    val Edit = Color(0xFF8E4EC6)
    val Security = Color(0xFF0090FF)
}

private val Light = lightColorScheme(
    primary = Brand.Red,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFEEBEC),
    onPrimaryContainer = Brand.RedStrong,
    secondary = Brand.RedStrong,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFEEBEC),
    onSecondaryContainer = Color(0xFF17171C),
    tertiary = Brand.Security,
    background = Color(0xFFF7F7F9),
    onBackground = Color(0xFF17171C),
    surface = Color(0xFFF7F7F9),
    onSurface = Color(0xFF17171C),
    surfaceVariant = Color(0xFFF1F1F4),
    onSurfaceVariant = Color(0xFF63636E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF1F1F4),
    surfaceContainerHighest = Color(0xFFE9E9EE),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE3E3E8),
    outline = Color(0xFFC9C9D1),
    outlineVariant = Color(0xFFE3E3E8),
    error = Color(0xFFCD2B31),
    onError = Color.White,
    errorContainer = Color(0xFFFFEFEF),
    onErrorContainer = Color(0xFFCD2B31),
    inverseSurface = Color(0xFF222227),
    inverseOnSurface = Color(0xFFEDEDF0),
    inversePrimary = Color(0xFFFF9592),
    scrim = Color.Black,
)

private val Dark = darkColorScheme(
    primary = Brand.Red,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3B1219),
    onPrimaryContainer = Color(0xFFFF9592),
    secondary = Color(0xFFFF9592),
    onSecondary = Color(0xFF111114),
    secondaryContainer = Color(0xFF3B1219),
    onSecondaryContainer = Color(0xFFEDEDF0),
    tertiary = Brand.Security,
    background = Color(0xFF111114),
    onBackground = Color(0xFFEDEDF0),
    surface = Color(0xFF111114),
    onSurface = Color(0xFFEDEDF0),
    surfaceVariant = Color(0xFF222227),
    onSurfaceVariant = Color(0xFFA0A0AB),
    surfaceContainerLowest = Color(0xFF0C0C0F),
    surfaceContainerLow = Color(0xFF19191D),
    surfaceContainer = Color(0xFF19191D),
    surfaceContainerHigh = Color(0xFF222227),
    surfaceContainerHighest = Color(0xFF2A2A30),
    surfaceBright = Color(0xFF2A2A30),
    surfaceDim = Color(0xFF111114),
    outline = Color(0xFF45454D),
    outlineVariant = Color(0xFF2E2E35),
    error = Color(0xFFFF9592),
    onError = Color(0xFF111114),
    errorContainer = Color(0xFF3B1219),
    onErrorContainer = Color(0xFFFF9592),
    inverseSurface = Color(0xFFEDEDF0),
    inverseOnSurface = Color(0xFF17171C),
    inversePrimary = Brand.RedStrong,
    scrim = Color.Black,
)

/** Colors the Material scheme has no slot for: status tones and category accents. */
@Immutable
data class ExtendedColors(
    val ok: Color,
    val okSoft: Color,
    val warn: Color,
    val warnSoft: Color,
    val brandSoft: Color,
)

private val LightExtended = ExtendedColors(ok = Color(0xFF18794E), okSoft = Color(0xFFE9F9EE), warn = Color(0xFFAD5700), warnSoft = Color(0xFFFFF4D5), brandSoft = Color(0xFFFEEBEC))
private val DarkExtended = ExtendedColors(ok = Color(0xFF3DD68C), okSoft = Color(0xFF132D21), warn = Color(0xFFFFCA16), warnSoft = Color(0xFF302008), brandSoft = Color(0xFF3B1219))

val LocalExtendedColors = staticCompositionLocalOf { LightExtended }

fun Category.color(): Color = when (this) {
    Category.Organize -> Brand.Organize
    Category.Optimize -> Brand.Optimize
    Category.Convert -> Brand.Convert
    Category.Edit -> Brand.Edit
    Category.Security -> Brand.Security
}

private val base = Typography()

// System font (Roboto / Google Sans on most devices) with the web app's heavier headline weights.
private val AppTypography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.25).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge,
    bodyMedium = base.bodyMedium,
    bodySmall = base.bodySmall,
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun PdfUtilsTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    // Dynamic (wallpaper) color is intentionally off: the brand red is the product's identity.
    val scheme: ColorScheme = if (dark) Dark else Light
    CompositionLocalProvider(LocalExtendedColors provides if (dark) DarkExtended else LightExtended) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}

object AppTheme {
    val extended: ExtendedColors
        @Composable get() = LocalExtendedColors.current
}

val MonoDigits = TextStyle(fontFeatureSettings = "tnum")
