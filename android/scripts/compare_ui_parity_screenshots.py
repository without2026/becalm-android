#!/usr/bin/env python3
"""Compare Android UI parity screenshots against captured HTML prototype references.

This is intentionally a visual QA helper rather than a pixel-perfect golden test.
Native Compose and browser-rendered HTML differ in fonts, status-bar geometry, and
antialiasing, so the default contract is:

1. every mapped conditional/main screen must be present;
2. a side-by-side contact sheet is generated for human review;
3. coarse mean/max RGB differences are reported for trend tracking.
"""

from __future__ import annotations

import argparse
import csv
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

from PIL import Image, ImageChops, ImageDraw, ImageOps


@dataclass(frozen=True)
class Pair:
    label: str
    reference: str
    current: str
    note: str


DEFAULT_PAIRS: tuple[Pair, ...] = (
    Pair("Persons action-first", "t_person.png", "persons-action-first-source-status.png", "main tab"),
    Pair("Stale person recall", "t_choi.png", "person-detail-stale-recall.png", "person detail"),
    Pair("Schedule candidates", "t_schedule.png", "schedule-candidates-conflict-review.png", "schedule tab"),
    Pair("Open commitments", "t_commit.png", "commitments-open-give-take.png", "commitment tab"),
    Pair("Person evidence sheet", "evidence_sheet.png", "person-action-evidence-sheet.png", "conditional evidence"),
    Pair("Schedule evidence sheet", "evidence_sheet.png", "schedule-action-evidence-sheet.png", "conditional evidence"),
    Pair(
        "Commitment source evidence",
        "evidence_sheet.png",
        "commitment-detail-source-evidence-sheet.png",
        "conditional evidence",
    ),
    Pair("Evidence import sheet", "evidence_sheet.png", "evidence-import-sheet.png", "source import sheet"),
    Pair("Commitment compose context", "compose_sheet.png", "commitment-compose-sheet.png", "beta context foundation"),
    Pair("Onboarding source setup", "ob_mail.png", "onboarding-source-connections-first-source.png", "onboarding"),
    Pair("Gmail activation loading", "ob_mail_loading.png", "onboarding-gmail-activation-loading.png", "onboarding"),
    Pair("Gmail activation ready", "ob_mail_done.png", "onboarding-gmail-activation-ready.png", "onboarding"),
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference-dir", required=True, type=Path)
    parser.add_argument("--current-dir", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path, help="PNG contact sheet output path")
    parser.add_argument("--report", required=True, type=Path, help="CSV metrics output path")
    return parser.parse_args()


def fit_image(image: Image.Image, size: tuple[int, int]) -> Image.Image:
    fitted = ImageOps.contain(image.convert("RGB"), size, method=Image.Resampling.LANCZOS)
    canvas = Image.new("RGB", size, "white")
    offset = ((size[0] - fitted.width) // 2, (size[1] - fitted.height) // 2)
    canvas.paste(fitted, offset)
    return canvas


def diff_stats(reference: Image.Image, current: Image.Image) -> tuple[float, int, Image.Image]:
    ref = reference.convert("RGB")
    cur = current.convert("RGB").resize(ref.size, Image.Resampling.LANCZOS)
    diff = ImageChops.difference(ref, cur)
    extrema = diff.getextrema()
    max_diff = max(channel[1] for channel in extrema)
    histogram = diff.histogram()
    total_pixels = ref.width * ref.height
    weighted = sum((bin_index % 256) * count for bin_index, count in enumerate(histogram))
    mean = weighted / (total_pixels * 3)
    heat = ImageOps.autocontrast(diff.convert("L")).convert("RGB")
    return mean, max_diff, heat


def draw_label(draw: ImageDraw.ImageDraw, xy: tuple[int, int], lines: Iterable[str]) -> None:
    y = xy[1]
    for line in lines:
        draw.text((xy[0], y), line, fill=(24, 31, 44))
        y += 18


def main() -> int:
    args = parse_args()
    missing: list[str] = []
    rows: list[dict[str, str]] = []
    row_height = 330
    label_width = 220
    image_width = 190
    image_height = 304
    gap = 14
    sheet_width = label_width + (image_width * 3) + (gap * 4)
    sheet_height = 36 + row_height * len(DEFAULT_PAIRS)
    sheet = Image.new("RGB", (sheet_width, sheet_height), "white")
    draw = ImageDraw.Draw(sheet)
    draw_label(draw, (14, 12), ["HTML reference", "Android current", "Diff heatmap"])

    for index, pair in enumerate(DEFAULT_PAIRS):
        y = 36 + row_height * index
        ref_path = args.reference_dir / pair.reference
        cur_path = args.current_dir / pair.current
        if not ref_path.is_file() or not cur_path.is_file():
            if not ref_path.is_file():
                missing.append(str(ref_path))
            if not cur_path.is_file():
                missing.append(str(cur_path))
            continue

        reference = Image.open(ref_path)
        current = Image.open(cur_path)
        mean, max_diff, heat = diff_stats(reference, current)
        rows.append(
            {
                "label": pair.label,
                "reference": pair.reference,
                "current": pair.current,
                "note": pair.note,
                "mean_rgb_abs_diff": f"{mean:.2f}",
                "max_rgb_abs_diff": str(max_diff),
            },
        )

        draw_label(
            draw,
            (14, y + 10),
            [
                pair.label,
                pair.note,
                f"mean diff {mean:.2f}",
                f"max diff {max_diff}",
            ],
        )
        x = label_width + gap
        sheet.paste(fit_image(reference, (image_width, image_height)), (x, y + 10))
        x += image_width + gap
        sheet.paste(fit_image(current, (image_width, image_height)), (x, y + 10))
        x += image_width + gap
        sheet.paste(fit_image(heat, (image_width, image_height)), (x, y + 10))

    if missing:
        print("Missing required parity files:")
        for path in missing:
            print(f"- {path}")
        return 2

    args.output.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(args.output)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    with args.report.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(
            stream,
            fieldnames=[
                "label",
                "reference",
                "current",
                "note",
                "mean_rgb_abs_diff",
                "max_rgb_abs_diff",
            ],
        )
        writer.writeheader()
        writer.writerows(rows)
    print(f"Wrote {args.output}")
    print(f"Wrote {args.report}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
