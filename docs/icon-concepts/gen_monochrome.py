
import os
RES = r"D:\Project\SonicForge\app\src\main\res"

ZOOM = 1.08
BAR_W, GAP = 7.2*ZOOM, 4.5*ZOOM
HEIGHTS = [h*ZOOM for h in [33.0, 46.8, 60.0, 46.8, 33.0]]
total = 5*BAR_W + 4*GAP
x0 = (108 - total) / 2.0
r = BAR_W / 2.0

def rr(x, y, w, h, r):
    return (f"M{x+r:.2f},{y:.2f} H{x+w-r:.2f} A{r:.2f},{r:.2f} 0 0 1 {x+w:.2f},{y+r:.2f} "
            f"V{y+h-r:.2f} A{r:.2f},{r:.2f} 0 0 1 {x+w-r:.2f},{y+h:.2f} "
            f"H{x+r:.2f} A{r:.2f},{r:.2f} 0 0 1 {x:.2f},{y+h-r:.2f} "
            f"V{y+r:.2f} A{r:.2f},{r:.2f} 0 0 1 {x+r:.2f},{y:.2f} Z")

paths = []
for i, h in enumerate(HEIGHTS):
    x = x0 + i*(BAR_W+GAP)
    y = (108 - h)/2.0
    paths.append(rr(x, y, BAR_W, h, r))

body = "\n".join(
    '    <path\n        android:fillColor="#FFFFFFFF"\n        android:pathData="%s" />' % p
    for p in paths)

xml = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- SonicForge 图标前景：5 根圆头音柱（均衡波形），居中于 108dp 画布的安全区内 -->
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
print(xml)
