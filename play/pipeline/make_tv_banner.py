#!/usr/bin/env python
"""Draw the Play Store's TV banner: 1280x720, the app mark and its name.

Play's TV listing takes one banner and shows it as the app's tile on a
television's home row, where it is read from three metres away. So: the brand
gradient the adaptive icon already uses, the launcher mark at a size that
survives that distance, and the name in the app's own type (Manrope, the
settings app's face). Everything sits inside a 10% safe margin, which is what
older sets still crop.

Regenerated rather than hand-painted so an icon or a colour change carries here
by rerunning it:

    python play/pipeline/make_tv_banner.py

Writes fastlane/metadata/android/en-US/images/tvBanner.png, which the `graphics`
lane uploads with everything else in that folder.

Needs Pillow. On this machine `python` has it and `python3` does not.
"""

import os

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "fastlane", "metadata", "android", "en-US", "images", "tvBanner.png")
MARK = os.path.join(ROOT, "app", "src", "main", "res", "mipmap-xxxhdpi", "ic_launcher_fg.webp")
BOLD = os.path.join(ROOT, "app", "src", "main", "res", "font", "manrope_bold.ttf")
MEDIUM = os.path.join(ROOT, "app", "src", "main", "res", "font", "manrope_semibold.ttf")

WIDTH, HEIGHT = 1280, 720

# The adaptive icon's own gradient (drawable/ic_launcher_background.xml), so the
# tile and the icon are the same object rather than two shades of navy.
TOP_LEFT = (0x2F, 0x34, 0x74)
BOTTOM_RIGHT = (0x19, 0x1C, 0x42)

# A tenth of the width, which is what an older set may still crop.
SAFE_MARGIN = WIDTH // 10

TITLE = "WM Keyboard"
SUBTITLE = "Type on your TV with the remote"


def gradient():
    """The background, interpolated along the diagonal like the icon's."""
    image = Image.new("RGB", (WIDTH, HEIGHT))
    pixels = image.load()
    span = float(WIDTH + HEIGHT)
    for y in range(HEIGHT):
        for x in range(WIDTH):
            t = (x + y) / span
            pixels[x, y] = tuple(
                int(round(TOP_LEFT[c] + (BOTTOM_RIGHT[c] - TOP_LEFT[c]) * t)) for c in range(3)
            )
    return image


def font(path, size, fallback_size=None):
    try:
        return ImageFont.truetype(path, size)
    except (IOError, OSError):
        return ImageFont.load_default() if fallback_size is None else ImageFont.load_default()


def main():
    banner = gradient()
    draw = ImageDraw.Draw(banner)

    title_font = font(BOLD, 88)
    subtitle_font = font(MEDIUM, 34)
    title_w = draw.textbbox((0, 0), TITLE, font=title_font)[2]
    subtitle_w = draw.textbbox((0, 0), SUBTITLE, font=subtitle_font)[2]
    text_w = max(title_w, subtitle_w)

    mark = Image.open(MARK).convert("RGBA")
    # The launcher foreground is drawn inside the adaptive icon's safe circle,
    # so about a third of it is deliberate padding: oversize it and the padding
    # becomes the gap.
    size = 400
    mark = mark.resize((size, size), Image.LANCZOS)

    # The whole lockup is centred as one group, so the safe margin is the same
    # on both sides however long the name is in a future locale.
    gap = 24
    group = size + gap + text_w
    left = (WIDTH - group) // 2
    if left < SAFE_MARGIN:
        raise SystemExit(
            "the lockup is %dpx wide and does not clear the %dpx safe margin"
            % (group, SAFE_MARGIN)
        )

    banner.paste(mark, (left, (HEIGHT - size) // 2), mark)
    text_x = left + size + gap
    draw.text((text_x, 296), TITLE, font=title_font, fill=(0xFF, 0xFF, 0xFF))
    draw.text((text_x + 3, 400), SUBTITLE, font=subtitle_font, fill=(0xB9, 0xBC, 0xE8))

    banner.save(OUT, "PNG")
    print("wrote %s (%dx%d)" % (OUT, WIDTH, HEIGHT))


if __name__ == "__main__":
    main()
