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

        require(control("Create a BuildObject from what is selected now", 0) != null, "the Hierarchy window is always shown, empty at first");

        selectOnly(a());
        require(control("Create a BuildObject from what is selected now", 0) != null, "the window should still be there once something is selected");
        click(control("Create a BuildObject from what is selected now", 0));
        var one = saved(0);
        require(ClientState.savedSelections().size() == 1 && one.name().equals("BuildObject"), "Save selection should make BuildObject, got " + ClientState.savedSelections());
        require(one.size() == 8 && !one.active(), "an entry holds the 8 blocks and is not shown yet");
        require(liveIs(a()), "saving must leave the selection as it was");
        require(panelShows("BuildObject") && tipHas("'2 x 2 x 2 blocks"), "the entry reads its name, and its size is in the tip: " + texts());
        require(ClientState.savedColor(one.id()) == ClientState.options.selectionColor, "the first entry starts as the selection colour");

        selectOnly(b());
        click(control("Create a BuildObject from what is selected now", 0));
        var two = saved(1);
        require(two.name().equals("BuildObject (1)") && two.size() == 3, "the second entry is BuildObject (1) with 3 blocks, got " + ClientState.savedSelections());
        require(panelShows("BuildObject (1)") && tipHas("'3 x 1 x 1 blocks"), "the second entry has its size in the tip: " + texts());
        require(ClientState.savedColor(two.id()) != ClientState.savedColor(one.id()), "each entry starts with its own colour");
        require(liveIs(b()), "saving the second must not change the selection either");

        // click an entry: it replaces the selection, and clicking again gives the selection back
        selectOnly(cell());
        click(control("Select this BuildObject", 0));
        require(liveIs(a()) && saved(0).active() && !saved(0).editing(), "clicking BuildObject shows it in place of the selection");
        click(control("Let go", 0));
        require(liveIs(cell()) && ClientState.activeSaved() == null, "clicking it again gives the selection back");

        // changes made while an entry is only shown never reach it
        click(control("Select this BuildObject", 1));
        require(liveIs(b()), "BuildObject (1) is shown");
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
        require(ClientState.savedHidden(eyed), "a new BuildObject starts with its eye closed");
        click(control("Show this BuildObject's outline", 0));
        require(!ClientState.savedHidden(eyed), "opening the eye shows its outline");
        click(control("Hide this BuildObject's outline", 0));
        require(ClientState.savedHidden(eyed), "closing it hides the outline again");

        // the colour survives deleting and bringing an entry back
        int coloured = saved(1).id();
        ClientState.setSavedColor(coloured, 0xffaa3366);
        click(control("Delete this BuildObject", 1));
        require(ClientState.savedSelections().size() == 1, "the cross deletes without asking");
        require(!panelShows("BuildObject (1)"), "the deleted entry leaves the window");
        undo();
        require(ClientState.savedSelections().size() == 2 && saved(1).name().equals("BuildObject (1)") && saved(1).size() == 3, "Ctrl+Z brings the entry back where it was, with its blocks");
        require(ClientState.savedColor(saved(1).id()) == 0xffaa3366, "and with its colour");

        deleteAll();
        require(ClientState.savedSelections().isEmpty(), "everything deleted");
        selectOnly(a());
        click(control("Create a BuildObject from what is selected now", 0));
        require(saved(0).name().equals("BuildObject"), "the numbers start again at 1, got " + saved(0).name());
        require(ClientState.savedColor(saved(0).id()) == ClientState.options.selectionColor, "and so does the colour");
        selectOnly(b());
        click(control("Create a BuildObject from what is selected now", 0));

        claude();

        // a double click on a row renames it where it stands: the name is selected, so typing replaces it, and Enter keeps it
        doubleClick(control("Select this BuildObject", 0));
        typeText("Roof");press(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER);
        require(saved(0).name().equals("Roof") && saved(1).name().equals("BuildObject (1)"), "a double click renames the row, got " + ClientState.savedSelections());
        doubleClick(control("Let go", 0)); // the first click of the pair let go of it again
        press(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE);
        require(saved(0).name().equals("Roof"), "Esc keeps the old name");
        doubleClick(control("Select this BuildObject", 0));
        typeText("Wall");press(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE);
        require(saved(0).name().equals("Roof"), "Esc drops what was typed, got " + saved(0).name());
        click(control("Let go", 0)); // the double click showed it; let go before the picture

        // dragging a row to the top edge of another puts it before that row, to the bottom edge after it
        String top = saved(0).guid(), below = saved(1).guid();
        drag(control("Select this BuildObject", 1), control("Select this BuildObject", 0), 0.1);
        require(saved(0).guid().equals(below) && saved(1).guid().equals(top), "dragging the second row onto the top edge of the first moves it to the top: " + ClientState.savedSelections());
        drag(control("Select this BuildObject", 0), control("Select this BuildObject", 1), 0.9);
        require(saved(0).guid().equals(top) && saved(1).guid().equals(below), "and onto the bottom edge of the last puts it back");

        // dropping on the middle of a row makes it a child, and a folded BuildObject lists nothing under it
        drag(control("Select this BuildObject", 1), control("Select this BuildObject", 0), 0.5);
        require(saved(1).parent() == saved(0).id() && saved(0).parent() == 0, "the middle of a row makes a child: " + ClientState.savedSelections());
        click(control("Fold what is under this BuildObject", 0));
        require(control("Select this BuildObject", 1) == null, "a folded BuildObject lists nothing under it");
        click(control("Show what is under this BuildObject", 0));
        require(control("Select this BuildObject", 1) != null, "and unfolding lists it again");
        drag(control("Select this BuildObject", 1), control("Select this BuildObject", 0), 0.1);
        require(saved(0).guid().equals(below) && saved(0).parent() == 0 && saved(1).parent() == 0, "dragging a child to the top edge of its parent brings it out: " + ClientState.savedSelections());

        // names may repeat: the GUID tells two of a name apart
        doubleClick(control("Select this BuildObject", 1));
        typeText(saved(0).name());press(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER);
        require(saved(0).name().equals(saved(1).name()) && !saved(0).guid().equals(saved(1).guid()), "a second BuildObject may take the name of the first: " + ClientState.savedSelections());
        click(control("Let go", 0));

        // for a person to look at: one nested under the other, and the picture taken while a row is being carried
        drag(control("Select this BuildObject", 1), control("Select this BuildObject", 0), 0.5);
        require(saved(1).parent() == saved(0).id(), "nested for the picture");
        var from = control("Select this BuildObject", 1);
        c.runOnClient(mc -> {
            var screen = (EditorScreen) mc.gui.screen();
            double scale;
            try { var f = EditorScreen.class.getDeclaredField("scale"); f.setAccessible(true); scale = f.getFloat(screen); } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            var down = new MouseButtonEvent((from[0] + from[2] / 2.0) * scale, (from[1] + from[3] / 2.0) * scale, new MouseButtonInfo(0, 0));
            var up = new MouseButtonEvent((from[0] + from[2] / 2.0) * scale, (from[1] - 4) * scale, new MouseButtonInfo(0, 0));
            screen.mouseClicked(down, false);
            screen.mouseDragged(up, 0, -10);
        });
        c.waitTicks(4);
        c.takeScreenshot("hierarchy-drag");
        c.runOnClient(mc -> { var screen = (EditorScreen) mc.gui.screen(); screen.mouseReleased(new MouseButtonEvent(0, 0, new MouseButtonInfo(0, 0))); });
        settle();

        screenshot();
        transformsCarryObjects();
        overlapTakesObjectsWhole();
        hierarchyIsInHistory();
        severalAtOnceAndFocus();
        contextMenu();
        System.out.println("CRAFTIUM_SAVED_SELECTIONS_PASS save show release edit eye delete undo numbers claude follow overlap history multi focus menu");
    }

    // ---- Claude --------------------------------------------------------------------------------------------------------------------------

    /** Reads the saved selections by name and builds on two of them in one spec, through the same call the MCP server makes. */
    private void claude() {
        selectOnly(cell());
        var read = bridge("read_selection", "{\"name\":\"buildobject\"}");
        require(read.get("selected").getAsInt() == 8 && read.get("name").getAsString().equals("BuildObject"), "read_selection by name reads that selection (any case): " + read);
        var other = bridge("read_selection", "{\"name\":\"BuildObject (1)\"}");
        require(other.get("selected").getAsInt() == 3 && other.get("name").getAsString().equals("BuildObject (1)"), "and a second read stays apart from the first: " + other);
        require(bridge("read_selection", "{}").get("selected").getAsInt() == 1, "without a name it still reads the player's own selection");
        String unknown = failure("read_selection", "{\"name\":\"Nope\"}");
        require(unknown.contains("BuildObject") && unknown.contains("BuildObject (1)"), "an unknown name lists the names there are: " + unknown);

        // one spec, two selections: each op names its own
        bridge("get_state", "{}");
        var spec = "{\"spec\":{\"ops\":[{\"op\":\"replace_selection\",\"selection\":\"BuildObject\",\"block\":\"gold_block\"},{\"op\":\"replace_selection\",\"selection\":\"buildobject (1)\",\"block\":\"diamond_block\"}]}}";
        String refused = failure("preview_build", "{\"spec\":{\"ops\":[{\"op\":\"replace_selection\",\"selection\":\"BuildObject\",\"block\":\"gold_block\"},{\"op\":\"replace_selection\",\"selection\":\"Nope\",\"block\":\"diamond_block\"}]}}");
        require(refused.contains("Nope") && refused.contains("BuildObject (1)"), "a spec naming an unknown selection is refused, listing the names: " + refused);
        require(count(Blocks.GOLD_BLOCK) == 0, "and nothing was built by the refused one");
        bridge("preview_build", spec);
        bridge("apply_preview", "{}");
        c.waitTicks(20);
        require(allAre(a(), Blocks.GOLD_BLOCK), "BuildObject was changed to gold");
        require(allAre(b(), Blocks.DIAMOND_BLOCK), "BuildObject (1) was changed to diamond in the same build");
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
    private void redo() { sleep(); c.runOnClient(mc -> CraftiumClient.send("redo", null, null)); settle(); }
    private void undo() { sleep(); c.runOnClient(mc -> CraftiumClient.send("undo", null, null)); settle(); }
    private void deleteAll() { while (!ClientState.savedSelections().isEmpty()) click(control("Delete this BuildObject", 0)); }

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

    /** A click and a second click of a double click on the middle of a control. */
    private void doubleClick(int[] area) {
        require(area != null, "the control to double click is not in the window");
        sleep();
        c.runOnClient(mc -> {
            var screen = (EditorScreen) mc.gui.screen();
            double scale;
            try { var f = EditorScreen.class.getDeclaredField("scale"); f.setAccessible(true); scale = f.getFloat(screen); } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            var event = new MouseButtonEvent((area[0] + area[2] / 2.0) * scale, (area[1] + area[3] / 2.0) * scale, new MouseButtonInfo(0, 0));
            screen.mouseClicked(event, false); screen.mouseReleased(event);
            screen.mouseClicked(event, true); screen.mouseReleased(event);
        });
        settle();
    }
    /** Moving or scaling the selected blocks takes the BuildObjects they belong to along, and Undo gives both back. */
    private void transformsCarryObjects() {
        deleteAll();
        selectOnly(a());
        click(control("Create a BuildObject from what is selected now", 0));
        selectOnly(b());
        click(control("Create a BuildObject from what is selected now", 0));
        var minA = saved(0).min();
        var minB = saved(1).min();

        selectOnly(a());
        transform(10, 1, 1, 1);
        require(saved(0).min().equals(minA.add(10, 0, 0)) && saved(1).min().equals(minB), "moving the first one's blocks moves it and not the other: " + ClientState.savedSelections());
        undo();
        require(saved(0).min().equals(minA), "Undo puts the BuildObject back with its blocks: " + ClientState.savedSelections());

        var both = new ArrayList<>(a());
        both.addAll(b());
        selectOnly(both);
        transform(10, 1, 1, 1);
        require(saved(0).min().equals(minA.add(10, 0, 0)) && saved(1).min().equals(minB.add(10, 0, 0)), "moving both takes both: " + ClientState.savedSelections());
        undo();
        require(saved(0).min().equals(minA) && saved(1).min().equals(minB), "and Undo puts both back");

        selectOnly(a());
        transform(0, 2, 1, 1); // twice as wide
        require(saved(0).max().x() - saved(0).min().x() + 1 == 4 && saved(0).size() == 16, "scaling up keeps the BuildObject whole: " + saved(0));
        undo();
        require(saved(0).size() == 8 && saved(0).min().equals(minA), "and Undo shrinks it back");
    }
    /** A selection that touches BuildObjects makes a new one with them inside it, whole, whatever part of them it reached. */
    private void overlapTakesObjectsWhole() {
        deleteAll();
        selectOnly(a());
        click(control("Create a BuildObject from what is selected now", 0));
        selectOnly(b());
        click(control("Create a BuildObject from what is selected now", 0));
        var reach = new ArrayList<>(a());
        reach.add(b().get(0)); // all of the first, one block of the second
        reach.add(cell().get(0)); // and a block that belongs to none
        selectOnly(reach);
        click(control("Create a BuildObject from what is selected now", 0));
        require(ClientState.savedSelections().size() == 3, "one new BuildObject: " + ClientState.savedSelections());
        var around = saved(0);
        require(saved(1).parent() == around.id() && saved(2).parent() == around.id() && around.parent() == 0, "both went inside it, in their order: " + ClientState.savedSelections());
        require(saved(1).size() == 8 && saved(2).size() == 3, "each whole, though the selection reached one block of the second: " + ClientState.savedSelections());
        require(around.size() == 1, "and it holds only the block that belonged to none: " + around);
    }
    /** Several BuildObjects shown together are one selection, and F with exactly one shown puts the view where all of it is on screen, facing its middle. */
    private void severalAtOnceAndFocus() {
        deleteAll();
        selectOnly(a());
        click(control("Create a BuildObject from what is selected now", 0));
        selectOnly(b());
        click(control("Create a BuildObject from what is selected now", 0));
        int one = saved(0).id(), two = saved(1).id();
        sleep();
        c.runOnClient(mc -> CraftiumClient.send("saved_selection:viewset:" + one + "," + two, null, null));
        settle();
        var both = new HashSet<Point>(a());
        both.addAll(b());
        require(saved(0).active() && saved(1).active() && live().equals(both), "two shown together are one selection of both: " + ClientState.savedSelections());

        click(control("Let go", 0)); // a plain click on one of the two shown shows it alone
        require(saved(0).active() && !saved(1).active() && liveIs(a()), "a plain click shows one alone: " + ClientState.savedSelections());
        c.runOnClient(mc -> { mc.player.setPos(baseX + 40, baseY + 5, baseZ + 40); mc.player.setYRot(30f); mc.player.setXRot(10f); mc.player.setOldPosAndRot(); });
        c.waitTicks(3);
        c.runOnClient(mc -> { var e = new net.minecraft.client.input.KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_F, 0, 0); mc.gui.screen().keyPressed(e); mc.gui.screen().keyReleased(e); });
        c.waitTicks(3);
        var centre = new net.minecraft.world.phys.Vec3(baseX + 1, baseY + 1, baseZ + 1);
        double[] result = c.computeOnClient(mc -> {
            var toCentre = centre.subtract(mc.player.getEyePosition());
            return new double[]{toCentre.normalize().dot(mc.player.getLookAngle()), toCentre.length()};
        });
        require(result[0] > 0.999 && result[1] > 2 && result[1] < 40, "F puts the middle of the BuildObject in the middle of the view, back far enough to see it all: " + java.util.Arrays.toString(result));

        click(control("Let go", 0));
        c.runOnClient(mc -> { var e = new net.minecraft.client.input.KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_F, 0, 0); mc.gui.screen().keyPressed(e); mc.gui.screen().keyReleased(e); });
    }
    /** The right-click menu of a row: each item does what it says, and what it changes is in the history. */
    private void contextMenu() {
        deleteAll();
        selectOnly(a());
        click(control("Create a BuildObject from what is selected now", 0));
        selectOnly(b());
        click(control("Create a BuildObject from what is selected now", 0));
        int first = saved(0).id(), second = saved(1).id();

        rightClick(row(0));
        menuClick("Create empty parent");
        require(ClientState.savedSelections().size() == 3 && saved(0).size() == 0 && saved(1).id() == first && saved(1).parent() == saved(0).id() && saved(2).id() == second, "Create empty parent puts a new one around the row: " + ClientState.savedSelections());
        rightClick(row(1));
        menuClick("Move out of parent");
        require(saved(1).id() == first && saved(1).parent() == 0, "Move out of parent: " + ClientState.savedSelections());
        rightClick(row(0));
        menuClick("Delete");
        require(ClientState.savedSelections().size() == 2 && saved(0).id() == first && saved(1).id() == second, "Delete from the menu: " + ClientState.savedSelections());
        undo();
        require(ClientState.savedSelections().size() == 3, "and it is in the history: " + ClientState.savedSelections());
        redo();

        rightClick(row(0));
        menuClick("Move down");
        require(saved(0).id() == second && saved(1).id() == first, "Move down: " + ClientState.savedSelections());
        rightClick(row(1));
        menuClick("Move up");
        require(saved(0).id() == first && saved(1).id() == second, "Move up: " + ClientState.savedSelections());

        sleep();
        c.runOnClient(mc -> CraftiumClient.send("saved_selection:viewset:" + first + "," + second, null, null));
        settle();
        rightClick(row(0));
        menuClick("Group selected (2)");
        require(ClientState.savedSelections().size() == 3 && saved(1).parent() == saved(0).id() && saved(2).parent() == saved(0).id(), "Group selected puts both in a new parent: " + ClientState.savedSelections());
        undo();
        require(ClientState.savedSelections().size() == 2 && saved(0).parent() == 0 && saved(1).parent() == 0, "and Undo takes the group back: " + ClientState.savedSelections());

        rightClick(row(0));
        menuClick("Rename");
        typeText("Wall");press(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER);
        require(saved(0).name().equals("Wall"), "Rename from the menu: " + saved(0).name());

        rightClick(row(0));
        menuClick("Show outline");
        require(!ClientState.savedHidden(saved(0).id()), "Show outline opens the eye");
        rightClick(row(0));
        c.waitTicks(2);
        require(menuControl("Focus") != null && menuControl("Hide outline") != null, "a BuildObject with blocks offers Focus, and the eye item now offers to hide");
        c.takeScreenshot("hierarchy-menu"); // for a person to look at
        press(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE); // closes the menu
    }
    /** The n-th row of the Hierarchy, as an area. */
    private int[] row(int n) {
        int seen = 0;
        for (String line : lines()) {
            if (!line.startsWith("control hierarchy ")) continue;
            int quote = line.indexOf('\'');
            if (quote < 0) continue;
            String tip = line.substring(quote + 1).replaceFirst("^[0-9]+ x [0-9]+ x [0-9]+ blocks · ", "");
            if (!tip.startsWith("Select this BuildObject") && !tip.startsWith("Let go")) continue;
            if (seen++ != n) continue;
            var parts = line.substring("control hierarchy ".length(), quote).trim().split(" ");
            var at = parts[0].split(","); var size = parts[1].split("x");
            return new int[]{Integer.parseInt(at[0]), Integer.parseInt(at[1]), Integer.parseInt(size[0]), Integer.parseInt(size[1])};
        }
        return null;
    }
    /** The menu button with this label, as an area, or null. */
    private int[] menuControl(String label) {
        for (String line : lines()) {
            if (!line.startsWith("control  ")) continue;
            int quote = line.indexOf('\'');
            if (quote < 0 || !line.substring(quote + 1).startsWith("menu " + label)) continue;
            var parts = line.substring("control  ".length(), quote).trim().split(" ");
            var at = parts[0].split(","); var size = parts[1].split("x");
            return new int[]{Integer.parseInt(at[0]), Integer.parseInt(at[1]), Integer.parseInt(size[0]), Integer.parseInt(size[1])};
        }
        return null;
    }
    private void menuClick(String label) {
        c.waitTicks(2);
        click(menuControl(label));
    }
    /** A right click on the middle of an area. */
    private void rightClick(int[] area) {
        require(area != null, "the row to right click is not in the window");
        sleep();
        c.runOnClient(mc -> {
            var screen = (EditorScreen) mc.gui.screen();
            double scale;
            try { var f = EditorScreen.class.getDeclaredField("scale"); f.setAccessible(true); scale = f.getFloat(screen); } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            var event = new MouseButtonEvent((area[0] + area[2] / 2.0) * scale, (area[1] + area[3] / 2.0) * scale, new MouseButtonInfo(1, 0));
            screen.mouseClicked(event, false); screen.mouseReleased(event);
        });
        settle();
    }
    /** Everything done in the Hierarchy is in the history: Undo takes it back and Redo does it again. */
    private void hierarchyIsInHistory() {
        deleteAll();
        selectOnly(a());
        click(control("Create a BuildObject from what is selected now", 0));
        String guid = saved(0).guid();
        require(ClientState.savedSelections().size() == 1, "one BuildObject made");
        undo();
        require(ClientState.savedSelections().isEmpty(), "Undo takes the + back");
        redo();
        require(ClientState.savedSelections().size() == 1 && saved(0).guid().equals(guid), "Redo makes the same BuildObject again: " + ClientState.savedSelections());

        doubleClick(control("Select this BuildObject", 0));
        typeText("Tower");press(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER);
        require(saved(0).name().equals("Tower"), "renamed");
        click(control("Let go", 0));
        undo();
        require(saved(0).name().equals("BuildObject"), "Undo takes the rename back: " + saved(0).name());
        redo();
        require(saved(0).name().equals("Tower"), "and Redo does it again");

        selectOnly(b());
        click(control("Create a BuildObject from what is selected now", 0));
        drag(control("Select this BuildObject", 1), control("Select this BuildObject", 0), 0.5);
        require(saved(1).parent() == saved(0).id(), "dropped into the first: " + ClientState.savedSelections());
        undo();
        require(saved(1).parent() == 0 && saved(0).guid().equals(guid), "Undo takes the move back: " + ClientState.savedSelections());
        redo();
        require(saved(1).parent() == saved(0).id(), "and Redo does it again");

        click(control("Delete this BuildObject", 0));
        require(ClientState.savedSelections().isEmpty(), "the parent took its child with it");
        undo();
        require(ClientState.savedSelections().size() == 2 && saved(1).parent() == saved(0).id() && saved(0).name().equals("Tower"), "Undo brings back both, as they were: " + ClientState.savedSelections());
        redo();
        require(ClientState.savedSelections().isEmpty(), "and Redo deletes them again");
        undo();
        undo(); // the move
        undo(); // the second +
        require(ClientState.savedSelections().size() == 1, "and the stack goes back through all of it: " + ClientState.savedSelections());
    }
    /** Transforms the selected blocks as the Edit menu does: an offset along x and a size along each axis. */
    private void transform(int offsetX, double sizeX, double sizeY, double sizeZ) {
        sleep();
        c.runOnClient(mc -> {
            Options original = ClientState.options;
            var request = dev.craftium.net.Packets.JSON.fromJson(dev.craftium.net.Packets.JSON.toJson(original), Options.class);
            try {
                ClientState.options = request;
                request.yaw = 0; request.pitch = request.roll = 0;
                request.sizeX = sizeX; request.sizeY = sizeY; request.sizeZ = sizeZ;
                request.offsetX = offsetX; request.offsetY = request.offsetZ = 0; request.aboutSet = false; request.turnBlocks = true;
                CraftiumClient.send("selection_transform", null, null);
            } finally { ClientState.options = original; }
        });
        settle();
    }
    /** Presses on the middle of one control, drags to a place {@code at} of the way down another (0 is its top edge) and lets go there. */
    private void drag(int[] from, int[] to, double at) {
        require(from != null && to != null, "the rows to drag are not in the window");
        sleep();
        c.runOnClient(mc -> {
            var screen = (EditorScreen) mc.gui.screen();
            double scale;
            try { var f = EditorScreen.class.getDeclaredField("scale"); f.setAccessible(true); scale = f.getFloat(screen); } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            var down = new MouseButtonEvent((from[0] + from[2] / 2.0) * scale, (from[1] + from[3] / 2.0) * scale, new MouseButtonInfo(0, 0));
            var up = new MouseButtonEvent((to[0] + to[2] / 2.0) * scale, (to[1] + to[3] * at) * scale, new MouseButtonInfo(0, 0));
            screen.mouseClicked(down, false);
            screen.mouseDragged(up, up.x() - down.x(), up.y() - down.y());
            screen.mouseReleased(up);
        });
        settle();
    }
    private void typeText(String text) {
        c.runOnClient(mc -> { for (char ch : text.toCharArray()) mc.gui.screen().charTyped(new net.minecraft.client.input.CharacterEvent(ch)); });
    }
    private void press(int key) {
        c.runOnClient(mc -> { var e = new net.minecraft.client.input.KeyEvent(key, 0, 0); mc.gui.screen().keyPressed(e); mc.gui.screen().keyReleased(e); });
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
        click(control("Select this BuildObject", 1));
        c.runOnClient(mc -> { mc.player.setPos(baseX + 3.5, baseY + 2, baseZ - 6); mc.player.setYRot(0f); mc.player.setXRot(15f); mc.player.setOldPosAndRot(); });
        c.waitTicks(15);
        c.takeScreenshot("saved-selections");
    }
}
