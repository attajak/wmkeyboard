#!/usr/bin/env python3
"""Turns the JVM-rendered settings shots into docs assets, and puts them on the pages.

    ./gradlew :app:testFullEnDebugUnitTest -Pwmkb.docShots=true [-Pwmkb.docShots.only=<regex>]
    python3 docs/screenshots/finish.py [ids or regexes...] [--no-pages] [--dry-run]

For every `<id>.light.png` / `<id>.dark.png` under app/build/docshots/ (from
app/src/docShots), it:

1. adds the phone chrome Robolectric does not draw: the frame moves down by a
   status bar, which is painted in the colour the app draws under it (the app
   is edge to edge), with a clean clock, signal, wifi and battery, and a
   gesture pill at the bottom. One fixed time, no notifications: a docs shot
   shows the app, not somebody's phone;
2. marks the setting the shot is about, if the run named one (the `.json`
   beside the PNG): a soft scrim over everything else and a ring around it;
3. writes docs/src/assets/screens/<id>.light.webp and <id>.dark.webp,
   lossless — Astro re-encodes them to AVIF/WebP/JPEG at build, so the source
   should lose nothing on the way in;
4. swaps the page's `{/* shot: id */}` placeholder, or its old single-image
   markdown, for `<Shot id=… alt=…/>`, deletes the old single image, and marks
   the manifest entry done.
"""
import argparse
import json
import os
import re
import subprocess
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
DOCS = os.path.dirname(HERE)
REPO = os.path.dirname(DOCS)
RENDERS = os.path.join(REPO, "app", "build", "docshots")
SCREENS = os.path.join(DOCS, "src", "assets", "screens")
MANIFEST = os.path.join(HERE, "manifest.json")
FONT = os.path.join(REPO, "app", "src", "main", "res", "font", "inter_medium.ttf")

# Pixel 5 geometry, 440dpi: 2.75 px per dp.
DP = 2.75
BAR = 96
TIME = "12:00"
SS = 3  # supersampling for the ring and scrim edges

# The docs' own brand gradient, so the mark reads as the docs pointing at the
# app rather than as part of the app.
RING_FROM = (76, 141, 246)
RING_TO = (139, 92, 246)


def luminance(rgb):
    r, g, b = (c / 255 for c in rgb[:3])
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def add_chrome(shot):
    w, h = shot.size
    band = shot.getpixel((w // 2, 2))
    fg = (28, 27, 31, 255) if luminance(band) > 0.5 else (236, 236, 242, 255)

    out = Image.new("RGBA", (w, h), band)
    out.paste(shot.crop((0, 0, w, h - BAR)), (0, BAR))
    d = ImageDraw.Draw(out)
    cy = BAR // 2

    font = ImageFont.truetype(FONT, round(14 * DP))
    d.text((round(24 * DP), cy), TIME, font=font, fill=fg, anchor="lm")

    x = w - round(24 * DP)
    bw, bh = round(22 * DP), round(11 * DP)
    body = (x - bw, cy - bh // 2, x, cy + bh // 2)
    d.rounded_rectangle(body, radius=round(3 * DP), outline=fg, width=round(1.4 * DP))
    inset = round(2.4 * DP)
    d.rounded_rectangle((body[0] + inset, body[1] + inset, body[2] - inset, body[3] - inset),
                        radius=round(1.2 * DP), fill=fg)
    d.rectangle((x + 1, cy - round(2.5 * DP), x + round(1.6 * DP), cy + round(2.5 * DP)), fill=fg)
    x = body[0] - round(8 * DP)

    bar_w, gap, tall = round(2.6 * DP), round(1.4 * DP), round(12 * DP)
    base = cy + tall // 2
    for i in range(4):
        right = x - (3 - i) * (bar_w + gap)
        d.rectangle((right - bar_w, base - round(tall * (i + 1) / 4), right, base), fill=fg)
    x = x - 4 * (bar_w + gap) - round(6 * DP)

    r = round(9 * DP)
    apex_y = cy + round(6 * DP)
    d.pieslice((x - 2 * r, apex_y - r, x, apex_y + r), start=225, end=315, fill=fg)

    pw, ph = round(54 * DP), round(2 * DP)
    py = h - round(10 * DP)
    d.rounded_rectangle((w // 2 - pw, py - ph, w // 2 + pw, py + ph), radius=ph, fill=fg)
    return out


def gradient(size):
    w, h = size
    grad = Image.new("RGBA", (w, h))
    px = grad.load()
    for xx in range(w):
        t = xx / max(1, w - 1)
        c = tuple(round(a + (b - a) * t) for a, b in zip(RING_FROM, RING_TO)) + (255,)
        for yy in range(h):
            px[xx, yy] = c
    return grad


def add_ring(img, box, dark, group):
    """A scrim over everything but `box`, and a gradient ring around it."""
    w, h = img.size
    # Clear of the card: an icon tile sits a few dp inside its edge, and a
    # ring hugging it reads as touching it. A group's heading already gives
    # it air on top, so it sits closer.
    pad = round((8 if group else 16) * DP)
    radius = round((22 if group else 30) * DP)
    stroke = round(2.5 * DP)
    l, t, r, b = box
    l, t = max(stroke, l - pad), max(stroke, t - pad)
    r, b = min(w - stroke, r + pad), min(h - stroke, b + pad)
    if r <= l or b <= t:
        # Wholly off the frame: the setting was never scrolled into view.
        print(f"  ring {box} is off the frame, left unmarked", file=sys.stderr)
        return img

    big = (w * SS, h * SS)
    hole = Image.new("L", big, 0)
    ImageDraw.Draw(hole).rounded_rectangle([v * SS for v in (l, t, r, b)], radius=radius * SS, fill=255)
    ring = Image.new("L", big, 0)
    rd = ImageDraw.Draw(ring)
    rd.rounded_rectangle([v * SS for v in (l, t, r, b)], radius=radius * SS,
                         outline=255, width=stroke * SS)
    hole = hole.resize((w, h), Image.LANCZOS)
    ring = ring.resize((w, h), Image.LANCZOS)

    scrim_alpha = 0.42 if dark else 0.30
    scrim = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    scrim_mask = ImageChops.invert(hole).point(lambda v: round(v * scrim_alpha))
    scrim.putalpha(scrim_mask)
    out = Image.alpha_composite(img, scrim)

    # A soft glow under the ring lifts it off both backgrounds.
    glow = ring.filter(ImageFilter.GaussianBlur(3 * DP)).point(lambda v: round(v * 0.55))
    grad = gradient((w, h))
    glow_layer = grad.copy()
    glow_layer.putalpha(glow)
    out = Image.alpha_composite(out, glow_layer)
    ring_layer = grad.copy()
    ring_layer.putalpha(ring)
    return Image.alpha_composite(out, ring_layer)


def finish_one(shot_id, mode, screens=SCREENS):
    base = os.path.join(RENDERS, f"{shot_id}.{mode}")
    img = Image.open(base + ".png").convert("RGBA")
    img = add_chrome(img)
    meta_path = base + ".json"
    meta = json.load(open(meta_path)) if os.path.exists(meta_path) else {}
    if meta.get("ring"):
        l, t, r, b = meta["ring"]
        # Renders from before the flag existed: a group's frame spans the column
        # its cards are inset in (16 dp, x=44), a row's only the card (x=60).
        group = meta.get("group", l < 52)
        img = add_ring(img, (l, t + BAR, r, b + BAR), mode == "dark", group)
    dest = os.path.join(screens, f"{shot_id}.{mode}.webp")
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    img.convert("RGB").save(dest, "WEBP", lossless=True, quality=100, method=6)
    return dest


SHOT_IMPORT = "import Shot from '@components/Shot.astro';"


def old_image(entry):
    """The single image the entry had before, relative to assets/screens, or None."""
    old = entry["file"]
    if old.endswith((".light.webp", ".dark.webp")) or not old.startswith("src/assets/screens/"):
        return None
    return old[len("src/assets/screens/"):-len(".webp")]


def embed(entry, page=None):
    """Points the page at the pair. Returns what it did."""
    page = page or os.path.join(REPO, entry["page"])
    shot_id = entry["id"]
    with open(page) as handle:
        content = handle.read()
    alt = entry["caption"].replace('"', "&quot;")
    tag = f'<Shot id="{shot_id}" alt="{alt}" />'
    before = content
    content = content.replace("{/* shot: %s */}" % shot_id, tag)
    # The old single image, in either the alias or the relative form, by its
    # id or by the path it had, which an id can differ from.
    for name in {shot_id, old_image(entry) or shot_id}:
        content = re.sub(r"!\[([^\]]*)\]\((?:@assets|(?:\.\./)+assets)/screens/%s\.webp\)" % re.escape(name),
                         lambda m: f'<Shot id="{shot_id}" alt="{m.group(1) or alt}" />', content)
    if content == before:
        return "already" if f'<Shot id="{shot_id}"' in content else "missing"
    if SHOT_IMPORT not in content:
        # After the frontmatter's closing fence and any imports already there.
        match = re.match(r"(---\n.*?\n---\n)((?:\s*import [^\n]*\n)*)", content, re.S)
        head = match.group(0) if match else ""
        content = head.rstrip("\n") + "\n" + SHOT_IMPORT + "\n" + content[len(head):]
    with open(page, "w") as handle:
        handle.write(content)
    return "placed"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("only", nargs="*", help="ids or regexes; default: everything rendered")
    parser.add_argument("--no-pages", action="store_true", help="write assets only")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--out", help="write the finished images here instead, for a look (implies --no-pages)")
    args = parser.parse_args()
    if args.out:
        args.no_pages = True

    rendered = sorted(
        os.path.relpath(os.path.join(root, f), RENDERS)[: -len(".light.png")]
        for root, _, files in os.walk(RENDERS) for f in files if f.endswith(".light.png")
    )
    if args.only:
        pats = [re.compile(p) for p in args.only]
        rendered = [i for i in rendered if any(p.search(i) for p in pats)]
    manifest = json.load(open(MANIFEST))
    by_id = {e["id"]: e for e in manifest}

    for shot_id in rendered:
        entry = by_id.get(shot_id)
        if args.dry_run:
            print(f"  {shot_id}{'' if entry else '  (not in manifest)'}")
            continue
        sizes = []
        for mode in ("light", "dark"):
            if os.path.exists(os.path.join(RENDERS, f"{shot_id}.{mode}.png")):
                dest = finish_one(shot_id, mode, args.out or SCREENS)
                sizes.append(f"{mode} {os.path.getsize(dest) // 1024} KB")
        note = ""
        if entry and not args.no_pages:
            note = {"placed": "", "already": "  (page already shows it)",
                    "missing": "  (no placeholder or image on the page)"}[embed(entry)]
            # Other pages that show the same old image (the home page, a
            # second guide) get the pair too, or deleting it breaks them.
            if old_image(entry):
                for root, _, files in os.walk(os.path.join(DOCS, "src", "content")):
                    for f in files:
                        other = os.path.join(root, f)
                        if f.endswith((".md", ".mdx")) and other != os.path.join(REPO, entry["page"]) \
                                and f"screens/{old_image(entry)}.webp" in open(other).read():
                            embed(entry, other)
            old = os.path.join(DOCS, entry["file"])
            if old.endswith(".webp") and not old.endswith((".light.webp", ".dark.webp")) and os.path.exists(old):
                subprocess.run(["git", "rm", "-q", "--cached", "--ignore-unmatch", old], cwd=REPO)
                os.remove(old)
            entry["file"] = f"src/assets/screens/{shot_id}.light.webp"
            entry["dark"] = f"src/assets/screens/{shot_id}.dark.webp"
            entry["status"] = "done"
            entry["source"] = "jvm"
        print(f"  {shot_id}: {', '.join(sizes)}{note}")
    if not args.dry_run and not args.no_pages:
        with open(MANIFEST, "w") as handle:
            json.dump(manifest, handle, indent=2)
            handle.write("\n")


if __name__ == "__main__":
    main()
