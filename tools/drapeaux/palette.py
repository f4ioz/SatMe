# SatMe — the flag atlas in 256 colours (called by genere-atlas.mjs): libimagequant's
# palette, but each pixel keeps its own opacity class. Inside a flag (opaque in the
# drawing) only opaque colours, outside only the transparent one: the quantizer alone
# would put see-through colours on the stripes' edges.
#   python3 -I tools/drapeaux/palette.py drapeaux.png
import sys
from PIL import Image

src = Image.open(sys.argv[1]).convert('RGBA')
q = src.quantize(256, method=Image.Quantize.LIBIMAGEQUANT)
pal = q.getpalette('RGBA')
n = len(pal) // 4
couleurs = [tuple(pal[4 * i:4 * i + 4]) for i in range(n)]
opaques = [i for i, c in enumerate(couleurs) if c[3] == 255]
vides = [i for i, c in enumerate(couleurs) if c[3] == 0]
if not vides:
    # A fully transparent entry is needed: the least used colour gives way.
    usage = q.histogram()[:n]
    k = min(range(n), key=lambda i: usage[i])
    couleurs[k] = (0, 0, 0, 0); vides = [k]
    opaques = [i for i in opaques if i != k]
cache = {}
def proche(rgb):
    if rgb not in cache:
        cache[rgb] = min(opaques, key=lambda i: sum((couleurs[i][j] - rgb[j]) ** 2 for j in range(3)))
    return cache[rgb]
px = q.load(); s = src.load()
w, h = src.size
for y in range(h):
    for x in range(w):
        r, g, b, a = s[x, y]
        i = px[x, y]
        if a == 255 and couleurs[i][3] != 255:
            px[x, y] = proche((r, g, b))
        elif a == 0 and couleurs[i][3] != 0:
            px[x, y] = vides[0]
q.putpalette([v for c in couleurs for v in c], 'RGBA')
q.save(sys.argv[1], optimize=True)
