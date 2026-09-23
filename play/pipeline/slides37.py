"""Slides 03-07 for the WM Keyboard Play listing."""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

import wmkit as K
import gen
from gen import (brand_row, chip_img, chip_row, headline_two_tone,
                 keyboard_card, CAPS, OUT, STATUS_BAR)
from wmkit import (AMBER, BODY, CYAN, LIME, MAGENTA, SS, VIOLET, blob,
                   canvas, chip, font, gradient_text, grain, phone, save,
                   text, vignette)

AIRPLANE = Image.open(CAPS / "kb-airplane.png")
AI = Image.open(CAPS / "ai-panel.png")
CLIP = Image.open(CAPS / "clipboard-panel.png")
TOOLBOX = Image.open(CAPS / "toolbox-grid.png")
CALC = Image.open(CAPS / "tools-calculator.png")
TH_ALT = Image.open(CAPS / "theme-light-altbg.png")
EMOJI = Image.open(CAPS / "emoji-search.png")
DICT = Image.open(CAPS / "tools-dictionary.jpg")
SAKURA = Image.open(CAPS / "theme-sakura.jpg")

gen.KB_TOP.update({id(AI): 1212, id(CLIP): 1212, id(TOOLBOX): 1212,
                   id(CALC): 1212, id(TH_ALT): 1216, id(EMOJI): 850,
                   id(DICT): 1216, id(SAKURA): 1216})

W, H = 1080, 2400


def base(v1, v2, v3):
    img = canvas(W, H)
    blob(img, *v1, VIOLET, 60)
    blob(img, *v2, CYAN, 40)
    blob(img, *v3, MAGENTA, 32)
    brand_row(img, W // 2, 96)
    return img


def floating_chips(img, ob, specs):
    """specs: (text, accent, y, side) — side 'L' or 'R'."""
    rgba = img.convert("RGBA")
    f_c = font("Inter", 30, 560)
    for i, (s, a, y, side) in enumerate(specs):
        layer, w, h, m = chip_img(s, a, f_c, dot=a)
        rot = layer.rotate((-2.2, 2.2)[i % 2], expand=True,
                           resample=Image.BICUBIC)
        if side == "L":
            x0 = max(20, ob[0] + 48 - w)
        else:
            x0 = min(W - 20 - w, ob[2] - 48)
        rgba.alpha_composite(rot, ((x0 - m) * SS, (y - h // 2 - m) * SS))
    img.paste(rgba.convert("RGB"))


def fan_cards(img, shots_widths_angles_pos):
    glow_layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow_layer)
    gd.ellipse([80 * SS, 1150 * SS, 1000 * SS, 2350 * SS], fill=VIOLET + (70,))
    glow_layer = glow_layer.filter(ImageFilter.GaussianBlur(90 * SS))
    img.paste(Image.alpha_composite(img.convert("RGBA"), glow_layer).convert("RGB"))
    rgba = img.convert("RGBA")
    for shot, w, angle, (x, y) in shots_widths_angles_pos:
        card, cw, ch = keyboard_card(shot, w, radius=40)
        pad = 16
        framed = Image.new("RGBA", ((cw + pad * 2) * SS, (ch + pad * 2) * SS),
                           (0, 0, 0, 0))
        ImageDraw.Draw(framed).rounded_rectangle(
            [0, 0, framed.width - SS, framed.height - SS], 50 * SS,
            fill=(21, 26, 35, 255), outline=(255, 255, 255, 32), width=SS)
        framed.alpha_composite(card, (pad * SS, pad * SS))
        rot = framed.rotate(angle, expand=True, resample=Image.BICUBIC)
        rgba.alpha_composite(rot, (x * SS, y * SS))
    img.paste(rgba.convert("RGB"))


# ---------------------------------------------------------------- slide 03 --
def offline():
    img = base((140, 260, 420), (960, 1500, 440), (80, 2000, 360))
    f_h = font("Manrope", 84, 800)
    text(img, (W // 2, 208), "Everything here", f_h, anchor="ma")
    headline_two_tone(img, W // 2, 314, "runs ", "on your phone", "", size=84)
    text(img, (W // 2, 452), "Voice typing, AI and autocorrect in airplane mode.",
         font("Inter", 33, 480), fill=BODY, anchor="ma")

    pw = 640
    px = (W - pw) // 2
    py = 640
    # keep the status bar: the airplane icon is the proof
    ob = phone(img, AIRPLANE, px, py, pw, crop_top=0)

    # ring the airplane icon, label hangs below it inside the empty chat area
    scale = pw / 1080
    ix, iy = px + int(882 * scale), py + int(70 * scale)
    d = ImageDraw.Draw(img, "RGBA")
    d.ellipse([(ix - 34) * SS, (iy - 26) * SS, (ix + 34) * SS, (iy + 26) * SS],
              outline=CYAN + (230,), width=2 * SS)
    rgba = img.convert("RGBA")
    f_c = font("Inter", 30, 560)
    layer, w, h, m = chip_img("Airplane mode on", CYAN, f_c, dot=CYAN)
    ly = iy + 118
    rgba.alpha_composite(layer, ((ix + 34 - w - m) * SS, (ly - h // 2 - m) * SS))
    img.paste(rgba.convert("RGB"))
    d = ImageDraw.Draw(img, "RGBA")
    d.line([ix * SS, (iy + 28) * SS, ix * SS, (ly - h // 2 - 4) * SS],
           fill=CYAN + (200,), width=SS)

    floating_chips(img, ob, [
        ("Dictation without internet", LIME, 1450, "L"),
        ("AI without internet", VIOLET, 1650, "R"),
        ("Suggestions without internet", CYAN, 1850, "L"),
    ])

    f_b = font("Inter", 30, 540)
    chip_row(img, W // 2, ob[3] + 42,
             [("On-device", CYAN), ("No accounts", None),
              ("Private by default", VIOLET)], f_b)

    vignette(img)
    grain(img)
    return save(img, OUT / "03-offline.png")


# ---------------------------------------------------------------- slide 04 --
def ai():
    img = base((900, 240, 400), (140, 900, 420), (960, 1700, 360))
    f_h = font("Manrope", 84, 800)
    text(img, (W // 2, 208), "Rewrite, translate, fix", f_h, anchor="ma")
    headline_two_tone(img, W // 2, 314, "", "right on your phone", "", size=84)
    text(img, (W // 2, 452), "Small models run on the phone itself.",
         font("Inter", 33, 480), fill=BODY, anchor="ma")
    text(img, (W // 2, 502), "Cloud providers are yours to add, or ignore.",
         font("Inter", 33, 480), fill=BODY, anchor="ma")

    pw = 660
    px = (W - pw) // 2
    py = 700
    ob = phone(img, AI, px, py, pw, crop_top=STATUS_BAR)

    floating_chips(img, ob, [
        ("Pick your model", VIOLET, 1250, "L"),
        ("Works in airplane mode", LIME, 1450, "R"),
        ("Whisper voice typing", CYAN, 1650, "L"),
        ("Nothing saved by default", None, 1850, "R"),
    ])

    f_b = font("Inter", 30, 540)
    chip_row(img, W // 2, ob[3] + 42,
             [("Ask AI in any field", None), ("History stays local", VIOLET),
              ("Bring your own keys", None)], f_b)

    vignette(img)
    grain(img)
    return save(img, OUT / "04-ai.png")


# ---------------------------------------------------------------- slide 05 --
def clipboard():
    img = base((160, 240, 400), (940, 1200, 440), (120, 2000, 360))
    f_h = font("Manrope", 84, 800)
    text(img, (W // 2, 208), "A clipboard with", f_h, anchor="ma")
    headline_two_tone(img, W // 2, 314, "", "superpowers", "", size=84)
    text(img, (W // 2, 452), "History, pins, and codes copied for you.",
         font("Inter", 33, 480), fill=BODY, anchor="ma")

    pw = 660
    px = (W - pw) // 2
    py = 640
    ob = phone(img, CLIP, px, py, pw, crop_top=STATUS_BAR)

    floating_chips(img, ob, [
        ("OTP codes, caught from notifications", CYAN, 1330, "R"),
        ("Screenshots drop in too", MAGENTA, 1530, "L"),
        ("Pin what you reuse", VIOLET, 1730, "R"),
        ("Passwords never stored", LIME, 1930, "L"),
    ])

    f_b = font("Inter", 30, 540)
    chip_row(img, W // 2, ob[3] + 42,
             [("Search", None), ("Auto expiry", None), ("Dedup", None),
              ("Paste as image", CYAN)], f_b)

    vignette(img)
    grain(img)
    return save(img, OUT / "05-clipboard.png")


# ---------------------------------------------------------------- slide 06 --
def tools():
    img = base((140, 240, 400), (940, 1100, 440), (200, 2050, 380))
    f_h = font("Manrope", 84, 800)
    text(img, (W // 2, 208), "Stop switching apps", f_h, anchor="ma")
    headline_two_tone(img, W // 2, 322, "a toolbox ", "under one key",
                      "", size=68)
    text(img, (W // 2, 448), "Calculate, convert, scan and translate mid-sentence.",
         font("Inter", 33, 480), fill=BODY, anchor="ma")

    fan_cards(img, [
        (CALC, 560, -8, (10, 800)),
        (TOOLBOX, 600, 6, (420, 760)),
        (DICT, 640, -3, (200, 1250)),
    ])

    f_b = font("Inter", 30, 540)
    chip_row(img, W // 2, 2090,
             [("Calculator", AMBER), ("Converter", None), ("Scanner", None),
              ("Translator", None)], f_b)
    chip_row(img, W // 2, 2190,
             [("Snippets", None), ("GIFs", None), ("Grammar", LIME),
              ("Fancy text", None), ("And 50+ more", VIOLET)], f_b)

    vignette(img)
    grain(img)
    return save(img, OUT / "06-tools.png")


# ---------------------------------------------------------------- slide 07 --
def themes():
    img = base((180, 240, 420), (900, 1050, 440), (160, 2000, 380))
    f_h = font("Manrope", 84, 800)
    text(img, (W // 2, 208), "Make it", f_h, anchor="ma")
    headline_two_tone(img, W // 2, 314, "unmistakably ", "yours", "", size=84)
    text(img, (W // 2, 452), "Themes, icon packs, fonts and an emoji brain.",
         font("Inter", 33, 480), fill=BODY, anchor="ma")

    # draw order = z-order: sakura behind on the right, emoji panel on the
    # left above it, purple galaxy in front at the bottom
    fan_cards(img, [
        (SAKURA, 580, 6, (470, 780)),
        (EMOJI, 600, -8, (10, 760)),
        (gen.TH_PHOTO, 640, -3, (200, 1250)),
    ])

    f_b = font("Inter", 30, 540)
    chip_row(img, W // 2, 2090,
             [("Theme editor", VIOLET), ("Photo backgrounds", None),
              ("Icon packs", MAGENTA)], f_b)
    chip_row(img, W // 2, 2190,
             [("Key shapes", None), ("Fonts", None),
              ("Emoji search that gets you", CYAN)], f_b)

    vignette(img)
    grain(img)
    return save(img, OUT / "07-themes.png")


if __name__ == "__main__":
    for fn in (offline, ai, clipboard, tools, themes):
        print("wrote", fn())
