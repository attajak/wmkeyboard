"""Generate the four priority Play Store graphics for WM Keyboard. v3

Sources are the real device captures in play/screencaps/ (1080x2400).
No language counts, no em dashes, larger type throughout.
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

import wmkit as K
from wmkit import (AMBER, BODY, CYAN, LIME, MAGENTA, SS, VIOLET, WHITE,
                   blob, canvas, chip, font, gradient_text, grain, phone,
                   rrect, save, text, vignette)

# Staged copies first (Claude's cloud workspace), then the repo itself, so the
# same file runs in both places without edits. Same pattern as gen_tablet.py.
HERE = Path(__file__).resolve().parent
CAPS = next(p for p in (Path("/mnt/user-data/uploads/WMKeyboard/play/screencaps"),
                        HERE.parent / "screencaps") if p.is_dir())
LOGO = next(p for p in (Path("/mnt/user-data/uploads/WMKeyboard/docs/src/assets/logo-mark.png"),
                        HERE.parents[1] / "docs/src/assets/logo-mark.png") if p.is_file())
OUT = Path(__file__).parent / "out"
OUT.mkdir(exist_ok=True)

ENGLISH = Image.open(CAPS / "kb-english-dark.png")
BENGALI = Image.open(CAPS / "kb-bengali-typing.png")
TH_PHOTO = Image.open(CAPS / "theme-photo.png")
TH_LIGHT = Image.open(CAPS / "theme-light.png")

# Measured first row of the keyboard panel per capture (nothing gets cut).
KB_TOP = {id(ENGLISH): 1216, id(BENGALI): 1216, id(TH_PHOTO): 1194,
          id(TH_LIGHT): 1216}
STATUS_BAR = 140  # cropped off every full-phone composite


def brand_row(img, cx, y, size=58):
    logo = Image.open(LOGO).convert("RGBA")
    logo = logo.resize((size * SS, size * SS), Image.LANCZOS)
    f = font("Inter", 32, 640)
    d = ImageDraw.Draw(img)
    tw = K.measure(d, "WM Keyboard", f)[0] // SS
    total = size + 20 + tw
    x0 = cx - total // 2
    img.paste(logo, (x0 * SS, y * SS), logo)
    text(img, (x0 + size + 20, y + size // 2), "WM Keyboard", f,
         fill=(225, 230, 242), anchor="lm")


def headline_two_tone(img, cx, y, pre, accent, post, size=86):
    f = font("Manrope", size, 800)
    d = ImageDraw.Draw(img)
    w_pre = K.measure(d, pre, f)[0] // SS if pre else 0
    w_acc = K.measure(d, accent, f)[0] // SS
    w_post = K.measure(d, post, f)[0] // SS if post else 0
    x = cx - (w_pre + w_acc + w_post) // 2
    if pre:
        text(img, (x, y), pre, f)
        x += w_pre
    gradient_text(img, (x, y), accent, f, VIOLET, CYAN, glow=6)
    x += w_acc
    if post:
        text(img, (x, y), post, f)


def chip_img(s, accent=None, fnt=None, pad_x=26, pad_y=15, dot=None):
    fnt = fnt or font("Inter", 30, 560)
    probe = ImageDraw.Draw(Image.new("RGB", (8, 8)))
    tw, th = K.measure(probe, s, fnt)
    tw, th = tw // SS, th // SS
    dot_w = 18 if dot else 0
    w, h = tw + pad_x * 2 + dot_w, th + pad_y * 2
    m = 12
    layer = Image.new("RGBA", ((w + m * 2) * SS, (h + m * 2) * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    sh = Image.new("RGBA", layer.size, (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle(
        [(m + 2) * SS, (m + 6) * SS, (m + w + 2) * SS, (m + h + 6) * SS],
        (h // 2) * SS, fill=(0, 0, 0, 150))
    layer.alpha_composite(sh.filter(ImageFilter.GaussianBlur(8 * SS)))
    d.rounded_rectangle([m * SS, m * SS, (m + w) * SS, (m + h) * SS],
                        (h // 2) * SS, fill=(24, 30, 42, 235),
                        outline=(accent + (140,)) if accent else (255, 255, 255, 40),
                        width=SS)
    tx = m + pad_x + dot_w
    if dot:
        cy = m + h / 2
        d.ellipse([(m + pad_x - 2) * SS, (cy - 4) * SS,
                   (m + pad_x + 7) * SS, (cy + 5) * SS], fill=dot + (255,))
    d.text((tx * SS, (m + h / 2) * SS), s, font=fnt,
           fill=(accent if accent else (226, 232, 244)), anchor="lm")
    return layer, w, h, m


def keyboard_card(shot, w, radius=44, outline=True):
    kb = shot.crop((0, KB_TOP[id(shot)], shot.width, shot.height))
    ch = int(w * kb.height / kb.width)
    kbr = kb.resize((w * SS, ch * SS), Image.LANCZOS)
    mask = Image.new("L", kbr.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, kbr.width, kbr.height],
                                           radius * SS, fill=255)
    card = Image.new("RGBA", kbr.size, (0, 0, 0, 0))
    card.paste(kbr, (0, 0), mask)
    if outline:
        ImageDraw.Draw(card).rounded_rectangle(
            [0, 0, kbr.width - SS, kbr.height - SS], radius * SS,
            outline=(255, 255, 255, 34), width=SS)
    return card, w, ch


def chip_row(img, cx, y, labels, fnt):
    d = ImageDraw.Draw(img, "RGBA")
    widths = [K.measure(d, s, fnt)[0] // SS + 52 + (18 if a else 0)
              for s, a in labels]
    total = sum(widths) + 16 * (len(labels) - 1)
    x = cx - total // 2
    for (s, a), w in zip(labels, widths):
        chip(img, x, y, s, accent=a, fnt=fnt, dot=bool(a), pad_x=26, pad_y=15)
        x += w + 16


# ================================================================== HERO ==
def hero():
    W, H = 1080, 2400
    img = canvas(W, H)
    blob(img, 140, 260, 420, VIOLET, 70)
    blob(img, 980, 1450, 460, CYAN, 40)
    blob(img, 60, 1950, 380, MAGENTA, 30)

    brand_row(img, W // 2, 96)
    f_h = font("Manrope", 86, 800)
    text(img, (W // 2, 204), "A keyboard that", f_h, anchor="ma")
    headline_two_tone(img, W // 2, 312, "does ", "so much more", "", size=86)
    text(img, (W // 2, 452), "Every chip below is a real feature.",
         font("Inter", 34, 480), fill=BODY, anchor="ma")

    pw = 620
    px = (W - pw) // 2
    py = 690
    ob = phone(img, ENGLISH, px, py, pw, crop_top=STATUS_BAR)

    rgba = img.convert("RGBA")
    f_c = font("Inter", 30, 560)
    left = [
        ("Works before unlock", None, 810),
        ("60+ toolbox tools", VIOLET, 960),
        ("Optional number row", None, 1110),
        ("Glide in any language", CYAN, 1260),
        ("Typo-model key nudge", None, 1410),
        ("Double-tap caps lock", None, 1560),
        ("Semantic emoji search", MAGENTA, 1710),
        ("Two symbol pages", None, 1860),
    ]
    right = [
        ("Material You theming", None, 880),
        ("Clipboard history & pins", CYAN, 1030),
        ("Per-app modes", VIOLET, 1180),
        ("Long-press accents", None, 1330),
        ("Word-swipe delete", None, 1480),
        ("Spacebar is a trackpad", LIME, 1630),
        ("Swipe space for language", None, 1780),
        ("Enter adapts to the app", AMBER, 1930),
    ]
    for i, (s, a, y) in enumerate(left):
        layer, w, h, m = chip_img(s, a, f_c, dot=a)
        rot = layer.rotate((-2.2, 2.2)[i % 2], expand=True,
                           resample=Image.BICUBIC)
        tuck = 56 if y < 1300 else 24  # keep the keyboard rows visible
        x0 = max(20, ob[0] + tuck - w)
        rgba.alpha_composite(rot, ((x0 - m) * SS, (y - h // 2 - m) * SS))
    for i, (s, a, y) in enumerate(right):
        layer, w, h, m = chip_img(s, a, f_c, dot=a)
        rot = layer.rotate((2.2, -2.2)[i % 2], expand=True,
                           resample=Image.BICUBIC)
        tuck = 56 if y < 1300 else 24
        x0 = min(W - 20 - w, ob[2] - tuck)
        rgba.alpha_composite(rot, ((x0 - m) * SS, (y - h // 2 - m) * SS))
    img.paste(rgba.convert("RGB"))

    f_b = font("Inter", 30, 540)
    chip_row(img, W // 2, ob[3] + 40,
             [("One-handed", None), ("Split", None), ("Floating", None),
              ("Offline first", CYAN)], f_b)
    text(img, (W // 2, ob[3] + 140), "No accounts. No ads. Learning stays on your phone.",
         font("Inter", 32, 520), fill=BODY, anchor="ma")

    vignette(img)
    grain(img)
    return save(img, OUT / "01-hero.png")


# =============================================================== BENGALI ==
def bengali():
    W, H = 1080, 2400
    img = canvas(W, H)
    blob(img, 900, 220, 400, VIOLET, 60)
    blob(img, 120, 800, 420, CYAN, 45)
    blob(img, 980, 1500, 380, MAGENTA, 35)

    brand_row(img, W // 2, 96)
    d = ImageDraw.Draw(img, "RGBA")

    headline_two_tone(img, W // 2, 206, "Type it how it ", "sounds", "", size=80)
    text(img, (W // 2, 330), "Avro phonetic Bangla, fully offline.",
         font("Inter", 34, 480), fill=BODY, anchor="ma")

    f_mono = font("SpaceGrotesk", 62, 500)
    latin = "ami valo achi"
    tw = K.measure(d, latin, f_mono)[0] // SS
    pw_, ph_ = tw + 130, 140
    px_, py_ = (W - pw_) // 2, 440
    rrect(img, (px_, py_, px_ + pw_, py_ + ph_), 34, fill=(255, 255, 255, 14),
          outline=(255, 255, 255, 34), width=1)
    text(img, (W // 2 - 15, py_ + ph_ // 2), latin, f_mono,
         fill=(210, 218, 233), anchor="mm")
    cx = W // 2 - 15 + tw // 2 + 12
    d.rounded_rectangle([cx * SS, (py_ + 36) * SS, (cx + 4) * SS,
                         (py_ + ph_ - 36) * SS], 2 * SS, fill=CYAN + (255,))

    gradient_text(img, (W // 2, 616), "↓", font("Inter", 58, 700),
                  VIOLET, CYAN, anchor="ma", glow=4)

    f_bn = font("NotoSansBengali", 132, 700)
    gradient_text(img, (W // 2, 712), "আমি ভালো আছি", f_bn, VIOLET, CYAN,
                  anchor="ma", glow=10)

    text(img, (W // 2, 986), "Spell it loosely: asi, achi, achhi",
         font("Inter", 34, 480), fill=BODY, anchor="ma")
    text(img, (W // 2, 1038), "all reach the word you meant.",
         font("Inter", 34, 480), fill=BODY, anchor="ma")

    f_c = font("Inter", 30, 540)
    chip_row(img, W // 2, 1130,
             [("Bangla and English together", CYAN), ("Probhat", None),
              ("Conjunct-aware delete", VIOLET)], f_c)

    # full phone with the real chat, suggestions and Bengali keyboard
    pw = 720
    px = (W - pw) // 2
    py = 1290
    phone(img, BENGALI, px, py, pw, crop_top=STATUS_BAR)

    vignette(img)
    grain(img)
    return save(img, OUT / "02-bengali.png")


# ========================================================== FEATURE GRAPHIC ==
def feature_graphic():
    W, H = 2048, 1000
    img = canvas(W, H)
    blob(img, 240, 250, 520, VIOLET, 75)
    blob(img, 1750, 850, 520, CYAN, 55)
    blob(img, 1500, 60, 300, MAGENTA, 30)

    def framed_card(shot, w, angle):
        card, cw, ch = keyboard_card(shot, w, radius=40, outline=False)
        pad = 18
        base = Image.new("RGBA", ((cw + pad * 2) * SS, (ch + pad * 2) * SS),
                         (0, 0, 0, 0))
        ImageDraw.Draw(base).rounded_rectangle(
            [0, 0, base.width - SS, base.height - SS], 52 * SS,
            fill=(21, 26, 35, 255), outline=(255, 255, 255, 32), width=SS)
        base.alpha_composite(card, (pad * SS, pad * SS))
        return base.rotate(angle, expand=True, resample=Image.BICUBIC)

    glow_layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow_layer)
    gd.ellipse([1150 * SS, 150 * SS, 2000 * SS, 900 * SS], fill=VIOLET + (60,))
    glow_layer = glow_layer.filter(ImageFilter.GaussianBlur(90 * SS))
    img.paste(Image.alpha_composite(img.convert("RGBA"), glow_layer).convert("RGB"))

    rgba = img.convert("RGBA")
    rgba.alpha_composite(framed_card(TH_LIGHT, 620, -9), (1080 * SS, 220 * SS))
    rgba.alpha_composite(framed_card(TH_PHOTO, 660, 6), (1400 * SS, 260 * SS))
    logo = Image.open(LOGO).convert("RGBA").resize((150 * SS, 150 * SS),
                                                   Image.LANCZOS)
    rgba.alpha_composite(logo, (130 * SS, 160 * SS))
    img.paste(rgba.convert("RGB"))

    text(img, (130, 370), "WM Keyboard", font("Manrope", 122, 800))
    gradient_text(img, (132, 546), "Private. Offline-first. Yours.",
                  font("Manrope", 60, 640), VIOLET, CYAN)

    f_c = font("Inter", 38, 540)
    x = 132
    d = ImageDraw.Draw(img, "RGBA")
    for s, a in [("No ads", LIME), ("On-device AI", CYAN),
                 ("Bangla-first", MAGENTA)]:
        w, _ = chip(img, x, 668, s, accent=a, fnt=f_c, dot=True,
                    pad_x=28, pad_y=17)
        x += w + 20

    vignette(img, 90)
    grain(img)
    return save(img, OUT / "feature-graphic.png", final_size=(1024, 500))


# ============================================================== CHIP WALL ==
CHIPS = [
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


def chip_wall():
    W, H = 1080, 2400
    img = canvas(W, H)
    blob(img, 160, 200, 380, VIOLET, 55)
    blob(img, 950, 1100, 420, CYAN, 35)
    blob(img, 120, 2100, 400, MAGENTA, 35)

    brand_row(img, W // 2, 96)
    text(img, (W // 2, 196), "THE PART NOBODY EXPECTS", font("Inter", 30, 640),
         fill=CYAN, anchor="ma")
    headline_two_tone(img, W // 2, 244, "It does ", "that", ", too.", size=86)
    text(img, (W // 2, 382), "A sample of what ships inside, all real.",
         font("Inter", 34, 480), fill=BODY, anchor="ma")
    text(img, (W // 2, 434), "Every network feature is documented.",
         font("Inter", 34, 480), fill=BODY, anchor="ma")

    d = ImageDraw.Draw(img, "RGBA")
    f_c = font("Inter", 28, 540)
    pad_x, gap = 22, 13
    items = []
    for s, a in CHIPS:
        w = K.measure(d, s, f_c)[0] // SS + pad_x * 2 + (15 if a else 0)
        items.append((s, a, w))
    rows, row, used = [], [], 0
    maxw = W - 64
    for s, a, w in items:
        if used + w + (gap if row else 0) > maxw:
            rows.append(row)
            row, used = [], 0
        row.append((s, a, w))
        used += w + (gap if len(row) > 1 else 0)
    if row:
        rows.append(row)

    y = 520
    for row in rows:
        total = sum(w for _, _, w in row) + gap * (len(row) - 1)
        x = (W - total) // 2
        for s, a, w in row:
            chip(img, x, y, s, accent=a, fnt=f_c, pad_x=pad_x, pad_y=15,
                 dot=bool(a))
            x += w + gap
        y += 86

    y += 36
    grad_line = K.h_gradient(((W - 200) * SS, 2 * SS), VIOLET, CYAN)
    img.paste(grad_line, (100 * SS, y * SS))
    text(img, (W // 2, y + 40),
         "No ads. No accounts. Learning stays on your phone.",
         font("Inter", 32, 560), fill=(225, 230, 242), anchor="ma")

    vignette(img)
    grain(img)
    print("chip rows:", len(rows), "content ends at", y + 90)
    return save(img, OUT / "08-chip-wall.png")


if __name__ == "__main__":
    for fn in (hero, bengali, feature_graphic, chip_wall):
        print("wrote", fn())
