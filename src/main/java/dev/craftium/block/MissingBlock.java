package dev.craftium.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Stands in for a block that no installed mod provides. It has no states of its own: which block it stands for is the number its block
 * entity holds, so any number of different missing blocks cost one registered state.
 */
public final class MissingBlock extends Block implements EntityBlock {
    public MissingBlock(Properties properties){super(properties);}
    @Override public BlockEntity newBlockEntity(BlockPos pos,BlockState state){return new MissingBlockEntity(pos,state);}
}
