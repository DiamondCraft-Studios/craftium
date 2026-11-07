"""Minimal reader for Bedrock .mcstructure (little-endian, uncompressed NBT). Pure stdlib, read-only."""
import struct, sys, json

END, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BYTE_ARRAY, STRING, LIST, COMPOUND, INT_ARRAY, LONG_ARRAY = range(13)


class R:
    def __init__(self, b):
        self.b, self.i = b, 0

    def take(self, n):
        s = self.b[self.i:self.i + n]
        if len(s) != n:
            raise ValueError("truncated")
        self.i += n
        return s

    def u8(self): return self.take(1)[0]
    def s16(self): return struct.unpack("<h", self.take(2))[0]
    def s32(self): return struct.unpack("<i", self.take(4))[0]
    def s64(self): return struct.unpack("<q", self.take(8))[0]
    def f32(self): return struct.unpack("<f", self.take(4))[0]
    def f64(self): return struct.unpack("<d", self.take(8))[0]

    def string(self):
        n = struct.unpack("<H", self.take(2))[0]
        return self.take(n).decode("utf-8", "replace")


def payload(r, t, depth=0):
    if depth > 64:
        raise ValueError("too deep")
    if t == BYTE: return struct.unpack("<b", r.take(1))[0]
    if t == SHORT: return r.s16()
    if t == INT: return r.s32()
    if t == LONG: return r.s64()
    if t == FLOAT: return r.f32()
    if t == DOUBLE: return r.f64()
    if t == STRING: return r.string()
    if t == BYTE_ARRAY:
        n = r.s32(); return r.take(n)
    if t == INT_ARRAY:
        n = r.s32(); return list(struct.unpack("<%di" % n, r.take(4 * n)))
    if t == LONG_ARRAY:
        n = r.s32(); return list(struct.unpack("<%dq" % n, r.take(8 * n)))
    if t == LIST:
        et = r.u8(); n = r.s32()
        if et == INT:  # fast path: block index lists are huge
            return list(struct.unpack("<%di" % n, r.take(4 * n)))
        return [payload(r, et, depth + 1) for _ in range(n)]
    if t == COMPOUND:
        out = {}
        while True:
            tt = r.u8()
            if tt == END:
                return out
            name = r.string()
            out[name] = payload(r, tt, depth + 1)
    raise ValueError("bad tag %d" % t)


def load(path):
    data = open(path, "rb").read()
    r = R(data)
    if r.u8() != COMPOUND:
        raise ValueError("not a compound root")
    r.string()
    return payload(r, COMPOUND)


if __name__ == "__main__":
    root = load(sys.argv[1])
    st = root.get("structure", {})
    pal = st.get("palette", {})
    print("root keys:", list(root.keys()))
    print("format_version:", root.get("format_version"), "size:", root.get("size"), "origin:", root.get("structure_world_origin"))
    print("structure keys:", list(st.keys()))
    print("palette keys:", list(pal.keys()))
    default = pal.get("default", {})
    print("default keys:", list(default.keys()))
    bp = default.get("block_palette", [])
    print("palette entries:", len(bp))
    for e in bp[:8]:
        print("  ", json.dumps({"name": e.get("name"), "states": e.get("states")}))
    layers = st.get("block_indices", [])
    print("layers:", len(layers), [len(l) for l in layers])
    print("entities:", len(st.get("entities", [])), "block_entity_data keys:", len(default.get("block_position_data", {})))
