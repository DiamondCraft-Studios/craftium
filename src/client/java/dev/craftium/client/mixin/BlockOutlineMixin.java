package dev.craftium.client.mixin;

import dev.craftium.client.*;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.phys.shapes.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockBehaviour.BlockStateBase.class)
abstract class BlockOutlineMixin {
    @Inject(method="getShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",at=@At("RETURN"),cancellable=true)
    private void craftium$editingOutline(BlockGetter level,BlockPos pos,CollisionContext context,CallbackInfoReturnable<VoxelShape> cir){
        if(!(level instanceof ClientLevel)||!CraftiumClient.supported())return;var o=ClientState.options;var state=(BlockState)(Object)this;
        if(!o.expandHitboxes&&state.getBlock() instanceof SlabBlock&&state.getValue(BlockStateProperties.SLAB_TYPE)==SlabType.DOUBLE){var half=SlabSplit.half(pos);if(half!=null){cir.setReturnValue(half);return;}}
        if(!state.isAir()&&(o.expandHitboxes||(o.showLightBlocks&&state.is(Blocks.LIGHT))||(o.showStructureVoid&&state.is(Blocks.STRUCTURE_VOID))))cir.setReturnValue(Shapes.block());
    }
}
