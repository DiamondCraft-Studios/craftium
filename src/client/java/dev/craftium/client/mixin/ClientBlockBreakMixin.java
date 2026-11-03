package dev.craftium.client.mixin;
import dev.craftium.runtime.PlayerCapabilities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
/** The client's guess at a break uses the same flags as the server's, so a plant on top does not pop off for a moment. */
@Mixin(MultiPlayerGameMode.class)
abstract class ClientBlockBreakMixin {
    @ModifyConstant(method="destroyBlock",constant=@Constant(intValue=11))
    private int craftium$flags(int flags){
        var options=PlayerCapabilities.of(Minecraft.getInstance().player);
        return options!=null&&options.noUpdates?2|16:flags;
    }
}
