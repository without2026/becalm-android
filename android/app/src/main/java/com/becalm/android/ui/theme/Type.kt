/**
 * Material3 Typography for BeCalm Android.
 *
 * Font chain: Pretendard Variable → SEC CJK (One UI ships NotoSansCJK-KR as
 * system fallback) → sans-serif.  Pretendard is self-hosted (v3 rule P14 — no
 * network font loading).  The `res/font/pretendard_variable.ttf` binary is
 * added in R10; this file only declares the [FontFamily] reference.
 *
 * The scale follows DESIGN.md: light product UI, one Korean-first family,
 * no oversized app-surface hero type, and 0 sp letter spacing by default.
 */
package com.becalm.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.becalm.android.R

// ─── Font family ─────────────────────────────────────────────────────────────

/**
 * Pretendard Variable font family.
 *
 * The same variable-font binary is registered across the weights used by the
 * BeCalm type scale. Android keeps SEC CJK / NotoSansCJK-KR as the system
 * fallback for missing glyphs, but Hangul and Latin both render through the
 * bundled Pretendard resource in normal app surfaces.
 */
internal val PretendardFontFamily: FontFamily = FontFamily(
    Font(R.font.pretendard_variable, weight = FontWeight.Light),
    Font(R.font.pretendard_variable, weight = FontWeight.Normal),
    Font(R.font.pretendard_variable, weight = FontWeight.Medium),
    Font(R.font.pretendard_variable, weight = FontWeight.SemiBold),
)

// ─── Typography scale ─────────────────────────────────────────────────────────

/**
 * BeCalm M3 type scale.  Every slot maps exactly to the size / weight /
 * line-height row in design token spec §4.
 *
 * Line-height is expressed as [lineHeight] in sp, matching the spec values which
 * were defined as px-equivalent on a 1 dp/sp baseline.
 */
internal val BecalmTypography = Typography(

    // ── Display ──────────────────────────────────────────────────────────────
    displayLarge = TextStyle(
        fontFamily = PretendardFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp,
    ),
    displayMedium = TextStyle(
        fontFamily = PretendardFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = PretendardFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp,
    ),

    // ── Headline ─────────────────────────────────────────────────────────────
    headlineLarge = TextStyle(
        fontFamily = PretendardFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = PretendardFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = PretendardFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),

    // ── Title ─────────────────────────────────────────────────────────────────
    titleLarge = TextStyle(
        fontFamily = PretendardFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = PretendardFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = PretendardFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),

    // ── Body ──────────────────────────────────────────────────────────────────
    bodyLarge = TextStyle(
        fontFamily = PretendardFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = PretendardFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = PretendardFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    ),

    // ── Label ─────────────────────────────────────────────────────────────────
    labelLarge = TextStyle(
        fontFamily = PretendardFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = PretendardFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = PretendardFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    ),
)
