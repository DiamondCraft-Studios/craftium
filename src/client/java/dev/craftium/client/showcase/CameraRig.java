package dev.craftium.client.showcase;

import com.mojang.authlib.GameProfile;
import dev.craftium.client.compat.ClientCompat;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.Entity;

/**
 * A detached camera. While it is installed the game draws the world from a stand-in entity that exists only on this client, so the
 * player is never moved, turned or touched: their position, look direction and input stay as they were. Closing it puts everything
 * back. Both calls must be made on the client thread.
 */
final class CameraRig implements AutoCloseable {
    private final Minecraft mc;
    private final Entity previousCamera;
    private final int previousFov;
    private final boolean hudWasHidden, hudChanged, playerWasInvisible;
    private final int previousWidth, previousHeight;
    private boolean closed;

    private CameraRig(Minecraft mc, ShotSpec spec) {
        this.mc = mc;
        previousCamera = mc.getCameraEntity();
        previousFov = mc.options.fov().get();
        hudWasHidden = ClientCompat.hudHidden(mc);
        playerWasInvisible = mc.player.isInvisible();
        previousWidth = mc.getWindow().getWidth();
        previousHeight = mc.getWindow().getHeight();

        // The camera looks from the entity's eyes, so the entity stands one eye height below the requested point.
        var stand = new RemotePlayer(mc.level, new GameProfile(UUID.randomUUID(), "craftium-camera"));
        stand.setPos(spec.x(), spec.y() - stand.getEyeHeight(), spec.z());
        stand.setYRot(spec.yaw());
        stand.setXRot(spec.pitch());
        stand.setYHeadRot(spec.yaw());
        stand.yBodyRot = spec.yaw();
        stand.setOldPosAndRot();
        // The camera looks along the head's rotation and blends it from the previous frame's, which setOldPosAndRot does not cover:
        // left alone, the first frame would turn part of the way from yaw 0.
        stand.yHeadRotO = spec.yaw();
        stand.yBodyRotO = spec.yaw();

        mc.setCameraEntity(stand);
        mc.options.fov().set(spec.fov());
        // The real player would otherwise stand in the picture.
        mc.player.setInvisible(true);
        boolean hide = spec.hideHud() && !hudWasHidden;
        if (hide) ClientCompat.toggleHud(mc);
        hudChanged = hide;
        ClientCompat.setRenderSize(mc, spec.width(), spec.height());
    }

    static CameraRig install(Minecraft mc, ShotSpec spec) {
        if (mc.level == null || mc.player == null) throw new IllegalStateException("Not in a world");
        if (ClientCompat.screen(mc) != null) throw new IllegalStateException("A menu is open; close it so the world can be drawn");
        return new CameraRig(mc, spec);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        mc.setCameraEntity(previousCamera != null ? previousCamera : mc.player);
        mc.options.fov().set(previousFov);
        if (mc.player != null) mc.player.setInvisible(playerWasInvisible);
        if (hudChanged && ClientCompat.hudHidden(mc) != hudWasHidden) ClientCompat.toggleHud(mc);
        ClientCompat.setRenderSize(mc, previousWidth, previousHeight);
    }
}
