package com.socialdrive.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val KoreanSans = FontFamily.SansSerif

val RouteMemoryTypography = Typography(
    displayLarge = TextStyle(fontFamily = KoreanSans, fontWeight = FontWeight.Bold, fontSize = 60.sp, lineHeight = 66.sp),
    displayMedium = TextStyle(fontFamily = KoreanSans, fontWeight = FontWeight.Bold, fontSize = 46.sp, lineHeight = 52.sp),
    headlineLarge = TextStyle(fontFamily = KoreanSans, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = KoreanSans, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontFamily = KoreanSans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp),
    titleMedium = TextStyle(fontFamily = KoreanSans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 26.sp),
    bodyLarge = TextStyle(fontFamily = KoreanSans, fontWeight = FontWeight.Normal, fontSize = 18.sp, lineHeight = 28.sp),
    bodyMedium = TextStyle(fontFamily = KoreanSans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    labelLarge = TextStyle(fontFamily = KoreanSans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
)
