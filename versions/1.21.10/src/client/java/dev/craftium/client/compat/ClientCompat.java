package dev.craftium.client.compat;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.server.MinecraftServer;

/** Minecraft 1.21.10 implementation: the open screen is a public field on the client. */
public final class ClientCompat {
    private ClientCompat(){}

    public static Screen screen(Minecraft mc){return mc.screen;}
    public static void setScreen(Minecraft mc,Screen screen){mc.setScreen(screen);}
    public static boolean hudHidden(Minecraft mc){return mc.options.hideGui;}
    public static void toggleHud(Minecraft mc){mc.options.hideGui=!mc.options.hideGui;}

    public static Camera camera(Minecraft mc){return mc.gameRenderer.getMainCamera();}
    public static Camera.NearPlane nearPlane(Camera camera){return camera.getNearPlane();}
    public static KeyMapping registerKey(KeyMapping key){return KeyBindingHelper.registerKeyBinding(key);}

    /**
     * 1.21.10 keeps the world's cheats flag in an immutable LevelSettings with no setter, so this opens
     * commands to all players on the integrated server and refreshes their permission level instead.
     */
    public static void enableCheats(MinecraftServer server){
        server.getPlayerList().setAllowCommandsForAllPlayers(true);
        for(var player:server.getPlayerList().getPlayers())server.getPlayerList().sendPlayerPermissionLevel(player);
    }

    /** 1.21.10 exposes only the configured field of view, so dynamic effects such as sprinting are not included. */
    public static float fov(Minecraft mc,Camera camera){return mc.options.fov().get();}
    public static void rebuildChunks(Minecraft mc){mc.levelRenderer.allChanged();}
    public static int blockTint(Minecraft mc,BlockState state,BlockPos pos){return mc.getBlockColors().getColor(state,mc.level,pos,0);}
}
