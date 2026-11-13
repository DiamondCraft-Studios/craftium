package dev.craftium.client;

import dev.craftium.block.MissingBlockEntity;
import dev.craftium.core.Options;
import dev.craftium.server.MissingBlocks;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * The Missing blocks window end to end on a real server: it is not there while nothing is missing, comes up by itself when blocks are found,
 * lists their original ids, and its right-click menu adds a block to the block mask and selects every one of it in the loaded chunks.
 * <p>
 * Run it with {@code CRAFTIUM_TEST_SCOPE=missingblocks}. It takes the screenshots {@code missing-blocks} and {@code missing-blocks-menu}.
 */
public final class MissingBlocksGameTest {
    private final ClientGameTestContext c;
    private final TestSingleplayerContext w;

    private MissingBlocksGameTest(ClientGameTestContext c, TestSingleplayerContext w) { this.c = c; this.w = w; }

    public static void run(ClientGameTestContext c, TestSingleplayerContext w) { new MissingBlocksGameTest(c, w).all(); }

    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    private static final List<String> IDS = List.of(
        "fakemod:marble[axis=y]", "fakemod:marble_bricks", "fakemod:polished_marble[axis=x]",
        "othermod:copper_pipe[waterlogged=false]", "othermod:glow_crystal[age=2]", "othermod:ancient_runestone");

    private void all() {
        c.runOnClient(mc -> { mc.getWindow().setWindowed(1200, 800); mc.options.guiScale().set(1); mc.resizeGui(); mc.player.getAbilities().flying = true; });
        c.waitFor(mc -> mc.player.onGround() || mc.player.getAbilities().flying, 600);
        c.runOnClient(mc -> {
            ClientState.options = new Options();
            ClientState.options.confirmEdits = false;
            CraftiumClient.resetEditor();
            WorkspaceLayout.reset();
            mc.player.setYRot(0f); mc.player.setXRot(20f); mc.player.setOldPosAndRot();
            CraftiumClient.open(false);
        });
        c.waitTicks(10);
        var base = c.computeOnClient(mc -> mc.player.blockPosition().offset(-4, 0, 7));

        // nothing is missing yet: the window is not there
        require(c.computeOnClient(mc -> ClientState.missingBlocks.isEmpty()), "no missing blocks to start with");
        require(!lines().stream().anyMatch(l -> l.contains(" missing ")), "the window is not drawn while nothing is missing");

        // the world meets six kinds of missing block; five of the first and two of the second are placed in the world
        w.getServer().runOnServer(server -> {
            var table = MissingBlocks.table();
            var level = server.overworld();
            for (int i = 0; i < IDS.size(); i++) {
                int key = table.intern(IDS.get(i));
                int count = i == 0 ? 5 : i == 1 ? 2 : 0;
                for (int n = 0; n < count; n++) {
                    var at = new BlockPos(base.getX() + n, base.getY() + i, base.getZ());
                    level.setBlock(at, MissingBlocks.BLOCK.defaultBlockState(), 3);
                    if (level.getBlockEntity(at) instanceof MissingBlockEntity entity) entity.set(key, null);
                    level.sendBlockUpdated(at, level.getBlockState(at), level.getBlockState(at), 3);
                }
            }
        });
        c.waitFor(mc -> ClientState.missingBlocks.size() == IDS.size(), 400);
        c.waitTicks(5);
        require(c.computeOnClient(mc -> ClientState.missingBlocks.equals(IDS)), "the client lists the ids in the order of the table");
        require(lines().stream().anyMatch(l -> l.startsWith("text missing ") && l.contains("fakemod:marble[axis=y]")), "the window lists the first id");
        require(rows().size() == IDS.size(), "one row for each missing block, found " + rows().size());
        c.takeScreenshot("missing-blocks");

        // Add to mask
        rightClick(rows().get(0));
        require(c.computeOnClient(mc -> menuOpen()), "right-clicking a row opens its menu");
        c.takeScreenshot("missing-blocks-menu");
        clickMenuItem(0);
        require(c.computeOnClient(mc -> ClientState.options.maskActive.contains("fakemod:marble[axis=y]")), "Add to mask puts the original id in the block mask");
        require(c.computeOnClient(mc -> !menuOpen()), "the menu closes after an item");

        // Select all: the five marble blocks in the loaded chunks, and not the two bricks
        rightClick(rows().get(0));
        clickMenuItem(1);
        c.waitFor(mc -> ClientState.status.selectionSize() == 5, 400);
        require(c.computeOnClient(mc -> ClientState.status.selectionSize()) == 5, "Select all selects the five blocks of that id");
        c.computeOnClient(mc -> { ClientState.options.missingBlock = ""; return null; });
        require(c.computeOnClient(mc -> ClientState.options.missingBlock.isEmpty()), "the id is not kept in the tool settings");

        rightClick(rows().get(1));
        clickMenuItem(1);
        c.waitFor(mc -> ClientState.status.selectionSize() == 2, 400);
        require(c.computeOnClient(mc -> ClientState.status.selectionSize()) == 2, "the other id selects its own two blocks");

        // one with none placed says so instead of selecting nothing
        rightClick(rows().get(3));
        clickMenuItem(1);
        settle();
        require(c.computeOnClient(mc -> ClientState.status.error() && ClientState.status.message().contains("No othermod:copper_pipe")), "an id with no block in the loaded chunks reports that");
        c.runOnClient(mc -> { mc.player.setPos(base.getX() + 2.5, base.getY() + 3, base.getZ() - 6); mc.player.setYRot(0f); mc.player.setXRot(15f); mc.player.setOldPosAndRot(); });
        rightClick(rows().get(0));
        clickMenuItem(1);
        c.waitFor(mc -> ClientState.status.selectionSize() == 5, 400);
        c.takeScreenshot("missing-blocks-selected");
    }

    // ---- driving the window -----------------------------------------------------------------------------------------------------------------

    private List<String> lines() {
        c.runOnClient(mc -> PanelLog.sink = new ArrayList<>());
        c.waitTicks(3);
        return c.computeOnClient(mc -> { var copy = new ArrayList<>(PanelLog.sink); PanelLog.sink = null; return copy; });
    }
    /** The areas of the window's rows, {x, y, width, height}, top down. */
    private List<int[]> rows() {
        var out = new ArrayList<int[]>();
        for (String line : lines()) {
            if (!line.startsWith("control missing ")) continue;
            int quote = line.indexOf('\'');
            if (quote < 0 || !line.contains("Right-click for options")) continue;
            var parts = line.substring("control missing ".length(), quote).trim().split(" ");
            var at = parts[0].split(","); var size = parts[1].split("x");
            out.add(new int[]{Integer.parseInt(at[0]), Integer.parseInt(at[1]), Integer.parseInt(size[0]), Integer.parseInt(size[1])});
        }
        return out;
    }
    private static boolean menuOpen() { return (int) field("missingMenu") >= 0; }
    private static Object field(String name) {
        try {
            var f = EditorScreen.class.getDeclaredField(name); f.setAccessible(true);
            return f.get((EditorScreen) net.minecraft.client.Minecraft.getInstance().gui.screen());
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private void rightClick(int[] area) {
        require(area != null, "the row to right click is not in the window");
        c.runOnClient(mc -> {
            var screen = (EditorScreen) mc.gui.screen();
            double scale = (float) field("scale");
            var event = new MouseButtonEvent((area[0] + area[2] / 2.0) * scale, (area[1] + area[3] / 2.0) * scale, new MouseButtonInfo(1, 0));
            screen.mouseClicked(event, false); screen.mouseReleased(event);
        });
        c.waitTicks(3);
    }
    /** Clicks the n-th item of the open menu. */
    private void clickMenuItem(int n) {
        c.runOnClient(mc -> {
            var screen = (EditorScreen) mc.gui.screen();
            double scale = (float) field("scale");
            int x = (int) field("missingMenuX") + 20, y = (int) field("missingMenuY") + n * 18 + 9;
            var event = new MouseButtonEvent(x * scale, y * scale, new MouseButtonInfo(0, 0));
            screen.mouseClicked(event, false); screen.mouseReleased(event);
        });
        settle();
    }
    private void settle() {
        w.getConnection().waitForServerboundPackets(); c.waitTicks(4);
        w.getConnection().waitForClientboundPackets();
        c.waitFor(mc -> !ClientState.status.busy(), 400);
    }
}
