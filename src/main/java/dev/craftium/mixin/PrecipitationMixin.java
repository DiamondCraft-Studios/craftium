package dev.craftium.mixin;
import dev.craftium.server.WorldRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
/** Freezing water, adding snow layers and covering the ground with snow are the three block writes in tickPrecipitation. Skipping only those leaves rain filling cauldrons alone. */
@Mixin(ServerLevel.class)
abstract class PrecipitationMixin {
    @Redirect(method="tickPrecipitation",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean craftium$weather(ServerLevel level,BlockPos pos,BlockState state){return WorldRules.get(level).snowAndIce&&level.setBlockAndUpdate(pos,state);}
}
