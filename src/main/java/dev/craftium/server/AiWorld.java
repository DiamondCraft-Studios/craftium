package dev.craftium.server;

import com.google.gson.*;
import dev.craftium.core.BlockRuns;
import dev.craftium.core.Point;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Reads of the world an AI client asks for. They come from the server's own level, so they do not depend on what the player's client
 * has loaded, and a position that is not part of the world says so instead of reading as air. Nothing here changes the world.
 */
final class AiWorld {
    private AiWorld() {}

    static final int MAX_BLOCKS = 32_768, MAX_COLUMNS = 16_384, MAX_NEW_CHUNKS = 16, MAX_REPLY = 900_000;
    /** A build may make more ground than a read can, since it has to stand on it: 36 chunks, a square 96 blocks across. */
    static final int MAX_BUILD_CHUNKS = 36;

    /** The blocks in a box, as a palette plus run-length runs running x first, then z, then y upward from the minimum corner. */
    static String blocks(ServerPlayer player, Point a, Point b) {
        require(a, b);
        var level = (ServerLevel) player.level();
        int x0 = Math.min(a.x(), b.x()), x1 = Math.max(a.x(), b.x()), y0 = Math.min(a.y(), b.y()), y1 = Math.max(a.y(), b.y()), z0 = Math.min(a.z(), b.z()), z1 = Math.max(a.z(), b.z());
        long total = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
        if (total > MAX_BLOCKS) throw new IllegalArgumentException("Region has " + total + " blocks; read at most " + MAX_BLOCKS + " at a time");
        int fresh = unloaded(level, x0, z0, x1, z1);
        var runs = new BlockRuns();
        var at = new BlockPos.MutableBlockPos();
        for (int y = y0; y <= y1; y++)
            for (int z = z0; z <= z1; z++)
                for (int x = x0; x <= x1; x++) runs.add(name(level, at.set(x, y, z)));
        var out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("source", "server");
        out.add("min", ints(x0, y0, z0));
        out.add("size", ints(x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1));
        out.add("palette", strings(runs.palette()));
        out.addProperty("order", "x fastest, then z, then y; each run is [paletteIndex, count]");
        var list = new JsonArray();
        for (int[] run : runs.runs()) list.add(ints(run[0], run[1]));
        out.add("runs", list);
        out.addProperty("outsideWorld", BlockRuns.OUTSIDE + " marks a position above or below the world's height limits or past the world border");
        out.addProperty("chunksLoadedForThisRead", fresh);
        return reply(out);
    }

    /**
     * The highest block of each column in a rectangle. {@code floor} looks under water (and anything else that is not solid), otherwise
     * the top block is the highest one that is solid or liquid, ignoring leaves. Trunks and buildings count as the surface, and in the
     * Nether the surface is the ceiling, so read the blocks to look below it.
     */
    static String surface(ServerPlayer player, Point a, Point b, boolean floor) {
        require(a, b);
        var level = (ServerLevel) player.level();
        int x0 = Math.min(a.x(), b.x()), x1 = Math.max(a.x(), b.x()), z0 = Math.min(a.z(), b.z()), z1 = Math.max(a.z(), b.z());
        long columns = (long) (x1 - x0 + 1) * (z1 - z0 + 1);
        if (columns > MAX_COLUMNS) throw new IllegalArgumentException("Area has " + columns + " columns; read at most " + MAX_COLUMNS + " (128 by 128) at a time");
        int fresh = unloaded(level, x0, z0, x1, z1);
        var type = floor ? Heightmap.Types.OCEAN_FLOOR : Heightmap.Types.MOTION_BLOCKING_NO_LEAVES;
        var names = new BlockRuns();
        var heights = new JsonArray();
        var blocks = new JsonArray();
        var at = new BlockPos.MutableBlockPos();
        for (int z = z0; z <= z1; z++)
            for (int x = x0; x <= x1; x++) {
                if (!level.getWorldBorder().isWithinBounds(at.set(x, level.getMinY(), z))) { heights.add(Integer.MIN_VALUE); blocks.add(-1); continue; }
                int top = level.getHeight(type, x, z) - 1;
                if (top < level.getMinY()) { heights.add(Integer.MIN_VALUE); blocks.add(-1); continue; }
                heights.add(top);
                blocks.add(names.indexOf(name(level, at.set(x, top, z))));
            }
        var out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("source", "server");
        out.addProperty("kind", floor ? "floor" : "top");
        out.add("min", ints(x0, z0));
        out.add("size", ints(x1 - x0 + 1, z1 - z0 + 1));
        out.addProperty("order", "x fastest, then z; heights[i] is the y of the column's top block, blocks[i] indexes the palette");
        out.add("palette", strings(names.palette()));
        out.add("heights", heights);
        out.add("blocks", blocks);
        out.addProperty("noColumn", "A column past the world border or with no block in it has heights[i] = -2147483648 and blocks[i] = -1. A height of -1 is real: the top block is at y = -1 (as in a superflat world), not an empty column");
        out.addProperty("chunksLoadedForThisRead", fresh);
        return reply(out);
    }

    /**
     * Makes sure the chunks a build reaches are loaded, loading (and, for ground nobody has visited, making) the ones that are not.
     * Planning needs them to read what is there, and an AI build can be far from any player. Refused when it needs more than
     * {@link #MAX_BUILD_CHUNKS} new chunks; chunks past the world border are left alone, and the plan refuses them itself.
     */
    static void load(ServerLevel level, int x0, int z0, int x1, int z1) {
        var needed = new java.util.ArrayList<int[]>();
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++)
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                if (!level.getWorldBorder().isWithinBounds(new ChunkPos(cx, cz)) || level.getChunkSource().getChunkNow(cx, cz) != null) continue;
                needed.add(new int[]{cx, cz});
                if (needed.size() > MAX_BUILD_CHUNKS)
                    throw new IllegalArgumentException("That build reaches more than " + MAX_BUILD_CHUNKS + " chunks that are not loaded; build closer to loaded ground, or in smaller pieces");
            }
        for (int[] at : needed) level.getChunk(at[0], at[1]);
    }

    /** Cells a read of the preview may cover, and the longest reply worth sending: a model cannot use much more than this in one answer. */
    static final int MAX_VIEW_CELLS = 1_048_576, MAX_VIEW_REPLY = 100_000;

    /** The block kinds met while filling a box, numbered in the order they are first seen. */
    private static final class Palette {
        final java.util.List<String> names = new java.util.ArrayList<>();
        final java.util.Set<Integer> omit = new java.util.HashSet<>();
        private final java.util.IdentityHashMap<net.minecraft.world.level.block.state.BlockState, Integer> ids = new java.util.IdentityHashMap<>();
        private final boolean hideAir;
        private int outside = -1;

        Palette(boolean hideAir) { this.hideAir = hideAir; }

        int of(net.minecraft.world.level.block.state.BlockState state) {
            Integer id = ids.get(state);
            if (id == null) {
                id = names.size();
                names.add(BlockStateParser.serialize(state));
                ids.put(state, id);
                if (hideAir && state.isAir()) omit.add(id);
            }
            return id;
        }

        int outside() {
            if (outside < 0) { outside = names.size(); names.add(BlockRuns.OUTSIDE); }
            return outside;
        }
    }

    /**
     * The blocks in a box, so the AI can check the ground or its work: exact blocks and states (which way a stair faces, say), where a picture
     * shows flat colour and the game draws only part of a big preview. "world" is the world as it is; "after" is the world as it would be once
     * the waiting preview is applied (the world itself when none is waiting); "only" is just what the preview places, and air where it clears.
     * The answer is written as boxes or as runs, whichever is shorter (see {@link dev.craftium.core.BlockView}), and a box may hold a million cells.
     */
    static String previewBlocks(ServerPlayer player, Session ai, Point a, Point b, String view, String format) {
        require(a, b);
        if (view == null || view.isBlank()) view = "after";
        if (!view.equals("after") && !view.equals("only") && !view.equals("world")) throw new IllegalArgumentException("\"view\" must be \"world\", \"after\" or \"only\"");
        var edit = ai.pendingEdit;
        if (edit == null && view.equals("only")) {
            var none = new JsonObject();
            none.addProperty("ok", true);
            none.addProperty("previewBlocks", 0);
            none.addProperty("message", "There is no waiting preview. Call preview_build first, or read the world with source \"world\".");
            return reply(none);
        }
        int x0 = Math.min(a.x(), b.x()), x1 = Math.max(a.x(), b.x()), y0 = Math.min(a.y(), b.y()), y1 = Math.max(a.y(), b.y()), z0 = Math.min(a.z(), b.z()), z1 = Math.max(a.z(), b.z());
        int sx = x1 - x0 + 1, sy = y1 - y0 + 1, sz = z1 - z0 + 1;
        long total = (long) sx * sy * sz;
        if (total > MAX_VIEW_CELLS) throw new IllegalArgumentException("Region has " + total + " blocks; read at most " + String.format(java.util.Locale.ROOT, "%,d", MAX_VIEW_CELLS) + " at a time");
        var level = (ServerLevel) player.level();
        boolean only = view.equals("only"), withPreview = !view.equals("world");
        var palette = new Palette(!only);
        int[] ids = new int[(int) total];
        int fresh = 0;
        if (!only) {
            fresh = unloaded(level, x0, z0, x1, z1);
            fillFromWorld(level, palette, ids, x0, y0, z0, x1, y1, z1);
        } else java.util.Arrays.fill(ids, -1);
        // The preview over it, in one pass over the edit's own changes: nothing is copied or indexed, so a read costs a walk of the list and
        // the box. It counts what the preview places the way the game's ghost does, leaving out a change that keeps the block as it was.
        int placed = 0;
        if (edit != null) for (var change : edit.changes()) {
            if (change.before().state() == change.after().state() && change.after().data() == null) continue;
            placed++;
            var at = change.pos();
            if (!withPreview || at.x() < x0 || at.x() > x1 || at.y() < y0 || at.y() > y1 || at.z() < z0 || at.z() > z1) continue;
            ids[(at.x() - x0) + sx * ((at.z() - z0) + sz * (at.y() - y0))] = palette.of(change.after().state());
        }
        var out = dev.craftium.core.BlockView.describe(x0, y0, z0, sx, sy, sz, ids, palette.names, palette.omit, format,
            !only ? "Anything not listed is air." : "Anything not listed is not changed by the preview, and air means the preview clears that block.");
        out.addProperty("source", "server");
        out.addProperty("view", view);
        // How much of a waiting preview there is, also on a world read, so a read that leaves it out says that one is waiting.
        out.addProperty("previewBlocks", placed);
        if (!only) out.addProperty("chunksLoadedForThisRead", fresh);
        String text = out.toString();
        if (text.length() > MAX_VIEW_REPLY)
            throw new IllegalArgumentException("The answer is " + text.length() / 1000 + " KB; the limit is " + MAX_VIEW_REPLY / 1000 + " KB. Read a smaller box, or part of the build at a time (the preview holds " + placed + " blocks in all)");
        return text;
    }

    /**
     * What a preview that was just made holds (see {@link dev.craftium.core.PreviewSummary}), sent with the reply to preview_build so the AI
     * learns where the build went and what it is made of without another request. A change that keeps the block as it was is left out, as
     * the ghost and read_blocks leave it out.
     */
    static String previewSummary(Session.Edit edit) {
        var summary = new dev.craftium.core.PreviewSummary();
        var names = new java.util.IdentityHashMap<net.minecraft.world.level.block.Block, String>();
        for (var change : edit.changes()) {
            if (change.before().state() == change.after().state() && change.after().data() == null) continue;
            var state = change.after().state();
            var at = change.pos();
            String facing = null;
            for (var property : state.getProperties())
                if (property.getName().equals("facing")) { facing = facing(state, property); break; }
            summary.add(at.x(), at.y(), at.z(), names.computeIfAbsent(state.getBlock(), b -> net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).toString()), facing, state.isAir());
        }
        var out = summary.toJson();
        out.addProperty("ok", true);
        out.addProperty("kind", "previewSummary");
        return out.toString();
    }

    private static <T extends Comparable<T>> String facing(net.minecraft.world.level.block.state.BlockState state, net.minecraft.world.level.block.state.properties.Property<T> property) {
        return property.getName(state.getValue(property));
    }

    /** Fills {@code ids} with the world's blocks in the box, chunk by chunk, the world border and height limits reading as outside the world. */
    private static void fillFromWorld(ServerLevel level, Palette palette, int[] ids, int x0, int y0, int z0, int x1, int y1, int z1) {
        int sx = x1 - x0 + 1, sz = z1 - z0 + 1, minY = level.getMinY(), maxY = minY + level.getHeight();
        var at = new BlockPos.MutableBlockPos();
        var border = level.getWorldBorder();
        var inside = new boolean[sx * sz]; // per column, so the border is asked once for each and not for every block in it
        for (int z = 0; z < sz; z++) for (int x = 0; x < sx; x++) inside[x + sx * z] = border.isWithinBounds(at.set(x0 + x, minY, z0 + z));
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++)
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                net.minecraft.world.level.chunk.LevelChunk chunk = null;
                int lx0 = Math.max(x0, cx << 4), lx1 = Math.min(x1, (cx << 4) + 15), lz0 = Math.max(z0, cz << 4), lz1 = Math.min(z1, (cz << 4) + 15);
                for (int y = y0; y <= y1; y++) {
                    boolean outsideY = y < minY || y >= maxY;
                    for (int z = lz0; z <= lz1; z++) {
                        int row = (lx0 - x0) + sx * ((z - z0) + sz * (y - y0));
                        for (int x = lx0; x <= lx1; x++) {
                            int i = row + (x - lx0);
                            if (outsideY || !inside[(x - x0) + sx * (z - z0)]) { ids[i] = palette.outside(); continue; }
                            if (chunk == null) chunk = level.getChunk(cx, cz);
                            ids[i] = palette.of(chunk.getBlockState(at.set(x, y, z)));
                        }
                    }
                }
            }
    }

    /**
     * The player's selection as it really is: its exact shape and what it is made of, with the clear space above it. Answered here, in one
     * pass over the blocks, because the selection lives on the server and can hold a million blocks: the answer is a few lines whatever its
     * size. It reads only chunks that are already loaded (a selection is made in loaded ground, and reading it must not make new ground).
     */
    static String selection(ServerPlayer player, Session s) {
        var level = (ServerLevel) player.level();
        var points = s.selection.points();
        if (points.isEmpty()) return reply(dev.craftium.core.SelectionReport.describe(points, null, 0, null));
        var out = dev.craftium.core.SelectionReport.describe(points, new LoadedBlocks(level), dev.craftium.core.SelectionReport.HEADROOM, null);
        out.addProperty("source", "server");
        return reply(out);
    }

    /**
     * Block ids in loaded chunks, found without allocating per block. Lookups arrive in no particular order (the clear space above a
     * selection is checked column by column in hash order), so the chunks are kept in a small direct-mapped cache and not just the last
     * one: a selection spanning up to a thousand chunks finds its chunk in the cache almost every time, instead of asking the world, and
     * the border, for it on every lookup.
     */
    private static final class LoadedBlocks implements dev.craftium.core.SelectionReport.World {
        private static final int SLOTS = 1024;
        private final ServerLevel level;
        private final BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        private final java.util.IdentityHashMap<net.minecraft.world.level.block.Block, String> names = new java.util.IdentityHashMap<>();
        private final long[] keys = new long[SLOTS];
        private final net.minecraft.world.level.chunk.LevelChunk[] chunks = new net.minecraft.world.level.chunk.LevelChunk[SLOTS];
        private final boolean[] inBorder = new boolean[SLOTS], filled = new boolean[SLOTS];

        LoadedBlocks(ServerLevel level) { this.level = level; }

        @Override public String at(int x, int y, int z) {
            if (y < level.getMinY() || y >= level.getMinY() + level.getHeight()) return BlockRuns.OUTSIDE;
            int cx = x >> 4, cz = z >> 4;
            long key = ((long) cx << 32) | (cz & 0xffffffffL); // our own key: ChunkPos's differs between Minecraft versions
            int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> 54) & (SLOTS - 1);
            if (!filled[slot] || keys[slot] != key) {
                filled[slot] = true;
                keys[slot] = key;
                chunks[slot] = level.getChunkSource().getChunkNow(cx, cz);
                inBorder[slot] = level.getWorldBorder().isWithinBounds(new ChunkPos(cx, cz));
            }
            if (!inBorder[slot]) return BlockRuns.OUTSIDE;
            var chunk = chunks[slot];
            if (chunk == null) return null;
            var block = chunk.getBlockState(at.set(x, y, z)).getBlock();
            return names.computeIfAbsent(block, b -> net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).toString());
        }
    }

    private static String name(ServerLevel level, BlockPos at) {
        if (!level.isInWorldBounds(at) || !level.getWorldBorder().isWithinBounds(at)) return BlockRuns.OUTSIDE;
        return BlockStateParser.serialize(level.getBlockState(at));
    }

    /**
     * How many chunks the area needs that the server has not got loaded. Reading them loads them (and makes them if they were never
     * visited), so one request is held to a few, and chunks past the world border are not counted because they are never read.
     */
    private static int unloaded(ServerLevel level, int x0, int z0, int x1, int z1) {
        int fresh = 0;
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++)
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                if (!level.getWorldBorder().isWithinBounds(new ChunkPos(cx, cz))) continue;
                if (level.getChunkSource().getChunkNow(cx, cz) == null && ++fresh > MAX_NEW_CHUNKS)
                    throw new IllegalArgumentException("That area needs more than " + MAX_NEW_CHUNKS + " chunks that are not loaded; read a smaller area, or one closer to what is loaded");
            }
        return fresh;
    }

    private static void require(Point a, Point b) {
        if (a == null || b == null) throw new IllegalArgumentException("Give two corners");
    }

    private static String reply(JsonObject out) {
        String text = out.toString();
        if (text.length() > MAX_REPLY) throw new IllegalArgumentException("The result is too large to send; read a smaller area");
        return text;
    }

    private static JsonArray ints(int... values) {
        var out = new JsonArray();
        for (int v : values) out.add(v);
        return out;
    }

    private static JsonArray strings(java.util.List<String> values) {
        var out = new JsonArray();
        values.forEach(out::add);
        return out;
    }
}
