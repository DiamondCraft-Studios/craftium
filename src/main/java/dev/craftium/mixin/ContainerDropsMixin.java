package dev.craftium.mixin;

import dev.craftium.server.WorldRules;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Containers.class)
abstract class ContainerDropsMixin {
    @Inject(method = "dropItemStack", at = @At("HEAD"), cancellable = true)
    private static void craftium$inventoryDrops(
            Level level, double x, double y, double z, ItemStack stack, CallbackInfo ci) {
        if (level instanceof ServerLevel server && !WorldRules.get(server).blockDrops) ci.cancel();
    }
}
