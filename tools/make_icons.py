"""Draws the legacy launcher bitmaps (for Android 7 and older launchers) from the same
geometry as the vector icon.  Run: uv run --with pillow python tools/make_icons.py"""
import os
from PIL import Image, ImageChops, ImageDraw

RES = os.path.join(os.path.dirname(__file__), "..", "android", "app", "src", "main", "res")
TOP, BOTTOM, MARK = (0x2E, 0x36, 0x90), (0x14, 0x18, 0x42), (0xFF, 0xE3, 0xA1)


def icon(px, round_):
    s = px * 4                                   # draw large, then shrink for smooth edges
    k = s / 72.0                                 # the visible middle 72 of the 108 viewport
    f = lambda v: (v - 18) * k
    bg = Image.new("RGB", (s, s))
    d = ImageDraw.Draw(bg)
    for y in range(s):
        t = min(1, max(0, (y / k + 18 - 10) / 88))
        d.line([(0, y), (s, y)], fill=tuple(round(a + (b - a) * t) for a, b in zip(TOP, BOTTOM)))
    outer, inner = Image.new("L", (s, s), 0), Image.new("L", (s, s), 0)
    ImageDraw.Draw(outer).ellipse([f(54 - 25), f(58 - 25), f(54 + 25), f(58 + 25)], fill=255)
    ImageDraw.Draw(inner).ellipse([f(54 - 19), f(50 - 19), f(54 + 19), f(50 + 19)], fill=255)
    mark = ImageChops.subtract(outer, inner)
    ImageDraw.Draw(mark).rounded_rectangle([f(50), f(25), f(58), f(58)], radius=4 * k, fill=255)
    bg.paste(Image.new("RGB", (s, s), MARK), (0, 0), mark)
    shape = Image.new("L", (s, s), 0)
    pad = s * 0.04
    if round_:
        ImageDraw.Draw(shape).ellipse([pad, pad, s - pad, s - pad], fill=255)
    else:
        ImageDraw.Draw(shape).rounded_rectangle([pad, pad, s - pad, s - pad], radius=s * 0.22, fill=255)
    out = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    out.paste(bg, (0, 0), shape)
    return out.resize((px, px), Image.LANCZOS)


for name, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
    d = os.path.join(RES, "mipmap-" + name)
    os.makedirs(d, exist_ok=True)
    icon(px, False).save(os.path.join(d, "ic_launcher.png"))
    icon(px, True).save(os.path.join(d, "ic_launcher_round.png"))
icon(256, False).save(os.path.join(os.path.dirname(__file__), "..", "logs", "icon-preview.png"))
