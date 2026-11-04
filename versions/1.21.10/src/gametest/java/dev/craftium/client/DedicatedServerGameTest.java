package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.core.Options;
import dev.craftium.core.Point;
import dev.craftium.server.WorldRules;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Properties;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Runs the Fabric mod on a real in-process dedicated server and connects the normal client to it over loopback TCP, so every
 * request crosses the network codec and is authorised by ops.json as on a hosted server. World assertions read the server's level.
 * Opt in with CRAFTIUM_TEST_SCOPE=dedicated and -Pcraftium.acceptEula=true (see docs/SERVER-TESTS.md).
 */
public final class DedicatedServerGameTest implements FabricClientGameTest {
    private static final String TAG = "CRAFTIUM_DEDICATED ";
    /** A stone platform far above the terrain, so the tests do not depend on world generation. */
    private static final int Y = 200;
    private final Point up = new Point(0, 1, 0);
    private ClientGameTestContext ctx;
    private TestDedicatedServerContext server;
    private String player;

    @Override public void runTest(ClientGameTestContext context) {
        if (!"dedicated".equals(System.getenv("CRAFTIUM_TEST_SCOPE"))) return;
        ctx = context;
        var props = new Properties();
        props.setProperty("gamemode", "creative");
        props.setProperty("force-gamemode", "true");
        props.setProperty("spawn-protection", "0");
        props.setProperty("op-permission-level", "4");
        try (var dedicated = ctx.worldBuilder().createServer(props)) {
            server = dedicated;
            boot();
            TestServerConnection connection = server.connect(); // closed by persistence(), which reconnects
            {
                join(connection);
                permissions();
                selectionAndHistory();
                clipboardAndChest();
                largeSelection();
                builder();
                bucketFill();
                entities();
                worldRules();
                libraries();
                editorAndClientCommands();
                persistence(connection);
            }
            ctx.runOnClient(mc -> ClientCompat.setScreen(mc, new TitleScreen())); // the framework requires tests to end here
        }
        System.out.println(TAG + "PASSED dedicated 1.21.10: boot, permissions, selection/history, clipboard, chest NBT, fragmented transfer, builder, entities, world rules, blueprints, editor, reconnect");
    }

    // ---- stages ----------------------------------------------------------------------------------------------------

    private void boot() {
        require(server.computeOnServer(s -> s.isDedicatedServer()), "The test server is not a dedicated server");
        require(FabricLoader.getInstance().isModLoaded("craftium"), "Craftium is not loaded");
        require(server.computeOnServer(s -> s.getPlayerCount() == 0), "The server already has players");
        System.out.println(TAG + "boot dedicated_server_started");
    }

    private void join(TestServerConnection connection) {
        ctx.runOnClient(mc -> ClientState.options = new Options());
        connection.getClientWorld().waitForChunksDownload();
        player = ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
        // The platform: floor, then clear air above it, then move the player onto it.
        server.runCommand("fill -12 " + (Y - 1) + " -12 12 " + (Y - 1) + " 20 stone");
        server.runCommand("fill -12 " + Y + " -12 12 " + (Y + 8) + " 20 air");
        server.runCommand("tp " + player + " 0 " + Y + " 14 180 25");
        ctx.waitTicks(20);
        connection.getClientWorld().waitForChunksDownload();
        until(mc -> CraftiumClient.supported(), "channel negotiation");
        require(server.computeOnServer(s -> s.getPlayerCount() == 1), "The server does not see the client");
        System.out.println(TAG + "join channel_negotiated as " + player);
    }

    /** Operator status comes from ops.json on a dedicated server, so a fresh player is refused until opped. */
    private void permissions() {
        pos(0, Y, 0, 1, Y, 1);
        send("select_box", null);
        require(error(), "A non-operator's selection was accepted: " + message());
        options(o -> o.block = "minecraft:gold_block");
        send("fill", null);
        require(error() && block(0, Y, 0) == Blocks.AIR, "A non-operator edit changed the world");
        ctx.runOnClient(mc -> mc.getConnection().sendCommand("craftiumDoBlockGravity false"));
        ctx.waitTicks(10);
        require(server.computeOnServer(s -> WorldRules.get(s.overworld()).blockGravity), "A non-operator changed a world rule");
        System.out.println(TAG + "permissions non_operator_denied");

        server.runCommand("op " + player);
        ctx.waitTicks(10);
        request("select_box", null);
        require(ok() && size() == 4, "An operator's selection failed: " + message() + " size=" + size());
        server.runCommand("deop " + player);
        ctx.waitTicks(5);
        send("fill", null);
        require(error() && block(0, Y, 0) == Blocks.AIR, "A deopped player could still edit");
        server.runCommand("op " + player);
        ctx.waitTicks(5);
        request("clear", null);
        System.out.println(TAG + "permissions op_granted deop_revoked");
    }

    private void selectionAndHistory() {
        options(o -> o.block = "minecraft:gold_block");
        pos(0, Y, 0, 2, Y + 1, 2);
        request("select_box", null);
        require(size() == 18, "Selection size is " + size());
        request("fill", null);
        checkBlock(1, Y, 1, Blocks.GOLD_BLOCK);
        request("undo", null);
        checkBlock(1, Y, 1, Blocks.AIR);
        request("redo", null);
        checkBlock(1, Y, 1, Blocks.GOLD_BLOCK);
        // Operations on the existing selection.
        options(o -> o.block = "minecraft:diamond_block");
        request("fill_outline", null);
        checkBlock(0, Y, 0, Blocks.DIAMOND_BLOCK);
        request("undo", null);
        checkBlock(0, Y, 0, Blocks.GOLD_BLOCK);
        request("hollow", null);
        request("undo", null);
        checkBlock(1, Y, 1, Blocks.GOLD_BLOCK);
        checkBlock(1, Y + 1, 1, Blocks.GOLD_BLOCK);
        options(o -> o.block = "minecraft:emerald_block");
        request("replace", null);
        checkBlock(1, Y, 1, Blocks.EMERALD_BLOCK);
        request("undo", null);
        checkBlock(1, Y, 1, Blocks.GOLD_BLOCK);
        request("delete", null);
        checkBlock(1, Y, 1, Blocks.AIR);
        request("undo", null);
        checkBlock(1, Y, 1, Blocks.GOLD_BLOCK);
        request("delete", null);
        request("clear", null);
        System.out.println(TAG + "selection_history select fill undo redo outline replace delete");
    }

    private void clipboardAndChest() {
        server.runCommand("fill 0 " + Y + " 0 2 " + (Y + 1) + " 2 air");
        server.runCommand("setblock 0 " + Y + " 0 gold_block");
        server.runCommand("setblock 1 " + Y + " 0 diamond_block");
        pos(0, Y, 0, 1, Y, 0);
        request("select_box", null);
        request("copy", null);
        require(ctx.computeOnClient(mc -> ClientState.status.clipboardSize()) == 2, "Clipboard size is " + ctx.computeOnClient(mc -> ClientState.status.clipboardSize()));
        request("paste_at", new Point(6, Y, 0));
        checkBlock(6, Y, 0, Blocks.GOLD_BLOCK);
        checkBlock(7, Y, 0, Blocks.DIAMOND_BLOCK);
        request("undo", null);
        checkBlock(6, Y, 0, Blocks.AIR);
        System.out.println(TAG + "clipboard copy paste undo");

        // Container contents survive a native delete and its undo.
        server.runCommand("setblock 3 " + Y + " 3 chest{Items:[{Slot:0b,id:\"minecraft:diamond\",count:7}]}");
        pos(3, Y, 3, 3, Y, 3);
        request("select_box", null);
        request("delete", null);
        checkBlock(3, Y, 3, Blocks.AIR);
        request("undo", null);
        int diamonds = server.computeOnServer(s -> s.overworld().getBlockEntity(new BlockPos(3, Y, 3)) instanceof Container c ? c.getItem(0).getCount() : -1);
        require(diamonds == 7, "The chest held " + diamonds + " diamonds after undo");
        request("clear", null);
        server.runCommand("fill 0 " + Y + " 0 7 " + (Y + 1) + " 3 air");
        System.out.println(TAG + "chest_nbt diamonds=" + diamonds);
    }

    /** 16,000 positions are far over one packet, so this exercises fragmentation both ways. */
    private void largeSelection() {
        ctx.runOnClient(mc -> {
            var points = new ArrayList<Point>();
            for (int x = 0; x < 60; x++) for (int y = 0; y < 20; y++) for (int z = 0; z < 20; z++) if ((x + y + z) % 3 != 0) points.add(new Point(x - 30, Y + y, z - 10));
            Collections.shuffle(points, new Random(7));
            ClientState.options.selectionPoints.clear();
            ClientState.options.selectionPoints.addAll(points);
        });
        request("lasso_select", null);
        require(size() == 16000, "Fragmented selection counted " + size() + ": " + message());
        request("clear", null);
        ctx.runOnClient(mc -> ClientState.options.selectionPoints.clear());
        System.out.println(TAG + "fragmented_selection 16000");
    }

    private void bucketFill() {
        server.runCommand("fill 0 " + Y + " 6 2 " + Y + " 6 stone");
        options(o -> {o.noUpdates = true; o.block = "minecraft:gold_block";});
        request("bucket_fill", new Point(0, Y, 6));
        for (int x = 0; x <= 2; x++) checkBlock(x, Y, 6, Blocks.GOLD_BLOCK);
        request("undo", null);
        for (int x = 0; x <= 2; x++) checkBlock(x, Y, 6, Blocks.STONE);
        server.runCommand("fill 0 " + Y + " 6 2 " + Y + " 6 air");
        // With "confirm edits" on, as it is by default, dropping a block applies at once: the drop is the confirmation.
        server.runCommand("fill 0 " + Y + " 6 2 " + Y + " 6 stone");
        pause(90);
        ctx.runOnClient(mc -> {ClientState.options.confirmEdits = true; CraftiumClient.send("bucket_fill", new Point(0, Y, 6), up);});
        for (int i = 0; i < 8; i++) {pause(60); ctx.waitTicks(2);}
        until(mc -> !ClientState.status.busy(), "completion of bucket_fill");
        System.out.println(TAG + "bucket_fill with confirm edits: " + message());
        for (int x = 0; x <= 2; x++) checkBlock(x, Y, 6, Blocks.GOLD_BLOCK);
        request("undo", null);
        server.runCommand("fill 0 " + Y + " 6 2 " + Y + " 6 air");
        System.out.println(TAG + "bucket fill undo");
    }

    private void builder() {
        server.runCommand("setblock 0 " + Y + " 6 gold_block");
        server.runCommand("setblock 1 " + Y + " 6 diamond_block");
        options(o -> {o.noUpdates = true; o.builderTool = Options.BuilderTool.MOVE; o.builderOffset = new Point(3, 0, 0);});
        pos(0, Y, 6, 1, Y, 6);
        request("builder_commit", new Point(0, Y, 6));
        checkBlock(0, Y, 6, Blocks.AIR);
        checkBlock(3, Y, 6, Blocks.GOLD_BLOCK);
        checkBlock(4, Y, 6, Blocks.DIAMOND_BLOCK);
        request("undo", null);
        checkBlock(0, Y, 6, Blocks.GOLD_BLOCK);
        checkBlock(3, Y, 6, Blocks.AIR);
        options(o -> o.builderTool = Options.BuilderTool.CLONE);
        request("builder_commit", new Point(0, Y, 6));
        checkBlock(0, Y, 6, Blocks.GOLD_BLOCK);
        checkBlock(3, Y, 6, Blocks.GOLD_BLOCK);
        request("undo", null);
        options(o -> {o.builderTool = Options.BuilderTool.STACK; o.stackX = 0; o.stackY = 0; o.stackZ = 3;}); // whole copies laid edge to edge
        request("builder_commit", new Point(0, Y, 6));
        checkBlock(0, Y, 9, Blocks.GOLD_BLOCK);
        request("undo", null);
        checkBlock(0, Y, 9, Blocks.AIR);
        options(o -> o.builderTool = Options.BuilderTool.ERASE);
        request("builder_commit", new Point(0, Y, 6));
        checkBlock(0, Y, 6, Blocks.AIR);
        request("undo", null);
        checkBlock(0, Y, 6, Blocks.GOLD_BLOCK);
        server.runCommand("fill 0 " + Y + " 6 4 " + Y + " 12 air");
        System.out.println(TAG + "builder move clone stack erase undo");
    }

    private void entities() {
        options(o -> {o.entityType = "block_display"; o.entityNbt = "{block_state:{Name:\"minecraft:stone\"}}"; o.entityUuid = "";});
        request("entity_spawn", new Point(0, Y, 8));
        require(displays() == 1, "Expected one display entity, found " + displays());
        request("entity_delete", new Point(0, Y, 8));
        require(displays() == 0, "The display entity was not deleted");
        request("undo", null);
        require(displays() == 1, "Undo did not restore the display entity");
        request("undo", null);
        require(displays() == 0, "Undo did not remove the spawned display entity");
        options(o -> o.entityUuid = "");
        System.out.println(TAG + "entities spawn delete undo");
    }

    private void worldRules() {
        // Gravity off: sand stays where it is placed. Gravity on: it falls.
        server.runCommand("craftiumDoBlockGravity false");
        require(server.computeOnServer(s -> !WorldRules.get(s.overworld()).blockGravity), "The gravity rule did not apply");
        server.runCommand("setblock 9 " + (Y + 5) + " 9 sand");
        ctx.waitTicks(20);
        checkBlock(9, Y + 5, 9, Blocks.SAND);
        server.runCommand("craftiumDoBlockGravity true");
        server.runCommand("setblock 9 " + (Y + 6) + " 9 sand");
        ctx.waitTicks(30);
        require(block(9, Y + 6, 9) == Blocks.AIR, "Sand did not fall with gravity on");
        server.runCommand("fill 9 " + Y + " 9 9 " + (Y + 8) + " 9 air");
        System.out.println(TAG + "world_rules gravity_off gravity_on");
    }

    private void libraries() {
        options(o -> {o.name = "Dedicated-transfer-test"; o.downloadExports = true;});
        server.runCommand("setblock 0 " + Y + " 0 gold_block");
        server.runCommand("setblock 1 " + Y + " 0 diamond_block");
        pos(0, Y, 0, 1, Y, 0);
        request("select_box", null);
        request("copy", null);
        int version = ctx.computeOnClient(mc -> ClientState.libraryVersion);
        send("structure_export", null);
        until(mc -> ClientState.libraryVersion > version && ClientState.notice.startsWith("Downloaded "), "export download to the client");
        // The client's own copy goes back up to the server as a blueprint.
        ctx.runOnClient(mc -> ClientAssets.load("Dedicated-transfer-test.nbt"));
        until(mc -> ClientState.status.message().startsWith("Loaded local") && ClientState.status.clipboardSize() == 2, "local blueprint upload");
        pause(300); // let the paste that follows queue behind the upload
        request("paste_at", new Point(6, Y, 0));
        checkBlock(6, Y, 0, Blocks.GOLD_BLOCK);
        checkBlock(7, Y, 0, Blocks.DIAMOND_BLOCK);
        request("undo", null);
        request("clear", null);
        server.runCommand("fill 0 " + Y + " 0 7 " + Y + " 0 air");
        System.out.println(TAG + "libraries export_download local_upload paste");
    }

    private void editorAndClientCommands() {
        ctx.runOnClient(mc -> CraftiumClient.open(false));
        ctx.waitTicks(10);
        ctx.takeScreenshot("dedicated-editor");
        require(ctx.computeOnClient(mc -> ClientCompat.screen(mc) instanceof EditorScreen), "The editor did not open on a dedicated server");
        ctx.runOnClient(mc -> mc.screen.onClose());
        ctx.waitTicks(5);
        require(ctx.computeOnClient(mc -> mc.player.isCreative()), "The game mode was not restored when the editor closed");
        ctx.runOnClient(mc -> mc.getConnection().sendCommand("craftium toggle fastPlace"));
        ctx.waitTicks(5);
        require(ctx.computeOnClient(mc -> ClientState.options.fastPlace), "The client command did not run on the dedicated server");
        ctx.runOnClient(mc -> mc.getConnection().sendCommand("craftium toggle fastPlace"));
        ctx.waitTicks(3);
        ctx.runOnClient(mc -> mc.getConnection().sendCommand("craftium pos1 5 " + Y + " 5"));
        ctx.waitTicks(3);
        ctx.runOnClient(mc -> mc.getConnection().sendCommand("craftium pos2 6 " + Y + " 5"));
        until(mc -> ClientState.status.selectionSize() == 2, "position commands to select");
        request("clear", null);
        System.out.println(TAG + "client editor_open mode_restored commands");
    }

    /** History written before a disconnect is available again after reconnecting. */
    private void persistence(TestServerConnection connection) {
        options(o -> o.block = "minecraft:lapis_block");
        pos(4, Y, 4, 5, Y, 4);
        request("select_box", null);
        request("fill", null);
        checkBlock(4, Y, 4, Blocks.LAPIS_BLOCK);
        request("history_save", null);
        connection.close();
        for (int i = 0; i < 100 && server.computeOnServer(s -> s.getPlayerCount() != 0); i++) ctx.waitTick();
        require(server.computeOnServer(s -> s.getPlayerCount() == 0), "The player is still counted after disconnecting");
        try (var again = server.connect()) {
            ctx.runOnClient(mc -> ClientState.options = new Options());
            again.getClientWorld().waitForChunksDownload();
            until(mc -> CraftiumClient.supported(), "channel negotiation after reconnect");
            request("history_reload", null);
            require(ctx.computeOnClient(mc -> ClientState.status.undo()) >= 1, "Saved history was not restored after reconnecting");
            request("undo", null);
            checkBlock(4, Y, 4, Blocks.AIR);
            System.out.println(TAG + "persistence disconnect reconnect history_reload undo");
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private void send(String action, Point target) {
        pause(90); // the server throttles requests by wall-clock time
        ctx.runOnClient(mc -> {ClientState.options.confirmEdits = false; CraftiumClient.send(action, target, up);});
        for (int i = 0; i < 8; i++) {pause(60); ctx.waitTicks(2);}
        until(mc -> !ClientState.status.busy(), "completion of " + action);
        System.out.println(TAG + action + ": " + message());
    }
    private void request(String action, Point target) {
        send(action, target);
        require(ok(), "Request " + action + " failed: " + message() + " (player at " + ctx.computeOnClient(mc -> mc.player.blockPosition().toShortString()) + ")");
    }
    private void options(Consumer<Options> set) {ctx.runOnClient(mc -> {ClientState.options.confirmEdits = false; set.accept(ClientState.options);});}
    private void pos(int x1, int y1, int z1, int x2, int y2, int z2) {options(o -> {o.position1 = new Point(x1, y1, z1); o.position2 = new Point(x2, y2, z2); o.selectionPoints.clear();});}
    private boolean error() {return ctx.computeOnClient(mc -> ClientState.status.error());}
    private boolean ok() {return !error();}
    private int size() {return ctx.computeOnClient(mc -> ClientState.status.selectionSize());}
    private String message() {return ctx.computeOnClient(mc -> ClientState.status.message());}
    private Block block(int x, int y, int z) {return server.computeOnServer(s -> s.overworld().getBlockState(new BlockPos(x, y, z)).getBlock());}
    private void checkBlock(int x, int y, int z, Block expected) {
        Block actual = block(x, y, z);
        require(actual == expected, "Block at " + x + "," + y + "," + z + " is " + actual + ", expected " + expected);
    }
    private int displays() {return server.computeOnServer(s -> s.overworld().getEntities(EntityType.BLOCK_DISPLAY, new AABB(-20, Y - 5, -20, 20, Y + 20, 20), e -> true).size());}
    private void until(Predicate<Minecraft> condition, String what) {
        for (int i = 0; i < 600; i++) {if (ctx.computeOnClient(condition::test)) return; pause(50); ctx.waitTicks(2);}
        throw new AssertionError("Timed out waiting for " + what + "; " + message());
    }
    private static void require(boolean test, String message) {if (!test) throw new AssertionError(message);}
    private static void pause(long millis) {try {Thread.sleep(millis);} catch (InterruptedException e) {throw new AssertionError(e);}}
}
