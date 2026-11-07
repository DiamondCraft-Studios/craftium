"""Per-build measurements of .mcstructure files: height, footprint, windows, roof steps, headroom, materials.
Usage: python -I measure.py <folder of .mcstructure files>   (read-only; stdlib only)"""
import sys, glob, os, collections, json
sys.path.insert(0, os.path.dirname(__file__))
import mcs

GROUND = {"dirt", "grass_block", "sand", "red_sand", "snow", "podzol", "gravel", "water", "stone", "coarse_dirt", "mud", "moss_block", "dirt_with_roots", "snow_layer", "grass_path", "dirt_path", "farmland", "packed_ice", "ice", "blue_ice", "netherrack", "red_sandstone", "sandstone", "hardened_clay", "stained_hardened_clay", "terracotta", "flowing_water", "bubble_column"}
BASE = 3  # layers 0..2 are the shared ground pad in every file
DETAIL = ("stairs", "slab", "trapdoor", "fence", "wall", "button", "lantern", "torch", "chain", "ladder", "door", "bars", "carpet", "candle", "pot", "sign", "banner", "shelf", "campfire", "pane")


def analyse(path):
    root = mcs.load(path)
    sx, sy, sz = root["size"]
    st = root["structure"]
    pal = [e["name"].replace("minecraft:", "") for e in st["palette"]["default"]["block_palette"]]
    l0 = st["block_indices"][0]

    def at(x, y, z):
        if x < 0 or y < 0 or z < 0 or x >= sx or y >= sy or z >= sz: return "air"
        p = l0[(x * sy + y) * sz + z]
        return "air" if p < 0 else pal[p]

    name = {}
    for x in range(sx):
        for y in range(sy):
            for z in range(sz):
                n = at(x, y, z)
                if n != "air": name[(x, y, z)] = n
    build = {k: n for k, n in name.items() if k[1] >= BASE and n not in GROUND}
    out = {}
    ys = [k[1] for k in build]
    out["height_above_ground"] = max(ys) - BASE + 1
    xs = [k[0] for k in build]; zs = [k[2] for k in build]
    out["bbox_xz"] = (max(xs) - min(xs) + 1, max(zs) - min(zs) + 1)
    out["blocks"] = len(build)

    # windows: 3D connected components of glass / panes
    glass = {k for k, n in build.items() if "glass" in n or n.endswith("pane")}
    seen, dims = set(), []
    for start in glass:
        if start in seen: continue
        stack, comp = [start], []
        seen.add(start)
        while stack:
            c = stack.pop(); comp.append(c)
            for d in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
                nb = (c[0] + d[0], c[1] + d[1], c[2] + d[2])
                if nb in glass and nb not in seen: seen.add(nb); stack.append(nb)
        a = [c[0] for c in comp]; b = [c[1] for c in comp]; c2 = [c[2] for c in comp]
        ext = sorted([max(a) - min(a) + 1, max(c2) - min(c2) + 1])  # horizontal extents
        horiz_wide = ext[1]
        dims.append((horiz_wide, max(b) - min(b) + 1, ext[0]))  # (width along wall, height, depth)
    out["windows"] = collections.Counter((w, h) for w, h, d in dims if d <= 1)
    out["window_count"] = len(dims)

    # roof pitch: step in top-block height between adjacent columns that both end in stairs/slabs
    top = {}
    for (x, y, z), n in build.items():
        if (x, z) not in top or y > top[(x, z)][0]: top[(x, z)] = (y, n)
    roof = {k for k, (y, n) in top.items() if "stairs" in n or "slab" in n}
    steps = collections.Counter()
    for (x, z) in roof:
        for nb in ((x + 1, z), (x, z + 1)):
            if nb in roof:
                d = abs(top[(x, z)][0] - top[nb][0])
                steps[min(d, 3)] += 1
    out["roof_steps"] = dict(sorted(steps.items()))
    out["roof_columns"] = len(roof)

    # clear headroom: air runs with a block directly below and above
    runs = collections.Counter()
    for x in range(sx):
        for z in range(sz):
            y = BASE
            while y < sy:
                if at(x, y, z) == "air" and at(x, y - 1, z) != "air":
                    y2 = y
                    while y2 < sy and at(x, y2, z) == "air": y2 += 1
                    if y2 < sy: runs[y2 - y] += 1  # bounded above
                    y = y2
                else:
                    y += 1
    out["headroom"] = {k: runs[k] for k in range(2, 9)}

    # materials
    c = collections.Counter(build.values())
    total = sum(c.values())
    cum, k80 = 0, 0
    for n, v in c.most_common():
        cum += v; k80 += 1
        if cum >= 0.8 * total: break
    out["distinct_blocks"] = len(c)
    out["names_for_80pct"] = k80
    out["stairs_slab_pct"] = round(100 * sum(v for n, v in c.items() if "stairs" in n or "slab" in n) / total)
    out["detail_pct"] = round(100 * sum(v for n, v in c.items() if any(t in n for t in DETAIL)) / total)
    out["top_blocks"] = [(n, round(100 * v / total)) for n, v in c.most_common(5)]
    return out


results = {}
for path in sorted(glob.glob(os.path.join(sys.argv[1], "*.mcstructure"))):
    r = analyse(path)
    results[os.path.basename(path).replace(".mcstructure", "")] = r
    print("==", os.path.basename(path).replace(".mcstructure", ""))
    print("   height", r["height_above_ground"], "bbox", r["bbox_xz"], "blocks", r["blocks"])
    print("   windows", r["window_count"], "sizes(w,h):", dict(r["windows"].most_common(5)))
    print("   roof step shares |dh| per column:", r["roof_steps"], "roof cols", r["roof_columns"])
    print("   headroom runs 2..8:", r["headroom"])
    print("   distinct", r["distinct_blocks"], "for80%", r["names_for_80pct"], "stairs+slab%", r["stairs_slab_pct"], "detail%", r["detail_pct"])
