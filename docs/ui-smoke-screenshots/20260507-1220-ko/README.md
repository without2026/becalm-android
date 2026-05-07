# UI Smoke Screenshot Matrix: 2026-05-07

Device: `emulator-5554` (`sdk_gphone16k_x86_64`)

Captured with app locale `ko-KR`:

- `ko-360x800.png`
- `ko-430x932.png`
- `ko-tablet-1280x800.png`
- `ko-430x932-font-1_3.png`
- `ko-430x932-dark.png`

Audit note:

- Korean app-authored copy renders on the Today screen.
- Original source/commitment content remains in its original language.
- 360px and 430px phone widths do not show overlapping primary controls.
- Font scale 1.3 wraps the source warning and right-side "시간 없음" rail without clipping controls.
- Dark-mode smoke keeps the canonical light canvas. Status/navigation bar icon contrast was fixed in `MainActivity` by forcing light system bar styles for the light-first app theme.
