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
        out.addProperty("noColumn", "-2147483648 and -1 mark a column past the world border or with no block in it");
        out.addProperty("chunksLoadedForThisRead", fresh);
        return reply(out);
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
