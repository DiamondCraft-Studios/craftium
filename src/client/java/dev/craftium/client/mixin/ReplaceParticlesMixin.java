package dev.craftium.client.mixin;
import dev.craftium.runtime.PlayerCapabilities;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Replace mode: the block that gets replaced breaks into the game's own destruction particles, once the placement has gone through. */
@Mixin(BlockItem.class)
abstract class ReplaceParticlesMixin {
    @Unique private BlockState craftium$replaced;
    @Inject(method="place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;",at=@At("HEAD"))
    private void craftium$remember(BlockPlaceContext context,CallbackInfoReturnable<InteractionResult> cir){
        craftium$replaced=null;
        if(!context.getLevel().isClientSide())return;
        var options=PlayerCapabilities.of(context.getPlayer());
        if(options!=null&&options.replaceMode)craftium$replaced=context.getLevel().getBlockState(context.getClickedPos());
    }
    @Inject(method="place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;",at=@At("RETURN"))
    private void craftium$particles(BlockPlaceContext context,CallbackInfoReturnable<InteractionResult> cir){
        var replaced=craftium$replaced;craftium$replaced=null;
        if(replaced!=null&&!replaced.isAir()&&cir.getReturnValue().consumesAction()&&context.getLevel() instanceof ClientLevel level)level.addDestroyBlockEffect(context.getClickedPos(),replaced);
    }
}
