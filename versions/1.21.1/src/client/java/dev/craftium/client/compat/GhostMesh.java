package dev.craftium.client.compat;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.*;
import java.util.function.IntBinaryOperator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/**
 * Minecraft 1.21.1 implementation: a ghost of block models baked once into vertex buffers that stay on the graphics
 * card, so drawing it costs a few draw calls a frame however many blocks it holds.
 *
 * <p>As in later versions, each side of the model is kept in a bucket of its own (twelve: the six sides of a block,
 * and the six directions of the quads that belong to no side), sorted along that side's axis, so the blocks whose side
 * faces the camera are the first ones of the bucket. Here each bucket is a vertex buffer of its own, and a frame draws
 * the first quads of each with the bucket's shader set up by hand, as {@link VertexBuffer#drawWithShader} would.
 */
final class GhostMesh implements AutoCloseable {
    private static final float SCALE =
            1.01f; // about the block's centre, so the ghost clears the real block instead of fighting it for depth
    private static final Direction[] DIRS = Direction.values();
    private static boolean broken; // set when a draw failed: the immediate path takes over from then on

    private final VertexBuffer[] buckets;
    private final int originX, originY, originZ;
    /** Per bucket: the sort key of each block, and how many quads end with each block. */
    private final double[][] keys;

    private final int[][] quadsUpTo;
    private boolean closed;

    private GhostMesh(VertexBuffer[] buckets, int ox, int oy, int oz, double[][] keys, int[][] quadsUpTo) {
        this.buckets = buckets;
        originX = ox;
        originY = oy;
        originZ = oz;
        this.keys = keys;
        this.quadsUpTo = quadsUpTo;
    }

    static boolean usable() {
        return !broken;
    }

    private static RenderType type() {
        return RenderType.entityTranslucent(TextureAtlas.LOCATION_BLOCKS);
    }

    /**
     * Bakes the blocks. {@code outer} says which sides of each are on the outer surface, {@code loose} whether it has
     * quads that belong to no side, {@code tint} gives the biome tint of a block's tint index (or -1 for none). Null
     * when there is nothing to draw or the bake failed.
     */
    static GhostMesh bake(
            List<BlockPos> pos,
            List<Integer> outer,
            List<Boolean> loose,
            List<List<Overlay.Part>> parts,
            int ghostTint,
            IntBinaryOperator tint) {
        if (broken || pos.isEmpty()) return null;
        var buckets = new VertexBuffer[12];
        try {
            var format = type().format();
            int ox = Integer.MAX_VALUE, oy = ox, oz = ox;
            for (var p : pos) {
                ox = Math.min(ox, p.getX());
                oy = Math.min(oy, p.getY());
                oz = Math.min(oz, p.getZ());
            }
            float a = (ghostTint >>> 24) / 255f,
                    r = (ghostTint >> 16 & 255) / 255f,
                    g = (ghostTint >> 8 & 255) / 255f,
                    b = (ghostTint & 255) / 255f;
            var byBucket = new ArrayList<List<Integer>>();
            for (int i = 0; i < 12; i++) byBucket.add(new ArrayList<>());
            for (int i = 0; i < pos.size(); i++) {
                int mask = outer.get(i);
                for (Direction d : DIRS)
                    if ((mask >> d.ordinal() & 1) != 0)
                        byBucket.get(d.ordinal()).add(i);
                if (loose.get(i))
                    for (Direction d : DIRS) byBucket.get(6 + d.ordinal()).add(i);
            }
            var keys = new double[12][];
            var quadsUpTo = new int[12][];
            boolean any = false;
            for (int bucket = 0; bucket < 12; bucket++) {
                Direction d = DIRS[bucket % 6];
                boolean looseSide = bucket >= 6;
                var order = byBucket.get(bucket);
                order.sort(Comparator.comparingDouble(i -> key(pos.get(i), d)));
                var k = new double[order.size()];
                var upTo = new int[order.size()];
                int quads = 0;
                try (var bytes = new ByteBufferBuilder(1 << 16)) {
                    var builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, format);
                    var stack = new PoseStack();
                    for (int n = 0; n < order.size(); n++) {
                        int i = order.get(n);
                        var at = pos.get(i);
                        k[n] = key(at, d);
                        stack.pushPose();
                        stack.translate(at.getX() - ox + .5, at.getY() - oy + .5, at.getZ() - oz + .5);
                        stack.scale(SCALE, SCALE, SCALE);
                        stack.translate(-.5, -.5, -.5);
                        var pose = stack.last();
                        for (var part : parts.get(i))
                            for (var quad : part.getQuads(looseSide ? null : d)) {
                                if (looseSide && quad.getDirection() != d) continue;
                                int t = tint.applyAsInt(i, quad.getTintIndex());
                                int rgb = t == -1 ? 0xFFFFFF : t & 0xFFFFFF;
                                builder.putBulkData(
                                        pose,
                                        quad,
                                        r * (rgb >> 16 & 255) / 255f,
                                        g * (rgb >> 8 & 255) / 255f,
                                        b * (rgb & 255) / 255f,
                                        a,
                                        LightTexture.FULL_BRIGHT,
                                        OverlayTexture.NO_OVERLAY);
                                quads++;
                            }
                        stack.popPose();
                        upTo[n] = quads;
                    }
                    var mesh = builder.build();
                    if (mesh != null) {
                        try (mesh) {
                            var buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                            buckets[bucket] = buffer;
                            buffer.bind();
                            buffer.upload(mesh);
                            VertexBuffer.unbind();
                            any = true;
                        }
                    }
                }
                keys[bucket] = k;
                quadsUpTo[bucket] = upTo;
            }
            if (!any) return null;
            return new GhostMesh(buckets, ox, oy, oz, keys, quadsUpTo);
        } catch (Throwable e) {
            for (var buffer : buckets) if (buffer != null) buffer.close();
            broken = true;
            System.err.println("Craftium: the ghost mesh failed, drawing ghosts block by block instead: " + e);
            return null;
        }
    }

    /** Each side's sort key: how far the block's centre lies along the side's direction. A block's side shows when the camera is further along than that. */
    private static double key(BlockPos p, Direction d) {
        return d.getStepX() * (p.getX() + .5) + d.getStepY() * (p.getY() + .5) + d.getStepZ() * (p.getZ() + .5);
    }

    /** Draws every side facing the camera at {@code eye}. Returns false when it could not, so the caller draws the blocks itself. */
    boolean draw(Vec3 eye) {
        if (broken || closed) return false;
        var type = type();
        try {
            type.setupRenderState();
            try {
                var shader = RenderSystem.getShader();
                if (shader == null) return false;
                var modelView = new Matrix4f(RenderSystem.getModelViewMatrix())
                        .translate((float) (originX - eye.x), (float) (originY - eye.y), (float) (originZ - eye.z));
                shader.setDefaultUniforms(
                        VertexFormat.Mode.QUADS,
                        modelView,
                        RenderSystem.getProjectionMatrix(),
                        Minecraft.getInstance().getWindow());
                shader.apply();
                try {
                    var indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
                    for (int bucket = 0; bucket < 12; bucket++) {
                        int quads = buckets[bucket] == null ? 0 : visibleQuads(bucket, eye);
                        if (quads == 0) continue;
                        buckets[bucket].bind();
                        RenderSystem.drawElements(GL11.GL_TRIANGLES, quads * 6, indices.type().asGLType);
                    }
                } finally {
                    shader.clear();
                    VertexBuffer.unbind();
                }
            } finally {
                type.clearRenderState();
            }
            return true;
        } catch (Throwable e) {
            broken = true;
            System.err.println("Craftium: drawing the ghost mesh failed, drawing ghosts block by block instead: " + e);
            return false;
        }
    }

    /** How many quads of the bucket face the camera: those of the blocks with the key under the camera's own, less how far out a side sits (half a block, nothing for loose quads). */
    private int visibleQuads(int bucket, Vec3 eye) {
        var k = keys[bucket];
        if (k.length == 0) return 0;
        Direction d = DIRS[bucket % 6];
        double limit = d.getStepX() * eye.x + d.getStepY() * eye.y + d.getStepZ() * eye.z - (bucket < 6 ? .5 : 0);
        int lo = 0, hi = k.length; // the number of blocks with a key under the limit
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (k[mid] < limit) lo = mid + 1;
            else hi = mid;
        }
        return lo == 0 ? 0 : quadsUpTo[bucket][lo - 1];
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            for (var buffer : buckets) if (buffer != null) buffer.close();
        }
    }
}
