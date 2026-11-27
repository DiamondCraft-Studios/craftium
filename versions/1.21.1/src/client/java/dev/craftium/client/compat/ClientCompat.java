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

    /** A key mapping in Craftium's category of the controls screen, registered with the loader. */
    public static KeyMapping key(String name, int defaultKey) {
        return dev.craftium.client.platform.ClientPlatform.registerKey(new KeyMapping(
                name,
                com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
                defaultKey,
                "key.category.craftium.studio"));
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
        return net.minecraft.client.gui.screens.Screen.hasControlDown();
    }

    public static boolean shiftDown() {
        return net.minecraft.client.gui.screens.Screen.hasShiftDown();
    }

    public static boolean altDown() {
        return net.minecraft.client.gui.screens.Screen.hasAltDown();
    }

    /** The GLFW handle of the game window. */
    public static long windowHandle() {
        return Minecraft.getInstance().getWindow().getWindow();
    }

    public static net.minecraft.world.phys.Vec3 cameraPosition(Camera camera) {
        return camera.getPosition();
    }

    /** 1.21.1 images hold ABGR; this swaps the red and blue bytes either way. */
    private static int swapRedBlue(int color) {
        return color & 0xff00ff00 | (color & 0xff) << 16 | color >>> 16 & 0xff;
    }

    /** Writes one pixel, given as ARGB. */
    public static void setPixel(com.mojang.blaze3d.platform.NativeImage image, int x, int y, int argb) {
        image.setPixelRGBA(x, y, swapRedBlue(argb));
    }

    /** One pixel, as ARGB. */
    public static int getPixel(com.mojang.blaze3d.platform.NativeImage image, int x, int y) {
        return swapRedBlue(image.getPixelRGBA(x, y));
    }

    /** Every pixel, row by row, as ARGB. */
    public static int[] argbPixels(com.mojang.blaze3d.platform.NativeImage image) {
        int[] pixels = image.getPixelsRGBA();
        for (int i = 0; i < pixels.length; i++) pixels[i] = swapRedBlue(pixels[i]);
        return pixels;
    }

    /** Whether the key with this GLFW code is held now. */
    public static boolean keyDown(int key) {
        return com.mojang.blaze3d.platform.InputConstants.isKeyDown(
                Minecraft.getInstance().getWindow().getWindow(), key);
    }

    public static boolean matches(KeyMapping mapping, net.minecraft.client.input.KeyEvent event) {
        return mapping.matches(event.key(), event.scancode());
    }

    /** Reads the target's pixels and hands them on; the receiver closes the image. */
    public static void screenshot(
            com.mojang.blaze3d.pipeline.RenderTarget target,
            java.util.function.Consumer<com.mojang.blaze3d.platform.NativeImage> then) {
        then.accept(net.minecraft.client.Screenshot.takeScreenshot(target));
    }

    public static net.minecraft.client.gui.components.MultiLineEditBox multiLineBox(
            net.minecraft.client.gui.Font font,
            int x,
            int y,
            int width,
            int height,
            net.minecraft.network.chat.Component label) {
        return new net.minecraft.client.gui.components.MultiLineEditBox(
                font, x, y, width, height, net.minecraft.network.chat.Component.empty(), label);
    }

    // Passing input on to a vanilla widget or screen, in 1.21.1's form.

    public static boolean mouseClicked(
            net.minecraft.client.gui.components.events.GuiEventListener target,
            net.minecraft.client.input.MouseButtonEvent event,
            boolean doubleClick) {
        return target.mouseClicked(event.x(), event.y(), event.button());
    }

    public static boolean mouseReleased(
            net.minecraft.client.gui.components.events.GuiEventListener target,
            net.minecraft.client.input.MouseButtonEvent event) {
        return target.mouseReleased(event.x(), event.y(), event.button());
    }

    public static boolean mouseDragged(
            net.minecraft.client.gui.components.events.GuiEventListener target,
            net.minecraft.client.input.MouseButtonEvent event,
            double dx,
            double dy) {
        return target.mouseDragged(event.x(), event.y(), event.button(), dx, dy);
    }

    public static boolean keyPressed(
            net.minecraft.client.gui.components.events.GuiEventListener target,
            net.minecraft.client.input.KeyEvent event) {
        return target.keyPressed(event.key(), event.scancode(), event.modifiers());
    }

    public static boolean keyReleased(
            net.minecraft.client.gui.components.events.GuiEventListener target,
            net.minecraft.client.input.KeyEvent event) {
        return target.keyReleased(event.key(), event.scancode(), event.modifiers());
    }

    public static boolean charTyped(
            net.minecraft.client.gui.components.events.GuiEventListener target,
            net.minecraft.client.input.CharacterEvent event) {
        boolean handled = false;
        for (char c : Character.toChars(event.codepoint())) handled |= target.charTyped(c, event.modifiers());
        return handled;
    }

    /**
     * The faces of a box of an entity model, placed by {@code at}. 1.21.1 keeps a box's polygons to itself, so this has
     * the box draw itself into a consumer that keeps every four corners as a face.
     */
    public static java.util.List<dev.craftium.client.ModelFace> faces(
            com.mojang.blaze3d.vertex.PoseStack.Pose at, net.minecraft.client.model.geom.ModelPart.Cube cube) {
        var capture = new FaceCapture();
        cube.compile(at, capture, 0, 0, -1);
        return capture.faces;
    }

    private static final class FaceCapture implements com.mojang.blaze3d.vertex.VertexConsumer {
        final java.util.List<dev.craftium.client.ModelFace> faces = new java.util.ArrayList<>();
        private final org.joml.Vector3f[] corners = new org.joml.Vector3f[4];
        private final float[] u = new float[4], v = new float[4];
        private int count = -1;

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer addVertex(float x, float y, float z) {
            count++;
            corners[count] = new org.joml.Vector3f(x, y, z);
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setColor(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv(float u, float v) {
            this.u[count] = u;
            this.v[count] = v;
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setNormal(float x, float y, float z) {
            if (count == 3) { // the normal is the last thing given for a corner
                faces.add(new dev.craftium.client.ModelFace(
                        corners.clone(), u.clone(), v.clone(), new org.joml.Vector3f(x, y, z)));
                count = -1;
            }
            return this;
        }
    }

    /** The model layer of a shulker box: 1.21.1 draws boxes with the shulker's own model. */
    public static net.minecraft.client.model.geom.ModelLayerLocation shulkerBoxLayer() {
        return net.minecraft.client.model.geom.ModelLayers.SHULKER;
    }
}
