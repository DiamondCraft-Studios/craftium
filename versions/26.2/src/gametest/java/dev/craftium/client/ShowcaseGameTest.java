package dev.craftium.client;

import dev.craftium.client.showcase.ShotSpec;
import dev.craftium.client.showcase.ShowcaseCapture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * The showcase camera in a real world: takes pictures at 1080p and 4K from a detached camera and checks that the files are the size
 * asked for and show the build, and that the player's position, look direction, view and settings are exactly as they were. Run with
 * {@code CRAFTIUM_TEST_SCOPE=showcase}.
 */
public final class ShowcaseGameTest {
    private ShowcaseGameTest() {}

    private record Before(double x, double y, double z, float yaw, float pitch, int fov, boolean hudHidden, boolean invisible, Object camera, int width, int height) {}

    public static void run(ClientGameTestContext c, TestSingleplayerContext w) throws Exception {
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
        w.getConnection().waitForChunksDownload();

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

        var after = c.computeOnClient(mc -> new Before(mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.getYRot(), mc.player.getXRot(),
            mc.options.fov().get(), dev.craftium.client.compat.ClientCompat.hudHidden(mc), mc.player.isInvisible(), mc.getCameraEntity(), mc.getWindow().getWidth(), mc.getWindow().getHeight()));
        require(after.equals(before), "The player's view was not restored.\n before: " + before + "\n after:  " + after);
        // The restored view must draw at the window's size again; the renderer catches up on the next frame.
        c.waitTicks(3);
        require(c.computeOnClient(mc -> mc.gameRenderer.mainRenderTarget().width == before.width() && mc.gameRenderer.mainRenderTarget().height == before.height()),
            "The render target was not put back to the window size");
        System.out.println("CRAFTIUM_SHOWCASE_PASS files in " + dir + " : " + hd.file().getFileName() + " " + back.file().getFileName() + " " + uhd.file().getFileName());
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

    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
