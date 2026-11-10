package com.campusmarket.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import campusmarket.shared.generated.resources.Res
import campusmarket.shared.generated.resources.plus_jakarta_sans_bold
import campusmarket.shared.generated.resources.plus_jakarta_sans_extrabold
import campusmarket.shared.generated.resources.plus_jakarta_sans_medium
import campusmarket.shared.generated.resources.plus_jakarta_sans_semibold
import org.jetbrains.compose.resources.Font
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/*
 * "Golden Hour": Quad's identity. The quad at sunset, when everyone is outside.
 *
 * One warm gradient (amber #FFC35A → dusk pink #FF6B81 → berry #D6305C / deep berry #B02A78) is reserved for the
 * brand moments: the app icon and Welcome hero ([CampusTheme.brandGradient]) and the primary call to action,
 * selected filter chips and the selected bottom-bar tab ([CampusTheme.actionGradient], white text on top).
 * Everything else stays quiet so listing photos carry the screen: warm white #FFF8F3 with white cards in light mode,
 * "after dark" #1D0F18 with plum cards in dark mode. Type is Plus Jakarta Sans (800 for display / headlines /
 * prices, 500–600 for body and labels); shapes are generous (18 dp cards, pill buttons and chips, 28 dp sheets).
 *
 * Use MaterialTheme roles everywhere; the extra semantic colours (success / warning / neutral, stars, prices) live in
 * [CampusColors] via `CampusTheme.colors`. Never hard-code colours in features.
 *
 * Contrast (WCAG AA, checked for every on-colour pair): body text on every surface ≥ 4.5:1; berry (#D6305C) text is
 * 4.50:1 on the warm-white background and 4.73:1 on white, which is why the light container roles that can carry
 * primary-coloured text (dialogs, sheets, menus, bars, cards) are white rather than tinted. `surfaceContainerHighest`
 * is the only tinted light container (incoming chat bubbles, placeholders): don't put primary text on it.
 * Dark primary #FF9166 carries after-dark text (8.4:1). White on the action gradient is ≥ 4.5:1 from 25% of its width
 * onwards (labels are centred); on the brand gradient only large/bold text is legible, and only on its berry half.
 *
 * Fonts: Plus Jakarta Sans (SIL OFL 1.1, licence in composeResources/files/licenses/PlusJakartaSans-OFL.txt), static
 * instances of the upstream variable font from google/fonts, so every target (incl. Android API 24/25) gets real weights.
 */

// ---------------------------------------------------------------------------------------------------- palette

/** The kit's named swatches. Prefer the scheme roles; these are for gradients and the brand mark. */
object GoldenHour {
    val Amber = Color(0xFFFFC35A)
    val DuskPink = Color(0xFFFF6B81)
    val Berry = Color(0xFFD6305C)
    val DeepBerry = Color(0xFFB02A78)
    val Coral = Color(0xFFF0603A)
    val WarmWhite = Color(0xFFFFF8F3)
    val AfterDark = Color(0xFF1D0F18)
    val Ink = Color(0xFF2A1320)
}

private val LightColors = lightColorScheme(
    primary = GoldenHour.Berry,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDDE3),
    onPrimaryContainer = Color(0xFF6B0F30),
    // Deep berry: favourite hearts, unread badges.
    secondary = GoldenHour.DeepBerry,
    onSecondary = Color.White,
    // "Selected" fill (filter / input chips, tonal buttons): berry with white text, the solid stand-in for the
    // action gradient wherever Material draws the container itself.
    secondaryContainer = GoldenHour.Berry,
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFF8F4F00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE4B5),
    onTertiaryContainer = Color(0xFF4A2C00),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF7A0910),
    background = GoldenHour.WarmWhite,
    onBackground = GoldenHour.Ink,
    surface = GoldenHour.WarmWhite,
    onSurface = GoldenHour.Ink,
    surfaceVariant = Color(0xFFF5E4DF),
    onSurfaceVariant = Color(0xFF7A5A66),
    outline = Color(0xFF96777F),
    outlineVariant = Color(0xFFEDDAD5),
    scrim = Color.Black,
    inverseSurface = Color(0xFF3B2431),
    inverseOnSurface = Color(0xFFFFEEF0),
    inversePrimary = Color(0xFFFF9166),
    // Tonal elevation lifts towards white (raised = white, like the cards) instead of tinting surfaces pink.
    surfaceTint = Color.White,
    surfaceBright = GoldenHour.WarmWhite,
    surfaceDim = Color(0xFFF2E2DD),
    // Raised things are white on warm white (the kit's cards), not darker.
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFF5E4DF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF9166),
    onPrimary = GoldenHour.AfterDark,
    primaryContainer = Color(0xFF5C1D37),
    onPrimaryContainer = Color(0xFFFFD9DF),
    secondary = Color(0xFFFF8FA8),
    onSecondary = Color(0xFF3D0A24),
    secondaryContainer = Color(0xFFC42A60),
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFFFFB347),
    onTertiary = Color(0xFF2E1A00),
    tertiaryContainer = Color(0xFF5C3B00),
    onTertiaryContainer = Color(0xFFFFDDB0),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = GoldenHour.AfterDark,
    onBackground = Color(0xFFFFEEF0),
    surface = GoldenHour.AfterDark,
    onSurface = Color(0xFFFFEEF0),
    surfaceVariant = Color(0xFF46293B),
    onSurfaceVariant = Color(0xFFC49AA8),
    outline = Color(0xFF9C7B89),
    outlineVariant = Color(0xFF4A3042),
    scrim = Color.Black,
    inverseSurface = Color(0xFFFFEEF0),
    inverseOnSurface = Color(0xFF3B2431),
    inversePrimary = Color(0xFFC42752),
    // Tonal elevation lifts towards a pale plum (like the container ladder), not towards the orange primary.
    surfaceTint = Color(0xFFFFD9E4),
    surfaceBright = Color(0xFF45293A),
    surfaceDim = GoldenHour.AfterDark,
    surfaceContainerLowest = Color(0xFF160A12),
    surfaceContainerLow = Color(0xFF2E1826),
    surfaceContainer = Color(0xFF321B2A),
    surfaceContainerHigh = Color(0xFF3B2233),
    surfaceContainerHighest = Color(0xFF46293B),
)

/** Semantic colours Material doesn't have roles for. */
@Immutable
data class CampusColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val neutralContainer: Color,
    val onNeutralContainer: Color,
    /** Filled rating stars. */
    val star: Color,
    /** Price text: berry on light, amber on dark. */
    val price: Color,
)

private val LightCampusColors = CampusColors(
    success = Color(0xFF137A4C),
    onSuccess = Color.White,
    successContainer = Color(0xFFDAF2E3),
    onSuccessContainer = Color(0xFF0B4A2E),
    warningContainer = Color(0xFFFFE4B0),
    onWarningContainer = Color(0xFF5A3800),
    neutralContainer = Color(0xFFF3E5E1),
    onNeutralContainer = Color(0xFF5E4651),
    star = Color(0xFFF5A000),
    price = GoldenHour.Berry,
)

private val DarkCampusColors = CampusColors(
    success = Color(0xFF6FD6A0),
    onSuccess = Color(0xFF00391F),
    successContainer = Color(0xFF173B2A),
    onSuccessContainer = Color(0xFFA8F0C8),
    warningContainer = Color(0xFF4D3300),
    onWarningContainer = Color(0xFFFFD99A),
    neutralContainer = Color(0xFF3B2333),
    onNeutralContainer = Color(0xFFE8CBD5),
    star = Color(0xFFFFB347),
    price = Color(0xFFFFB347),
)

private val LocalCampusColors = staticCompositionLocalOf { LightCampusColors }

// ---------------------------------------------------------------------------------------------------- gradients

/**
 * A CSS-style `linear-gradient(<angle>deg, …)`: the gradient line runs through the centre at [angleDegrees]
 * (0 = upwards, 90 = to the right) and is just long enough to cover the corners, so the look doesn't depend on the
 * shape's aspect ratio the way corner-to-corner gradients do.
 */
@Immutable
private class AngleGradient(private val angleDegrees: Float, private val stops: List<Pair<Float, Color>>) : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val rad = angleDegrees * PI / 180.0
        val dx = sin(rad).toFloat()
        val dy = -cos(rad).toFloat()
        val half = (abs(size.width * dx) + abs(size.height * dy)) / 2f
        val cx = size.width / 2f
        val cy = size.height / 2f
        return LinearGradientShader(
            from = Offset(cx - dx * half, cy - dy * half),
            to = Offset(cx + dx * half, cy + dy * half),
            colors = stops.map { it.second },
            colorStops = stops.map { it.first },
        )
    }

    override fun equals(other: Any?) = other is AngleGradient && other.angleDegrees == angleDegrees && other.stops == stops
    override fun hashCode() = 31 * angleDegrees.hashCode() + stops.hashCode()
}

/** Icon / hero: amber → dusk pink → deep berry. Only large white type (the wordmark) goes on it. */
private val BrandGradient: Brush = AngleGradient(
    135f,
    listOf(0f to GoldenHour.Amber, 0.55f to GoldenHour.DuskPink, 1f to GoldenHour.DeepBerry),
)

/**
 * Welcome hero: the same sunset, steeper (160°) and passing through berry, so the bottom of the hero (where the
 * wordmark and tagline sit) is uniformly berry → deep berry behind white type, and the top under the status bar is
 * amber (dark status-bar icons).
 */
private val HeroGradient: Brush = AngleGradient(
    160f,
    listOf(0f to GoldenHour.Amber, 0.45f to GoldenHour.DuskPink, 0.75f to GoldenHour.Berry, 1f to GoldenHour.DeepBerry),
)

/**
 * Buttons / selected chips / selected tab: the deeper coral → berry → deep berry. Berry arrives early (30%) so a
 * centred white label sits on ≥ 4.5:1.
 */
private val ActionGradient: Brush = AngleGradient(
    120f,
    listOf(0f to GoldenHour.Coral, 0.3f to GoldenHour.Berry, 1f to GoldenHour.DeepBerry),
)

// ---------------------------------------------------------------------------------------------------- type

/** Plus Jakarta Sans, bundled as Compose resources. */
@Composable
fun plusJakartaSans(): FontFamily = FontFamily(
    Font(Res.font.plus_jakarta_sans_medium, FontWeight.Medium),
    Font(Res.font.plus_jakarta_sans_semibold, FontWeight.SemiBold),
    Font(Res.font.plus_jakarta_sans_bold, FontWeight.Bold),
    Font(Res.font.plus_jakarta_sans_extrabold, FontWeight.ExtraBold),
)

private fun quadTypography(family: FontFamily): Typography {
    fun style(size: Int, line: Int, weight: FontWeight, tracking: Double) = TextStyle(
        fontFamily = family,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = line.sp,
        letterSpacing = tracking.em,
    )
    return Typography(
        displayLarge = style(57, 64, FontWeight.ExtraBold, -0.04),
        displayMedium = style(45, 52, FontWeight.ExtraBold, -0.04),
        displaySmall = style(36, 44, FontWeight.ExtraBold, -0.04),
        headlineLarge = style(32, 40, FontWeight.ExtraBold, -0.035),
        headlineMedium = style(28, 36, FontWeight.ExtraBold, -0.03),
        headlineSmall = style(24, 32, FontWeight.ExtraBold, -0.025),
        titleLarge = style(22, 28, FontWeight.ExtraBold, -0.02),
        titleMedium = style(16, 24, FontWeight.Bold, -0.005),
        titleSmall = style(14, 20, FontWeight.Bold, 0.0),
        bodyLarge = style(16, 24, FontWeight.Medium, 0.0),
        bodyMedium = style(14, 20, FontWeight.Medium, 0.005),
        bodySmall = style(12, 16, FontWeight.Medium, 0.01),
        labelLarge = style(14, 20, FontWeight.SemiBold, 0.005),
        labelMedium = style(12, 16, FontWeight.SemiBold, 0.01),
        labelSmall = style(11, 16, FontWeight.SemiBold, 0.015),
    )
}

// ---------------------------------------------------------------------------------------------------- shape

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp), // text fields, menus, snackbars
    small = RoundedCornerShape(14.dp),      // thumbnails, banners, Material chips (≈ pill at 32 dp)
    medium = RoundedCornerShape(18.dp),     // cards and listing tiles
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp), // dialogs, bottom sheets (top corners)
)

/** Spacing scale. Use these instead of ad-hoc dp values so screens line up. */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** Horizontal padding for screen content. */
    val screen = 16.dp
}

object CampusTheme {
    val colors: CampusColors @Composable get() = LocalCampusColors.current

    /** Big bold price style used on detail screens. */
    val priceLarge: TextStyle @Composable get() = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold)

    /** App icon / Welcome hero gradient (135°, amber → dusk pink → deep berry). Brand moments only. */
    val brandGradient: Brush get() = BrandGradient

    /** The Welcome hero's sunset (160°, amber → dusk pink → berry → deep berry). Status-bar icons over it are dark. */
    val heroGradient: Brush get() = HeroGradient

    /** Primary buttons, selected chips and the selected tab (120°, coral → berry → deep berry). White content. */
    val actionGradient: Brush get() = ActionGradient

    /** Buttons and chips are pills. */
    val pill: Shape get() = CircleShape

    /** The lowercase `quad` wordmark style (Plus Jakarta Sans 800, tight tracking). Set the size with `fontSize`. */
    val wordmark: TextStyle @Composable get() = MaterialTheme.typography.displayLarge.copy(lineHeight = 1.0.em)
}

@Composable
fun CampusMarketTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme: ColorScheme = if (darkTheme) DarkColors else LightColors
    val family = plusJakartaSans()
    val typography = remember(family) { quadTypography(family) }
    CompositionLocalProvider(LocalCampusColors provides if (darkTheme) DarkCampusColors else LightCampusColors) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = AppShapes, content = content)
    }
}
