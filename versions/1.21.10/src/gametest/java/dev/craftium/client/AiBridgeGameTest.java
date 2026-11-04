package dev.craftium.client;

import com.google.gson.*;
import dev.craftium.client.compat.ClientCompat;
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
            // The AI tab of the inspector, off and then switched on the way its button does it.
            require(!ctx.computeOnClient(mc -> AiBridge.running()), "the bridge should start off");
            ctx.runOnClient(mc -> { CraftiumClient.open(false); var studio = new StudioScreen((EditorScreen) ClientCompat.screen(mc)); studio.selectTab("AI"); ClientCompat.setScreen(mc, studio); });
            ctx.waitTicks(5);
            ctx.takeScreenshot("ai-tab-off");
            ctx.runOnClient(mc -> AiBridge.toggle());
            ctx.waitTicks(5);
            ctx.takeScreenshot("ai-tab-waiting");
            require(ctx.computeOnClient(mc -> AiBridge.indicator()) == AiBridge.State.WAITING, "a bridge nobody has called should be waiting");
            ctx.runOnClient(mc -> { ClientCompat.screen(mc).onClose(); ((EditorScreen) ClientCompat.screen(mc)).showMenu("AI"); }); // back to the editor, with the AI menu of the bar open
            ctx.waitTicks(5);
            ctx.takeScreenshot("ai-menu");
            ctx.runOnClient(mc -> ClientCompat.screen(mc).onClose()); // out of the editor
            ctx.waitTicks(5);
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
            require(ctx.computeOnClient(mc -> ClientState.pending.ai), "an AI preview should be marked as the AI's");

            // Claude's own view: face the preview, take the picture an AI app would get, and turn back.
            var turned = call("look_at", "{\"target\":[" + (x + 2) + "," + (y + 4) + "," + (z + 2) + "]}");
            require(turned.get("ok").getAsBoolean(), "look_at: " + turned);
            var shot = call("get_screenshot", "{}");
            require(shot.get("ok").getAsBoolean() && shot.get("mime").getAsString().equals("image/jpeg") && shot.get("width").getAsInt() <= 1024, "get_screenshot: " + shot.keySet());
            byte[] jpeg = java.util.Base64.getDecoder().decode(shot.get("image").getAsString());
            require(jpeg.length > 5_000 && (jpeg[0] & 0xff) == 0xff && (jpeg[1] & 0xff) == 0xd8, "the screenshot should be a real JPEG, was " + jpeg.length + " bytes");
            try { Files.write(java.nio.file.Path.of("screenshots", "ai-tool-screenshot.jpg"), jpeg); } catch (java.io.IOException e) { throw new AssertionError(e); }
            var back = call("look_at", "{\"view\":" + turned.get("previousView") + "}");
            require(back.get("ok").getAsBoolean(), "look_at with a view: " + back);
            System.out.println("CRAFTIUM_AI screenshot_ok bytes=" + jpeg.length + " " + shot.get("width") + "x" + shot.get("height"));
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
            require("server".equals(read.get("source").getAsString()), "get_blocks should be read by the server: " + read);
            System.out.println("CRAFTIUM_AI read_back_ok");

            // The ground of the pillar's footprint: the cap is the top of the middle column.
            var ground = call("get_surface", "{\"from\":[" + x + "," + y + "," + z + "],\"to\":[" + (x + 4) + "," + y + "," + (z + 4) + "]}");
            require(ground.get("ok").getAsBoolean() && ground.getAsJsonArray("heights").size() == 25, "get_surface: " + ground);
            require(ground.getAsJsonArray("heights").get(12).getAsInt() == y + 9, "the middle column should top out at the cap: " + ground);
            require(ground.getAsJsonArray("palette").get(ground.getAsJsonArray("blocks").get(12).getAsInt()).getAsString().contains("smooth_sandstone"), "the top block should be the cap: " + ground);
            var wrongKind = call("get_surface", "{\"from\":[0,0,0],\"to\":[1,0,1],\"kind\":\"water\"}");
            require(!wrongKind.get("ok").getAsBoolean(), "an unknown kind should be refused: " + wrongKind);
            System.out.println("CRAFTIUM_AI surface_ok");

            // Far from the player: chunks this client never loaded are still read, from the server, as real ground and not as air.
            int farX = block.get(0).getAsInt() + 1000, farZ = block.get(2).getAsInt() + 1000;
            require(!ctx.computeOnClient(mc -> mc.level.getChunkSource().hasChunk(farX >> 4, farZ >> 4)), "the far area should not be loaded on the client");
            var far = call("get_blocks", "{\"from\":[" + farX + ",-64," + farZ + "],\"to\":[" + (farX + 3) + ",-60," + (farZ + 3) + "]}");
            require(far.get("ok").getAsBoolean() && far.get("chunksLoadedForThisRead").getAsInt() >= 1, "a far read should load its chunk on the server: " + far);
            String farPalette = far.getAsJsonArray("palette").toString();
            require(farPalette.contains("deepslate") || farPalette.contains("bedrock"), "far ground should be real blocks: " + farPalette);
            var tooMany = call("get_blocks", "{\"from\":[" + (farX + 2000) + ",0," + farZ + "],\"to\":[" + (farX + 2000 + 127) + ",0," + (farZ + 127) + "]}");
            require(!tooMany.get("ok").getAsBoolean() && tooMany.get("error").getAsString().contains("chunks"), "a read that needs too many new chunks should be refused: " + tooMany);
            System.out.println("CRAFTIUM_AI far_read_ok");

            var undone = call("undo", "{}");
            require(undone.get("ok").getAsBoolean(), "undo: " + undone);
            for (int i = 0; i < 200 && !ctx.computeOnClient(mc -> mc.level.getBlockState(new BlockPos(x + 2, y + 3, z + 2)).isAir()); i++) ctx.waitTick();
            require(ctx.computeOnClient(mc -> mc.level.getBlockState(new BlockPos(x + 2, y + 3, z + 2)).isAir()), "undo did not remove the pillar");
            System.out.println("CRAFTIUM_AI undo_ok");

            // Cancelling drops the preview without touching the world.
            call("preview_build", "{\"spec\":" + spec + "}");
            var cancelled = call("cancel_preview", "{}");
            require(cancelled.getAsJsonObject("state").get("previewBlocks").getAsInt() == 0, "cancel_preview should clear the ghost: " + cancelled);

            // The real craftium-mcp shim, started as Claude Desktop would, builds a pyramid through the same bridge.
            var mcp = findMcpDirectory();
            if (mcp != null) {
                int px = block.get(0).getAsInt() + 8, pz = block.get(2).getAsInt() + 8;
                ctx.runOnClient(mc -> mc.getConnection().sendCommand("fill " + px + " " + y + " " + pz + " " + (px + 8) + " " + (y + 5) + " " + (pz + 8) + " air"));
                ctx.waitTicks(10);
                try {
                    var process = new ProcessBuilder("node", "test/e2e-client.mjs", AiBridge.FILE.toString()).directory(mcp.toFile()).redirectErrorStream(true).start();
                    var output = new StringBuilder();
                    var reader = CompletableFuture.runAsync(() -> { try { output.append(new String(process.getInputStream().readAllBytes())); } catch (Exception ignored) {} });
                    for (int i = 0; i < 1200 && process.isAlive(); i++) ctx.waitTick();
                    require(!process.isAlive() && process.exitValue() == 0, "the MCP shim run failed:\n" + output);
                    reader.join();
                    require(output.toString().contains("E2E_DONE"), "the MCP shim did not finish:\n" + output);
                    for (int i = 0; i < 200 && !ctx.computeOnClient(mc -> mc.level.getBlockState(new BlockPos(px + 4, y + 5, pz + 4)).is(Blocks.GOLD_BLOCK)); i++) ctx.waitTick();
                    require(ctx.computeOnClient(mc -> mc.level.getBlockState(new BlockPos(px + 4, y + 5, pz + 4)).is(Blocks.GOLD_BLOCK)), "the shim's pyramid has no gold tip:\n" + output);
                    require(ctx.computeOnClient(mc -> mc.level.getBlockState(new BlockPos(px, y, pz)).is(Blocks.SANDSTONE)), "the shim's pyramid has no sandstone base");
                    System.out.println("CRAFTIUM_AI shim_ok\n" + output);
                } catch (java.io.IOException e) { throw new AssertionError("node is needed for the shim check", e); }
            } else System.out.println("CRAFTIUM_AI shim_skipped (no mcp directory found)");

            // With a preview waiting and an AI app that has just called, the tab and the corner marker show it.
            require(ctx.computeOnClient(mc -> AiBridge.indicator()) == AiBridge.State.ACTIVE, "a bridge that was just called should be active");
            ctx.waitTicks(5);
            ctx.takeScreenshot("ai-hud-active");
            call("preview_build", "{\"spec\":" + spec + "}");
            ctx.runOnClient(mc -> { CraftiumClient.open(false); var studio = new StudioScreen((EditorScreen) ClientCompat.screen(mc)); studio.selectTab("AI"); ClientCompat.setScreen(mc, studio); });
            ctx.waitTicks(5);
            ctx.takeScreenshot("ai-tab-active");
            ctx.runOnClient(mc -> { ClientCompat.screen(mc).onClose(); ClientCompat.screen(mc).onClose(); });
            ctx.waitTicks(5);
            call("cancel_preview", "{}");

            ctx.runOnClient(mc -> AiBridge.toggle());
            for (int i = 0; i < 100 && ctx.computeOnClient(mc -> AiBridge.running()); i++) ctx.waitTick();
            require(!ctx.computeOnClient(mc -> AiBridge.running()) && !Files.exists(AiBridge.FILE), "turning the bridge off should close it and remove its file");
        }
        System.out.println("CRAFTIUM_AI done");
    }

    /** The repository's mcp folder, found by walking up from the test's run directory; null when it is not there. */
    private static java.nio.file.Path findMcpDirectory() {
        for (var dir = java.nio.file.Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent())
            if (Files.exists(dir.resolve("mcp").resolve("package.json")) && Files.exists(dir.resolve("mcp").resolve("node_modules"))) return dir.resolve("mcp");
        return null;
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
