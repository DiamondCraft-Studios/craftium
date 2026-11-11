package dev.craftium.client.compat;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.server.MinecraftServer;

/** Client API differences between Minecraft releases. 26.2 implementation. */
public final class ClientCompat {
    private ClientCompat(){}

    /** The screen currently open, or null while playing. */
    public static Screen screen(Minecraft mc){return mc.gui.screen();}
    /** A line in the player's chat that only they see. */
    public static void chat(Minecraft mc,net.minecraft.network.chat.Component line){if(mc.player!=null)mc.player.sendSystemMessage(line);}
    /** Opens a screen, or returns to the game when {@code screen} is null. */
    public static void setScreen(Minecraft mc,Screen screen){mc.gui.setScreen(screen);}
    /** Shows a message above the hotbar, in the place the game puts its own action bar text. */
    public static void overlayMessage(Minecraft mc,String message){if(mc.player!=null)mc.player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(message));}
    public static boolean hudHidden(Minecraft mc){return mc.gui.hud.isHidden();}
    /** Flips the HUD between shown and hidden. */
    public static void toggleHud(Minecraft mc){mc.gui.hud.toggle();}

    public static Camera camera(Minecraft mc){return mc.gameRenderer.mainCamera();}
    /** The frame the game last drew, for screenshots. */
    public static com.mojang.blaze3d.pipeline.RenderTarget mainTarget(Minecraft mc){return mc.gameRenderer.mainRenderTarget();}
    /** Sets the size the world is drawn at, which may differ from the window's. The renderer resizes its target to match on the next frame. */
    public static void setRenderSize(Minecraft mc,int width,int height){mc.getWindow().setWidth(width);mc.getWindow().setHeight(height);}
    /** Where the camera is and which way it looks, as x, y, z, yaw, pitch. */
    public static double[] cameraPose(Camera camera){var p=camera.position();return new double[]{p.x,p.y,p.z,camera.yRot(),camera.xRot()};}
    /** The camera's near plane, used to turn screen points into world rays. */
    public static Camera.NearPlane nearPlane(Camera camera){return camera.getNearPlane(camera.getFov());}
    /** The direction from the camera through a point of the screen, x and y from -1 to 1 across the window. */
    public static net.minecraft.world.phys.Vec3 ray(Camera camera,float x,float y){return nearPlane(camera).getPointOnPlane(x,y).normalize();}
    public static KeyMapping registerKey(KeyMapping key){return KeyMappingHelper.registerKeyMapping(key);}

    /** Lets the local singleplayer owner use commands, then tells connected clients their new permission level. */
    public static void enableCheats(MinecraftServer server){
        server.getWorldData().setAllowCommands(true);
        server.getPlayerList().setAllowCommandsForAllPlayers(true);
        for(var player:server.getPlayerList().getPlayers())server.getPlayerList().sendPlayerPermissionLevel(player);
    }

    /** Vertical field of view in degrees. */
    public static float fov(Minecraft mc,Camera camera){return camera.getFov();}
    /** Rebuilds every compiled chunk so changed render settings take effect. */
    public static void rebuildChunks(Minecraft mc){mc.levelRenderer.invalidateCompiledGeometry(mc.level,mc.options,camera(mc),mc.getBlockColors());}
    /** Biome or block tint of the state's first tint layer at {@code pos}, or -1 when it has none. */
    public static int blockTint(Minecraft mc,BlockState state,BlockPos pos){return blockTint(mc,state,pos,0);}
    /** Biome or block tint of the state's tint layer {@code index} at {@code pos}, or -1 when it has none. */
    public static int blockTint(Minecraft mc,BlockState state,BlockPos pos,int index){
        var source=mc.getBlockColors().getTintSource(state,index);
        return source==null?-1:source.colorInWorld(state,mc.level,pos);
    }
    /** Sets the game's GUI scale back to the player's own and lays the current screen out again. */
    public static void refreshGui(Minecraft mc){mc.resizeGui();}
}
