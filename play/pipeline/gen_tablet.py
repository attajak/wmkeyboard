"""WM Keyboard — Play Store graphics, 10-inch tablet variant.

Landscape 2560x1440 (16:9), same Ink & Neon system as the phone set.
Reads captures from play/screencaps-tablet/ (any resolution, landscape
preferred) and writes play/store-listing-tablet10/.

Runs in two environments without edits:
  - Claude's cloud workspace (shots staged under /mnt/user-data/uploads)
  - locally from play/pipeline/  (python3 gen_tablet.py; needs ./fonts —
    run fetch_fonts.sh once, and Pillow >= 10: pip install pillow)

The keyboard-panel top row is auto-detected per capture (largest row-to-row
change in the lower part of the image); add a filename to KB_TOP_OVERRIDE
if a busy wallpaper fools it.
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

import wmkit as K
from wmkit import (AMBER, BODY, CYAN, LIME, MAGENTA, SS, VIOLET, blob,
                   canvas, chip, font, gradient_text, grain, save, text,
                   vignette)

HERE = Path(__file__).parent
CANDIDATE_SHOTS = [
    Path("/mnt/user-data/uploads/WMKeyboard/play/screencaps-tablet"),
    HERE.parent / "screencaps-tablet",
]
SHOTS = next((p for p in CANDIDATE_SHOTS if p.is_dir()), CANDIDATE_SHOTS[0])
CANDIDATE_LOGO = [
    Path("/mnt/user-data/uploads/WMKeyboard/docs/src/assets/logo-mark.png"),
    HERE.parent.parent / "docs/src/assets/logo-mark.png",
]
LOGO = next((p for p in CANDIDATE_LOGO if p.is_file()), CANDIDATE_LOGO[0])
OUT_CANDIDATES = [HERE / "out-tablet", HERE.parent / "store-listing-tablet10"]
OUT = OUT_CANDIDATES[0] if OUT_CANDIDATES[0].parent.exists() else OUT_CANDIDATES[1]
OUT.mkdir(parents=True, exist_ok=True)

W, H = 2560, 1440
KB_TOP_OVERRIDE: dict[str, int] = {}  # e.g. {"theme-photo.png": 812}


def load(name):
    for ext in (".png", ".jpg", ".jpeg", ".webp"):
        p = SHOTS / f"{name}{ext}"
        if p.is_file():
            im = Image.open(p)
            im.load()
            im._wmk_name = f"{name}{ext}"
            return im
    raise FileNotFoundError(f"{name}.(png|jpg|webp) not in {SHOTS}")


def kb_top(shot):
    """First row of the keyboard panel: biggest row-step in the lower image."""
    if getattr(shot, "_wmk_name", None) in KB_TOP_OVERRIDE:
        return KB_TOP_OVERRIDE[shot._wmk_name]
    im = shot.convert("RGB")
    w, h = im.size
    cols = [int(w * f) for f in (0.15, 0.35, 0.5, 0.65, 0.85)]
    best_y, best_d = int(h * 0.55), 0
    prev = None
    for y in range(int(h * 0.35), int(h * 0.8), 2):
        row = [im.getpixel((x, y)) for x in cols]
        avg = tuple(sum(c[i] for c in row) // len(cols) for i in range(3))
        if prev is not None:
            d = sum(abs(avg[i] - prev[i]) for i in range(3))
            if d > best_d:
                best_d, best_y = d, y
        prev = avg
    return best_y


def brand_row(img, x, y, size=54):
    logo = Image.open(LOGO).convert("RGBA").resize((size * SS, size * SS),
                                                   Image.LANCZOS)
    img.paste(logo, (x * SS, y * SS), logo)
    text(img, (x + size + 18, y + size // 2), "WM Keyboard",
         font("Inter", 30, 640), fill=(225, 230, 242), anchor="lm")


def headline(img, x, y, pre, accent, post="", size=76):
    f = font("Manrope", size, 800)
    d = ImageDraw.Draw(img)
    cx = x
    if pre:
        text(img, (cx, y), pre, f)
        cx += K.measure(d, pre, f)[0] // SS
    gradient_text(img, (cx, y), accent, f, VIOLET, CYAN, glow=6)
    cx += K.measure(d, accent, f)[0] // SS
    if post:
        text(img, (cx, y), post, f)


def chip_stack(img, x, y, items, gap=22):
    f_c = font("Inter", 30, 560)
    for s, a in items:
        _, h = chip(img, x, y, s, accent=a, fnt=f_c, dot=bool(a),
                    pad_x=24, pad_y=14)
        y += h + gap


def tablet(img, shot, x, y, w, crop_top=0, keyboard_only=False,
           glow_colors=(VIOLET, CYAN)):
    """Landscape tablet frame at (x, y), scaled to width w."""
    if keyboard_only:
        shot = shot.crop((0, kb_top(shot), shot.width, shot.height))
    elif crop_top:
        shot = shot.crop((0, crop_top, shot.width, shot.height))
    ratio = shot.height / shot.width
    sw, sh = w * SS, int(w * ratio) * SS
    s = shot.resize((sw, sh), Image.LANCZOS)
    bezel, r = 10, 40
    ob = (x - bezel, y - bezel, x + w + bezel, y + w * ratio + bezel)

    glow_layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow_layer)
    mid = (ob[0] + ob[2]) / 2
    gd.rounded_rectangle([ob[0] * SS, ob[1] * SS, mid * SS, ob[3] * SS],
                         (r + bezel) * SS, fill=glow_colors[0] + (70,))
    gd.rounded_rectangle([mid * SS, ob[1] * SS, ob[2] * SS, ob[3] * SS],
                         (r + bezel) * SS, fill=glow_colors[1] + (70,))
    img.paste(Image.alpha_composite(
        img.convert("RGBA"),
        glow_layer.filter(ImageFilter.GaussianBlur(70 * SS))).convert("RGB"))
    K.shadow(img, ob, r + bezel, 40, alpha=180, offset=(0, 24))
    K.rrect(img, ob, r + bezel, fill=(21, 26, 35, 255),
            outline=(255, 255, 255, 26), width=1)
    mask = Image.new("L", (sw, sh), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, sw, sh], r * SS, fill=255)
    img.paste(s, (x * SS, y * SS), mask)
    return ob


def base(v1, v2, v3):
    img = canvas(W, H)
    blob(img, *v1, VIOLET, 65)
    blob(img, *v2, CYAN, 45)
    blob(img, *v3, MAGENTA, 30)
    brand_row(img, 140, 96)
    return img


def finish(img, name):
    vignette(img, 100)
    grain(img)
    return save(img, OUT / name)


def left_column(img, lines, sub, chips, y0=300):
    """Standard text column: headline lines, sub lines, chip stack."""
    y = y0
    for pre, acc, post in lines:
        headline(img, 140, y, pre, acc, post)
        y += 108
    y += 24
    for s in sub:
        text(img, (140, y), s, font("Inter", 34, 480), fill=BODY)
        y += 52
    chip_stack(img, 140, y + 36, chips)


# Same list as the phone chip wall (gen.py), duplicated so this file runs
# standalone from play/pipeline/ without the phone captures present.
WALL_CHIPS = [
    ("Offline Whisper dictation", LIME), ("On-device AI writing", LIME),
    ("Offline grammar check", LIME), ("OTP codes from notifications", CYAN),
    ("Screenshot clipboard", CYAN), ("Sensitive-clip guard", None),
    ("Encrypted backups", CYAN), ("Works before unlock", CYAN),
    ("Incognito mode", None), ("Per-app keyboard modes", VIOLET),
    ("Typing speed test", VIOLET), ("Undo & redo", None),
    ("Spacebar trackpad", None), ("Hardware shortcut layer", VIOLET),
    ("Glide in any language", VIOLET), ("Double-tap caps lock", None),
    ("Word-swipe delete", None), ("One-handed · split · floating", VIOLET),
    ("Foldable-aware", None), ("Power-saving mode", None),
    ("Typing statistics", None), ("Mixed-language typing", CYAN),
    ("Avro phonetic Bangla", CYAN), ("Probhat & InScript", None),
    ("Layout editor", CYAN), ("Import old-keyboard layouts", CYAN),
    ("Numeral systems", None), ("Fancy text", None), ("Kaomoji", None),
    ("Latest emoji", MAGENTA), ("Semantic emoji search", MAGENTA),
    ("Dual skin tones", None), ("Sticker editor", MAGENTA),
    ("Custom sticker packs", None), ("GIF search", None),
    ("Animated emoji", MAGENTA), ("Calculator in the strip", AMBER),
    ("Unit & currency convert", AMBER), ("QR + document scanner", AMBER),
    ("Handwriting input", AMBER), ("Translator", None),
    ("Dictionary & Wikipedia", None), ("Media controls", AMBER),
    ("Snippets with {date} {clip}", AMBER), ("Clipboard manager", None),
    ("Theme editor", VIOLET), ("Photo backgrounds", None),
    ("Icon packs", MAGENTA), ("Custom key shapes", None),
    ("Auto light/dark themes", None), ("Lua plugins", LIME),
    ("Addon repositories", LIME), ("Screen-reader passthrough", CYAN),
    ("Color-vision themes", None), ("Settings search", None),
    ("Easter eggs", MAGENTA),
]


# ------------------------------------------------------------------ slides --
def s01_hero():
    img = base((300, 200, 500), (2300, 1200, 520), (150, 1300, 420))
    left_column(img,
                [("A keyboard that", "", ""), ("does ", "so much more", "")],
                ["Every chip here is a real feature."],
                [("Glide in any language", CYAN),
                 ("Clipboard history & pins", None),
                 ("Per-app modes", VIOLET),
                 ("Spacebar is a trackpad", LIME),
                 ("Semantic emoji search", MAGENTA),
                 ("Works before unlock", None)])
    sh = load("kb-english-dark")
    tablet(img, sh, 1180, 240, 1280, crop_top=int(sh.height * 0.055))
    text(img, (1180 + 640, 1330), "No accounts. No ads. Learning stays on your device.",
         font("Inter", 30, 500), fill=BODY, anchor="ma")
    return finish(img, "01-hero.png")


def s02_bengali():
    img = base((2250, 220, 480), (300, 1100, 500), (2300, 1250, 400))
    headline(img, 140, 290, "Type it how it ", "sounds")
    text(img, (140, 420), "Avro phonetic Bangla, fully offline.",
         font("Inter", 34, 480), fill=BODY)
    d = ImageDraw.Draw(img, "RGBA")
    f_mono = font("SpaceGrotesk", 52, 500)
    latin = "ami valo achi"
    tw = K.measure(d, latin, f_mono)[0] // SS
    K.rrect(img, (140, 520, 140 + tw + 110, 520 + 118), 30,
            fill=(255, 255, 255, 14), outline=(255, 255, 255, 34), width=1)
    text(img, (190, 579), latin, f_mono, fill=(210, 218, 233), anchor="lm")
    cx = 190 + tw + 12
    d.rounded_rectangle([cx * SS, 552 * SS, (cx + 4) * SS, 606 * SS],
                        2 * SS, fill=CYAN + (255,))
    gradient_text(img, (140, 690), "আমি ভালো আছি",
                  font("NotoSansBengali", 104, 700), VIOLET, CYAN, glow=10)
    text(img, (140, 920), "Spell it loosely: asi, achi, achhi",
         font("Inter", 32, 480), fill=BODY)
    text(img, (140, 968), "all reach the word you meant.",
         font("Inter", 32, 480), fill=BODY)
    chip_stack(img, 140, 1050,
               [("Bangla and English together", CYAN),
                ("Conjunct-aware delete", VIOLET)])
    sh = load("kb-bengali-typing")
    tablet(img, sh, 1300, 260, 1160, crop_top=int(sh.height * 0.055))
    return finish(img, "02-bengali.png")


def s03_offline():
    """Tablet set: no airplane capture, so this slot celebrates big screens
    instead — split and floating keyboards are the tablet-native story."""
    img = base((300, 200, 500), (2250, 1150, 520), (200, 1300, 400))
    left_column(img,
                [("Made for", "", ""), ("", "big screens", "")],
                ["Split it for thumbs, float it anywhere,", "with the same offline typing."],
                [("Split keyboard", VIOLET),
                 ("Floating keyboard", CYAN),
                 ("Foldable-aware", None),
                 ("Hardware shortcut layer", LIME)])
    # keep enough screen above the keys that the split gap / floating
    # placement reads; the subtle chat-to-keyboard boundary fools kb_top here
    a = load("kb-split")
    b = load("kb-floating")
    tablet(img, a, 1090, 190, 1360, crop_top=int(a.height * 0.36))
    tablet(img, b, 1230, 800, 1300, crop_top=int(b.height * 0.36),
           glow_colors=(CYAN, MAGENTA))
    return finish(img, "03-bigscreen.png")


def s04_ai():
    img = base((2250, 220, 480), (300, 1150, 500), (2200, 1300, 380))
    left_column(img,
                [("Rewrite, translate, fix", "", ""),
                 ("", "right on your tablet", "")],
                ["Small models run on the tablet itself.",
                 "Cloud providers are yours to add, or ignore."],
                [("Pick your model", VIOLET),
                 ("Works in airplane mode", LIME),
                 ("Whisper voice typing", CYAN),
                 ("Nothing saved by default", None)])
    sh = load("ai-panel")
    tablet(img, sh, 1180, 240, 1280, crop_top=int(sh.height * 0.055))
    return finish(img, "04-ai.png")


def s05_clipboard():
    img = base((300, 200, 480), (2280, 1100, 520), (250, 1300, 400))
    left_column(img,
                [("A clipboard with", "", ""), ("", "superpowers", "")],
                ["History, pins, and codes copied for you."],
                [("OTP codes from notifications", CYAN),
                 ("Screenshots drop in too", MAGENTA),
                 ("Pin what you reuse", VIOLET),
                 ("Passwords never stored", LIME)])
    sh = load("clipboard-panel")
    tablet(img, sh, 1180, 240, 1280, crop_top=int(sh.height * 0.055))
    return finish(img, "05-clipboard.png")


def s06_tools():
    img = base((300, 200, 480), (2280, 1150, 520), (200, 1250, 380))
    left_column(img,
                [("Stop switching apps", "", ""),
                 ("a toolbox ", "under one key", "")],
                ["Calculate, convert, scan and translate", "mid-sentence."],
                [("Calculator", AMBER), ("Converter", None),
                 ("Scanner", None), ("Translator", None),
                 ("And 50+ more", VIOLET)])
    a = load("toolbox-grid")
    b = load("tools-calculator")
    tablet(img, a, 1150, 200, 1100, keyboard_only=True)
    tablet(img, b, 1420, 720, 1080, keyboard_only=True,
           glow_colors=(CYAN, MAGENTA))
    return finish(img, "06-tools.png")


def s07_themes():
    img = base((2250, 220, 500), (300, 1150, 500), (2250, 1300, 400))
    left_column(img,
                [("Make it", "", ""), ("unmistakably ", "yours", "")],
                ["Themes, icon packs, fonts", "and an emoji brain."],
                [("Theme editor", VIOLET), ("Photo backgrounds", None),
                 ("Icon packs", MAGENTA), ("Emoji search that gets you", CYAN)])
    a = load("theme-sakura")
    b = load("theme-photo")
    tablet(img, a, 1150, 200, 1100, keyboard_only=True,
           glow_colors=(MAGENTA, VIOLET))
    tablet(img, b, 1420, 720, 1080, keyboard_only=True)
    return finish(img, "07-themes.png")


def s08_chip_wall():
    img = base((300, 200, 460), (2260, 1100, 520), (250, 1300, 420))
    text(img, (W // 2, 120), "THE PART NOBODY EXPECTS", font("Inter", 30, 640),
         fill=CYAN, anchor="ma")
    f = font("Manrope", 84, 800)
    d = ImageDraw.Draw(img)
    parts = [("It does ", None), ("that", "grad"), (", too.", None)]
    total = sum(K.measure(d, s, f)[0] // SS for s, _ in parts)
    x = (W - total) // 2
    for s, kind in parts:
        if kind:
            gradient_text(img, (x, 170), s, f, VIOLET, CYAN, glow=6)
        else:
            text(img, (x, 170), s, f)
        x += K.measure(d, s, f)[0] // SS
    text(img, (W // 2, 310), "A sample of what ships inside, all real. "
         "Every network feature is documented.",
         font("Inter", 32, 480), fill=BODY, anchor="ma")

    f_c = font("Inter", 29, 540)
    pad_x, gap = 22, 14
    items = []
    for s, a in WALL_CHIPS:
        w = K.measure(d, s, f_c)[0] // SS + pad_x * 2 + (15 if a else 0)
        items.append((s, a, w))
    rows, row, used = [], [], 0
    maxw = W - 400
    for s, a, w in items:
        if used + w + (gap if row else 0) > maxw:
            rows.append(row)
            row, used = [], 0
        row.append((s, a, w))
        used += w + (gap if len(row) > 1 else 0)
    if row:
        rows.append(row)
    y = 400
    for r_ in rows:
        total = sum(w for _, _, w in r_) + gap * (len(r_) - 1)
        x = (W - total) // 2
        for s, a, w in r_:
            chip(img, x, y, s, accent=a, fnt=f_c, pad_x=pad_x, pad_y=13,
                 dot=bool(a))
            x += w + gap
        y += 78
    y += 20
    img.paste(K.h_gradient(((W - 800) * SS, 2 * SS), VIOLET, CYAN),
              (400 * SS, y * SS))
    text(img, (W // 2, y + 30),
         "No ads. No accounts. Learning stays on your device.",
         font("Inter", 30, 560), fill=(225, 230, 242), anchor="ma")
    return finish(img, "08-chip-wall.png")


if __name__ == "__main__":
    import sys
    wanted = sys.argv[1:]  # e.g. python3 gen_tablet.py 01 07
    all_slides = {"01": s01_hero, "02": s02_bengali, "03": s03_offline,
                  "04": s04_ai, "05": s05_clipboard, "06": s06_tools,
                  "07": s07_themes, "08": s08_chip_wall}
    for key, fn in all_slides.items():
        if wanted and key not in wanted:
            continue
        try:
            print("wrote", fn())
        except FileNotFoundError as e:
            print(f"skip {key}: {e}")
