package com.usbforge.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Minimalist koyu tema.
 *
 * Tek bir vurgu rengi (teal) kullanılır; durum renkleri yalnızca hata ve
 * uyarıda görünür. Disk yazma uygulamalarında okunabilirlik ve yanlış
 * cihaza yazma riskini azaltmak için yüksek kontrast tercih edilmiştir.
 */
object ForgeColors {
    val Background = Color(0xFF0B0F14)
    val Surface = Color(0xFF121821)
    val SurfaceHigh = Color(0xFF1A2230)
    val Outline = Color(0xFF243040)
    val Accent = Color(0xFF19D3AE)
    val AccentDim = Color(0xFF0E7C68)
    val TextPrimary = Color(0xFFE6EDF5)
    val TextSecondary = Color(0xFF8A9AAF)
    val Danger = Color(0xFFFF5C6C)
    val Warning = Color(0xFFFFC24B)
    val Success = Color(0xFF4ADE80)
    val Slot = Color(0xFF0F141C)
}

private val ForgeDarkScheme = darkColorScheme(
    primary = ForgeColors.Accent,
    onPrimary = Color(0xFF00201A),
    primaryContainer = ForgeColors.AccentDim,
    onPrimaryContainer = Color(0xFFD5FFF5),
    secondary = ForgeColors.TextSecondary,
    onSecondary = ForgeColors.Background,
    background = ForgeColors.Background,
    onBackground = ForgeColors.TextPrimary,
    surface = ForgeColors.Surface,
    onSurface = ForgeColors.TextPrimary,
    surfaceVariant = ForgeColors.SurfaceHigh,
    onSurfaceVariant = ForgeColors.TextSecondary,
    outline = ForgeColors.Outline,
    error = ForgeColors.Danger,
    onError = Color(0xFF2A0004),
)

/**
 * Monospace odaklı tipografi: LBA, boyut ve hız değerleri hizalı görünsün.
 * [Typography] içindeki stiller varsayılan olarak [FontFamily.Monospace]
 * tabanlıdır; başlıklar hariç her şey sabit genişliklidir.
 */
private val ForgeTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 14.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 12.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        letterSpacing = 0.4.sp,
    ),
)

/** Sabit genişlikli sayı stili (ilerleme metinleri için). */
val MonoSmall = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    letterSpacing = 0.2.sp,
)

val MonoMedium = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 15.sp,
    fontWeight = FontWeight.Medium,
)

@Composable
fun UsbForgeTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }
    // Uygulama yalnızca koyu temada sunulur; sistem açık temayı yok sayılır.
    MaterialTheme(
        colorScheme = ForgeDarkScheme,
        typography = ForgeTypography,
        content = content,
    )
}

/** Kart köşe yarıçapı ve kenar boşlukları için ortak değerler. */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
}
