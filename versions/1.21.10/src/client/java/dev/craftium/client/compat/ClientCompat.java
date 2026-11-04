package dev.craftium.client.compat;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.server.MinecraftServer;

/** Minecraft 1.21.10 implementation: the open screen is a public field on the client. */
public final class ClientCompat {
    private ClientCompat(){}

    public static Screen screen(Minecraft mc){return mc.screen;}
    public static void setScreen(Minecraft mc,Screen screen){mc.setScreen(screen);}
    /** Shows a message above the hotbar, in the place the game puts its own action bar text. */
    public static void overlayMessage(Minecraft mc,String message){mc.gui.setOverlayMessage(net.minecraft.network.chat.Component.literal(message),false);}
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

    /** The vertical field of view the last frame was rendered with, set by FovMixin; NaN until a frame has been drawn. */
    public static float renderedFov=Float.NaN;
    /**
     * The field of view the world is drawn with: the configured one plus dynamic effects such as the boost while flying.
     * Camera.getNearPlane() in 1.21.10 knows only the configured one, so the picking and projection use this instead.
     */
    public static float fov(Minecraft mc,Camera camera){return Float.isNaN(renderedFov)?mc.options.fov().get():renderedFov;}
    /** The direction from the camera through a point of the screen, x and y from -1 to 1 across the window. */
    public static Vec3 ray(Camera camera,float x,float y){
        var mc=Minecraft.getInstance();
        // The near plane is built for the configured field of view; its offsets scale with the tangent of the real one.
        double ratio=Math.tan(Math.toRadians(fov(mc,camera))/2)/Math.tan(Math.toRadians((double)mc.options.fov().get())/2);
        var plane=camera.getNearPlane();Vec3 center=plane.getPointOnPlane(0,0);
        return center.add(plane.getPointOnPlane(x,y).subtract(center).scale(ratio)).normalize();
    }
    public static void rebuildChunks(Minecraft mc){mc.levelRenderer.allChanged();}
    public static int blockTint(Minecraft mc,BlockState state,BlockPos pos){return blockTint(mc,state,pos,0);}
    /** Biome or block tint of the state's tint layer {@code index} at {@code pos}, or -1 when it has none. */
    public static int blockTint(Minecraft mc,BlockState state,BlockPos pos,int index){return mc.getBlockColors().getColor(state,mc.level,pos,index);}
}
