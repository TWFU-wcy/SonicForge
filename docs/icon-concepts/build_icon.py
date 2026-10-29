
import os
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

# ---------------------------------------------------------------
# 几何：全部相对「图形自身最大柱高」的比例，来自选定稿 01-forge-wave
# ---------------------------------------------------------------
H_RATIOS = [0.4827, 0.7761, 1.0, 0.7761, 0.4827]  # 5 根柱高
W_RATIO  = 0.1325                                  # 柱宽 / 最大柱高
G_RATIO  = 0.0449                                  # 间距 / 最大柱高

# 选定稿里图形最大柱高占整块图标的 61.9%
GLYPH_OF_TILE = 0.6191

# 自适应图标：108dp 画布，系统只显示中间 72dp，所以按 72dp 设计再换算
SAFE_DP   = 72.0
CANVAS_DP = 108.0
GLYPH_FRAC_ADAPTIVE = GLYPH_OF_TILE * SAFE_DP / CANVAS_DP   # 0.4127
GLYPH_FRAC_LEGACY   = GLYPH_OF_TILE                          # 0.6191


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


def draw_bars(img, glyph_frac, ss=8):
    """glyph_frac = 最大柱高占画布边长的比例"""
    size = img.size[0]
    S = size * ss
    layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    H = glyph_frac * S
    w = W_RATIO * H
    gap = G_RATIO * H
    total = 5 * w + 4 * gap
    x0 = (S - total) / 2
    cy = S / 2
    for i, r in enumerate(H_RATIOS):
        h = r * H
        x = x0 + i * (w + gap)
        d.rounded_rectangle([x, cy - h / 2, x + w, cy + h / 2],
                            radius=w / 2, fill=(255, 255, 255, 255))
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

# ---- 自适应图标图层（108dp 画布，图形按 72dp 安全区设计）----
for dpi, scale in DENSITIES.items():
    px = int(round(CANVAS_DP * scale))
    folder = os.path.join(RES, "mipmap-" + dpi)
    os.makedirs(folder, exist_ok=True)

    bg = background(px)
    p = os.path.join(folder, "ic_launcher_background.png")
    bg.save(p); written.append(p)

    fg = draw_bars(Image.new("RGBA", (px, px), (0, 0, 0, 0)), GLYPH_FRAC_ADAPTIVE)
    p = os.path.join(folder, "ic_launcher_foreground.png")
    fg.save(p); written.append(p)

# ---- 老式图标（整块即图形，按选定稿比例）----
for dpi, scale in DENSITIES.items():
    px = int(round(48 * scale))
    master = draw_bars(background(px * 2).resize((px * 2, px * 2), Image.LANCZOS), GLYPH_FRAC_LEGACY)
    sq = master.copy(); sq.putalpha(squircle_alpha(px * 2))
    sq = sq.resize((px, px), Image.LANCZOS)
    p = os.path.join(RES, "mipmap-" + dpi, "ic_launcher.png")
    sq.save(p); written.append(p)

    rd = master.copy(); rd.putalpha(circle_alpha(px * 2))
    rd = rd.resize((px, px), Image.LANCZOS)
    p = os.path.join(RES, "mipmap-" + dpi, "ic_launcher_round.png")
    rd.save(p); written.append(p)

# ---- 商店 / 文档母版 ----
draw_bars(background(1024), GLYPH_FRAC_LEGACY).resize((512, 512), Image.LANCZOS).convert("RGB") \
    .save(os.path.join(OUT, "app-icon-512.png"))
written.append(os.path.join(OUT, "app-icon-512.png"))
sq = draw_bars(background(1024), GLYPH_FRAC_LEGACY)
sq.putalpha(squircle_alpha(1024))
sq.resize((512, 512), Image.LANCZOS).save(os.path.join(OUT, "app-icon-512-rounded.png"))
written.append(os.path.join(OUT, "app-icon-512-rounded.png"))

# ---- 预览：按系统遮罩裁切，圆形遮罩只露出中间 72dp ----
def masked(src, size, mask):
    im = src.resize((size, size), Image.LANCZOS).copy()
    im.putalpha(mask.resize((size, size), Image.LANCZOS))
    return im

prev = 220
canvas = Image.new("RGBA", (5 * prev + 60, prev + 60), (246, 247, 250, 255))
adaptive = Image.alpha_composite(background(1080).convert("RGBA"),
                                 draw_bars(Image.new("RGBA", (1080, 1080), (0, 0, 0, 0)), GLYPH_FRAC_ADAPTIVE))
# 系统遮罩直径 = 画布的 72/108
m72 = int(1080 * SAFE_DP / CANVAS_DP)
def inset_mask(shape):
    m = Image.new("L", (1080, 1080), 0)
    off = (1080 - m72) // 2
    if shape == "circle":
        ImageDraw.Draw(m).ellipse([off, off, off + m72, off + m72], fill=255)
    else:
        ImageDraw.Draw(m).rounded_rectangle([off, off, off + m72, off + m72],
                                            radius=int(m72 * 0.22), fill=255)
    return m
x = 30
for shape in ("circle", "squircle"):
    canvas.alpha_composite(masked(adaptive, prev, inset_mask(shape)), (x, 20))
    x += prev + 10
for sz in (96, 64, 48, 32, 24):
    canvas.alpha_composite(masked(adaptive, sz, inset_mask("circle")), (x, 20))
    x += sz + 8
canvas.save(os.path.join(OUT, "adaptive-preview.png"))

print("count", len(written))
print("glyph height on 108dp canvas: %.2f dp (%.1f%% of the 72dp visible area)"
      % (GLYPH_FRAC_ADAPTIVE * 108, GLYPH_OF_TILE * 100))
