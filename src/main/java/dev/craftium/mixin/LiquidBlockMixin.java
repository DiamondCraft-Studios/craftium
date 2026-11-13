package dev.craftium.mixin;
import dev.craftium.server.WorldRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Lava touching water (or the other way round) turns into cobblestone, stone or obsidian here. Returning true means "nothing reacted, keep flowing". */
@Mixin(LiquidBlock.class)
abstract class LiquidBlockMixin {
    @Inject(method="shouldSpreadLiquid",at=@At("HEAD"),cancellable=true)
    private void craftium$react(Level level,BlockPos pos,BlockState state,CallbackInfoReturnable<Boolean> cir){if(level instanceof ServerLevel server&&!WorldRules.get(server).fluidInteractions)cir.setReturnValue(true);}
}
