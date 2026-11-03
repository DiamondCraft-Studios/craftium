package dev.craftium.mixin;
import dev.craftium.runtime.PlayerCapabilities;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Replace mode: any block counts as replaceable for the player, so vanilla places into the clicked block, with its own sound and swing. */
@Mixin(BlockBehaviour.BlockStateBase.class)
abstract class ReplaceModeMixin {
    @Inject(method="canBeReplaced(Lnet/minecraft/world/item/context/BlockPlaceContext;)Z",at=@At("HEAD"),cancellable=true)
    private void craftium$replace(BlockPlaceContext context,CallbackInfoReturnable<Boolean> cir){
        var options=PlayerCapabilities.of(context.getPlayer());
        if(options!=null&&options.replaceMode)cir.setReturnValue(true);
    }
}
