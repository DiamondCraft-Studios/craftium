package dev.craftium.client.compat;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

/** Minecraft 1.21.1 implementation of {@link Gfx}: GUI drawing goes through a PoseStack and immediate draws there. */
public final class Gfx implements dev.craftium.client.Canvas {
    final GuiGraphics raw;
    private final Pose pose;

    private Gfx(GuiGraphics raw) {
        this.raw = raw;
        this.pose = new Pose(raw);
    }

    public static Gfx of(GuiGraphics raw) {
        return new Gfx(raw);
    }

    /**
     * The 2D transform, with the calls of the JOML stack later versions hand out: shared code pushes, pops, moves,
     * scales and turns the GUI through these.
     */
    public static final class Pose {
        private final GuiGraphics raw;

        private Pose(GuiGraphics raw) {
            this.raw = raw;
        }

        public Pose pushMatrix() {
            raw.pose().pushPose();
            return this;
        }

        public Pose popMatrix() {
            raw.pose().popPose();
            return this;
        }

        public Pose translate(float x, float y) {
            raw.pose().translate(x, y, 0);
            return this;
        }

        public Pose scale(float x, float y) {
            raw.pose().scale(x, y, 1);
            return this;
        }

        public Pose scale(float xy) {
            return scale(xy, xy);
        }

        /** Replaces the transform with no transform at all. */
        public Pose identity() {
            raw.pose().last().pose().identity();
            return this;
        }

        // The 2D transform's entries, named as in JOML's Matrix3x2f: mCR is column C, row R; m20, m21 the translation.

        public float m00() {
            return raw.pose().last().pose().m00();
        }

        public float m01() {
            return raw.pose().last().pose().m01();
        }

        public float m10() {
            return raw.pose().last().pose().m10();
        }

        public float m11() {
            return raw.pose().last().pose().m11();
        }

        public float m20() {
            return raw.pose().last().pose().m30();
        }

        public float m21() {
            return raw.pose().last().pose().m31();
        }

        /** Turns by {@code radians} around the screen's normal. */
        public Pose rotate(float radians) {
            raw.pose().mulPose(Axis.ZP.rotation(radians));
            return this;
        }
    }

    public Pose pose() {
        return pose;
    }

    public int guiWidth() {
        return raw.guiWidth();
    }

    public int guiHeight() {
        return raw.guiHeight();
    }

    public void enableScissor(int x1, int y1, int x2, int y2) {
        raw.enableScissor(x1, y1, x2, y2);
    }

    public void disableScissor() {
        raw.disableScissor();
    }

    public void fill(int x1, int y1, int x2, int y2, int color) {
        raw.fill(x1, y1, x2, y2, color);
    }

    public void fillGradient(int x1, int y1, int x2, int y2, int top, int bottom) {
        raw.fillGradient(x1, y1, x2, y2, top, bottom);
    }

    /** One-pixel border drawn just inside the rectangle, from four fills like the other versions. */
    public void outline(int x, int y, int width, int height, int color) {
        raw.fill(x, y, x + width, y + 1, color);
        raw.fill(x, y + height - 1, x + width, y + height, color);
        raw.fill(x, y + 1, x + 1, y + height - 1, color);
        raw.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
    }

    public void text(Font font, String text, int x, int y, int color) {
        if (!dev.craftium.client.CrispText.draw(this, font, text, x, y, color, true, false))
            raw.drawString(font, text, x, y, color);
    }

    public void text(Font font, String text, int x, int y, int color, boolean shadow) {
        if (!dev.craftium.client.CrispText.draw(this, font, text, x, y, color, shadow, false))
            raw.drawString(font, text, x, y, color, shadow);
    }

    public void text(Font font, Component text, int x, int y, int color) {
        if (!text.getStyle().isEmpty()
                || !text.getSiblings().isEmpty()
                || !dev.craftium.client.CrispText.draw(this, font, text.getString(), x, y, color, true, false))
            raw.drawString(font, text, x, y, color);
    }

    public void text(Font font, Component text, int x, int y, int color, boolean shadow) {
        if (!text.getStyle().isEmpty()
                || !text.getSiblings().isEmpty()
                || !dev.craftium.client.CrispText.draw(this, font, text.getString(), x, y, color, shadow, false))
            raw.drawString(font, text, x, y, color, shadow);
    }

    public void text(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
        raw.drawString(font, text, x, y, color, shadow);
    }

    public void centeredText(Font font, String text, int x, int y, int color) {
        if (!dev.craftium.client.CrispText.draw(this, font, text, x, y, color, true, true))
            raw.drawCenteredString(font, text, x, y, color);
    }

    public void centeredText(Font font, Component text, int x, int y, int color) {
        if (!text.getStyle().isEmpty()
                || !text.getSiblings().isEmpty()
                || !dev.craftium.client.CrispText.draw(this, font, text.getString(), x, y, color, true, true))
            raw.drawCenteredString(font, text, x, y, color);
    }

    public void item(ItemStack stack, int x, int y) {
        raw.renderItem(stack, x, y);
    }
    /** The count and durability bar over an item drawn with {@link #item}. */
    public void itemDecorations(Font font, ItemStack stack, int x, int y) {
        raw.renderItemDecorations(font, stack, x, y);
    }
    /** A piece of a vanilla GUI sprite: the {@code width} by {@code height} area at {@code u}, {@code v} of a sprite that is {@code spriteWidth} by {@code spriteHeight}, drawn at its own size. */
    public void spritePiece(
            String path, int spriteWidth, int spriteHeight, int u, int v, int x, int y, int width, int height) {
        raw.blitSprite(
                ResourceLocation.withDefaultNamespace(path), spriteWidth, spriteHeight, u, v, x, y, width, height);
    }
    /** A vanilla GUI sprite by path, like {@code hud/hotbar}, drawn at the given size. */
    public void sprite(String path, int x, int y, int width, int height) {
        raw.blitSprite(ResourceLocation.withDefaultNamespace(path), x, y, width, height);
    }

    public void blit(
            Tex texture,
            int x,
            int y,
            float u,
            float v,
            int width,
            int height,
            int regionWidth,
            int regionHeight,
            int textureWidth,
            int textureHeight,
            int color) {
        boolean tinted = color != -1;
        if (tinted)
            raw.setColor(
                    (color >> 16 & 255) / 255f, (color >> 8 & 255) / 255f, (color & 255) / 255f, (color >>> 24) / 255f);
        RenderSystem.enableBlend();
        raw.blit(texture.id(), x, y, width, height, u, v, regionWidth, regionHeight, textureWidth, textureHeight);
        RenderSystem.disableBlend();
        if (tinted) raw.setColor(1, 1, 1, 1);
    }

    /**
     * Fills a rectangle by inverting what is under it, the way the vanilla crosshair is drawn, so it shows on light and dark alike. The colour's
     * brightness is how much is inverted: white inverts fully, black leaves the pixel as it is.
     */
    public void invertFill(int x1, int y1, int x2, int y2, int color) {
        raw.flush();
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                com.mojang.blaze3d.platform.GlStateManager.SourceFactor.ONE_MINUS_DST_COLOR,
                com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE_MINUS_SRC_COLOR,
                com.mojang.blaze3d.platform.GlStateManager.SourceFactor.ONE,
                com.mojang.blaze3d.platform.GlStateManager.DestFactor.ZERO);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        var matrix = raw.pose().last().pose();
        var buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        buffer.addVertex(matrix, x1, y1, 0).setColor(color);
        buffer.addVertex(matrix, x1, y2, 0).setColor(color);
        buffer.addVertex(matrix, x2, y2, 0).setColor(color);
        buffer.addVertex(matrix, x2, y1, 0).setColor(color);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    /** The vanilla crosshair sprite, plain white, with its 15x15 box centred on the given point. */
    public void crosshair(int centerX, int centerY) {
        raw.flush();
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                com.mojang.blaze3d.platform.GlStateManager.SourceFactor.ONE_MINUS_DST_COLOR,
                com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE_MINUS_SRC_COLOR,
                com.mojang.blaze3d.platform.GlStateManager.SourceFactor.ONE,
                com.mojang.blaze3d.platform.GlStateManager.DestFactor.ZERO);
        raw.blitSprite(ResourceLocation.withDefaultNamespace("hud/crosshair"), centerX - 7, centerY - 7, 15, 15);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    public void widget(AbstractWidget widget, int mouseX, int mouseY, float delta) {
        widget.render(raw, mouseX, mouseY, delta);
    }
}
