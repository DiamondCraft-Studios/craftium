package dev.craftium.client.mixin;
import dev.craftium.client.Bulldozer;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
/** Bulldozer: the wait of five ticks between two blocks of a held break is dropped once the button has been held a moment. */
@Mixin(MultiPlayerGameMode.class)
abstract class BulldozerMixin {
    @ModifyConstant(method="continueDestroyBlock",constant=@Constant(intValue=5),require=0)
    private int craftium$delay(int delay){return Bulldozer.skipDelay()?0:delay;}
}
