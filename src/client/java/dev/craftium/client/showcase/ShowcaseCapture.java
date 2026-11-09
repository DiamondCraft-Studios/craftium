package dev.craftium.client.showcase;

import dev.craftium.core.ShotSpec;

import dev.craftium.client.compat.ClientCompat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Takes one showcase picture at a time: puts the detached camera in place, waits for the game to draw and mesh what it sees, saves
 * a PNG, and puts the player's view back. Callable from any thread; the work happens on the client thread, a step per tick.
 */
public final class ShowcaseCapture {
    private ShowcaseCapture() {}

    /** What was taken. {@code chunksReady} is false when the wait ran out before every chunk in view was drawn; {@code cameraPose} is x, y, z, yaw, pitch as the game actually drew them. */
    public record Result(Path file, int width, int height, boolean chunksReady, int ticksWaited, double[] cameraPose) {}

    /** Ticks to let the game draw frames from the new camera before looking, and the most to wait for chunks to be built. */
    static final int SETTLE_TICKS = 4, MAX_WAIT_TICKS = 200;

    private static final class Job {
        final ShotSpec spec;
        final Path file;
        final CompletableFuture<Result> done = new CompletableFuture<>();
        CameraRig rig;
        int ticks;
        boolean grabbing;

        Job(ShotSpec spec, Path file) { this.spec = spec; this.file = file; }
    }

    private static Job current;
    private static boolean registered;

    /** Starts a picture and returns at once; the future completes when the file is written, or fails with the reason. */
    public static CompletableFuture<Result> capture(ShotSpec spec, Path file) {
        var job = new Job(spec, file);
        Minecraft.getInstance().execute(() -> start(job));
        return job.done;
    }

    private static void start(Job job) {
        var mc = Minecraft.getInstance();
        try {
            if (current != null) throw new IllegalStateException("Another picture is being taken");
            if (!registered) { ClientTickEvents.END_CLIENT_TICK.register(ShowcaseCapture::tick); registered = true; }
            Files.createDirectories(job.file.toAbsolutePath().getParent());
            job.rig = CameraRig.install(mc, job.spec);
            current = job;
        } catch (Throwable t) {
            fail(job, t);
        }
    }

    private static void tick(Minecraft mc) {
        var job = current;
        if (job == null || job.grabbing) return;
        try {
            if (mc.level == null || mc.player == null) throw new IllegalStateException("The world closed during the picture");
            job.ticks++;
            boolean ready = mc.levelRenderer.hasRenderedAllSections();
            if (job.ticks < SETTLE_TICKS || (!ready && job.ticks < MAX_WAIT_TICKS)) return;
            job.grabbing = true;
            double[] pose = ClientCompat.cameraPose(ClientCompat.camera(mc));
            Screenshot.takeScreenshot(ClientCompat.mainTarget(mc), image -> {
                try {
                    image.writeToFile(job.file);
                    var result = new Result(job.file, image.getWidth(), image.getHeight(), ready, job.ticks, pose);
                    mc.execute(() -> finish(job, result));
                } catch (Throwable t) {
                    mc.execute(() -> fail(job, t));
                } finally {
                    image.close();
                }
            });
        } catch (Throwable t) {
            fail(job, t);
        }
    }

    private static void finish(Job job, Result result) {
        release(job);
        job.done.complete(result);
    }

    private static void fail(Job job, Throwable t) {
        release(job);
        job.done.completeExceptionally(t);
    }

    /** Puts the player's view back, whatever happened. */
    private static void release(Job job) {
        if (current == job) current = null;
        if (job.rig != null) {
            try { job.rig.close(); } finally { job.rig = null; }
        }
    }
}
