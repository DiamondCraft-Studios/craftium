package dev.craftium.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.craftium.client.compat.ReferenceTexture;
import dev.craftium.client.compat.Tex;
import net.minecraft.client.Minecraft;
import java.util.UUID;

/**
 * A texture a window fills from a software-drawn pixel buffer (the preview rasteriser's output) and shows with a blit. It sizes itself to the area it is
 * shown in and the GUI scale, and gives itself back to the texture manager on {@link #release()}. Shared by every window with a software preview.
 */
final class PixelTexture {
    private static final int MAX_PIXELS = 700_000;
    private final String group;
    private Tex tex;
    private ReferenceTexture texture;
    private int[] pixels = new int[0];
    private int width, height;

    PixelTexture(String group) { this.group = group; }

    /** Makes a fresh texture for an area of {@code areaWidth} by {@code areaHeight} GUI pixels. */
    void resize(int areaWidth, int areaHeight) {
        release();
        double scale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        int w = (int) Math.max(16, areaWidth * scale), h = (int) Math.max(16, areaHeight * scale);
        while ((long) w * h > MAX_PIXELS) { w = w * 3 / 4; h = h * 3 / 4; }
        width = w; height = h; pixels = new int[w * h];
        texture = new ReferenceTexture(new NativeImage(w, h, false));
        texture.filtering(true);
        tex = Tex.of("craftium", group + "/" + UUID.randomUUID());
        Minecraft.getInstance().getTextureManager().register(tex.id(), texture);
    }

    boolean ready() { return texture != null; }
    int[] pixels() { return pixels; }
    int width() { return width; }
    int height() { return height; }
    Tex tex() { return tex; }

    /** Copies the pixel buffer to the GPU texture. */
    void upload() {
        if (texture == null) return;
        var image = texture.getPixels();
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) image.setPixel(x, y, pixels[y * width + x]);
        texture.upload();
    }

    void release() {
        if (tex != null) Minecraft.getInstance().getTextureManager().release(tex.id());
        tex = null; texture = null;
    }
}
