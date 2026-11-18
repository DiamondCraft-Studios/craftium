package dev.craftium.client;

import dev.craftium.core.Options;
import dev.craftium.core.Point;
import dev.craftium.core.Tool;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** What the height tools show over the brush besides it: the level Flatten brings the ground to, when that level is typed in rather than taken from the click. */
public final class TerrainPreview {
    private TerrainPreview() {}

    public static void draw(BlockHitResult hit, Options o) {
        if (hit == null || o.tool != Tool.FLATTEN || o.flattenPick) return;
        var at = hit.getBlockPos();
        OutlineShape.box(
                        new Point(at.getX() - o.radius, o.flattenY, at.getZ() - o.radius),
                        new Point(at.getX() + o.radius + 1, o.flattenY + 1, at.getZ() + o.radius + 1))
                .drawPlain(Vec3.ZERO, o.hoverColor, (float) o.selectionWidth, 0, false, true);
    }
}
