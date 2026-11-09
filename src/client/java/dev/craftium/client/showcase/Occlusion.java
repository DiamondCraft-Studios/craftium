package dev.craftium.client.showcase;

import com.google.gson.JsonObject;
import dev.craftium.core.Point;
import dev.craftium.core.ShowcasePlan;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Looks before the picture is taken: whether terrain, trees or another build stand between the camera and the room. Nine rays go from the
 * camera, to the room's middle and to points just inside its eight corners; one that reaches a block outside the room (plus a block's
 * margin) first is blocked. A shot with too many blocked is retried once from higher up, and the better of the two is kept.
 */
final class Occlusion {
    private Occlusion() {}

    /** At or above this many blocked rays out of {@link #SAMPLES} the shot is judged hidden. */
    static final int SAMPLES = 9, HIDDEN_AT = 4;
    /** How many degrees higher the one retry stands. */
    static final double RAISE = 30, MAX_ELEVATION = 80;

    /** The shot to take (possibly raised), and what the check found, for the manifest. */
    record Resolved(ShowcasePlan.Entry entry, JsonObject info) {}

    static Resolved resolve(Minecraft mc, ShowcasePlan.Entry entry) {
        int first = blocked(mc, entry);
        var info = new JsonObject();
        info.addProperty("samples", SAMPLES);
        info.addProperty("blocked", first);
        if (first < HIDDEN_AT) return new Resolved(entry, info);
        // Only a shot worked out from a bearing can be raised; a camera placed outright is left where the agent put it and reported.
        double elevation = entry.orbit() == null ? 0 : Math.min(MAX_ELEVATION, entry.orbit()[4] + RAISE);
        var higher = entry.orbit() == null || elevation <= entry.orbit()[4] ? null : ShowcasePlan.raised(entry, elevation);
        if (higher == null) { info.addProperty("hidden", true); return new Resolved(entry, info); }
        int second = blocked(mc, higher);
        info.addProperty("retriedFromElevation", Math.round(entry.orbit()[4] * 10) / 10.0);
        info.addProperty("blockedBeforeRetry", first);
        if (second < first) {
            info.addProperty("blocked", second);
            info.addProperty("raised", true);
            if (second >= HIDDEN_AT) info.addProperty("hidden", true);
            return new Resolved(higher, info);
        }
        // Higher did no better: keep the shot as asked.
        info.addProperty("raised", false);
        info.addProperty("hidden", true);
        return new Resolved(entry, info);
    }

    /** How many of the nine rays from the camera are stopped by something outside the room. */
    static int blocked(Minecraft mc, ShowcasePlan.Entry entry) {
        var spec = entry.spec();
        Vec3 from = new Vec3(spec.x(), spec.y(), spec.z());
        Point min = entry.min(), max = entry.max();
        double inset = 0.25;
        int count = 0;
        for (int i = -1; i < 8; i++) {
            Vec3 to = i < 0
                ? new Vec3((min.x() + max.x() + 1) / 2.0, (min.y() + max.y() + 1) / 2.0, (min.z() + max.z() + 1) / 2.0)
                : new Vec3((i & 1) == 0 ? min.x() + inset : max.x() + 1 - inset, (i & 2) == 0 ? min.y() + inset : max.y() + 1 - inset, (i & 4) == 0 ? min.z() + inset : max.z() + 1 - inset);
            var hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() == HitResult.Type.MISS) continue;
            var at = hit.getBlockPos();
            boolean inRoom = at.getX() >= min.x() - 1 && at.getX() <= max.x() + 1 && at.getY() >= min.y() - 1 && at.getY() <= max.y() + 1 && at.getZ() >= min.z() - 1 && at.getZ() <= max.z() + 1;
            if (!inRoom) count++;
        }
        return count;
    }
}
