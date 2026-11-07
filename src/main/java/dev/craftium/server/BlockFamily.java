package dev.craftium.server;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

/** Which blocks belong together as one material: oak planks, oak stairs, oak slabs and oak fences are all "oak". Smart Extrude takes a face of one in. */
public final class BlockFamily {
    private BlockFamily() {}
    /** The block's name without what makes it a variant: oak_stairs, oak_slab, oak_fence and oak_planks are all "oak". */
    public static String of(BlockState state) {
        String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return name.replaceAll("_(stairs|slab|wall|fence_gate|fence|button|pressure_plate)$", "").replaceAll("_(planks|block)$", "").replaceAll("_bricks$", "_brick").replaceAll("_tiles$", "_tile");
    }
    /** Whether {@code other} is a solid block of the same family as {@code original}: the same block, or a stair, slab, wall or other variant of the same material. */
    public static boolean same(BlockState original, BlockState other) {
        return !other.isAir() && (other.is(original.getBlock()) || of(original).equals(of(other)));
    }
}
