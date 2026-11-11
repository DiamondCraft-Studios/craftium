package dev.craftium.client.mixin;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
/** Lets {@link dev.craftium.client.InterfaceScale} say how many interface units wide and high the window is at a half-step scale, which the game's whole-number GUI scale cannot. */
@Mixin(Window.class)
public interface WindowAccessor {
    @Mutable @Accessor("guiScaledWidth") void craftium$setGuiScaledWidth(int width);
    @Mutable @Accessor("guiScaledHeight") void craftium$setGuiScaledHeight(int height);
}
