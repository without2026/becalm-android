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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ─── Font family ─────────────────────────────────────────────────────────────

/**
 * Pretendard Variable font family — PLACEHOLDER.
 *
 * The actual variable-font `.ttf` is scheduled for commit in R10.  Until then we
 * fall back to [FontFamily.Default] so the type scale below compiles and renders
 * with the system font (NotoSansCJK on One UI, sans-serif elsewhere).  Replacing
 * this constant with the proper [FontFamily] once `res/font/pretendard_variable.ttf`
 * lands is a drop-in swap — no other file needs to change.
 */
internal val PretendardFontFamily: FontFamily = FontFamily.Default

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
