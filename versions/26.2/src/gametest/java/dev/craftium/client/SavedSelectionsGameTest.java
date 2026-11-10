package dev.craftium.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.craftium.core.Options;
import dev.craftium.core.Point;
import dev.craftium.core.Selection;
import dev.craftium.core.SelectionCodec;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Hierarchy window and the saved selections behind it, end to end on a real server: Save selection, showing an entry in place of the
 * selection and getting yours back, editing, the eye, deleting and bringing it back with Ctrl+Z, the numbers starting again, and Claude reading
 * and building on two selections by name in one spec. The window is driven through its own controls, found where the panel says it laid them
 * out (see {@link PanelLog}), so a button that moved or stopped answering fails here.
 * <p>
 * Run it with {@code CRAFTIUM_TEST_SCOPE=savedselections}. It takes a screenshot, {@code saved-selections}, for visual review.
 */
public final class SavedSelectionsGameTest {
    private final ClientGameTestContext c;
    private final TestSingleplayerContext w;
    private int baseX, baseY, baseZ;

    private SavedSelectionsGameTest(ClientGameTestContext c, TestSingleplayerContext w) { this.c = c; this.w = w; }

    public static void run(ClientGameTestContext c, TestSingleplayerContext w) { new SavedSelectionsGameTest(c, w).all(); }

    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

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
        baseX = base.getX(); baseY = base.getY(); baseZ = base.getZ();
        // three places of real stone: A is 2 x 2 x 2, B is 3 x 1 x 1, C is one block
        w.getServer().runCommand("fill " + baseX + " " + baseY + " " + baseZ + " " + (baseX + 1) + " " + (baseY + 1) + " " + (baseZ + 1) + " stone");
        w.getServer().runCommand("fill " + (baseX + 4) + " " + baseY + " " + baseZ + " " + (baseX + 6) + " " + baseY + " " + baseZ + " stone");
        w.getServer().runCommand("setblock " + (baseX + 9) + " " + baseY + " " + baseZ + " stone");
        c.waitTicks(10);

        require(control("Save what is selected now", 0) != null, "the Hierarchy window is always shown, empty at first");

        selectOnly(a());
        require(control("Save what is selected now", 0) != null, "the window should still be there once something is selected");
        click(control("Save what is selected now", 0));
        var one = saved(0);
        require(ClientState.savedSelections().size() == 1 && one.name().equals("Selection 1"), "Save selection should make Selection 1, got " + ClientState.savedSelections());
        require(one.size() == 8 && !one.active(), "an entry holds the 8 blocks and is not shown yet");
        require(liveIs(a()), "saving must leave the selection as it was");
        require(panelShows("Selection 1") && tipHas("'2 x 2 x 2 blocks"), "the entry reads its name, and its size is in the tip: " + texts());
        require(ClientState.savedColor(one.id()) == ClientState.options.selectionColor, "the first entry starts as the selection colour");

        selectOnly(b());
        click(control("Save what is selected now", 0));
        var two = saved(1);
        require(two.name().equals("Selection 2") && two.size() == 3, "the second entry is Selection 2 with 3 blocks, got " + ClientState.savedSelections());
        require(panelShows("Selection 2") && tipHas("'3 x 1 x 1 blocks"), "the second entry has its size in the tip: " + texts());
        require(ClientState.savedColor(two.id()) != ClientState.savedColor(one.id()), "each entry starts with its own colour");
        require(liveIs(b()), "saving the second must not change the selection either");

        // click an entry: it replaces the selection, and clicking again gives the selection back
        selectOnly(cell());
        click(control("Show this selection in place of yours", 0));
        require(liveIs(a()) && saved(0).active() && !saved(0).editing(), "clicking Selection 1 shows it in place of the selection");
        click(control("Let go", 0));
        require(liveIs(cell()) && ClientState.activeSaved() == null, "clicking it again gives the selection back");

        // changes made while an entry is only shown never reach it
        click(control("Show this selection in place of yours", 1));
        require(liveIs(b()), "Selection 2 is shown");
        addToLive(cellAt(7));
        require(liveCount() == 4 && saved(1).size() == 3, "an entry that is only shown keeps its blocks when the selection is changed");
        click(control("Let go", 0));
        require(liveIs(cell()), "letting go drops the changes made while it was shown and gives back the selection from before");

        // edit: any selection tool changes the entry, and stopping gives the selection back
        click(control("Edit:", 1));
        require(saved(1).active() && saved(1).editing() && liveIs(b()), "the pencil shows the entry and starts editing it");
        addToLive(cellAt(7));
        require(saved(1).size() == 4, "a block added while editing is kept in the entry, got " + saved(1).size());
        subtractFromLive(b().get(0));
        require(saved(1).size() == 3, "a block taken while editing comes out of the entry, got " + saved(1).size());
        click(control("Stop editing", 0));
        require(liveIs(cell()) && ClientState.activeSaved() == null, "stopping the edit gives the selection back");
        require(saved(1).size() == 3, "the edit stays");

        // the eye
        int eyed = saved(0).id();
        click(control("Hide this selection in the world", 0));
        require(ClientState.savedHidden(eyed), "the eye hides the entry");
        click(control("Show this selection in the world", 0));
        require(!ClientState.savedHidden(eyed), "the eye shows it again");

        // the colour survives deleting and bringing an entry back
        int coloured = saved(1).id();
        ClientState.setSavedColor(coloured, 0xffaa3366);
        click(control("Delete this selection", 1));
        require(ClientState.savedSelections().size() == 1, "the cross deletes without asking");
        require(!panelShows("Selection 2"), "the deleted entry leaves the window");
        undo();
        require(ClientState.savedSelections().size() == 2 && saved(1).name().equals("Selection 2") && saved(1).size() == 3, "Ctrl+Z brings the entry back where it was, with its blocks");
        require(ClientState.savedColor(saved(1).id()) == 0xffaa3366, "and with its colour");

        deleteAll();
        require(ClientState.savedSelections().isEmpty(), "everything deleted");
        selectOnly(a());
        click(control("Save what is selected now", 0));
        require(saved(0).name().equals("Selection 1"), "the numbers start again at 1, got " + saved(0).name());
        require(ClientState.savedColor(saved(0).id()) == ClientState.options.selectionColor, "and so does the colour");
        selectOnly(b());
        click(control("Save what is selected now", 0));

        claude();
        screenshot();
        System.out.println("CRAFTIUM_SAVED_SELECTIONS_PASS save show release edit eye delete undo numbers claude");
    }

    // ---- Claude --------------------------------------------------------------------------------------------------------------------------

    /** Reads the saved selections by name and builds on two of them in one spec, through the same call the MCP server makes. */
    private void claude() {
        selectOnly(cell());
        var read = bridge("read_selection", "{\"name\":\"selection 1\"}");
        require(read.get("selected").getAsInt() == 8 && read.get("name").getAsString().equals("Selection 1"), "read_selection by name reads that selection (any case): " + read);
        var other = bridge("read_selection", "{\"name\":\"Selection 2\"}");
        require(other.get("selected").getAsInt() == 3 && other.get("name").getAsString().equals("Selection 2"), "and a second read stays apart from the first: " + other);
        require(bridge("read_selection", "{}").get("selected").getAsInt() == 1, "without a name it still reads the player's own selection");
        String unknown = failure("read_selection", "{\"name\":\"Nope\"}");
        require(unknown.contains("Selection 1") && unknown.contains("Selection 2"), "an unknown name lists the names there are: " + unknown);

        // one spec, two selections: each op names its own
        bridge("get_state", "{}");
        var spec = "{\"spec\":{\"ops\":[{\"op\":\"replace_selection\",\"selection\":\"Selection 1\",\"block\":\"gold_block\"},{\"op\":\"replace_selection\",\"selection\":\"selection 2\",\"block\":\"diamond_block\"}]}}";
        String refused = failure("preview_build", "{\"spec\":{\"ops\":[{\"op\":\"replace_selection\",\"selection\":\"Selection 1\",\"block\":\"gold_block\"},{\"op\":\"replace_selection\",\"selection\":\"Nope\",\"block\":\"diamond_block\"}]}}");
        require(refused.contains("Nope") && refused.contains("Selection 2"), "a spec naming an unknown selection is refused, listing the names: " + refused);
        require(count(Blocks.GOLD_BLOCK) == 0, "and nothing was built by the refused one");
        bridge("preview_build", spec);
        bridge("apply_preview", "{}");
        c.waitTicks(20);
        require(allAre(a(), Blocks.GOLD_BLOCK), "Selection 1 was changed to gold");
        require(allAre(b(), Blocks.DIAMOND_BLOCK), "Selection 2 was changed to diamond in the same build");
        require(allAre(cell(), Blocks.STONE), "the player's own selection, which no op named, was not touched");
        bridge("undo", "{}");
        c.waitTicks(20);
        require(allAre(a(), Blocks.STONE) && allAre(b(), Blocks.STONE), "undo puts both back");
    }

    /**
     * Makes the call the MCP server makes. The bridge is for the thread of its own socket, not the test's thread (it asks for the game's thread
     * itself), so it runs on one of its own while the test keeps the game ticking until it answers.
     */
    private JsonObject bridge(String tool, String args) {
        var outcome = callOnOwnThread(tool, args);
        if (outcome instanceof Throwable t) throw new AssertionError(tool + " failed: " + t, t);
        return (JsonObject) outcome;
    }

    /** What a call that is refused says: the message of what it throws, or the error in what it answers. */
    private String failure(String tool, String args) {
        var outcome = callOnOwnThread(tool, args);
        if (outcome instanceof Throwable t) return String.valueOf(t.getMessage());
        var reply = (JsonObject) outcome;
        require(reply.has("ok") && !reply.get("ok").getAsBoolean(), tool + " should have been refused: " + reply);
        return reply.toString();
    }

    /** The answer, or the exception it ended in. */
    private Object callOnOwnThread(String tool, String args) {
        var done = new java.util.concurrent.CompletableFuture<Object>();
        var thread = new Thread(() -> {
            try {
                var call = AiBridge.class.getDeclaredMethod("call", String.class, JsonObject.class);
                call.setAccessible(true);
                done.complete(call.invoke(null, tool, JsonParser.parseString(args).getAsJsonObject()));
            } catch (java.lang.reflect.InvocationTargetException e) { done.complete(e.getCause()); }
            catch (Throwable t) { done.complete(t); }
        }, "test bridge call");
        thread.setDaemon(true);
        thread.start();
        for (int ticks = 0; !done.isDone(); ticks++) {
            require(ticks < 1200, tool + " did not answer in a minute");
            c.waitTicks(1);
        }
        return done.join();
    }

    // ---- the world and the selection -----------------------------------------------------------------------------------------------------

    private List<Point> a() { var out = new ArrayList<Point>(); for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) out.add(new Point(baseX + x, baseY + y, baseZ + z)); return out; }
    private List<Point> b() { return List.of(new Point(baseX + 4, baseY, baseZ), new Point(baseX + 5, baseY, baseZ), new Point(baseX + 6, baseY, baseZ)); }
    private List<Point> cell() { return List.of(new Point(baseX + 9, baseY, baseZ)); }
    private List<Point> cellAt(int x) { return List.of(new Point(baseX + x, baseY, baseZ)); }

    /** Makes exactly these blocks the player's selection, the way the lasso does. */
    private void selectOnly(List<Point> points) { lasso(points, Selection.Mode.REPLACE); }
    private void addToLive(List<Point> points) { lasso(points, Selection.Mode.ADD); }
    private void subtractFromLive(Point point) { lasso(List.of(point), Selection.Mode.SUBTRACT); }
    private void lasso(List<Point> points, Selection.Mode mode) {
        sleep();
        c.runOnClient(mc -> {
            ClientState.options.selectionPoints.clear(); ClientState.options.selectionPoints.addAll(points);
            ClientState.options.mode = mode;
            CraftiumClient.send("lasso_select", null, new Point(0, 1, 0));
        });
        settle();
        c.runOnClient(mc -> ClientState.options.mode = Selection.Mode.REPLACE);
    }
    private void undo() { sleep(); c.runOnClient(mc -> CraftiumClient.send("undo", null, null)); settle(); }
    private void deleteAll() { while (!ClientState.savedSelections().isEmpty()) click(control("Delete this selection", 0)); }

    private Set<Point> live() { return c.computeOnClient(mc -> new HashSet<>(SelectionCodec.decode(ClientState.status.selectionData()))); }
    private int liveCount() { return live().size(); }
    private boolean liveIs(List<Point> expected) { return live().equals(new HashSet<>(expected)); }
    private dev.craftium.net.Packets.SavedSelection saved(int index) { return c.computeOnClient(mc -> ClientState.savedSelections().get(index)); }

    private boolean allAre(List<Point> points, net.minecraft.world.level.block.Block block) {
        return w.getServer().computeOnServer(server -> { for (Point p : points) if (!server.overworld().getBlockState(new BlockPos(p.x(), p.y(), p.z())).is(block)) return false; return true; });
    }
    private int count(net.minecraft.world.level.block.Block block) {
        return w.getServer().computeOnServer(server -> { int n = 0; for (var list : List.of(a(), b(), cell())) for (Point p : list) if (server.overworld().getBlockState(new BlockPos(p.x(), p.y(), p.z())).is(block)) n++; return n; });
    }

    // ---- the window ------------------------------------------------------------------------------------------------------------------------

    private List<String> lines() {
        c.runOnClient(mc -> PanelLog.sink = new ArrayList<>());
        c.waitTicks(3);
        return c.computeOnClient(mc -> { var copy = new ArrayList<>(PanelLog.sink); PanelLog.sink = null; return copy; });
    }
    private List<String> texts() { return lines().stream().filter(l -> l.startsWith("text hierarchy ")).toList(); }
    private boolean panelShows(String text) { return lines().stream().anyMatch(l -> l.startsWith("text hierarchy ") && l.contains(text)); }

    /** The area of the n-th control of the Selections window whose tip starts with {@code tip}, as {x, y, width, height}; null when there is none. */
    private boolean tipHas(String text) { return lines().stream().anyMatch(l -> l.startsWith("control hierarchy ") && l.contains(text)); }
    private int[] control(String tip, int n) {
        int seen = 0;
        for (String line : lines()) {
            if (!line.startsWith("control hierarchy ")) continue;
            int quote = line.indexOf('\'');
            if (quote < 0 || !line.substring(quote + 1).replaceFirst("^[0-9]+ x [0-9]+ x [0-9]+ blocks · ", "").startsWith(tip)) continue; // an entry's tip opens with its size
            if (seen++ != n) continue;
            var parts = line.substring("control hierarchy ".length(), quote).trim().split(" ");
            var at = parts[0].split(","); var size = parts[1].split("x");
            return new int[]{Integer.parseInt(at[0]), Integer.parseInt(at[1]), Integer.parseInt(size[0]), Integer.parseInt(size[1])};
        }
        return null;
    }

    /** Presses and lets go in the middle of a control, as the pointer would, and waits for the server's answer. */
    private void click(int[] area) {
        require(area != null, "the control to click is not in the window");
        sleep();
        c.runOnClient(mc -> {
            var screen = (EditorScreen) mc.gui.screen();
            double scale;
            try { var f = EditorScreen.class.getDeclaredField("scale"); f.setAccessible(true); scale = f.getFloat(screen); } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            double x = (area[0] + area[2] / 2.0) * scale, y = (area[1] + area[3] / 2.0) * scale;
            var event = new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0));
            screen.mouseClicked(event, false); screen.mouseReleased(event);
        });
        settle();
    }

    /** The server throttles requests by wall-clock time, including in accelerated tests. */
    private static void sleep() { try { Thread.sleep(80); } catch (InterruptedException e) { throw new AssertionError(e); } }
    private void settle() {
        w.getConnection().waitForServerboundPackets(); c.waitTicks(4);
        w.getConnection().waitForClientboundPackets();
        c.waitFor(mc -> !ClientState.status.busy(), 400);
        w.getConnection().waitForClientboundPackets();
        c.waitTicks(2);
        require(c.computeOnClient(mc -> !ClientState.status.error()), "a request failed: " + c.computeOnClient(mc -> ClientState.status.message()));
    }

    private void screenshot() {
        // two entries drawn in their own colours beside the selection, one shown in full: for a person to look at
        click(control("Show this selection in place of yours", 1));
        c.runOnClient(mc -> { mc.player.setPos(baseX + 3.5, baseY + 2, baseZ - 6); mc.player.setYRot(0f); mc.player.setXRot(15f); mc.player.setOldPosAndRot(); });
        c.waitTicks(15);
        c.takeScreenshot("saved-selections");
    }
}
