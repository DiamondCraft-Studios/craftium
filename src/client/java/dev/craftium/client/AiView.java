package dev.craftium.client;

import com.google.gson.*;
import dev.craftium.core.Point;
import dev.craftium.core.VoxelView;
import java.io.ByteArrayOutputStream;
import java.util.*;

/**
 * The AI's own camera: a picture of a box of the world, drawn in software from what the server reports, with the AI's own preview
 * tinted violet. It never touches the player's view or screen, and the box can be anywhere the server can read.
 */
final class AiView {
    private AiView() {}

    static final int MAX_SIDE = 128, MAX_BLOCKS = 262_144, TILE = 32, WIDTH = 1024, HEIGHT = 768;
    private static final int VIOLET = 0xb57cf0, LEGEND = 12;
    /** Blocks nothing is drawn for: air, and the ones that exist only to be placed or to mark something. */
    private static final Set<String> EMPTY = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:light", "minecraft:structure_void", "minecraft:barrier", "craftium:outside_world");
    /** Liquids have no block model to take a colour from. */
    private static final Map<String, Integer> FLUIDS = Map.of("minecraft:water", 0x3f76e4, "minecraft:lava", 0xf56a1a, "minecraft:bubble_column", 0x3f76e4);
    private record Ghost(List<String> names, int[] placed, int[] removed) {}

    static JsonObject render(JsonObject args) throws Exception {
        Point a = AiBridge.point(args, "from"), b = AiBridge.point(args, "to");
        var angle = VoxelView.Angle.parse(args.has("angle") ? args.get("angle").getAsString() : "se");
        boolean preview = !args.has("preview") || args.get("preview").getAsBoolean();
        int x0 = Math.min(a.x(), b.x()), y0 = Math.min(a.y(), b.y()), z0 = Math.min(a.z(), b.z());
        int sx = Math.abs(a.x() - b.x()) + 1, sy = Math.abs(a.y() - b.y()) + 1, sz = Math.abs(a.z() - b.z()) + 1;
        if (sx > MAX_SIDE || sy > MAX_SIDE || sz > MAX_SIDE) throw new IllegalArgumentException("The box is " + sx + " by " + sy + " by " + sz + "; each side can be at most " + MAX_SIDE);
        if ((long) sx * sy * sz > MAX_BLOCKS) throw new IllegalArgumentException("The box holds " + (long) sx * sy * sz + " blocks; draw at most " + MAX_BLOCKS + " (64 by 64 by 64, for example)");
        AiBridge.onClient(() -> { BlockColours.build(); return null; });

        // The world, read in tiles the server's limits allow, as indexes into one palette.
        var names = new ArrayList<String>();
        var index = new HashMap<String, Integer>();
        int[] ids = new int[sx * sy * sz];
        int reads = 0;
        for (int ty = 0; ty < sy; ty += TILE)
            for (int tz = 0; tz < sz; tz += TILE)
                for (int tx = 0; tx < sx; tx += TILE) {
                    int tw = Math.min(TILE, sx - tx), th = Math.min(TILE, sy - ty), td = Math.min(TILE, sz - tz);
                    var reply = AiBridge.read("ai_blocks", new Point(x0 + tx, y0 + ty, z0 + tz), new Point(x0 + tx + tw - 1, y0 + ty + th - 1, z0 + tz + td - 1));
                    reads++;
                    var palette = reply.getAsJsonArray("palette");
                    int[] global = new int[palette.size()];
                    for (int i = 0; i < global.length; i++) global[i] = index.computeIfAbsent(palette.get(i).getAsString(), k -> { names.add(k); return names.size() - 1; });
                    int at = 0;
                    for (var element : reply.getAsJsonArray("runs")) {
                        var run = element.getAsJsonArray();
                        int id = global[run.get(0).getAsInt()], count = run.get(1).getAsInt();
                        for (int k = 0; k < count; k++, at++) {
                            int lx = at % tw, lz = (at / tw) % td, ly = at / (tw * td);
                            ids[tx + lx + sx * (tz + lz + sz * (ty + ly))] = id;
                        }
                    }
                }

        var colourOf = new HashMap<String, Integer>();
        int[] palette = new int[names.size()];
        for (int i = 0; i < palette.length; i++) palette[i] = colour(names.get(i), colourOf);
        int[] colours = new int[ids.length];
        int[] counts = new int[names.size()];
        for (int i = 0; i < ids.length; i++) {
            colours[i] = palette[ids[i]];
            if (colours[i] != 0) counts[ids[i]]++;
        }

        // The preview, if the player has one waiting and it is wanted: its blocks over the world's, tinted, and its removals carved out.
        int shown = 0;
        if (preview) {
            var ghost = AiBridge.onClient(() -> {
                var pending = ClientState.aiPending;
                return pending.total() == 0 ? null : new Ghost(pending.stateNames(), pending.placedCells().clone(), pending.removedCells().clone());
            });
            if (ghost != null) {
                var cache = new HashMap<String, Integer>();
                int[] states = new int[ghost.names().size()];
                for (int i = 0; i < states.length; i++) {
                    int c = colour(ghost.names().get(i), cache);
                    states[i] = c == 0 ? 0 : tint(c);
                }
                for (int i = 0; i + 3 < ghost.placed().length; i += 4) {
                    int cell = cell(ghost.placed()[i] - x0, ghost.placed()[i + 1] - y0, ghost.placed()[i + 2] - z0, sx, sy, sz);
                    if (cell < 0) continue;
                    colours[cell] = states[ghost.placed()[i + 3]];
                    shown++;
                }
                for (int i = 0; i + 2 < ghost.removed().length; i += 3) {
                    int cell = cell(ghost.removed()[i] - x0, ghost.removed()[i + 1] - y0, ghost.removed()[i + 2] - z0, sx, sy, sz);
                    if (cell >= 0) colours[cell] = 0;
                }
            }
        }

        var result = VoxelView.render(sx, sy, sz, colours, angle, WIDTH, HEIGHT);
        var png = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(result.image(), "png", png);

        var out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("image", Base64.getEncoder().encodeToString(png.toByteArray()));
        out.addProperty("mime", "image/png");
        out.addProperty("width", result.image().getWidth());
        out.addProperty("height", result.image().getHeight());
        out.addProperty("angle", angle.name().toLowerCase(Locale.ROOT));
        out.add("min", AiBridge.array(x0, y0, z0));
        out.add("size", AiBridge.array(sx, sy, sz));
        out.addProperty("pixelsPerBlock", Math.round(result.scale() * 10) / 10.0);
        var compass = new JsonObject();
        compass.addProperty("east", result.east());
        compass.addProperty("north", result.north());
        out.add("compass", compass);
        out.addProperty("previewBlocksShown", shown);
        if (shown > 0) out.addProperty("previewLook", "blocks tinted violet are the preview, not yet built");
        if (preview) {
            // previewBlocksShown above counts only what fell inside this box; these say whether the preview was cut short as a whole.
            var report = AiBridge.onClient(() -> { var r = new JsonObject(); AiBridge.previewReport(r, ClientState.aiPending); return r; });
            for (var entry : report.entrySet()) out.add(entry.getKey(), entry.getValue());
        }
        out.add("blocks", legend(names, counts, colourOf));
        out.addProperty("reads", reads);
        return out;
    }

    private static int cell(int x, int y, int z, int sx, int sy, int sz) {
        return x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz ? -1 : x + sx * (z + sz * y);
    }

    /** The commonest blocks and the colour each is drawn in, so a picture can be read back to block names. */
    private static JsonArray legend(List<String> names, int[] counts, Map<String, Integer> colours) {
        var merged = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < names.size(); i++) if (counts[i] > 0) merged.merge(baseId(names.get(i)), counts[i], Integer::sum);
        var list = new ArrayList<>(merged.entrySet());
        list.sort((p, q) -> q.getValue() - p.getValue());
        var out = new JsonArray();
        for (var entry : list.subList(0, Math.min(LEGEND, list.size()))) {
            var item = new JsonObject();
            item.addProperty("block", entry.getKey());
            item.addProperty("count", entry.getValue());
            item.addProperty("colour", String.format("#%06x", colours.getOrDefault(entry.getKey(), 0) & 0xffffff));
            out.add(item);
        }
        return out;
    }

    private static String baseId(String state) {
        int bracket = state.indexOf('[');
        String id = bracket < 0 ? state : state.substring(0, bracket);
        return id.contains(":") ? id : "minecraft:" + id;
    }

    /** The colour a block state is drawn in, as 0xAARRGGBB, or 0 for the ones nothing is drawn for. */
    private static int colour(String state, Map<String, Integer> cache) {
        String id = baseId(state);
        Integer known = cache.get(id);
        if (known != null) return known;
        int colour;
        if (EMPTY.contains(id)) colour = 0;
        else if (FLUIDS.containsKey(id)) colour = 0xff000000 | FLUIDS.get(id);
        else {
            var entry = BlockColours.get(id);
            colour = entry != null ? 0xff000000 | entry.rgb() : fallback(id);
        }
        cache.put(id, colour);
        return colour;
    }

    /** A block with no texture to average (a chest, a banner) gets a steady colour of its own from its name, so it still shows. */
    private static int fallback(String id) {
        return 0xff000000 | java.awt.Color.HSBtoRGB((id.hashCode() & 0xffff) / 65535f, 0.35f, 0.75f) & 0xffffff;
    }

    private static int tint(int colour) {
        int r = (((colour >> 16) & 255) * 4 + ((VIOLET >> 16) & 255) * 6) / 10, g = (((colour >> 8) & 255) * 4 + ((VIOLET >> 8) & 255) * 6) / 10, b = ((colour & 255) * 4 + (VIOLET & 255) * 6) / 10;
        return 0xff000000 | r << 16 | g << 8 | b;
    }
}
