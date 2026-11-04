package dev.craftium.client.mixin;
import dev.craftium.runtime.PlayerCapabilities;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Replace mode: the block that gets replaced breaks into the game's own destruction particles, as if it had been mined. */
@Mixin(BlockItem.class)
abstract class ReplaceParticlesMixin {
    @Inject(method="place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;",at=@At("HEAD"))
    private void craftium$particles(BlockPlaceContext context,CallbackInfoReturnable<?> cir){
        var level=context.getLevel();
        if(!level.isClientSide())return;
        var options=PlayerCapabilities.of(context.getPlayer());
        if(options==null||!options.replaceMode)return;
        var pos=context.getClickedPos();var state=level.getBlockState(pos);
        if(!state.isAir()&&state.getBlock()!=((BlockItem)(Object)this).getBlock()&&level instanceof net.minecraft.client.multiplayer.ClientLevel client)client.addDestroyBlockEffect(pos,state);
    }
}
