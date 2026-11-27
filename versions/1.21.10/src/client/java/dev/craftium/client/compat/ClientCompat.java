package dev.craftium.client.compat;

import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Minecraft 1.21.10 implementation: the open screen is a public field on the client. */
public final class ClientCompat {
    private ClientCompat() {}

    public static Screen screen(Minecraft mc) {
        return mc.screen;
    }
    /** A line in the player's chat that only they see. */
    public static void chat(Minecraft mc, net.minecraft.network.chat.Component line) {
        mc.gui.getChat().addMessage(line);
    }

    public static void setScreen(Minecraft mc, Screen screen) {
        mc.setScreen(screen);
    }
    /** Shows a message above the hotbar, in the place the game puts its own action bar text. */
    public static void overlayMessage(Minecraft mc, String message) {
        mc.gui.setOverlayMessage(net.minecraft.network.chat.Component.literal(message), false);
    }

    public static boolean hudHidden(Minecraft mc) {
        return mc.options.hideGui;
    }

    public static void toggleHud(Minecraft mc) {
        mc.options.hideGui = !mc.options.hideGui;
    }

    public static Camera camera(Minecraft mc) {
        return mc.gameRenderer.getMainCamera();
    }
    /** The frame the game last drew, for screenshots. */
    public static com.mojang.blaze3d.pipeline.RenderTarget mainTarget(Minecraft mc) {
        return mc.getMainRenderTarget();
    }
    /** Sets the size the world is drawn at, which may differ from the window's, and resizes the render target to match. */
    public static void setRenderSize(Minecraft mc, int width, int height) {
        mc.getWindow().setWidth(width);
        mc.getWindow().setHeight(height);
        mc.resizeDisplay();
    }
    /** Where the camera is and which way it looks, as x, y, z, yaw, pitch. */
    public static double[] cameraPose(Camera camera) {
        var p = camera.getPosition();
        return new double[] {p.x, p.y, p.z, camera.getYRot(), camera.getXRot()};
    }

    public static Camera.NearPlane nearPlane(Camera camera) {
        return camera.getNearPlane();
    }

    private static KeyMapping.Category category;

    /** A key mapping in Craftium's category of the controls screen, registered with the loader. */
    public static KeyMapping key(String name, int defaultKey) {
        if (category == null)
            category = KeyMapping.Category.register(dev.craftium.compat.Compat.id("craftium", "studio"));
        return dev.craftium.client.platform.ClientPlatform.registerKey(
                new KeyMapping(name, com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, defaultKey, category));
    }

    /**
     * 1.21.10 keeps the world's cheats flag in an immutable LevelSettings with no setter, so this opens
     * commands to all players on the integrated server and refreshes their permission level instead.
     */
    public static void enableCheats(MinecraftServer server) {
        server.getPlayerList().setAllowCommandsForAllPlayers(true);
        for (var player : server.getPlayerList().getPlayers())
            server.getPlayerList().sendPlayerPermissionLevel(player);
    }

    /** The vertical field of view the last frame was rendered with, set by FovMixin; NaN until a frame has been drawn. */
    public static float renderedFov = Float.NaN;
    /**
     * The field of view the world is drawn with: the configured one plus dynamic effects such as the boost while flying.
     * Camera.getNearPlane() in 1.21.10 knows only the configured one, so the picking and projection use this instead.
     */
    public static float fov(Minecraft mc, Camera camera) {
        return Float.isNaN(renderedFov) ? mc.options.fov().get() : renderedFov;
    }
    /** The direction from the camera through a point of the screen, x and y from -1 to 1 across the window. */
    public static Vec3 ray(Camera camera, float x, float y) {
        var mc = Minecraft.getInstance();
        // The near plane is built for the configured field of view; its offsets scale with the tangent of the real one.
        double ratio = Math.tan(Math.toRadians(fov(mc, camera)) / 2)
                / Math.tan(Math.toRadians((double) mc.options.fov().get()) / 2);
        var plane = camera.getNearPlane();
        Vec3 center = plane.getPointOnPlane(0, 0);
        return center.add(plane.getPointOnPlane(x, y).subtract(center).scale(ratio))
                .normalize();
    }

    public static void rebuildChunks(Minecraft mc) {
        mc.levelRenderer.allChanged();
    }

    public static int blockTint(Minecraft mc, BlockState state, BlockPos pos) {
        return blockTint(mc, state, pos, 0);
    }
    /** Biome or block tint of the state's tint layer {@code index} at {@code pos}, or -1 when it has none. */
    public static int blockTint(Minecraft mc, BlockState state, BlockPos pos, int index) {
        return mc.getBlockColors().getColor(state, mc.level, pos, index);
    }
    /** Sets the game's GUI scale back to the player's own and lays the current screen out again. */
    public static void refreshGui(Minecraft mc) {
        mc.resizeDisplay();
    }

    public static boolean ctrlDown() {
        return Minecraft.getInstance().hasControlDown();
    }

    public static boolean shiftDown() {
        return Minecraft.getInstance().hasShiftDown();
    }

    public static boolean altDown() {
        return Minecraft.getInstance().hasAltDown();
    }

    /** The GLFW handle of the game window. */
    public static long windowHandle() {
        return Minecraft.getInstance().getWindow().handle();
    }

    public static net.minecraft.world.phys.Vec3 cameraPosition(Camera camera) {
        return camera.position();
    }

    /** Writes one pixel, given as ARGB. */
    public static void setPixel(com.mojang.blaze3d.platform.NativeImage image, int x, int y, int argb) {
        image.setPixel(x, y, argb);
    }

    /** One pixel, as ARGB. */
    public static int getPixel(com.mojang.blaze3d.platform.NativeImage image, int x, int y) {
        return image.getPixel(x, y);
    }

    /** Every pixel, row by row, as ARGB. */
    public static int[] argbPixels(com.mojang.blaze3d.platform.NativeImage image) {
        return image.getPixels();
    }

    /** Whether the key with this GLFW code is held now. */
    public static boolean keyDown(int key) {
        return com.mojang.blaze3d.platform.InputConstants.isKeyDown(
                Minecraft.getInstance().getWindow(), key);
    }

    public static boolean matches(KeyMapping mapping, net.minecraft.client.input.KeyEvent event) {
        return mapping.matches(event);
    }

    /** Reads the target's pixels and hands them on; the receiver closes the image. */
    public static void screenshot(
            com.mojang.blaze3d.pipeline.RenderTarget target,
            java.util.function.Consumer<com.mojang.blaze3d.platform.NativeImage> then) {
        net.minecraft.client.Screenshot.takeScreenshot(target, then);
    }

    public static net.minecraft.client.gui.components.MultiLineEditBox multiLineBox(
            net.minecraft.client.gui.Font font,
            int x,
            int y,
            int width,
            int height,
            net.minecraft.network.chat.Component label) {
        return net.minecraft.client.gui.components.MultiLineEditBox.builder()
                .setX(x)
                .setY(y)
                .build(font, width, height, label);
    }

    // Passing input on to a vanilla widget or screen.

    public static boolean mouseClicked(
            net.minecraft.client.gui.components.events.GuiEventListener target,
            net.minecraft.client.input.MouseButtonEvent event,
            boolean doubleClick) {
        return target.mouseClicked(event, doubleClick);
    }

    public static boolean mouseReleased(
            net.minecraft.client.gui.components.events.GuiEventListener target,
            net.minecraft.client.input.MouseButtonEvent event) {
        return target.mouseReleased(event);
    }

    public static boolean mouseDragged(
            net.minecraft.client.gui.components.events.GuiEventListener target,
            net.minecraft.client.input.MouseButtonEvent event,
            double dx,
            double dy) {
        return target.mouseDragged(event, dx, dy);
    }

    public static boolean keyPressed(
            net.minecraft.client.gui.components.events.GuiEventListener target,
            net.minecraft.client.input.KeyEvent event) {
        return target.keyPressed(event);
    }

    public static boolean keyReleased(
            net.minecraft.client.gui.components.events.GuiEventListener target,
            net.minecraft.client.input.KeyEvent event) {
        return target.keyReleased(event);
    }

    public static boolean charTyped(
            net.minecraft.client.gui.components.events.GuiEventListener target,
            net.minecraft.client.input.CharacterEvent event) {
        return target.charTyped(event);
    }

    /** The faces of a box of an entity model, placed by {@code at}. */
    public static java.util.List<dev.craftium.client.ModelFace> faces(
            com.mojang.blaze3d.vertex.PoseStack.Pose at, net.minecraft.client.model.geom.ModelPart.Cube cube) {
        var out = new java.util.ArrayList<dev.craftium.client.ModelFace>();
        for (var polygon : cube.polygons) {
            var v = polygon.vertices();
            if (v.length < 4) continue;
            var corners = new org.joml.Vector3f[4];
            float[] u = new float[4], t = new float[4];
            for (int i = 0; i < 4; i++) {
                corners[i] = at.pose()
                        .transformPosition(v[i].worldX(), v[i].worldY(), v[i].worldZ(), new org.joml.Vector3f());
                u[i] = v[i].u();
                t[i] = v[i].v();
            }
            out.add(new dev.craftium.client.ModelFace(
                    corners, u, t, at.transformNormal(polygon.normal(), new org.joml.Vector3f())));
        }
        return out;
    }

    /** The model layer of a shulker box. */
    public static net.minecraft.client.model.geom.ModelLayerLocation shulkerBoxLayer() {
        return net.minecraft.client.model.geom.ModelLayers.SHULKER_BOX;
    }
}
