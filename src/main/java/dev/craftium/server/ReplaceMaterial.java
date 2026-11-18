package dev.craftium.server;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Replace material builder setting: placing a block of one material onto a stair, slab, wall or other variant of another turns the variant it is aimed at into the
 * same variant of the held block's material, with its facing and other states kept. Holding spruce planks, or spruce stairs, or any other block with spruce in it,
 * turns oak stairs into spruce stairs. What the held block is as a variant does not matter, only its material. Both sides ask this, so the client's prediction of
 * the placement agrees with the server's result.
 */
public final class ReplaceMaterial {
    private ReplaceMaterial() {}
    /** The block in the player's hand, or null when it is not a block. */
    public static Block held(BlockPlaceContext context) {
        return context.getItemInHand().getItem() instanceof BlockItem item ? item.getBlock() : null;
    }
    /** What the block the placement is aimed at becomes under the held block, or null: {@link #resolve} for the block that was clicked, whether the placement goes into it or beside it. */
    public static BlockState forContext(BlockPlaceContext context) {
        var clicked = context.replacingClickedOnBlock()
                ? context.getClickedPos()
                : context.getClickedPos().relative(context.getClickedFace().getOpposite());
        return resolve(context.getLevel().getBlockState(clicked), held(context));
    }
    /**
     * What {@code old} becomes under a held block, or null when nothing is to change: the held block or the old one is not a material with variants, they are the same
     * material, or the held block's material has no such variant, or the old block is two blocks tall (a door).
     */
    public static BlockState resolve(BlockState old, Block held) {
        if (held == null || old.isAir()) return null;
        // a door is two blocks that must change together; one placement only changes one, and the neighbour update
        // would break the door
        if (old.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF))
            return null;
        var index = MaterialFamilies.index();
        var from = index.slot(old.getBlock());
        var to = index.slot(held);
        if (from == null || to == null || from.material().equals(to.material())) return null;
        var target = index.member(to.material(), from.kind());
        if (target == null) return null;
        var next = target.defaultBlockState();
        for (var property : old.getProperties()) if (next.hasProperty(property)) next = copy(old, next, property);
        return next.equals(old) ? null : next;
    }

    private static <T extends Comparable<T>> BlockState copy(
            BlockState from, BlockState to, net.minecraft.world.level.block.state.properties.Property<T> property) {
        return to.setValue(property, from.getValue(property));
    }
}
