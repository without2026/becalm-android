# BeCalm Android — Design Token Specification

> BeCalm Android는 v3 desktop의 cosmic glassmorphism을 모바일로 이식한다. 검은 우주 위에 떠 있는 반투명 유리 패널, 희미한 성운 빛, 흰색 중심의 단색 타이포그래피. 색은 상태 구분용으로만 — 브랜드의 목소리는 고요함이다.

Source of truth: `BeCalmv3/desktop/src/renderer/styles/global.css`
Extracted: 2026-04-16

---

## 1. Material3 ColorScheme — Dark Scheme (canonical brand expression)

v3 uses white as the sole accent color. `primary` is therefore near-white at high opacity — a deliberate monochrome inheritance. There are no hue-based brand colors.

### Dark scheme

| M3 slot | Hex / Color | Rationale |
|---|---|---|
| `primary` | `#EAEAEA` (α=0.92) → `0xEAEAEAEA` | v3 `--accent` `rgba(255,255,255,0.85)` mapped to M3 primary. Off-white preserves cosmic tone without pure-white harshness on OLED. |
| `onPrimary` | `#111111` | Dark text on white primary button (matches v3 `.primary-button { color: #111111 }`). |
| `primaryContainer` | `#1A1A1A` | Tinted container — 1% step above background. |
| `onPrimaryContainer` | `#D9D9D9` | Readable on dark container. |
| `secondary` | `#B3B3B3` (α=0.70) → `0xB2B2B2B2` | v3 `--muted` `rgba(255,255,255,0.70)` — secondary interactive elements. |
| `onSecondary` | `#111111` | |
| `secondaryContainer` | `#1C1C1C` | |
| `onSecondaryContainer` | `#C2C2C2` | |
| `tertiary` | `#F5AD0B` | v3 amber `rgba(245,158,11,1)` — the only sanctioned hue. Used for D-0/warning states only. |
| `onTertiary` | `#111111` | |
| `tertiaryContainer` | `#1C1600` | Very dark amber tint. |
| `onTertiaryContainer` | `#FFD282` | Honey-gold `rgba(255,210,130,0.95)` — v3 `.permission-banner-title`. |
| `error` | `#FF6464` | v3 `--danger` `rgba(255,100,100,0.8)` rounded to full alpha for M3 slot. |
| `onError` | `#111111` | |
| `errorContainer` | `#3D0000` | |
| `onErrorContainer` | `#FF9090` | |
| `background` | `#111111` | v3 `--bg`. True near-black. Do NOT drift to `#1a1a1a`. |
| `onBackground` | `#EAEAEA` | v3 `--text` `rgba(255,255,255,0.92)`. |
| `surface` | `#111111` | Same as background — flat cosmic field. |
| `onSurface` | `#EAEAEA` | |
| `surfaceVariant` | `#1A1A1A` | Slightly elevated input / chip background. |
| `onSurfaceVariant` | `#B3B3B3` | v3 `--muted`. |
| `outline` | `#3D3D3D` | Derived from v3 `--border` `rgba(255,255,255,0.08)` on `#111` → approximately `#1E1E1E`; raised slightly for One UI touch-target visibility. |
| `outlineVariant` | `#1E1E1E` | v3 `--border` exact composite. |
| `inverseSurface` | `#E5E5E5` | |
| `inverseOnSurface` | `#111111` | |
| `inversePrimary` | `#333333` | |
| `scrim` | `#000000` | Full black for modal scrims. |
| `surfaceTint` | `Color.Transparent` | **Critical**: disables M3 tonal elevation tinting, which would corrupt the cosmic black aesthetic. Must be set explicitly. |

### Light scheme (daytime BeCalm — same product, brighter atmosphere)

Light is provided for accessibility/user preference. The brand expression is dark; light is a supported alternative, not the default.

| M3 slot | Hex | Note |
|---|---|---|
| `primary` | `#1A1A1A` | Inverted — near-black on white |
| `onPrimary` | `#F5F5F5` | |
| `primaryContainer` | `#E8E8E8` | |
| `onPrimaryContainer` | `#1A1A1A` | |
| `secondary` | `#555555` | |
| `onSecondary` | `#F5F5F5` | |
| `secondaryContainer` | `#EBEBEB` | |
| `onSecondaryContainer` | `#333333` | |
| `tertiary` | `#C47D00` | Amber darkened for light bg contrast |
| `onTertiary` | `#FFFFFF` | |
| `tertiaryContainer` | `#FDEFC8` | |
| `onTertiaryContainer` | `#3D2700` | |
| `error` | `#C62828` | |
| `onError` | `#FFFFFF` | |
| `errorContainer` | `#FDECEA` | |
| `onErrorContainer` | `#7F0000` | |
| `background` | `#F5F5F5` | Warm off-white — not pure white |
| `onBackground` | `#1A1A1A` | |
| `surface` | `#F5F5F5` | |
| `onSurface` | `#1A1A1A` | |
| `surfaceVariant` | `#E8E8E8` | |
| `onSurfaceVariant` | `#555555` | |
| `outline` | `#AFAFAF` | |
| `outlineVariant` | `#DDDDDD` | |
| `inverseSurface` | `#2A2A2A` | |
| `inverseOnSurface` | `#F5F5F5` | |
| `inversePrimary` | `#C8C8C8` | |
| `scrim` | `#000000` | |
| `surfaceTint` | `Color.Transparent` | Same as dark — no tonal elevation. |

---

## 2. BecalmColors — Semantic Extension

Access via `MaterialTheme.becalmColors`. Defined as a `CompositionLocal<BecalmColors>`.

### Glass surface tokens

v3 glass is built from two layers: translucent fill + hairline border + outer shadow + inset highlight. These four primitives compose every glass surface in the app.

| Token | Compose Color | Source |
|---|---|---|
| `glassPanel` | `Color(0x0AFFFFFF)` | v3 `--panel` `rgba(255,255,255,0.04)` |
| `glassPanelHighlight` | `Color(0x0DFFFFFF)` | v3 `--panel-highlight` `rgba(255,255,255,0.05)` |
| `glassBorder` | `Color(0x14FFFFFF)` | v3 `--border` `rgba(255,255,255,0.08)` |
| `glassInset` | `Color(0x0AFFFFFF)` | Inset highlight from `--glass-shadow` `rgba(255,255,255,0.04)` |
| `glassOuterShadow` | `Color(0x4D000000)` | Outer shadow `rgba(0,0,0,0.30)` |

### Nebula / ambient tokens

Used in `Brush.radialGradient()` to reproduce v3's `body::before`/`::after` glows. Radial gradient, not `filter:blur` — matches v3 P11 performance note.

| Token | Compose Color | Use |
|---|---|---|
| `cosmicBackground` | `Color(0xFF111111)` | Solid canvas — always pass this as `containerColor` on the root scaffold |
| `ambientGlowCore` | `Color(0x59505050)` | Center of primary glow `rgba(80,80,80,0.35)` |
| `ambientGlowEdge` | `Color.Transparent` | Outer stop at 70% radius |
| `ambientGlowCool` | `Color(0x4D3C3C3C)` | v3 `body::after` `rgba(60,60,60,0.30)` — secondary screen corners |
| `ambientGlowWarm` | `Color(0x33503818)` | Subtle warm amber fog — used behind commitment-heavy screens |
| `ambientGlowStrong` | `Color(0x66505050)` | Stronger center for full-screen modals (ColdSyncScreen) |

### Commitment action states

Monochrome-first. State is communicated through alpha-shifted white, not hue shifts. Four states form a visual hierarchy from neutral (pending) to resolved (completed).

Each state defines a triple: `border` (low alpha), `fill` (very low alpha), `text` (near-opaque).

**Dark scheme:**

| State | Border | Fill | Text |
|---|---|---|---|
| `pending` | `Color(0x40FFFFFF)` (α=0.25) | `Color(0x1AFFFFFF)` (α=0.10) | `Color(0xEAEAEAEA)` (α=0.92) |
| `reminded` | `Color(0x59F5AD0B)` (α=0.35) | `Color(0x1AF5AD0B)` (α=0.10) | `Color(0xF2FFD282)` (α=0.95) honey-gold |
| `followed_up` | `Color(0x40FFFFFF)` (α=0.25) | `Color(0x26FFFFFF)` (α=0.15) | `Color(0xD9D9D9D9)` (α=0.85) |
| `completed` | `Color(0x1AFFFFFF)` (α=0.10) | `Color(0x0DFFFFFF)` (α=0.05) | `Color(0x99B2B2B2)` (α=0.60) dimmed |

**Light scheme:** same hues, invert alpha direction — borders `rgba(0,0,0,0.12)`, fills `rgba(0,0,0,0.04)`, text `rgba(0,0,0,0.87)`.

### Direction tokens (give / take)

Give commitments carry a faint amber cast (v3's established attention color). Take commitments carry a faint cool cast. Both are low-chroma — readable as monochrome in low light.

| Token | Dark | Light |
|---|---|---|
| `directionGiveBorder` | `Color(0x40F5C842)` | `Color(0x26C47D00)` |
| `directionGiveFill` | `Color(0x1AF5C842)` | `Color(0x0DC47D00)` |
| `directionTakeBorder` | `Color(0x40A0B4C8)` | `Color(0x267090A8)` |
| `directionTakeFill` | `Color(0x1AA0B4C8)` | `Color(0x0D7090A8)` |

Give = warm amber cast; take = cool slate cast. Both read as neutral at a glance — the cast only becomes apparent on direct comparison.

### D-N badge tokens

D-N distance badges map urgency to color. Only D-0 and overdue use color; the rest use white. Matches v3 amber/red semantic pairing.

| State | Condition | Background fill | Text | Border |
|---|---|---|---|---|
| `dBadgeUrgent` (D-0) | due today | `Color(0x33F5AD0B)` α=0.20 | `Color(0xF2FFD282)` honey-gold | `Color(0x59F5AD0B)` α=0.35 |
| `dBadgeSoon` (D-1..D-3) | 1–3 days | `Color(0x1AFFD282)` α=0.10 | `Color(0xD9FFD282)` α=0.85 | `Color(0x33FFD282)` α=0.20 |
| `dBadgeNeutral` (D-4+) | 4+ days | `Color(0x0AFFFFFF)` α=0.04 | `Color(0xB3B3B3B3)` α=0.70 muted | `Color(0x14FFFFFF)` α=0.08 |
| `dBadgeOverdue` (D+N) | past due | `Color(0x33EF4444)` α=0.20 | `Color(0xCCFF6464)` α=0.80 | `Color(0x59EF4444)` α=0.35 |

### Source status dots

Three states. Monochrome for "ok" — color only when action is needed.

| Token | Color | Note |
|---|---|---|
| `sourceStatusOk` | `Color(0xCCFFFFFF)` α=0.80 | Off-white — healthy, no call to action |
| `sourceStatusStale` | `Color(0xE6F5AD0B)` α=0.90 amber | Needs attention — v3 amber |
| `sourceStatusError` | `Color(0xCCFF6464)` α=0.80 | v3 `--danger` |

Rationale: `ok` stays monochrome to avoid false urgency. Amber/red reserved for states requiring user action (stale = re-sync; error = reconnect).

---

## 3. Glass Recipes

Three reusable modifier recipes. The kotlin-specialist implements these as `Modifier` extension functions in `GlassModifiers.kt`.

### `glassPanel` — default cards and list items

Property stack:
- Background: `glassPanel` `Color(0x0AFFFFFF)` as a `Box` background
- Border: 1 dp solid `glassBorder` `Color(0x14FFFFFF)` via `Modifier.border(1.dp, glassBorder, shape)`
- Corner radius: 12 dp (`RoundedCornerShape(12.dp)`)
- Outer shadow: `Modifier.shadow(elevation=0.dp)` (disabled) + `drawBehind` drop-shadow using `glassOuterShadow` — 0 dp x-offset, 4 dp y-offset, 24 dp blur radius
- Blur (API 31+): `Modifier.blur(10.dp, BlurredEdgeTreatment.Unbounded)`
- **Fallback SDK 28–30**: omit `Modifier.blur()`, raise background fill to `Color(0x1AFFFFFF)` (α=0.10) to compensate for absent backdrop blur

### `glassPanelElevated` — modals and bottom sheets

Same stack as `glassPanel` with:
- Background fill raised to `Color(0x0FFFFFFF)` (α=0.06)
- Blur radius 14 dp (API 31+); fallback fill `Color(0x26FFFFFF)` (α=0.15) on SDK 28–30
- Outer shadow: y-offset 8 dp, blur 32 dp, alpha 0.40 → `Color(0x66000000)`
- Inset highlight: 1 dp top-edge line `Color(0x0DFFFFFF)` (α=0.05) via `drawBehind`
- Corner radius: 20 dp (`RoundedCornerShape(20.dp)`) — matches v3 `.card` 24 dp, reduced slightly for Android bottom sheet convention

### `glassPanelMuted` — disabled and secondary surfaces

Same stack as `glassPanel` with:
- Background fill `Color(0x08FFFFFF)` (α=0.03)
- Border `Color(0x0DFFFFFF)` (α=0.05)
- No shadow (`drawBehind` omitted)
- Blur 10 dp (API 31+); fallback fill `Color(0x14FFFFFF)` (α=0.08) — lower than `glassPanel` fallback since content is intentionally de-emphasized
- Content alpha: apply `Modifier.alpha(0.55f)` on the container to dim the complete card (used for `completed` commitments per CMT-009)

---

## 4. Typography — Material3 Type Scale

Font chain: `Pretendard Variable` → `SEC CJK` (Samsung One UI ships NotoSansCJK-KR as fallback) → `sans-serif`

Font loading note for kotlin-specialist: bundle `PretendardVariable.woff2` (v3 desktop already has it) as `res/font/pretendard_variable.ttf` — use `FontVariation` API (`androidx.compose.ui.text.font.FontVariation`) for weight axis. Confirm Hangul glyph coverage at each size before release.

| Slot | Size | Weight | Line-height | Hangul note |
|---|---|---|---|---|
| `displayLarge` | 57 sp | 300 | 64 sp | Not used for Korean body — display only |
| `displayMedium` | 45 sp | 300 | 52 sp | Headings on ColdSyncScreen |
| `displaySmall` | 36 sp | 400 | 44 sp | |
| `headlineLarge` | 32 sp | 600 | 40 sp | Screen titles |
| `headlineMedium` | 28 sp | 600 | 36 sp | Section headers |
| `headlineSmall` | 24 sp | 600 | 32 sp | Card group headers |
| `titleLarge` | 22 sp | 600 | 28 sp | Bottom sheet titles |
| `titleMedium` | 16 sp | 600 | 24 sp | Card titles, commitment title |
| `titleSmall` | 14 sp | 600 | 20 sp | Chip labels, filter tabs |
| `bodyLarge` | 16 sp | 400 | 24 sp | Quote full-text (CMT-003) — needs adequate Hangul line-height |
| `bodyMedium` | 14 sp | 400 | 20 sp | List item body, source status text |
| `bodySmall` | 12 sp | 400 | 16 sp | Timestamps, metadata (HH:mm, D-N badge) — test Hangul legibility at 12 sp on Galaxy S24 |
| `labelLarge` | 14 sp | 600 | 20 sp | Button labels (리마인드/팔로업/완료) |
| `labelMedium` | 12 sp | 600 | 16 sp | Status chip text |
| `labelSmall` | 11 sp | 500 | 16 sp | Overline / secondary metadata |

Korean minimum readable size: 12 sp on OLED display. Do not use `labelSmall` for critical information (D-N badge at 12 sp is acceptable; error messages should use `bodySmall` minimum).

---

## 5. Spacing Scale — BecalmDimens

8 dp grid inherited from v3 (`--panel-padding: 24px`, nav `gap: 12px`, card `border-radius: 24px`).

### Base spacing

| Token | Value | Use |
|---|---|---|
| `spacingXxs` | 2 dp | Icon-to-label micro gap |
| `spacingXs` | 4 dp | Badge internal padding |
| `spacingS` | 8 dp | Between list items, inner chip padding |
| `spacingM` | 12 dp | Nav item gap (v3: `gap: 12px`) |
| `spacingL` | 16 dp | Card internal padding, input padding |
| `spacingXl` | 24 dp | Panel padding (v3: `.sidebar { padding: 24px }`) |
| `spacingXxl` | 32 dp | Section separation |

### Component slots

| Token | Value | Note |
|---|---|---|
| `iconSizeSmall` | 16 dp | Status dots, inline icons |
| `iconSizeMedium` | 24 dp | Tab bar icons, action icons |
| `iconSizeLarge` | 32 dp | Feature icons on empty states |
| `avatarSize` | 36 dp | Person_ref avatar (initials) |
| `chipHeight` | 28 dp | Filter tabs (전체/내가 한/상대가 한), source status chips |
| `cardCornerRadius` | 12 dp | `glassPanel` default |
| `cardCornerRadiusLarge` | 20 dp | `glassPanelElevated` (bottom sheets, modals) |
| `buttonHeight` | 48 dp | Primary / ghost buttons — One UI touch target minimum |
| `buttonCornerRadius` | 8 dp | v3 `.nav-button { border-radius: 8px }` |
| `inputHeight` | 52 dp | Text input fields |
| `inputCornerRadius` | 8 dp | v3 `input { border-radius: 8px }` |
| `bottomNavHeight` | 64 dp | Bottom navigation bar |
| `panelPaddingHorizontal` | 16 dp | Screen edge padding (tighter than v3 desktop 24 dp for mobile) |
| `panelPaddingVertical` | 12 dp | Panel top/bottom internal padding |
| `sourceStatusStripHeight` | 44 dp | SourceStatusStrip chip row (TDY-003) |

---

## 6. Shape Tokens

| M3 slot | Value | Applied to |
|---|---|---|
| `extraSmall` | `RoundedCornerShape(4.dp)` | Badges, D-N pills |
| `small` | `RoundedCornerShape(8.dp)` | Buttons, inputs, nav items — matches v3 `.nav-button` |
| `medium` | `RoundedCornerShape(12.dp)` | Cards, list items (`glassPanel`) |
| `large` | `RoundedCornerShape(20.dp)` | Bottom sheets, modals (`glassPanelElevated`) |
| `extraLarge` | `RoundedCornerShape(28.dp)` | Full-screen surfaces, ColdSyncScreen container |

v3 uses 24 dp on `.card` — mapped to `large` (20 dp) and `extraLarge` (28 dp) to better fit Android One UI conventions while preserving the rounded aesthetic.

---

## 7. Elevation Tokens

BeCalm follows a flat elevation model. Depth is expressed through the glass-shadow recipe (section 3), not M3 tonal surface tinting.

| Surface | M3 elevation | Shadow treatment |
|---|---|---|
| Scaffold / background | 0 dp | None — `cosmicBackground` fills canvas |
| Cards / list items | 0 dp | `glassPanel` shadow recipe via `drawBehind` |
| App bar (top) | 0 dp | Transparent; no elevation line |
| Bottom navigation | 0 dp | `glassPanelMuted` recipe; no M3 elevation |
| Bottom sheets | 0 dp | `glassPanelElevated` recipe |
| Dialogs | 0 dp | `glassPanelElevated` + `scrim` overlay |

`surfaceTint = Color.Transparent` must be set on the `darkColorScheme` (and `lightColorScheme`) to suppress M3's automatic tonal elevation coloring — which would add blue-tinted surface overlays and destroy the cosmic black palette.

---

## 8. Implementation Notes for kotlin-specialist

### File structure expected under `android/app/src/main/java/.../ui/theme/`

| File | Contents |
|---|---|
| `Color.kt` | `val` color constants + `darkColorScheme(...)` + `lightColorScheme(...)` definitions |
| `Type.kt` | `val BecalmTypography = Typography(...)` with Pretendard font family |
| `Shape.kt` | `val BecalmShapes = Shapes(extraSmall=..., small=..., ...)` |
| `Dimens.kt` | `object BecalmDimens` with all spacing/size tokens as `Dp` vals |
| `BecalmColors.kt` | `data class BecalmColors(...)`, `val LocalBecalmColors = staticCompositionLocalOf { ... }`, `val MaterialTheme.becalmColors: BecalmColors` extension property |
| `Theme.kt` | `@Composable fun BecalmTheme(content: @Composable () -> Unit)` |
| `GlassModifiers.kt` | `fun Modifier.glassPanel(...)`, `fun Modifier.glassPanelElevated(...)`, `fun Modifier.glassPanelMuted(...)` extension functions |

### BecalmTheme composable behavior

For MVP: always use dark scheme — do NOT toggle based on `isSystemInDarkTheme()` alone. The canonical experience is dark. Provide a `useDarkTheme: Boolean = true` parameter to allow future settings integration.

```
BecalmTheme(useDarkTheme = true) {
    CompositionLocalProvider(LocalBecalmColors provides darkBecalmColors) {
        content()
    }
}
```

### Blur fallback (API 28–30)

```
val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S  // API 31
```

In each glass modifier: if `canBlur`, apply `Modifier.blur(radius, BlurredEdgeTreatment.Unbounded)` and use the base fill alpha. If not, omit blur and use the elevated fill alpha (see section 3 per-recipe values). This avoids a hard API gate crash on Samsung Galaxy devices still on Android 9–11.

### Pretendard font loading

Pretendard Variable is a variable font with a weight axis (100–900). Load via `FontFamily` with `FontVariation.weight()`. The `.ttf` file must be placed in `res/font/`. Do not depend on network loading (v3 rule: self-hosted fonts only, ref P14).

### surfaceTint — mandatory

Every `MaterialTheme` call must pass `colorScheme = darkColorScheme(..., surfaceTint = Color.Transparent)`. If omitted, M3 will tint elevated surfaces with `primary` color, breaking the cosmic black aesthetic on cards, bottom sheets, and navigation bars.

### Compose versions assumed

- `androidx.compose.material3:material3:1.3.x`
- `androidx.compose.ui:ui-graphics` for `BlurredEdgeTreatment`
- `minSdk = 28`, `targetSdk = 34`
- `compileSdk = 34` (pinned to AGP 8.2.2 bundled platform; upgrade to 35 requires AGP ≥ 8.5)
