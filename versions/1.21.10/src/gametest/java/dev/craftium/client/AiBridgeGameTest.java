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

            // The player's own selection, made the way the editor makes it. The AI never changes it.
            ctx.runOnClient(mc -> {
                var o = ClientState.options;
                o.position1 = new dev.craftium.core.Point(x + 20, y, z + 20);
                o.position2 = new dev.craftium.core.Point(x + 21, y + 1, z + 21);
                o.mode = dev.craftium.core.Selection.Mode.REPLACE;
                CraftiumClient.send("select_box", o.position1, null);
            });
            ctx.waitTicks(10);
            var mine = call("get_state", "{}").getAsJsonObject("selection");
            require(mine.get("size").getAsInt() == 8, "the player's own selection should be 8 blocks: " + mine);

            // read_selection describes it as it really is, an exact 2 x 2 x 2 box read from the server, and reading it changes nothing.
            var described = call("read_selection", "{}");
            require(described.get("ok").getAsBoolean() && described.get("selected").getAsInt() == 8 && "box".equals(described.get("shape").getAsString()), "read_selection: " + described);
            int counted = 0;
            for (var entry : described.getAsJsonArray("blocks")) counted += entry.getAsJsonObject().get("count").getAsInt();
            require(counted == 8 && described.has("above") && described.get("summary").getAsString().contains("8 blocks"), "read_selection should count its blocks and look above them: " + described);
            require(call("get_state", "{}").getAsJsonObject("selection").get("size").getAsInt() == 8, "read_selection must not change the selection");
            System.out.println("CRAFTIUM_AI read_selection_ok " + described.get("summary").getAsString());

            String spec = "{\"palette\":{\"sand\":\"minecraft:cut_sandstone\",\"cap\":\"minecraft:smooth_sandstone\"},\"ops\":["
                    + "{\"op\":\"cylinder\",\"base\":[" + (x + 2) + "," + y + "," + (z + 2) + "],\"radius\":2,\"height\":8,\"block\":\"sand\"},"
                    + "{\"op\":\"box\",\"from\":[" + x + "," + (y + 8) + "," + z + "],\"to\":[" + (x + 4) + "," + (y + 9) + "," + (z + 4) + "],\"block\":\"cap\"}]}";
            var bad = call("preview_build", "{\"spec\":{\"ops\":[{\"op\":\"cylinder\",\"base\":[0,0,0],\"block\":\"stone\"}]}}");
            require(!bad.get("ok").getAsBoolean() && bad.get("error").getAsString().contains("ops[0]"), "a bad spec should explain itself: " + bad);
            var preview = call("preview_build", "{\"spec\":" + spec + "}");
            require(preview.get("ok").getAsBoolean(), "preview_build: " + preview);
            int ghost = preview.getAsJsonObject("state").get("previewBlocks").getAsInt();
            require(ghost > 100, "preview should hold blocks as a ghost, had " + ghost);
            ctx.waitTicks(5);
            ctx.takeScreenshot("ai-preview");
            require(ctx.computeOnClient(mc -> ClientState.hasAiPending() && ClientState.aiPending.ai), "the AI's preview should be held as the AI's own");
            require(ctx.computeOnClient(mc -> !ClientState.hasPending()), "the AI's preview must not become the player's own pending edit");

            // What the player sees, as a screenshot: it is not turned or moved for it.
            float yawBefore = ctx.computeOnClient(mc -> mc.player.getYRot());
            var shot = call("get_screenshot", "{}");
            require(shot.get("ok").getAsBoolean() && shot.get("mime").getAsString().equals("image/jpeg") && shot.get("width").getAsInt() <= 1024, "get_screenshot: " + shot.keySet());
            byte[] jpeg = java.util.Base64.getDecoder().decode(shot.get("image").getAsString());
            require(jpeg.length > 5_000 && (jpeg[0] & 0xff) == 0xff && (jpeg[1] & 0xff) == 0xd8, "the screenshot should be a real JPEG, was " + jpeg.length + " bytes");
            try { Files.write(java.nio.file.Path.of("screenshots", "ai-tool-screenshot.jpg"), jpeg); } catch (java.io.IOException e) { throw new AssertionError(e); }
            System.out.println("CRAFTIUM_AI screenshot_ok bytes=" + jpeg.length + " " + shot.get("width") + "x" + shot.get("height"));

            // The AI's own camera: the box with the preview drawn in violet, from two angles, without turning the player.
            String box = "\"from\":[" + x + "," + y + "," + z + "],\"to\":[" + (x + 4) + "," + (y + 9) + "," + (z + 4) + "]";
            var view = call("render_view", "{" + box + ",\"angle\":\"se\"}");
            require(view.get("ok").getAsBoolean() && view.get("mime").getAsString().equals("image/png"), "render_view: " + view);
            var picture = decode(view);
            require(violet(picture) > 200, "the preview should be drawn tinted violet, found " + violet(picture) + " pixels");
            require(view.get("previewBlocksShown").getAsInt() == ghost, "every preview block in the box should be drawn: " + view);
            var side = call("render_view", "{" + box + ",\"angle\":\"south\",\"preview\":false}");
            require(side.get("ok").getAsBoolean() && violet(decode(side)) == 0 && side.get("previewBlocksShown").getAsInt() == 0, "without the preview nothing is violet: " + side.keySet());
            try { Files.write(java.nio.file.Path.of("screenshots", "ai-render-view.png"), java.util.Base64.getDecoder().decode(view.get("image").getAsString())); } catch (java.io.IOException e) { throw new AssertionError(e); }
            require(ctx.computeOnClient(mc -> mc.player.getYRot()) == yawBefore, "render_view must not turn the player");
            var tooBig = call("render_view", "{\"from\":[0,0,0],\"to\":[199,9,9]}");
            require(!tooBig.get("ok").getAsBoolean() && tooBig.get("error").getAsString().contains("128"), "an oversized box should be refused: " + tooBig);
            var badAngle = call("render_view", "{" + box + ",\"angle\":\"above\"}");
            require(!badAngle.get("ok").getAsBoolean(), "an unknown angle should be refused: " + badAngle);
            System.out.println("CRAFTIUM_AI render_view_ok " + view.get("width") + "x" + view.get("height") + " blocks=" + view.getAsJsonArray("blocks").size());
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
            var farView = call("render_view", "{\"from\":[" + farX + ",-64," + farZ + "],\"to\":[" + (farX + 15) + ",-50," + (farZ + 15) + "],\"angle\":\"nw\"}");
            require(farView.get("ok").getAsBoolean() && farView.getAsJsonArray("blocks").size() > 0, "a far render should show real ground: " + farView.keySet());
            System.out.println("CRAFTIUM_AI far_render_ok");

            var undone = call("undo", "{}");
            require(undone.get("ok").getAsBoolean(), "undo: " + undone);
            for (int i = 0; i < 200 && !ctx.computeOnClient(mc -> mc.level.getBlockState(new BlockPos(x + 2, y + 3, z + 2)).isAir()); i++) ctx.waitTick();
            require(ctx.computeOnClient(mc -> mc.level.getBlockState(new BlockPos(x + 2, y + 3, z + 2)).isAir()), "undo did not remove the pillar");
            System.out.println("CRAFTIUM_AI undo_ok");
            var after = call("get_state", "{}");
            require(after.getAsJsonObject("selection").get("size").getAsInt() == 8 && after.get("undoSteps").getAsInt() == 0,
                    "the AI must leave the player's selection alone, and its undo list is empty again: " + after);
            System.out.println("CRAFTIUM_AI selection_untouched");

            // A build far from the player, in ground nobody has loaded: previewed, applied and undone without anyone going there.
            int fx = block.get(0).getAsInt() + 3000, fz = block.get(2).getAsInt() + 3000;
            require(!ctx.computeOnClient(mc -> mc.level.getChunkSource().hasChunk(fx >> 4, fz >> 4)), "the far build site should not be loaded on the client");
            String farSpec = "{\"ops\":[{\"op\":\"pyramid\",\"base\":[" + fx + ",150," + fz + "],\"radius\":4,\"height\":5,\"block\":\"minecraft:sandstone\"}]}";
            var farPreview = call("preview_build", "{\"spec\":" + farSpec + "}");
            require(farPreview.get("ok").getAsBoolean() && farPreview.getAsJsonObject("state").get("previewBlocks").getAsInt() > 20, "a far preview: " + farPreview);
            var farApplied = call("apply_preview", "{}");
            require(farApplied.get("ok").getAsBoolean(), "a far apply: " + farApplied);
            JsonObject farRead = null;
            for (int i = 0; i < 100; i++) {
                ctx.waitTick();
                farRead = call("get_blocks", "{\"from\":[" + fx + ",150," + fz + "],\"to\":[" + fx + ",150," + fz + "]}");
                if (farRead.get("ok").getAsBoolean() && farRead.getAsJsonArray("palette").toString().contains("minecraft:sandstone")) break;
            }
            require(farRead != null && farRead.getAsJsonArray("palette").toString().contains("minecraft:sandstone"), "the far pyramid was not built: " + farRead);
            var farUndone = call("undo", "{}");
            require(farUndone.get("ok").getAsBoolean(), "a far undo: " + farUndone);
            for (int i = 0; i < 100; i++) {
                ctx.waitTick();
                farRead = call("get_blocks", "{\"from\":[" + fx + ",150," + fz + "],\"to\":[" + fx + ",150," + fz + "]}");
                if (farRead.get("ok").getAsBoolean() && !farRead.getAsJsonArray("palette").toString().contains("minecraft:sandstone")) break;
            }
            require(!farRead.getAsJsonArray("palette").toString().contains("minecraft:sandstone"), "the far undo did not remove the pyramid: " + farRead);
            System.out.println("CRAFTIUM_AI far_build_ok");

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

    private static java.awt.image.BufferedImage decode(JsonObject reply) {
        try { return javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(reply.get("image").getAsString()))); }
        catch (java.io.IOException e) { throw new AssertionError(e); }
    }

    /** Pixels that are clearly violet: red and blue both well above green, which neither the background nor sandstone is. */
    private static int violet(java.awt.image.BufferedImage image) {
        int n = 0;
        for (int py = 0; py < image.getHeight(); py++)
            for (int px = 0; px < image.getWidth(); px++) {
                int c = image.getRGB(px, py), r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                if (r > g + 30 && b > g + 30) n++;
            }
        return n;
    }

    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
