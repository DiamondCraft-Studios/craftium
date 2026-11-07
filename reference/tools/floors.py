"""Find interior floor levels: solid cells with 2 clear air blocks above them and a roof/ceiling somewhere higher in the column."""
import sys, glob, os, collections
sys.path.insert(0, os.path.dirname(__file__))
import mcs

BASE = 3
GROUND = {"dirt", "grass_block", "sand", "red_sand", "snow", "podzol", "gravel", "water", "stone", "coarse_dirt", "mud", "moss_block", "dirt_with_roots", "snow_layer", "packed_ice", "ice", "blue_ice"}

for path in sorted(glob.glob(os.path.join(sys.argv[1], "*.mcstructure"))):
    root = mcs.load(path)
    sx, sy, sz = root["size"]
    st = root["structure"]
    pal = [e["name"].replace("minecraft:", "") for e in st["palette"]["default"]["block_palette"]]
    l0 = st["block_indices"][0]

    def solid(x, y, z):
        if y < 0 or y >= sy: return False
        p = l0[(x * sy + y) * sz + z]
        return p >= 0 and pal[p] != "air"

    floor = collections.Counter()
    for x in range(sx):
        for z in range(sz):
            col = [solid(x, y, z) for y in range(sy)]
            top = max((y for y in range(sy) if col[y]), default=-1)
            for y in range(BASE, top - 1):
                if col[y] and not col[y + 1] and not col[y + 2] and any(col[y + 3:y + 16]):
                    p = l0[(x * sy + y) * sz + z]
                    if pal[p] in GROUND: continue
                    if any(t in pal[p] for t in ("fence", "trapdoor", "carpet", "button", "ladder", "lantern", "candle", "pot", "banner", "sign", "torch", "campfire")): continue
                    floor[y] += 1
    top = sorted(floor.items(), key=lambda kv: -kv[1])[:6]
    print(os.path.basename(path).replace(".mcstructure", ""), "interior floor cells by layer (y-3 = height above ground pad):",
          ", ".join("%d:%d" % (y - BASE, c) for y, c in sorted(top)))
