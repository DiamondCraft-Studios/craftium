package dev.craftium.client;

import com.google.gson.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;

/**
 * Opt-in (CRAFTIUM_TEST_SCOPE=ai): turns the AI bridge on in a real Creative world and drives it over HTTP the way the
 * craftium-mcp shim does: selecting, previewing a build, applying it, reading it back and undoing it.
 */
public final class AiBridgeGameTest implements FabricClientGameTest {
    private ClientGameTestContext ctx;
    private int port;
    private String token;

    @Override public void runTest(ClientGameTestContext context) {
        if (!"ai".equals(System.getenv("CRAFTIUM_TEST_SCOPE"))) return;
        ctx = context;
        try (var world = ctx.worldBuilder().adjustSettings(s -> { s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE); s.setAllowCommands(true); }).create()) {
            ctx.waitTicks(40);
            world.getClientWorld().waitForChunksDownload();
            ctx.runOnClient(mc -> ClientState.options.aiBridge = true);
            for (int i = 0; i < 100 && !ctx.computeOnClient(mc -> AiBridge.running()); i++) ctx.waitTick();
            require(ctx.computeOnClient(mc -> AiBridge.running()), "bridge did not start");
            try {
                var info = JsonParser.parseString(Files.readString(AiBridge.FILE)).getAsJsonObject();
                port = info.get("port").getAsInt();
                token = info.get("token").getAsString();
            } catch (Exception e) { throw new AssertionError(e); }
            System.out.println("CRAFTIUM_AI bridge_started port=" + port);

            // The doors: no token and a browser's Origin are both refused.
            require(status("/get_state", null, null) == 401, "request without a token was not refused");
            require(status("/get_state", "Bearer " + token, "http://evil.example") == 403, "request with an Origin was not refused");
            System.out.println("CRAFTIUM_AI auth_ok");

            var state = call("get_state", "{}");
            require(state.get("ok").getAsBoolean() && state.get("inWorld").getAsBoolean() && state.get("craftiumOnServer").getAsBoolean(), "get_state: " + state);
            var block = state.getAsJsonObject("player").getAsJsonArray("block");
            int x = block.get(0).getAsInt() + 3, y = block.get(1).getAsInt(), z = block.get(2).getAsInt() + 3;
            ctx.runOnClient(mc -> mc.getConnection().sendCommand("fill " + x + " " + y + " " + z + " " + (x + 6) + " " + (y + 12) + " " + (z + 6) + " air"));
            ctx.waitTicks(10);

            var selected = call("set_selection", "{\"from\":[" + x + "," + y + "," + z + "],\"to\":[" + (x + 4) + "," + (y + 9) + "," + (z + 4) + "]}");
            require(selected.get("ok").getAsBoolean(), "set_selection: " + selected);
            require(selected.getAsJsonObject("state").getAsJsonObject("selection").get("size").getAsInt() == 250, "selection size: " + selected);
            System.out.println("CRAFTIUM_AI selected " + selected.get("message").getAsString());

            String spec = "{\"palette\":{\"sand\":\"minecraft:cut_sandstone\",\"cap\":\"minecraft:smooth_sandstone\"},\"ops\":["
                    + "{\"op\":\"cylinder\",\"base\":[2,0,2],\"radius\":2,\"height\":8,\"block\":\"sand\"},"
                    + "{\"op\":\"box\",\"from\":[0,8,0],\"to\":[4,9,4],\"block\":\"cap\"}]}";
            var bad = call("preview_build", "{\"spec\":{\"ops\":[{\"op\":\"cylinder\",\"base\":[0,0,0],\"block\":\"stone\"}]}}");
            require(!bad.get("ok").getAsBoolean() && bad.get("error").getAsString().contains("ops[0]"), "a bad spec should explain itself: " + bad);
            var preview = call("preview_build", "{\"spec\":" + spec + "}");
            require(preview.get("ok").getAsBoolean(), "preview_build: " + preview);
            int ghost = preview.getAsJsonObject("state").get("previewBlocks").getAsInt();
            require(ghost > 100, "preview should hold blocks as a ghost, had " + ghost);
            ctx.waitTicks(5);
            ctx.takeScreenshot("ai-preview");
            require(ctx.computeOnClient(mc -> mc.level.getBlockState(new BlockPos(x + 2, y + 3, z + 2)).isAir()), "a preview must not touch the world");
            System.out.println("CRAFTIUM_AI preview_ok blocks=" + ghost);

            var applied = call("apply_preview", "{}");
            require(applied.get("ok").getAsBoolean(), "apply_preview: " + applied);
            for (int i = 0; i < 200 && !ctx.computeOnClient(mc -> mc.level.getBlockState(new BlockPos(x + 2, y + 3, z + 2)).is(Blocks.CUT_SANDSTONE)); i++) ctx.waitTick();
            require(ctx.computeOnClient(mc -> mc.level.getBlockState(new BlockPos(x + 2, y + 3, z + 2)).is(Blocks.CUT_SANDSTONE)), "the pillar was not built");
            require(ctx.computeOnClient(mc -> mc.level.getBlockState(new BlockPos(x, y + 9, z)).is(Blocks.SMOOTH_SANDSTONE)), "the cap was not built");
            ctx.takeScreenshot("ai-built");
            System.out.println("CRAFTIUM_AI applied");

            var read = call("get_blocks", "{\"from\":[" + x + "," + y + "," + z + "],\"to\":[" + (x + 4) + "," + (y + 1) + "," + (z + 4) + "]}");
            require(read.get("ok").getAsBoolean() && read.getAsJsonArray("palette").toString().contains("cut_sandstone"), "get_blocks: " + read);
            System.out.println("CRAFTIUM_AI read_back_ok");

            var undone = call("undo", "{}");
            require(undone.get("ok").getAsBoolean(), "undo: " + undone);
            for (int i = 0; i < 200 && !ctx.computeOnClient(mc -> mc.level.getBlockState(new BlockPos(x + 2, y + 3, z + 2)).isAir()); i++) ctx.waitTick();
            require(ctx.computeOnClient(mc -> mc.level.getBlockState(new BlockPos(x + 2, y + 3, z + 2)).isAir()), "undo did not remove the pillar");
            System.out.println("CRAFTIUM_AI undo_ok");

            // Cancelling drops the preview without touching the world.
            call("preview_build", "{\"spec\":" + spec + "}");
            var cancelled = call("cancel_preview", "{}");
            require(cancelled.getAsJsonObject("state").get("previewBlocks").getAsInt() == 0, "cancel_preview should clear the ghost: " + cancelled);
            ctx.runOnClient(mc -> ClientState.options.aiBridge = false);
            for (int i = 0; i < 100 && ctx.computeOnClient(mc -> AiBridge.running()); i++) ctx.waitTick();
            require(!ctx.computeOnClient(mc -> AiBridge.running()) && !Files.exists(AiBridge.FILE), "turning the bridge off should close it and remove its file");
        }
        System.out.println("CRAFTIUM_AI done");
    }

    /** Calls a tool from another thread while the game keeps ticking, since the bridge answers from the game thread. */
    private JsonObject call(String tool, String body) {
        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/" + tool)).header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.ofString(body)).build();
        CompletableFuture<HttpResponse<String>> future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        for (int i = 0; i < 1200 && !future.isDone(); i++) ctx.waitTick();
        try { return JsonParser.parseString(future.get().body()).getAsJsonObject(); } catch (Exception e) { throw new AssertionError("bridge call " + tool + " failed", e); }
    }

    private int status(String path, String authorization, String origin) {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).POST(HttpRequest.BodyPublishers.ofString("{}"));
        if (authorization != null) builder.header("Authorization", authorization);
        if (origin != null) builder.header("Origin", origin);
        try { return HttpClient.newHttpClient().sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).get().statusCode(); } catch (Exception e) { throw new AssertionError(e); }
    }

    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
