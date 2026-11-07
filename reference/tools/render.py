"""Draw an elevation and two cross-sections of a .mcstructure to a PNG (stdlib only)."""
import sys, os, zlib, struct, hashlib
sys.path.insert(0, os.path.dirname(__file__))
import mcs

S = int(os.environ.get("S", "11"))  # pixels per block
ONLY = os.environ.get("P")  # e.g. "1" for just the z cross-section
BASE = 3

GROUND = {"dirt", "grass_block", "sand", "red_sand", "snow", "podzol", "gravel", "stone", "coarse_dirt", "mud", "moss_block", "dirt_with_roots", "packed_ice", "ice", "blue_ice"}


def hsh(n, a, b):
    h = int(hashlib.md5(n.encode()).hexdigest()[:4], 16) / 65535.0
    return int(a + (b - a) * h)


def colour(n):
    if n == "water": return (60, 110, 200)
    if "glass" in n or n.endswith("pane"): return (150, 215, 235)
    if "leaves" in n or "azalea" in n or "bush" in n: return (60, 140, 60)
    for k, c in (("dark_oak", (70, 45, 25)), ("spruce", (105, 75, 45)), ("birch", (215, 200, 150)), ("acacia", (190, 100, 55)), ("cherry", (240, 175, 190)),
                 ("pale_oak", (225, 220, 210)), ("jungle", (160, 115, 75)), ("mangrove", (130, 50, 50)), ("bamboo", (200, 190, 70)), ("oak", (175, 140, 80))):
        if k in n and any(t in n for t in ("plank", "log", "wood", "stairs", "slab", "fence", "door", "stem", "trapdoor", "shelf", "button")):
            r, g, b = c
            d = hsh(n, -12, 12)
            return (r + d, g + d, b + d)
    if "snow" in n or "quartz" in n or "white" in n or "diorite" in n: return (240, 240, 245)
    if "red_sand" in n or "terracotta" in n or "clay" in n or "red_nether" in n: return (180, 85, 55)
    if "sand" in n: return (225, 210, 150)
    if "brick" in n or "stone" in n or "deepslate" in n or "tuff" in n or "andesite" in n or "cobble" in n or "tile" in n or "granite" in n: return (125 + hsh(n, -15, 15),) * 3
    if "dirt" in n or "grass" in n or "mud" in n or "moss" in n: return (110, 150, 80) if "grass" in n or "moss" in n else (120, 85, 55)
    if "wool" in n or "carpet" in n or "concrete" in n or "terracotta" in n:
        return (hsh(n, 60, 220), hsh(n[::-1], 60, 220), hsh(n + "x", 60, 220))
    return (hsh(n, 90, 230), hsh(n + "a", 60, 200), hsh(n + "b", 90, 230))


def main(path, out):
    root = mcs.load(path)
    sx, sy, sz = root["size"]
    st = root["structure"]
    entries = st["palette"]["default"]["block_palette"]
    pal = [e["name"].replace("minecraft:", "") for e in entries]
    sta = [e.get("states", {}) for e in entries]
    l0 = st["block_indices"][0]

    def get(x, y, z):
        p = l0[(x * sy + y) * sz + z]
        return None if p < 0 or pal[p] == "air" else p

    cells = [(x, y, z) for x in range(sx) for y in range(BASE, sy) for z in range(sz) if (p := get(x, y, z)) is not None and pal[p] not in GROUND and pal[p] != "water"]
    ytop = max(c[1] for c in cells)
    xs = [c[0] for c in cells]; zs = [c[2] for c in cells]
    cx = (min(xs) + max(xs)) // 2
    cz = (min(zs) + max(zs)) // 2
    rows = ytop + 1  # show from y=0

    panels = []
    # elevation looking along z, nearest block from max z down
    panels.append(("elevation z", sx, lambda a, y: next((get(a, y, z) for z in range(sz - 1, -1, -1) if get(a, y, z) is not None), None)))
    panels.append(("section z=%d" % cz, sx, lambda a, y: get(a, y, cz)))
    panels.append(("section x=%d" % cx, sz, lambda a, y: get(cx, y, a)))

    if ONLY is not None:
        panels = [panels[int(i)] for i in ONLY.split(",")]
    gap = 10
    W = sum(p[1] * S for p in panels) + gap * (len(panels) - 1)
    H = rows * S
    img = [bytearray([30, 30, 34] * W) for _ in range(H)]

    def rect(x0, y0, x1, y1, c):
        for yy in range(max(0, y0), min(H, y1)):
            row = img[yy]
            for xx in range(max(0, x0), min(W, x1)):
                row[xx * 3:xx * 3 + 3] = bytes(c)

    ox = 0
    for title, width, fn in panels:
        for y in range(rows):
            py = (rows - 1 - y) * S
            band = (y - BASE) // 5 % 2 if y >= BASE else 2
            bg = (46, 46, 52) if band == 0 else (38, 38, 44) if band == 1 else (24, 24, 26)
            rect(ox, py, ox + width * S, py + S, bg)
            # ruler: height above the ground pad, a mark every layer, a long red one every 5th (layer 0 = first build layer)
            h = y - BASE
            if h >= 0:
                long_ = h % 5 == 0
                rect(ox, py + S - 1, ox + (width * S if long_ else 4), py + S, (200, 60, 60) if long_ else (150, 150, 150))
            for a in range(width):
                p = fn(a, y)
                if p is None: continue
                n = pal[p]; c = colour(n); s = sta[p]
                x0 = ox + a * S
                if "slab" in n:
                    top = s.get("minecraft:vertical_half") == "top"
                    rect(x0, py if top else py + S // 2, x0 + S, py + S // 2 if top else py + S, c)
                elif "fence" in n or n.endswith("_wall") or "bars" in n:
                    rect(x0 + 4, py, x0 + S - 4, py + S, c)
                elif "trapdoor" in n:
                    rect(x0, py + S - 3, x0 + S, py + S, c)
                elif "stairs" in n:
                    rect(x0, py, x0 + S, py + S, c)
                    rect(x0 + 3, py + 3, x0 + S - 3, py + S - 3, tuple(min(255, v + 45) for v in c))
                elif "glass" in n or n.endswith("pane"):
                    rect(x0 + 1, py + 1, x0 + S - 1, py + S - 1, c)
                else:
                    rect(x0, py, x0 + S, py + S, c)
                    rect(x0, py + S - 1, x0 + S, py + S, tuple(max(0, v - 35) for v in c))
        ox += width * S + gap

    raw = b"".join(b"\x00" + bytes(r) for r in img)

    def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b"")
    open(out, "wb").write(png)
    print(os.path.basename(out), "%dx%d" % (W, H), "top y", ytop, "(%d layers above ground), cx %d cz %d" % (ytop - BASE + 1, cx, cz))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
