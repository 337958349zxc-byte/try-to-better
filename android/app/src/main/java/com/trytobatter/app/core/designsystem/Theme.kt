package com.trytobatter.app.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object BatterColors {
    val Background = Color(0xFFF6F8FC)
    val Surface = Color.White
    val Text = Color(0xFF1D2B44)
    val Secondary = Color(0xFF647284)
    val Primary = Color(0xFF3569EC)
    val Success = Color(0xFF137B5C)
    val Warning = Color(0xFF956017)
    val Error = Color(0xFFC33C46)
    val Border = Color(0xFFE3E8F0)
    val Training = Color(0xFF101722)
    val LightBlue = Color(0xFFEBF0FE)
    val PrimaryDeep = Color(0xFF2354D3)
    val LearningTint = Color(0xFFEEF1FF)
    val LanguageTint = Color(0xFFF5EFFF)
    val FoodTint = Color(0xFFFFF3E6)
    val ExerciseTint = Color(0xFFEAF8F1)
    val LifeTint = Color(0xFFEFF3F8)
}

object Space {
    val Page = 16.dp
    val Card = 14.dp
    val Small = 8.dp
    val Section = 16.dp
    val Touch = 48.dp
    val Gap = 8.dp
    val RowGap = 4.dp
    val Radius = 16.dp
    val HeroRadius = 22.dp
    val TaskMin = 64.dp
}

private val LightColors = lightColorScheme(
    primary = BatterColors.Primary, onPrimary = Color.White,
    primaryContainer = BatterColors.LightBlue, onPrimaryContainer = BatterColors.PrimaryDeep,
    secondary = Color(0xFF4D6588), onSecondary = Color.White,
    secondaryContainer = BatterColors.LightBlue, onSecondaryContainer = BatterColors.Primary,
    background = BatterColors.Background, onBackground = BatterColors.Text,
    surface = Color.White, onSurface = BatterColors.Text,
    surfaceVariant = Color(0xFFEDF1F7), onSurfaceVariant = BatterColors.Secondary,
    outline = Color(0xFF748094), outlineVariant = BatterColors.Border,
    error = BatterColors.Error, onError = Color.White,
)
private val TrainingColors = darkColorScheme(
    primary = Color(0xFF9EB8FF), onPrimary = Color(0xFF102856),
    background = BatterColors.Training, surface = Color(0xFF182433),
    onBackground = Color.White, onSurface = Color.White,
    surfaceVariant = Color(0xFF243448), onSurfaceVariant = Color(0xFFBCC9DB),
    outlineVariant = Color(0xFF33445B), error = Color(0xFFFFB4AB),
)
private val BatterTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 34.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 23.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 22.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp),
)

@Composable fun BatterTheme(training: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (training) TrainingColors else LightColors, typography = BatterTypography,
        shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(Space.Radius), large = RoundedCornerShape(Space.HeroRadius)), content = content)
}
