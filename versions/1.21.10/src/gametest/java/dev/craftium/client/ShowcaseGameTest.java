package dev.craftium.client;

import dev.craftium.core.ShotSpec;
import dev.craftium.client.showcase.ShowcaseCapture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;

/**
 * The showcase camera in a real world: takes pictures at 1080p and 4K from a detached camera and checks that the files are the size
 * asked for and show the build, and that the player's position, look direction, view and settings are exactly as they were. Run with
 * {@code CRAFTIUM_TEST_SCOPE=showcase}.
 */
public final class ShowcaseGameTest implements net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest {

    private record Before(double x, double y, double z, float yaw, float pitch, int fov, boolean hudHidden, boolean invisible, Object camera, int width, int height) {}

    @Override public void runTest(ClientGameTestContext c) {
        String scope = System.getenv("CRAFTIUM_TEST_SCOPE");
        boolean live = "showcase-live".equals(scope);
        if (!live && !"showcase".equals(scope)) return;
        try (var world = c.worldBuilder().adjustSettings(st -> st.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE)).create()) {
            c.waitTicks(40);
            world.getClientWorld().waitForChunksDownload();
            if (live) runLive(c, world); else run(c, world);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void run(ClientGameTestContext c, TestSingleplayerContext w) throws Exception {
        // Let the player land first, so their position is the same before and after.
        c.waitFor(mc -> mc.player.onGround(), 600);
        int g = c.computeOnClient(mc -> (int) Math.floor(mc.player.getY()));
        // The tower stands 18 blocks to the north of wherever the player landed, whatever the test world's spawn is.
        int cx = c.computeOnClient(mc -> (int) Math.floor(mc.player.getX())), cz = c.computeOnClient(mc -> (int) Math.floor(mc.player.getZ())) - 18;
        // A gold and stone tower in the open, away from where the player stands.
        w.getServer().runCommand("fill " + (cx - 4) + " " + (g - 1) + " " + (cz - 4) + " " + (cx + 4) + " " + (g - 1) + " " + (cz + 4) + " stone_bricks");
        w.getServer().runCommand("fill " + (cx - 1) + " " + g + " " + (cz - 1) + " " + (cx + 1) + " " + (g + 6) + " " + (cz + 1) + " gold_block");
        w.getServer().runCommand("fill " + (cx - 1) + " " + (g + 7) + " " + (cz - 1) + " " + (cx + 1) + " " + (g + 7) + " " + (cz + 1) + " oak_planks");
        c.waitTicks(20);
        w.getClientWorld().waitForChunksDownload();

        var before = c.computeOnClient(mc -> new Before(mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.getYRot(), mc.player.getXRot(),
            mc.options.fov().get(), dev.craftium.client.compat.ClientCompat.hudHidden(mc), mc.player.isInvisible(), mc.getCameraEntity(), mc.getWindow().getWidth(), mc.getWindow().getHeight()));

        var dir = Path.of("screenshots", "craftium", "showcase-test").toAbsolutePath();
        var hd = shot(c, ShotSpec.of("tower-hd", cx + 9, g + 4, cz + 9, aimYaw(9, 9), aimPitch(9, g + 4, 9, g + 4)), dir);
        require(hd.width() == 1920 && hd.height() == 1080, "1080p picture was " + hd.width() + " by " + hd.height());
        var back = shot(c, ShotSpec.of("tower-back", cx - 9, g + 4, cz - 9, aimYaw(-9, -9), aimPitch(-9, g + 4, -9, g + 4)), dir);
        var uhd = shot(c, new ShotSpec("tower-4k", cx + 9, g + 4, cz + 9, aimYaw(9, 9), aimPitch(9, g + 4, 9, g + 4), 50, 3840, 2160, true), dir);
        require(uhd.width() == 3840 && uhd.height() == 2160, "4K picture was " + uhd.width() + " by " + uhd.height());

        System.out.println("CRAFTIUM_SHOWCASE_INFO player=" + before.x() + "," + before.y() + "," + before.z() + " tower=" + cx + "," + g + "," + cz + " hd camera=" + java.util.Arrays.toString(hd.cameraPose()));
        requirePose(hd, 9 + cx, g + 4, 9 + cz, aimYaw(9, 9), aimPitch(9, g + 4, 9, g + 4));
        requirePose(back, cx - 9, g + 4, cz - 9, aimYaw(-9, -9), aimPitch(-9, g + 4, -9, g + 4));
        for (var r : new ShowcaseCapture.Result[]{hd, back, uhd}) {
            require(Files.size(r.file()) > 10_000, r.file() + " is too small to be a picture");
            require(r.chunksReady(), r.file() + " was taken before every chunk in view was drawn");
            require(goldPixels(r.file()) > 300, r.file() + " does not show the gold tower: it was not aimed at it, or its chunks were not drawn");
        }

        // A job: two rooms with several angles each, and a third far outside the loaded chunks that has to fail clearly without stopping the rest.
        w.getServer().runCommand("fill " + (cx + 14) + " " + g + " " + cz + " " + (cx + 18) + " " + (g + 4) + " " + (cz + 4) + " emerald_block");
        // A third room with a low wall along its south side, where the "south" camera stands: the low view is blocked and has to be raised.
        w.getServer().runCommand("fill " + (cx - 18) + " " + g + " " + cz + " " + (cx - 14) + " " + (g + 4) + " " + (cz + 4) + " emerald_block");
        w.getServer().runCommand("fill " + (cx - 26) + " " + g + " " + (cz + 7) + " " + (cx - 6) + " " + (g + 2) + " " + (cz + 7) + " stone_bricks");
        c.waitTicks(20);
        // The player saves the annex as a selection in the Selections window; a room can then name it instead of giving corners.
        c.runOnClient(mc -> {
            var o = ClientState.options;
            o.position1 = new dev.craftium.core.Point(cx + 14, g, cz);
            o.position2 = new dev.craftium.core.Point(cx + 18, g + 4, cz + 4);
            CraftiumClient.send("select_box", null, new dev.craftium.core.Point(0, 1, 0));
        });
        c.waitTicks(30);
        c.runOnClient(mc -> CraftiumClient.send("saved_selection:save", null, new dev.craftium.core.Point(0, 1, 0)));
        c.waitFor(mc -> !ClientState.savedSelections().isEmpty(), 200);
        require(c.computeOnClient(mc -> ClientState.savedSelections().get(0).name()).equals("Selection 1"), "the annex was not saved as Selection 1");
        var request = com.google.gson.JsonParser.parseString("{\"job\":\"gametest-job\",\"rooms\":["
            + "{\"name\":\"Tower\",\"from\":[" + (cx - 1) + "," + g + "," + (cz - 1) + "],\"to\":[" + (cx + 1) + "," + (g + 7) + "," + (cz + 1) + "],\"shots\":[\"hero\",\"south\",{\"name\":\"overhead\",\"preset\":\"top\"}]},"
            + "{\"name\":\"Annex\",\"from\":[" + (cx + 14) + "," + g + "," + cz + "],\"to\":[" + (cx + 18) + "," + (g + 4) + "," + (cz + 4) + "],\"shots\":[\"hero\",{\"name\":\"close\",\"preset\":\"east\",\"fov\":50}]},"
            + "{\"name\":\"Walled\",\"from\":[" + (cx - 18) + "," + g + "," + cz + "],\"to\":[" + (cx - 14) + "," + (g + 4) + "," + (cz + 4) + "],\"shots\":[\"south\"]},"
            + "{\"selection\":\"selection 1\",\"shots\":[\"south\"]},"
            + "{\"name\":\"Faraway\",\"from\":[" + (cx + 5000) + "," + g + "," + cz + "],\"to\":[" + (cx + 5004) + "," + (g + 4) + "," + (cz + 4) + "],\"shots\":[\"hero\"]}]}").getAsJsonObject();
        c.runOnClient(mc -> { try { dev.craftium.client.showcase.ShowcaseJobs.start(request); } catch (java.io.IOException e) { throw new AssertionError(e); } });
        c.waitFor(mc -> !"running".equals(dev.craftium.client.showcase.ShowcaseJobs.status().get("state").getAsString()), 6000);
        var status = c.computeOnClient(mc -> dev.craftium.client.showcase.ShowcaseJobs.status());
        require("done".equals(status.get("state").getAsString()), "The job ended as " + status);
        require(status.get("total").getAsInt() == 8 && status.get("done").getAsInt() == 8, "The job did not go through all eight shots: " + status);
        require(status.get("failed").getAsInt() == 1, "Exactly the far room should have failed: " + status);
        var folder = Path.of(status.get("folder").getAsString());
        for (var name : new String[]{"tower/tower_hero.png", "tower/tower_south.png", "tower/tower_overhead.png", "annex/annex_hero.png", "annex/annex_close.png", "walled/walled_south.png", "selection-1/selection-1_south.png"}) {
            require(Files.size(folder.resolve(name)) > 10_000, name + " was not saved");
            require(goldOrGreen(folder.resolve(name)) > 300, name + " does not show the build it is of");
        }
        require(!Files.exists(folder.resolve("faraway/faraway_hero.png")), "A picture of unloaded chunks was saved");
        String error = null;
        for (var shot : status.getAsJsonArray("shots")) if (!shot.getAsJsonObject().get("ok").getAsBoolean()) error = shot.getAsJsonObject().get("error").getAsString();
        require(error != null && error.contains("not loaded"), "The far room's failure did not say the chunk is not loaded: " + error);
        var manifest = com.google.gson.JsonParser.parseString(Files.readString(folder.resolve("manifest.json"))).getAsJsonObject();
        require("done".equals(manifest.get("state").getAsString()) && manifest.getAsJsonArray("shots").size() == 8, "manifest.json is incomplete");
        // The walled room's low view was blocked, so it was retaken from higher up; the open rooms were left alone.
        for (var shot : status.getAsJsonArray("shots")) {
            var o = shot.getAsJsonObject();
            if (!o.get("ok").getAsBoolean()) continue;
            var occlusion = o.getAsJsonObject("occlusion");
            if (o.get("room").getAsString().equals("walled")) {
                require(occlusion.has("raised") && occlusion.get("raised").getAsBoolean(), "The walled room's blocked view was not raised: " + occlusion);
                require(occlusion.get("blockedBeforeRetry").getAsInt() >= 4 && occlusion.get("blocked").getAsInt() < occlusion.get("blockedBeforeRetry").getAsInt(), "Raising did not clear the view: " + occlusion);
            } else {
                require(!occlusion.has("raised") && !occlusion.has("hidden"), o.get("file").getAsString() + " was judged blocked in open ground: " + occlusion);
            }
        }
        // A contact sheet of the tower's three angles: one image, no wider than 1024, saved beside them.
        var sheet = dev.craftium.client.showcase.ShowcaseSheets.build(c.computeOnClient(mc -> dev.craftium.client.showcase.ShowcaseJobs.sheetSources("Tower")));
        require(sheet.get("width").getAsInt() == 1024 && sheet.getAsJsonArray("thumbnails").size() == 3, "The contact sheet is not 1024 wide with three thumbnails: " + sheet.get("width") + " " + sheet.get("thumbnails"));
        require(Files.size(folder.resolve("tower/contact-sheet.jpg")) > 5_000, "The contact sheet was not saved");
        require(java.util.Base64.getDecoder().decode(sheet.get("image").getAsString()).length == Files.size(folder.resolve("tower/contact-sheet.jpg")), "The returned sheet is not the saved one");
        // Taking it again with resume must find every saved picture and take none of them twice.
        c.runOnClient(mc -> { request.addProperty("resume", true); try { dev.craftium.client.showcase.ShowcaseJobs.start(request); } catch (java.io.IOException e) { throw new AssertionError(e); } });
        c.waitFor(mc -> !"running".equals(dev.craftium.client.showcase.ShowcaseJobs.status().get("state").getAsString()), 6000);
        int skipped = 0;
        for (var shot : c.computeOnClient(mc -> dev.craftium.client.showcase.ShowcaseJobs.status()).getAsJsonArray("shots")) if (shot.getAsJsonObject().has("skipped")) skipped++;
        require(skipped == 7, "Resuming should have skipped the seven saved pictures, skipped " + skipped);

        var after = c.computeOnClient(mc -> new Before(mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.getYRot(), mc.player.getXRot(),
            mc.options.fov().get(), dev.craftium.client.compat.ClientCompat.hudHidden(mc), mc.player.isInvisible(), mc.getCameraEntity(), mc.getWindow().getWidth(), mc.getWindow().getHeight()));
        require(after.equals(before), "The player's view was not restored.\n before: " + before + "\n after:  " + after);
        // The restored view must draw at the window's size again; the renderer catches up on the next frame.
        c.waitTicks(3);
        require(c.computeOnClient(mc -> dev.craftium.client.compat.ClientCompat.mainTarget(mc).width == before.width() && dev.craftium.client.compat.ClientCompat.mainTarget(mc).height == before.height()),
            "The render target was not put back to the window size");
        System.out.println("CRAFTIUM_SHOWCASE_PASS files in " + dir + " : " + hd.file().getFileName() + " " + back.file().getFileName() + " " + uhd.file().getFileName());
    }

    /**
     * The live run: the AI bridge is switched on in a real world and the real MCP server (a Node process, as an AI app starts it) builds a
     * castle and photographs it through the bridge, the way an agent following the craftium-showcase skill would. Run with
     * {@code CRAFTIUM_TEST_SCOPE=showcase-live}; the contact sheets it fetched are written to a live-run folder for review.
     */
    public static void runLive(ClientGameTestContext c, TestSingleplayerContext w) throws Exception {
        c.waitFor(mc -> mc.player.onGround(), 600);
        c.runOnClient(mc -> { if (!AiBridge.running()) AiBridge.toggle(); });
        for (int i = 0; i < 200 && !c.computeOnClient(mc -> AiBridge.running()); i++) c.waitTick();
        require(c.computeOnClient(mc -> AiBridge.running()), "the AI bridge did not start");
        Path mcp = null;
        for (Path dir = Path.of("").toAbsolutePath(); dir != null && mcp == null; dir = dir.getParent())
            if (Files.exists(dir.resolve("mcp/test/e2e-showcase.mjs"))) mcp = dir.resolve("mcp");
        require(mcp != null, "mcp/test/e2e-showcase.mjs was not found above " + Path.of("").toAbsolutePath());
        Path out = Path.of("live-run").toAbsolutePath();
        var process = new ProcessBuilder("node", "test/e2e-showcase.mjs", AiBridge.FILE.toString(), out.toString()).directory(mcp.toFile()).redirectErrorStream(true).start();
        var output = new StringBuffer();
        var reader = CompletableFuture.runAsync(() -> { try { output.append(new String(process.getInputStream().readAllBytes())); } catch (Exception ignored) {} });
        long deadline = System.currentTimeMillis() + 300_000;
        while (process.isAlive() && System.currentTimeMillis() < deadline) c.waitTick();
        if (process.isAlive()) process.destroyForcibly();
        reader.join();
        System.out.println("CRAFTIUM_SHOWCASE_LIVE_OUTPUT\n" + output);
        require(process.exitValue() == 0 && output.toString().contains("LIVE_DONE"), "the live run failed:\n" + output);
        require(Files.size(out.resolve("sheet-castle.jpg")) > 5_000 && Files.size(out.resolve("sheet-hall.jpg")) > 5_000, "the contact sheets were not fetched");
        // The player's own view must be back after a job run from outside the game.
        c.waitTicks(5);
        require(c.computeOnClient(mc -> mc.getCameraEntity() == mc.player && !dev.craftium.client.compat.ClientCompat.hudHidden(mc) && !mc.player.isInvisible()), "the player's view was not restored after the live run");
        System.out.println("CRAFTIUM_SHOWCASE_LIVE_PASS sheets in " + out);
    }

    private static ShowcaseCapture.Result shot(ClientGameTestContext c, ShotSpec spec, Path dir) throws Exception {
        CompletableFuture<ShowcaseCapture.Result> future = c.computeOnClient(mc -> ShowcaseCapture.capture(spec, dir.resolve(spec.name() + ".png")));
        c.waitFor(mc -> future.isDone(), 1200);
        return future.get();
    }

    /** The yaw that looks from (x, z) toward the tower's axis at the origin: the game's yaw 0 faces +z, 90 faces -x. */
    private static float aimYaw(double x, double z) { return (float) Math.toDegrees(Math.atan2(x, -z)) + 0f; }

    private static float aimPitch(double x, double y, double z, double targetY) {
        return (float) -Math.toDegrees(Math.atan2(targetY - y, Math.hypot(x, z)));
    }

    /** How many sampled pixels are gold-coloured: the tower is the only gold in the world, so a picture of it holds plenty. */
    private static int goldPixels(Path file) throws Exception {
        var image = javax.imageio.ImageIO.read(file.toFile());
        int gold = 0;
        for (int y = 0; y < image.getHeight(); y += 4) for (int x = 0; x < image.getWidth(); x += 4) {
            int rgb = image.getRGB(x, y), r = rgb >> 16 & 255, gr = rgb >> 8 & 255, b = rgb & 255;
            if (r > gr && gr > b * 1.7 && r - b > 60) gold++; // yellow: red over green over blue, which grass, stone and sky never are
        }
        return gold;
    }

    /** The game drew from where and where to the shot asked, to within a hair. */
    private static void requirePose(ShowcaseCapture.Result r, double x, double y, double z, float yaw, float pitch) {
        double[] p = r.cameraPose();
        double dyaw = Math.abs(((p[3] - yaw + 540) % 360) - 180);
        require(Math.abs(p[0] - x) < .01 && Math.abs(p[1] - y) < .01 && Math.abs(p[2] - z) < .01 && dyaw < .1 && Math.abs(p[4] - pitch) < .1,
            r.file().getFileName() + ": the camera was at " + java.util.Arrays.toString(p) + ", not " + java.util.Arrays.toString(new double[]{x, y, z, yaw, pitch}));
    }

    /** How many sampled pixels are gold, oak or emerald-green: the only strong colours in the test world besides the sky and grass, so a picture of the builds holds plenty. */
    private static int goldOrGreen(Path file) throws Exception {
        var image = javax.imageio.ImageIO.read(file.toFile());
        int hits = 0;
        for (int y = 0; y < image.getHeight(); y += 4) for (int x = 0; x < image.getWidth(); x += 4) {
            int rgb = image.getRGB(x, y), r = rgb >> 16 & 255, gr = rgb >> 8 & 255, b = rgb & 255;
            boolean gold = r > gr && gr > b * 1.3 && r - b > 40; // gold, and the tower's oak top
            boolean emerald = gr > 100 && gr > r * 1.8 && gr > b * 1.4;
            if (gold || emerald) hits++;
        }
        return hits;
    }

    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
