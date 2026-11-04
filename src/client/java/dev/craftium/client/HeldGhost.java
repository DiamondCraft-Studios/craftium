package dev.craftium.client;

import dev.craftium.client.compat.Overlay;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import java.util.ArrayList;
import java.util.List;

/**
 * The held block as the game would place it for a click: the state it asks for (so a door faces the way it will, a bed lies
 * the way it will), together with the second half of a block made of two, which the game only places after the first.
 */
final class HeldGhost {
    private HeldGhost(){}
    static void draw(Minecraft mc,BlockItem item,BlockHitResult hit,int tint){
        var context=new BlockPlaceContext(new UseOnContext(mc.player,InteractionHand.MAIN_HAND,hit));
        BlockPos pos=context.getClickedPos();
        BlockState state=item.getBlock().getStateForPlacement(context);
        if(state==null)state=item.getBlock().defaultBlockState();
        var states=new ArrayList<BlockState>(List.of(state));var places=new ArrayList<BlockPos>(List.of(pos));
        if(state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)&&state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)==DoubleBlockHalf.LOWER){ // doors and tall plants
            states.add(state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,DoubleBlockHalf.UPPER));places.add(pos.above());
        }else if(state.getBlock() instanceof BedBlock&&state.hasProperty(BlockStateProperties.BED_PART)&&state.getValue(BlockStateProperties.BED_PART)==BedPart.FOOT){ // beds lie toward their head
            states.add(state.setValue(BlockStateProperties.BED_PART,BedPart.HEAD));places.add(pos.relative(state.getValue(BedBlock.FACING)));
        }
        Overlay.blockStates(states,places,tint);
    }
}
