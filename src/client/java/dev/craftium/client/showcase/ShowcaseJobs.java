package dev.craftium.client.showcase;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.craftium.client.compat.ClientCompat;
import dev.craftium.core.ShowcasePlan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * Showcase jobs: many rooms, many angles each, saved to disk. A job runs in the background, one picture at a time, while the agent asks
 * how it is going. Everything here runs on the client thread, so no locking is needed. Pictures go to
 * {@code <game folder>/screenshots/craftium/<job>/<room>/<room>_<shot>.png}, next to a {@code manifest.json} that records every camera
 * and what came of it. The agent never chooses a path outside that folder: it only names the job, rooms and shots.
 */
public final class ShowcaseJobs {
    private ShowcaseJobs() {}

    private static final class Run {
        final String job;
        final Path dir;
        final ShowcasePlan.Plan plan;
        final boolean resume;
        final List<JsonObject> results = new ArrayList<>();
        final long started = System.currentTimeMillis();
        int next;
        boolean cancelled;
        String state = "running", problem;

        Run(String job, Path dir, ShowcasePlan.Plan plan, boolean resume) { this.job = job; this.dir = dir; this.plan = plan; this.resume = resume; }
    }

    private static Run current, last;

    /** Where every job's folder goes. */
    public static Path root() { return FabricLoader.getInstance().getGameDir().resolve("screenshots").resolve("craftium"); }

    /** Plans the request and starts taking its pictures; returns at once with where they will be. */
    public static JsonObject start(JsonObject args) throws IOException {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) throw new IllegalStateException("Not in a world");
        if (ClientCompat.screen(mc) != null) throw new IllegalStateException("A menu is open in the game; ask the player to close it so the world can be drawn");
        if (current != null) throw new IllegalStateException("The job \"" + current.job + "\" is still running (" + current.next + " of " + current.plan.entries().size() + " pictures); wait for it, or cancel it with capture_cancel");
        var plan = ShowcasePlan.parse(args, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)));
        boolean resume = args.has("resume") && args.get("resume").isJsonPrimitive() && args.get("resume").getAsBoolean();
        String job = plan.job();
        Path dir = root().resolve(job);
        // Never write over an earlier job unless this one is meant to carry on from it.
        if (!resume) for (int n = 2; Files.exists(dir); n++) { job = plan.job() + "-" + n; dir = root().resolve(job); }
        if (!dir.normalize().startsWith(root().normalize())) throw new IllegalArgumentException("The job folder is outside the screenshots folder");
        Files.createDirectories(dir);
        var run = new Run(job, dir, plan, resume);
        current = run;
        writeManifest(run);
        step(run);
        return status();
    }

    /** How the running job, or else the last one, is going. */
    public static JsonObject status() {
        var run = current != null ? current : last;
        if (run == null) throw new IllegalStateException("No showcase job has been started in this game session; start one with capture_batch");
        var out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("job", run.job);
        out.addProperty("state", run.state);
        out.addProperty("total", run.plan.entries().size());
        out.addProperty("done", run.next);
        int failed = 0, notReady = 0, hidden = 0;
        for (var r : run.results) {
            if (!r.get("ok").getAsBoolean()) failed++;
            else if (r.has("chunksReady") && !r.get("chunksReady").getAsBoolean()) notReady++;
            if (r.has("occlusion") && r.getAsJsonObject("occlusion").has("hidden")) hidden++;
        }
        out.addProperty("failed", failed);
        out.addProperty("folder", run.dir.toAbsolutePath().toString());
        out.addProperty("manifest", run.dir.resolve("manifest.json").toAbsolutePath().toString());
        if (run.problem != null) out.addProperty("problem", run.problem);
        var warnings = new StringBuilder();
        if (notReady > 0) warnings.append(notReady).append(" picture(s) were taken before every chunk in view was drawn; they may have gaps (chunksReady: false). ");
        if (hidden > 0) warnings.append(hidden).append(" picture(s) have terrain, trees or another build between the camera and the room even after raising the camera (occlusion.hidden); try another angle, a closer distance or a placed camera. ");
        if (warnings.length() > 0) out.addProperty("warning", warnings.toString().trim());
        var shots = new JsonArray();
        for (var r : run.results) shots.add(r);
        out.add("shots", shots);
        return out;
    }

    /** What a contact sheet of one room is made from: its saved pictures so far, in order. Client thread only. */
    public static ShowcaseSheets.Sources sheetSources(String room) {
        var run = current != null ? current : last;
        if (run == null) throw new IllegalStateException("No showcase job has been started in this game session; start one with capture_batch");
        String name = ShowcasePlan.safeName(room, "room");
        var files = new ArrayList<String>();
        var shots = new ArrayList<String>();
        for (var r : run.results) {
            if (r.get("room").getAsString().equals(name) && r.get("ok").getAsBoolean()) { files.add(r.get("file").getAsString()); shots.add(r.get("shot").getAsString()); }
        }
        boolean known = false;
        for (var e : run.plan.entries()) known |= e.room().equals(name);
        if (!known) throw new IllegalArgumentException("The job \"" + run.job + "\" has no room called \"" + name + "\"; its rooms are " + ShowcasePlan.rooms(run.plan));
        return new ShowcaseSheets.Sources(run.job, run.state, name, run.dir, files, shots);
    }

    /** Stops after the picture being taken, and gives the player their view back at once if none is being saved. */
    public static JsonObject cancel() {
        var run = current;
        if (run == null) throw new IllegalStateException("No showcase job is running");
        run.cancelled = true;
        ShowcaseCapture.cancel();
        return status();
    }

    private static void step(Run run) {
        var mc = Minecraft.getInstance();
        var entries = run.plan.entries();
        while (true) {
            if (run.cancelled) { finish(run, "cancelled", null); return; }
            if (run.next >= entries.size()) { finish(run, "done", null); return; }
            if (mc.level == null || mc.player == null) { finish(run, "failed", "The world was closed during the job"); return; }
            var entry = entries.get(run.next);
            Path file = run.dir.resolve(entry.path());
            if (run.resume && Files.exists(file)) {
                var skipped = result(entry, true);
                skipped.addProperty("skipped", true);
                run.results.add(skipped);
                run.next++;
                continue;
            }
            // Look before shooting: if something is in the way, the shot is retaken once from higher up when it can be.
            var framed = Tight.refit(mc, entry);
            var resolved = Occlusion.resolve(mc, framed);
            var used = resolved.entry();
            ShowcaseCapture.capture(used.spec(), file, used.aim()).whenComplete((r, t) -> {
                var picture = result(used, t == null);
                picture.add("occlusion", resolved.info());
                picture.addProperty("framedOn", framed != entry ? "blocks" : "box");
                if (t == null) {
                    picture.addProperty("width", r.width());
                    picture.addProperty("height", r.height());
                    picture.addProperty("chunksReady", r.chunksReady());
                    var pose = new JsonArray();
                    for (double d : r.cameraPose()) pose.add(Math.round(d * 100) / 100.0);
                    picture.add("cameraPose", pose);
                } else {
                    Throwable cause = t instanceof java.util.concurrent.CompletionException && t.getCause() != null ? t.getCause() : t;
                    picture.addProperty("error", String.valueOf(cause.getMessage() != null ? cause.getMessage() : cause));
                }
                run.results.add(picture);
                run.next++;
                step(run);
            });
            return;
        }
    }

    private static JsonObject result(ShowcasePlan.Entry entry, boolean ok) {
        var o = new JsonObject();
        o.addProperty("room", entry.room());
        o.addProperty("shot", entry.shot());
        o.addProperty("file", entry.path());
        o.addProperty("ok", ok);
        var spec = entry.spec();
        var camera = new JsonObject();
        camera.addProperty("x", Math.round(spec.x() * 100) / 100.0);
        camera.addProperty("y", Math.round(spec.y() * 100) / 100.0);
        camera.addProperty("z", Math.round(spec.z() * 100) / 100.0);
        camera.addProperty("yaw", Math.round(spec.yaw() * 10) / 10.0);
        camera.addProperty("pitch", Math.round(spec.pitch() * 10) / 10.0);
        camera.addProperty("fov", spec.fov());
        o.add("requested", camera);
        return o;
    }

    private static void finish(Run run, String state, String problem) {
        run.state = state;
        run.problem = problem;
        if (current == run) current = null;
        last = run;
        writeManifest(run);
    }

    private static void writeManifest(Run run) {
        try {
            var manifest = new JsonObject();
            manifest.addProperty("job", run.job);
            manifest.addProperty("state", run.state);
            manifest.addProperty("startedAt", java.time.Instant.ofEpochMilli(run.started).toString());
            manifest.addProperty("total", run.plan.entries().size());
            var shots = new JsonArray();
            for (var r : run.results) shots.add(r);
            manifest.add("shots", shots);
            Files.writeString(run.dir.resolve("manifest.json"), new GsonBuilder().setPrettyPrinting().create().toJson(manifest));
        } catch (IOException e) {
            if (run.problem == null) run.problem = "Could not write manifest.json: " + e.getMessage();
        }
    }
}
