
import os, math
import numpy as np
from PIL import Image, ImageDraw

ROOT = r"D:\Project\SonicForge"
RES  = os.path.join(ROOT, "app", "src", "main", "res")
OUT  = os.path.join(ROOT, "docs", "icon-concepts")
os.makedirs(OUT, exist_ok=True)

C1 = (58, 116, 240)    # #3A74F0
C2 = (126, 74, 232)    # #7E4AE8
WARM = (255, 140, 60)
COOL = (120, 190, 255)

def background(size):
    y = np.linspace(0, 1, size)[:, None]
    x = np.linspace(0, 1, size)[None, :]
    t = np.clip(x * 0.55 + y * 0.75, 0, 1)
    arr = np.zeros((size, size, 3), dtype=np.float64)
    for i in range(3):
        arr[..., i] = C1[i] + (C2[i] - C1[i]) * t
    yy, xx = np.mgrid[0:size, 0:size]
    def add(center, radius, color, strength, power=2.2):
        d = np.sqrt((xx - center[0]) ** 2 + (yy - center[1]) ** 2) / radius
        m = (np.clip(1 - d, 0, 1) ** power) * strength
        for i in range(3):
            arr[..., i] = arr[..., i] + (color[i] - arr[..., i]) * m
    add((size * 0.22, size * 0.92), size * 0.70, WARM, 0.30)
    add((size * 0.85, size * 0.08), size * 0.55, COOL, 0.35)
    return Image.fromarray(np.clip(arr, 0, 255).astype("uint8"))

# bars defined in 108dp space -> fractions of canvas
BAR_W   = 7.2 / 108.0
BAR_GAP = 4.5 / 108.0
HEIGHTS = [33.0, 46.8, 60.0, 46.8, 33.0]  # dp
MAX_H   = 60.0

def draw_bars(img, ss=8, zoom=1.0):
    size = img.size[0]
    layer = Image.new("RGBA", (size * ss, size * ss), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    S = size * ss
    w = BAR_W * S * zoom
    gap = BAR_GAP * S * zoom
    total = 5 * w + 4 * gap
    x0 = (S - total) / 2
    cy = S / 2
    for i, hdp in enumerate(HEIGHTS):
        h = (hdp / 108.0) * S * zoom
        x = x0 + i * (w + gap)
        d.rounded_rectangle([x, cy - h / 2, x + w, cy + h / 2], radius=w / 2, fill=(255, 255, 255, 255))
    layer = layer.resize((size, size), Image.LANCZOS)
    return Image.alpha_composite(img.convert("RGBA"), layer)

def squircle_alpha(size, ratio=0.225):
    ss = 4
    m = Image.new("L", (size * ss, size * ss), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size * ss - 1, size * ss - 1],
                                        radius=int(size * ss * ratio), fill=255)
    return m.resize((size, size), Image.LANCZOS)

def circle_alpha(size):
    ss = 4
    m = Image.new("L", (size * ss, size * ss), 0)
    ImageDraw.Draw(m).ellipse([0, 0, size * ss - 1, size * ss - 1], fill=255)
    return m.resize((size, size), Image.LANCZOS)

DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
written = []

# ---- adaptive icon layers (108dp canvas) ----
for dpi, scale in DENSITIES.items():
    px = int(round(108 * scale))
    folder = os.path.join(RES, "mipmap-" + dpi)
    os.makedirs(folder, exist_ok=True)

    bg = background(px)
    p = os.path.join(folder, "ic_launcher_background.png")
    bg.save(p); written.append(p)

    fg = draw_bars(Image.new("RGBA", (px, px), (0, 0, 0, 0)), zoom=1.08)
    p = os.path.join(folder, "ic_launcher_foreground.png")
    fg.save(p); written.append(p)

# ---- legacy launcher icons ----
for dpi, scale in DENSITIES.items():
    px = int(round(48 * scale))
    master = draw_bars(background(px * 2).resize((px * 2, px * 2), Image.LANCZOS), zoom=1.36)
    sq = master.copy(); sq.putalpha(squircle_alpha(px * 2))
    sq = sq.resize((px, px), Image.LANCZOS)
    p = os.path.join(RES, "mipmap-" + dpi, "ic_launcher.png")
    sq.save(p); written.append(p)

    rd = master.copy(); rd.putalpha(circle_alpha(px * 2))
    rd = rd.resize((px, px), Image.LANCZOS)
    p = os.path.join(RES, "mipmap-" + dpi, "ic_launcher_round.png")
    rd.save(p); written.append(p)

# ---- Play Store / docs master ----
master = draw_bars(background(1024), zoom=1.36).resize((512, 512), Image.LANCZOS).convert("RGB")
p = os.path.join(OUT, "app-icon-512.png"); master.save(p); written.append(p)
sq = draw_bars(background(1024), zoom=1.36)
sq.putalpha(squircle_alpha(1024))
sq.resize((512, 512), Image.LANCZOS).save(os.path.join(OUT, "app-icon-512-rounded.png"))
written.append(os.path.join(OUT, "app-icon-512-rounded.png"))

# ---- preview: adaptive icon under launcher masks ----
def masked(src, size, mask):
    im = src.resize((size, size), Image.LANCZOS).copy()
    im.putalpha(mask.resize((size, size), Image.LANCZOS))
    return im

prev_size = 220
canvas = Image.new("RGBA", (5 * prev_size + 60, prev_size + 60), (246, 247, 250, 255))
bg_big = background(1080); fg_big = draw_bars(Image.new("RGBA", (1080, 1080), (0, 0, 0, 0)), zoom=1.08)
adaptive = Image.alpha_composite(bg_big.convert("RGBA"), fg_big)
masks = [
    ("圆形", circle_alpha(1080)),
    ("圆角方", squircle_alpha(1080, 0.20)),
    ("方形", Image.new("L", (1080, 1080), 255)),
]
x = 30
for name, m in masks:
    canvas.alpha_composite(masked(adaptive, prev_size, m.resize((prev_size, prev_size), Image.LANCZOS)), (x, 20))
    x += prev_size + 10
for sz in (96, 64, 48, 32):
    canvas.alpha_composite(masked(adaptive, sz, squircle_alpha(sz, 0.22)), (x, 20))
    x += sz + 8
canvas.save(os.path.join(OUT, "adaptive-preview.png"))

print("\n".join(written))
print("count", len(written))
