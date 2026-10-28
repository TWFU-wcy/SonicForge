
import math, os
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

OUT = r"D:\Project\SonicForge\docs\icon-concepts"
S, N = 4, 512
W = N*S

def lerp(a,b,t): return tuple(int(round(a[i]+(b[i]-a[i])*t)) for i in range(3))

def gradient(size, c1, c2, diagonal=True):
    y = np.linspace(0,1,size)[:,None]
    x = np.linspace(0,1,size)[None,:]
    t = np.clip((x*0.55 + y*0.75) if diagonal else y, 0, 1)
    arr = np.zeros((size,size,3), dtype=np.float64)
    for i in range(3):
        arr[...,i] = c1[i] + (c2[i]-c1[i])*t
    return Image.fromarray(arr.astype("uint8"))

def glow(base, color, center, radius, strength=0.5):
    a = np.asarray(base).astype(np.float64)
    yy, xx = np.mgrid[0:base.size[0], 0:base.size[1]]
    d = np.sqrt((xx-center[0])**2 + (yy-center[1])**2) / radius
    m = np.clip(1-d, 0, 1)**2.2 * strength
    for i in range(3):
        a[...,i] = a[...,i] + (color[i]-a[...,i])*m
    return Image.fromarray(np.clip(a,0,255).astype("uint8"))

def squircle_mask(size, ratio=0.225):
    m = Image.new("L",(size,size),0)
    ImageDraw.Draw(m).rounded_rectangle([0,0,size-1,size-1], radius=int(size*ratio), fill=255)
    return m

MASK = squircle_mask(W)

def finish(img, name):
    out = img.convert("RGBA"); out.putalpha(MASK)
    small = out.resize((N,N), Image.LANCZOS)
    small.save(os.path.join(OUT, name + ".png"))
    return small

# A. Forge Wave — clean 5-bar waveform, warm underglow
def a():
    img = gradient(W,(58,116,240),(126,74,232))
    img = glow(img,(255,140,60),(W*0.22,W*0.92),W*0.70,0.30)
    img = glow(img,(120,190,255),(W*0.85,W*0.08),W*0.55,0.35)
    layer = Image.new("RGBA",(W,W),(0,0,0,0))
    d = ImageDraw.Draw(layer)
    bars = [(0.265,0.30),(0.375,0.48),(0.485,0.62),(0.595,0.48),(0.705,0.30)]
    bw = W*0.082
    for cx,h in bars:
        x = W*cx - bw/2
        d.rounded_rectangle([x, W*0.5-W*h/2, x+bw, W*0.5+W*h/2], radius=bw/2, fill=(255,255,255,255))
    return finish(Image.alpha_composite(img.convert("RGBA"), layer), "01-forge-wave")

# B. Sonic Cone
def b():
    img = gradient(W,(9,15,30),(20,38,72))
    img = glow(img,(34,226,214),(W*0.28,W*0.52),W*0.62,0.45)
    layer = Image.new("RGBA",(W,W),(0,0,0,0))
    d = ImageDraw.Draw(layer); cy=W*0.5; cx=W*0.315
    d.ellipse([cx-W*0.050, cy-W*0.050, cx+W*0.050, cy+W*0.050], fill=(226,255,252,255))
    for r in (0.135,0.225,0.315):
        d.arc([cx-r*W,cy-r*W,cx+r*W,cy+r*W], start=-57, end=57, fill=(122,246,236,255), width=int(W*0.037))
    return finish(Image.alpha_composite(img.convert("RGBA"), layer), "02-sonic-cone")

# C. Molten Core
def c():
    img = gradient(W,(26,11,8),(62,23,11))
    img = glow(img,(255,110,30),(W*0.5,W*0.56),W*0.52,0.42)
    layer = Image.new("RGBA",(W,W),(0,0,0,0))
    d = ImageDraw.Draw(layer)
    d.arc([W*0.265,W*0.265,W*0.735,W*0.735], start=0, end=360, fill=(255,150,45,255), width=int(W*0.050))
    pts=[]
    for i in range(0,101):
        t=i/100
        x=W*(0.5-0.132)+t*W*0.264
        y=W*0.5-math.sin(t*math.pi*2.2)*W*0.058*math.sin(t*math.pi)
        pts.append((x,y))
    d.line(pts, fill=(255,242,226,255), width=int(W*0.028), joint="curve")
    for p in (pts[0],pts[-1]):
        d.ellipse([p[0]-W*0.020,p[1]-W*0.020,p[0]+W*0.020,p[1]+W*0.020], fill=(255,242,226,255))
    return finish(Image.alpha_composite(img.convert("RGBA"), layer), "03-molten-core")

# D. Gain Dial
def d():
    img = gradient(W,(14,22,38),(28,44,76))
    img = glow(img,(80,180,255),(W*0.5,W*0.42),W*0.55,0.30)
    layer = Image.new("RGBA",(W,W),(0,0,0,0))
    dr = ImageDraw.Draw(layer)
    cx, cy, R = W*0.5, W*0.52, W*0.245
    dr.arc([cx-R,cy-R,cx+R,cy+R], start=140, end=400, fill=(255,255,255,235), width=int(W*0.058))
    # progress part in cyan
    dr.arc([cx-R,cy-R,cx+R,cy+R], start=270, end=400, fill=(90,214,255,255), width=int(W*0.058))
    # ticks
    for ang in range(140, 401, 26):
        a=math.radians(ang)
        r1, r2 = R*1.28, R*1.42
        dr.line([(cx+math.cos(a)*r1, cy+math.sin(a)*r1),(cx+math.cos(a)*r2, cy+math.sin(a)*r2)],
                fill=(255,255,255,110), width=int(W*0.014))
    # needle
    a=math.radians(355)
    dr.line([(cx,cy),(cx+math.cos(a)*R*0.78, cy+math.sin(a)*R*0.78)], fill=(255,255,255,255), width=int(W*0.042))
    dr.ellipse([cx-W*0.038,cy-W*0.038,cx+W*0.038,cy+W*0.038], fill=(255,255,255,255))
    return finish(Image.alpha_composite(img.convert("RGBA"), layer), "04-gain-dial")

imgs=[a(),b(),c(),d()]

# contact sheet: large + small size legibility
pad=24; cell=280; smalls=[96,72,48,32,24]
sheet_w = pad + len(imgs)*(cell+pad)
sheet_h = pad + cell + 40 + 96 + pad
sheet = Image.new("RGBA",(sheet_w, sheet_h),(248,249,252,255))
sd = ImageDraw.Draw(sheet)
for i,im in enumerate(imgs):
    x = pad + i*(cell+pad)
    sheet.alpha_composite(im.resize((cell,cell), Image.LANCZOS),(x,pad))
    xx = x
    for sz in smalls:
        sheet.alpha_composite(im.resize((sz,sz), Image.LANCZOS),(xx, pad+cell+40))
        xx += sz + 8
sheet.save(os.path.join(OUT,"contact-sheet.png"))
print("ok")
