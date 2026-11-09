package dev.craftium.client.showcase;

import dev.craftium.core.ShowcasePlan;
import java.util.ArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** Frames a shot on the blocks that are really there: the corners of every solid block in the room's box, read from the client's own copy of the world. */
final class Tight {
    private Tight() {}

    /** A box bigger than this is framed by its corners as it always was: scanning it would cost more than the picture is worth. */
    static final long MAX_BLOCKS = 262_144;

    static ShowcasePlan.Entry refit(Minecraft mc, ShowcasePlan.Entry entry) {
        if (entry.orbit() == null || (entry.orbit()[6] == 0 || entry.orbit()[7] == 0)) return entry;
        var min = entry.min();
        var max = entry.max();
        long size = (long) (max.x() - min.x() + 1) * (max.y() - min.y() + 1) * (max.z() - min.z() + 1);
        if (size > MAX_BLOCKS) return entry;
        var points = new ArrayList<double[]>();
        var at = new BlockPos.MutableBlockPos();
        for (int x = min.x(); x <= max.x(); x++)
            for (int y = min.y(); y <= max.y(); y++)
                for (int z = min.z(); z <= max.z(); z++) {
                    at.set(x, y, z);
                    if (mc.level.getBlockState(at).isAir()) continue;
                    // Only blocks with an air neighbour can be seen, and only their outer corners matter to the framing.
                    boolean open = mc.level.getBlockState(at.set(x + 1, y, z)).isAir() || mc.level.getBlockState(at.set(x - 1, y, z)).isAir()
                        || mc.level.getBlockState(at.set(x, y + 1, z)).isAir() || mc.level.getBlockState(at.set(x, y - 1, z)).isAir()
                        || mc.level.getBlockState(at.set(x, y, z + 1)).isAir() || mc.level.getBlockState(at.set(x, y, z - 1)).isAir();
                    if (!open) continue;
                    for (int i = 0; i < 8; i++) points.add(new double[]{x + (i & 1), y + ((i >> 1) & 1), z + ((i >> 2) & 1)});
                }
        if (points.isEmpty()) return entry;
        return ShowcasePlan.refit(entry, points.toArray(new double[0][]));
    }
}
