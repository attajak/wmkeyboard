#!/usr/bin/env python3
"""Adds phone chrome to a JVM-rendered settings shot: status bar and gesture pill.

Robolectric draws the window with no system bars, so a Roborazzi shot starts
the app at y=0 and ends with no navigation handle. This shifts the frame down by
a status bar's height, paints the bar in the colour the app draws under it (the
app is edge to edge, so that is whatever the top row already is), and draws a
clean clock, signal, wifi and battery, then the gesture pill at the bottom.

Deliberately generic: one fixed time, full signal, full battery, no
notification icons. A docs shot should show the app, not somebody's phone.

Usage:
    python3 docs/screenshots/chrome.py in.png out.png
    python3 docs/screenshots/chrome.py in.png out.webp   # WebP, as the docs ship
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
FONT = os.path.join(HERE, "..", "..", "app", "src", "main", "res", "font", "inter_medium.ttf")

# Pixel 5 geometry, 440dpi: 2.75 px per dp. The bar height matches the device
# shots already in the docs, so a JVM shot and a phone shot line up.
DP = 2.75
BAR = 96
TIME = "12:00"


def luminance(rgb):
    r, g, b = (c / 255 for c in rgb[:3])
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def chrome(src, dst):
    shot = Image.open(src).convert("RGBA")
    w, h = shot.size
    band = shot.getpixel((w // 2, 2))
    fg = (28, 27, 31, 255) if luminance(band) > 0.5 else (240, 240, 244, 255)

    out = Image.new("RGBA", (w, h), band)
    out.paste(shot.crop((0, 0, w, h - BAR)), (0, BAR))
    d = ImageDraw.Draw(out)

    # Clock, left.
    font = ImageFont.truetype(FONT, round(14 * DP))
    cy = BAR // 2
    d.text((round(24 * DP), cy), TIME, font=font, fill=fg, anchor="lm")

    # Right cluster, drawn right to left: battery, signal, wifi.
    x = w - round(24 * DP)
    # Battery: body, fill, nub.
    bw, bh = round(22 * DP), round(11 * DP)
    body = (x - bw, cy - bh // 2, x, cy + bh // 2)
    d.rounded_rectangle(body, radius=round(3 * DP), outline=fg, width=round(1.4 * DP))
    inset = round(2.4 * DP)
    d.rounded_rectangle(
        (body[0] + inset, body[1] + inset, body[2] - inset, body[3] - inset),
        radius=round(1.2 * DP), fill=fg,
    )
    nub = round(1.6 * DP)
    d.rectangle((x + 1, cy - round(2.5 * DP), x + nub, cy + round(2.5 * DP)), fill=fg)
    x = body[0] - round(8 * DP)

    # Signal: four rising bars.
    bar_w, gap, tall = round(2.6 * DP), round(1.4 * DP), round(12 * DP)
    base = cy + tall // 2
    for i in range(4):
        right = x - (3 - i) * (bar_w + gap)
        top = base - round(tall * (i + 1) / 4)
        d.rectangle((right - bar_w, top, right, base), fill=fg)
    x = x - 4 * (bar_w + gap) - round(6 * DP)

    # Wifi: filled quarter-circle wedge pointing down.
    r = round(9 * DP)
    apex_y = cy + round(6 * DP)
    d.pieslice((x - 2 * r, apex_y - r, x, apex_y + r), start=225, end=315, fill=fg)

    # Gesture pill, bottom centre.
    pw, ph = round(108 * DP / 2), round(4 * DP / 2) + 2
    py = h - round(10 * DP)
    d.rounded_rectangle((w // 2 - pw, py - ph, w // 2 + pw, py + ph), radius=ph, fill=fg)

    if dst.lower().endswith(".webp"):
        out.convert("RGB").save(dst, "WEBP", quality=90, method=6)
    else:
        out.save(dst)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    chrome(sys.argv[1], sys.argv[2])
