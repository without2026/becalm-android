/**
 * Material3 Typography for BeCalm Android.
 *
 * Font chain: Pretendard Variable → SEC CJK (One UI ships NotoSansCJK-KR as
 * system fallback) → sans-serif.  Pretendard is self-hosted (v3 rule P14 — no
 * network font loading).  The `res/font/pretendard_variable.ttf` binary is
 * added in R10; this file only declares the [FontFamily] reference.
 *
 * Every slot follows the size / weight / line-height table in design token spec
 * §4.  Hangul minimum readable size is 12 sp on OLED; [labelSmall] (11 sp) must
 * not be used for critical text — see spec note.
 */
package com.becalm.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.becalm.android.R

// ─── Font family ─────────────────────────────────────────────────────────────

/**
 * Pretendard Variable font family.
 *
 * The variable font supports the weight axis (wght 100–900).  Each [Font] entry
 * pins a [FontVariation.weight] so the system can pick the correct optical weight
 * when the slot is requested.
 *
 * NOTE: `R.font.pretendard_variable` will be an unresolved reference until the
 * `.ttf` file is committed in R10.  The reference is declared here now so all
 * other files can compile once R10 lands without further changes.
 */
internal val PretendardFontFamily = FontFamily(
    Font(
        resId = R.font.pretendard_variable,
        weight = FontWeight.Light,
        variationSettings = FontVariation.Settings(FontVariation.weight(300)),
    ),
    Font(
        resId = R.font.pretendard_variable,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        resId = R.font.pretendard_variable,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    Font(
        resId = R.font.pretendard_variable,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600)),
    ),
    Font(
        resId = R.font.pretendard_variable,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    ),
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
    // Not used for Korean body text — display / hero surfaces only.
    displayLarge = TextStyle(
        fontFamily = PretendardFontFamily,
        fontWeight = FontWeight.Light,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = PretendardFontFamily,
        fontWeight = FontWeight.Light,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = PretendardFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp,
    ),

    // ── Headline ─────────────────────────────────────────────────────────────
    headlineLarge = TextStyle(
        fontFamily = PretendardFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = PretendardFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = PretendardFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp,
    ),

    // ── Title ─────────────────────────────────────────────────────────────────
    titleLarge = TextStyle(
        fontFamily = PretendardFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = PretendardFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = PretendardFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),

    // ── Body ──────────────────────────────────────────────────────────────────
    // bodyLarge: used for quote full-text (CMT-003) — needs adequate Hangul line-height.
    bodyLarge = TextStyle(
        fontFamily = PretendardFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = PretendardFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    // bodySmall: timestamps, metadata (HH:mm, D-N badge). Verify Hangul legibility
    // at 12 sp on Galaxy S24 before release.
    bodySmall = TextStyle(
        fontFamily = PretendardFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),

    // ── Label ─────────────────────────────────────────────────────────────────
    labelLarge = TextStyle(
        fontFamily = PretendardFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = PretendardFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    // labelSmall (11 sp): do NOT use for critical information.
    // Hangul minimum readable: 12 sp on OLED.  Error messages must use bodySmall minimum.
    labelSmall = TextStyle(
        fontFamily = PretendardFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)
