
import os
RES = r"D:\Project\SonicForge\app\src\main\res"

CANVAS = 108.0
SAFE   = 72.0
GLYPH_OF_TILE = 0.6191
H = GLYPH_OF_TILE * SAFE
H_RATIOS = [0.4827, 0.7761, 1.0, 0.7761, 0.4827]
W = 0.1325 * H
G = 0.0449 * H

total = 5*W + 4*G
x0 = (CANVAS - total) / 2.0
r = W / 2.0

def rr(x, y, w, h, r):
    return (f"M{x+r:.3f},{y:.3f} H{x+w-r:.3f} A{r:.3f},{r:.3f} 0 0 1 {x+w:.3f},{y+r:.3f} "
            f"V{y+h-r:.3f} A{r:.3f},{r:.3f} 0 0 1 {x+w-r:.3f},{y+h:.3f} "
            f"H{x+r:.3f} A{r:.3f},{r:.3f} 0 0 1 {x:.3f},{y+h-r:.3f} "
            f"V{y+r:.3f} A{r:.3f},{r:.3f} 0 0 1 {x+r:.3f},{y:.3f} Z")

paths = []
for i, ratio in enumerate(H_RATIOS):
    h = ratio * H
    x = x0 + i*(W+G)
    y = (CANVAS - h)/2.0
    paths.append(rr(x, y, W, h, r))

body = "\n".join(
    '    <path\n        android:fillColor="#FFFFFFFF"\n        android:pathData="%s" />' % p
    for p in paths)

xml = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- SonicForge 图标单色层：5 根圆头音柱，按 72dp 安全区设计（最大柱高 {H:.2f}dp / 108dp 画布） -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
{body}
</vector>
'''
p = os.path.join(RES, "drawable", "ic_launcher_monochrome.xml")
open(p, "w", encoding="utf-8", newline="\n").write(xml)
print(f"H={H:.3f} W={W:.3f} G={G:.3f} x0={x0:.3f}")
