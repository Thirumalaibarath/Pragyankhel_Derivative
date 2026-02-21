package com.example.highspeedcamera.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.highspeedcamera.R

// ── Local font: lex.ttf ───────────────────────────────────────────────────────

val LexendMegaFamily: FontFamily = FontFamily(
    Font(R.font.lex)
)

// ── Color palette ─────────────────────────────────────────────────────────────

private val AppPurple   = Color(0xFF6931FF)
private val AppOnPurple = Color.White

private val AppColors = darkColorScheme(
    background       = AppPurple,
    surface          = AppPurple,
    onBackground     = AppOnPurple,
    onSurface        = AppOnPurple,
    onSurfaceVariant = Color.White.copy(alpha = 0.65f),
    primary          = Color.White,
    onPrimary        = AppPurple,
    error            = Color(0xFFFF6B6B),
    onError          = Color.White,
)

// ── Typography (Lexend Mega for every text style) ─────────────────────────────

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontFamily = LexendMegaFamily, fontWeight = FontWeight.Bold,   fontSize = 26.sp),
    headlineSmall  = TextStyle(fontFamily = LexendMegaFamily, fontWeight = FontWeight.Bold,   fontSize = 22.sp),
    titleLarge     = TextStyle(fontFamily = LexendMegaFamily, fontWeight = FontWeight.Medium, fontSize = 20.sp),
    titleMedium    = TextStyle(fontFamily = LexendMegaFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp),
    titleSmall     = TextStyle(fontFamily = LexendMegaFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp),
    bodyLarge      = TextStyle(fontFamily = LexendMegaFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyMedium     = TextStyle(fontFamily = LexendMegaFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp),
    bodySmall      = TextStyle(fontFamily = LexendMegaFamily, fontWeight = FontWeight.Normal, fontSize = 11.sp),
    labelLarge     = TextStyle(fontFamily = LexendMegaFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp),
    labelMedium    = TextStyle(fontFamily = LexendMegaFamily, fontWeight = FontWeight.Normal, fontSize = 11.sp),
    labelSmall     = TextStyle(fontFamily = LexendMegaFamily, fontWeight = FontWeight.Normal, fontSize = 10.sp),
)

// ── Theme entry point ─────────────────────────────────────────────────────────

@Composable
fun HighSpeedCameraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColors,
        typography  = AppTypography,
        content     = content
    )
}
