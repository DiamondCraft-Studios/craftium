package dev.craftium.mixin;

import dev.craftium.server.WorldRules;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Block.class)
abstract class BlockDropsMixin {
    @Inject(method = "popResource", at = @At("HEAD"), cancellable = true)
    private static void craftium$blockDrops(Level level, BlockPos pos, ItemStack stack, CallbackInfo ci) {
        if (level instanceof ServerLevel server && !WorldRules.get(server).blockDrops) ci.cancel();
    }

    @Inject(method = "popResourceFromFace", at = @At("HEAD"), cancellable = true)
    private static void craftium$faceDrops(
            Level level, BlockPos pos, Direction face, ItemStack stack, CallbackInfo ci) {
        if (level instanceof ServerLevel server && !WorldRules.get(server).blockDrops) ci.cancel();
    }
}
