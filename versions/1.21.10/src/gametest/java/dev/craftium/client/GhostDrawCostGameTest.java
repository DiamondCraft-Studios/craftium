package dev.craftium.client;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import java.util.*;
import java.util.function.Supplier;

/**
 * Measures what drawing a preview ghost costs per frame, to judge how many block models it can afford. The ghost is redrawn as
 * real block models every frame, so the cost grows with the block count; this shows by how much, for a solid mass (most faces
 * hidden), a house-like shell and a scatter (every face drawn, the worst case). Reported as frame times; nothing is asserted,
 * because it depends on the machine. Run with {@code CRAFTIUM_TEST_SCOPE=ghostperf}.
 */
public final class GhostDrawCostGameTest implements FabricClientGameTest {
    private static final List<Long> STAMPS = Collections.synchronizedList(new ArrayList<>());
    private static final int SETTLE_TICKS = 30, MEASURE_TICKS = 120;
    private static final String[] STATES = {
        "minecraft:spruce_planks", "minecraft:stone_bricks", "minecraft:spruce_stairs[facing=north,half=bottom,shape=straight]",
        "minecraft:spruce_slab[type=bottom]", "minecraft:oak_log[axis=y]", "minecraft:cobblestone", "minecraft:spruce_stairs[facing=south,half=bottom,shape=straight]", "minecraft:glass"};

    private record Shape(String name, Supplier<List<int[]>> cells) {}

    @Override public void runTest(ClientGameTestContext c) {
        if (!"ghostperf".equals(System.getenv("CRAFTIUM_TEST_SCOPE"))) return;
        try (var world = c.worldBuilder().adjustSettings(st -> st.setGameMode(net.minecraft.client.gui.screens.worldselection.WorldCreationUiState.SelectedGameMode.CREATIVE)).create()) {
            c.waitTicks(40);
            world.getClientWorld().waitForChunksDownload();
            run(c);
        }
    }

    /** A solid 32 x 32 x h mass of about {@code n} blocks. */
    private static List<int[]> mass(int n) {
        int h = Math.max(1, n / 1024);
        var out = new ArrayList<int[]>();
        for (int x = 0; x < 32; x++) for (int y = 0; y < h; y++) for (int z = 0; z < 32; z++) out.add(new int[]{x, y, z});
        return out;
    }

    /** The surface of a cube whose side gives the number of blocks nearest {@code n}: walls, floor and ceiling, like a house with no inside. */
    private static List<int[]> shell(int n) {
        int side = 4, best = 4;
        long bestGap = Long.MAX_VALUE;
        for (int s = 4; s < 80; s++) {
            long cells = (long) s * s * s - (long) (s - 2) * (s - 2) * (s - 2);
            if (Math.abs(cells - n) < bestGap) { bestGap = Math.abs(cells - n); best = s; }
        }
        side = best;
        var out = new ArrayList<int[]>();
        for (int x = 0; x < side; x++) for (int y = 0; y < side; y++) for (int z = 0; z < side; z++)
            if (x == 0 || y == 0 || z == 0 || x == side - 1 || y == side - 1 || z == side - 1) out.add(new int[]{x, y, z});
        return out;
    }

    /** Blocks scattered at a quarter density: almost none touch, so nearly every face is drawn. */
    private static List<int[]> scatter(int n, long seed) {
        int side = (int) Math.ceil(Math.cbrt(n * 4.0));
        var rnd = new Random(seed);
        var all = new ArrayList<int[]>();
        for (int x = 0; x < side; x++) for (int y = 0; y < side; y++) for (int z = 0; z < side; z++) if (rnd.nextDouble() < .25) all.add(new int[]{x, y, z});
        return all.size() > n ? new ArrayList<>(all.subList(0, n)) : all;
    }

    private static String ghostJson(List<int[]> cells, int ox, int oy, int oz) {
        var blocks = new StringBuilder("[");
        for (int i = 0; i < cells.size(); i++) {
            int[] p = cells.get(i);
            if (i > 0) blocks.append(',');
            blocks.append(p[0] + ox).append(',').append(p[1] + oy).append(',').append(p[2] + oz).append(',').append(Math.floorMod(p[0] * 7 + p[1] * 3 + p[2], STATES.length));
        }
        blocks.append(']');
        var states = new StringBuilder("[");
        for (int i = 0; i < STATES.length; i++) { if (i > 0) states.append(','); states.append('"').append(STATES[i]).append('"'); }
        states.append(']');
        return "{\"states\":" + states + ",\"blocks\":" + blocks + ",\"removed\":[],\"total\":" + cells.size() + "}";
    }

    private static long gcMillis() { long sum = 0; for (var b : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) sum += Math.max(0, b.getCollectionTime()); return sum; }

    public static void run(ClientGameTestContext c) {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> { if (screen instanceof EditorScreen) ScreenEvents.afterRender(screen).register((sc, g, mx, my, delta) -> STAMPS.add(System.nanoTime())); });
        c.waitFor(mc -> mc.player.onGround(), 600);
        c.runOnClient(mc -> { mc.player.setYRot(0f); mc.player.setXRot(-8f); mc.player.setOldPosAndRot(); });
        c.runOnClient(mc -> CraftiumClient.open(false));
        c.waitTicks(10);
        var base = c.computeOnClient(mc -> mc.player.blockPosition());
        int ox = base.getX() - 16, oy = base.getY(), oz = base.getZ() + 30; // in front of the player, in view

        c.runOnClient(mc -> { mc.options.enableVsync().set(false); mc.options.framerateLimit().set(260); mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED); });
        var report = new StringBuilder();
        report.append(String.format(Locale.ROOT, "%-9s %7s | %8s %8s %8s %8s %8s%n", "shape", "blocks", "fps", "med ms", "p95 ms", "heap MB", "gc ms"));
        String settings = c.computeOnClient(mc -> "vsync=" + mc.options.enableVsync().get() + " framerateLimit=" + mc.options.framerateLimit().get() + " windowActive=" + mc.isWindowActive());
        report.append("options: ").append(settings).append('\n');

        var order = new ArrayList<Shape>();
        order.add(new Shape("none", List::of));
        for (int n : new int[]{4096, 8192, 16384, 32768}) {
            order.add(new Shape("mass", () -> mass(n)));
            order.add(new Shape("shell", () -> shell(n)));
            order.add(new Shape("scatter", () -> scatter(n, 5)));
        }
        order.add(new Shape("none", List::of)); // control: the empty scene again, to see whether the machine drifted

        for (int drawLimit : new int[]{8192, 32768}) { // the default draw limit, then the most a player can allow
        c.runOnClient(mc -> ClientState.options.aiGhostDrawLimit = drawLimit);
        report.append("-- AI preview models drawn: up to ").append(drawLimit).append('\n');
        for (var shape : order) {
            var cells = shape.cells().get();
            String json = ghostJson(cells, ox, oy, oz);
            c.runOnClient(mc -> {
                var ghost = GhostBlocks.parse(json);
                ghost.ai = true;
                ClientState.setAiGhost("", ghost);
            });
            c.waitTicks(SETTLE_TICKS);
            long gc0 = gcMillis();
            STAMPS.clear();
            c.waitTicks(MEASURE_TICKS);
            long gc = gcMillis() - gc0;
            long heap = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20;
            var stamps = new ArrayList<>(STAMPS);
            var deltas = new ArrayList<Double>();
            for (int i = 1; i < stamps.size(); i++) deltas.add((stamps.get(i) - stamps.get(i - 1)) / 1e6);
            Collections.sort(deltas);
            double median = deltas.isEmpty() ? -1 : deltas.get(deltas.size() / 2), p95 = deltas.isEmpty() ? -1 : deltas.get((int) (deltas.size() * .95));
            double seconds = stamps.size() < 2 ? 1 : (stamps.get(stamps.size() - 1) - stamps.get(0)) / 1e9;
            report.append(String.format(Locale.ROOT, "%-9s %7d | %8.1f %8.1f %8.1f %8d %8d%n", shape.name(), cells.size(), (stamps.size() - 1) / seconds, median, p95, heap, gc));
        }
        }
        c.runOnClient(mc -> ClientState.clearAi());
        System.out.println("CRAFTIUM_GHOST_DRAW_COST\n" + report);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of("ghost-draw-cost.txt"), report.toString()); } catch (java.io.IOException e) { System.out.println("could not write report: " + e); }
    }
}
