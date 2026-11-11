package dev.craftium.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.craftium.core.VoxelOrbit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;
import org.joml.Vector3f;
import javax.imageio.ImageIO;
import java.util.*;
import java.util.function.Consumer;

/**
 * Blocks the game draws from an entity model instead of from block model files: chests (single, double, trapped, ender, copper), shulker boxes and beds.
 * The model is the game's own, baked from its layer, and is placed with the same moves the game's renderer makes (taken from the renderers themselves), so
 * the shape, the textures and their layout are exactly the game's, with the player's resource packs. Where a model or texture cannot be had, the block has
 * no shape here and the preview falls back to a cube.
 */
final class BlockEntityShapes {
    private record Picture(int[] pixels, int width, int height) {}
    private static final Map<String, Optional<Picture>> TEXTURES = new HashMap<>();
    private static Object resources;
    private BlockEntityShapes() {}

    /** The shape of a block of one of these kinds, or null when the block is of another kind or cannot be made. */
    static VoxelOrbit.Shape of(String id, Map<String, String> properties) {
        if (!id.startsWith("minecraft:")) return null;
        var mc = Minecraft.getInstance();
        if (resources != mc.getResourceManager()) { resources = mc.getResourceManager(); TEXTURES.clear(); }
        String name = id.substring(10);
        try {
            if (name.endsWith("shulker_box")) return shulker(name, properties);
            if (name.endsWith("_bed")) return bed(name, properties);
            return chest(name.startsWith("waxed_") ? name.substring(6) : name, properties);
        } catch (RuntimeException e) { // a layer or texture this game does not have
            return null;
        }
    }

    // ---- the kinds

    private static VoxelOrbit.Shape chest(String name, Map<String, String> properties) {
        String texture = switch (name) {
            case "chest" -> "chest/normal";
            case "trapped_chest" -> "chest/trapped";
            case "ender_chest" -> "chest/ender";
            case "copper_chest" -> "chest/copper";
            case "exposed_copper_chest" -> "chest/copper_exposed";
            case "weathered_copper_chest" -> "chest/copper_weathered";
            case "oxidized_copper_chest" -> "chest/copper_oxidized";
            default -> null;
        };
        if (texture == null) return null;
        String type = name.equals("ender_chest") ? "single" : properties.getOrDefault("type", "single");
        var layer = type.equals("left") ? ModelLayers.DOUBLE_CHEST_LEFT : type.equals("right") ? ModelLayers.DOUBLE_CHEST_RIGHT : ModelLayers.CHEST;
        if (!type.equals("single")) texture += "_" + type;
        Direction facing = direction(properties.getOrDefault("facing", "north"));
        return make(layer, texture, true, pose -> {
            pose.translate(0.5f, 0.5f, 0.5f);
            pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
            pose.translate(-0.5f, -0.5f, -0.5f);
        });
    }

    private static VoxelOrbit.Shape shulker(String name, Map<String, String> properties) {
        String colour = name.equals("shulker_box") ? "" : "_" + name.substring(0, name.length() - "_shulker_box".length());
        Direction facing = direction(properties.getOrDefault("facing", "up"));
        return make(ModelLayers.SHULKER_BOX, "shulker/shulker" + colour, true, pose -> {
            pose.translate(0.5f, 0.5f, 0.5f);
            pose.scale(0.9995f, 0.9995f, 0.9995f);
            pose.mulPose(facing.getRotation());
            pose.scale(1, -1, -1);
            pose.translate(0, -1, 0);
        });
    }

    private static VoxelOrbit.Shape bed(String name, Map<String, String> properties) {
        String colour = name.substring(0, name.length() - "_bed".length());
        boolean head = properties.getOrDefault("part", "foot").equals("head");
        Direction facing = direction(properties.getOrDefault("facing", "north"));
        var layer = new ModelLayerLocation(dev.craftium.compat.Compat.id("minecraft", head ? "bed_head" : "bed_foot"), "main"); // not a constant in every version of the game
        return make(layer, "bed/" + colour, true, pose -> {
            pose.translate(0, 0.5625f, 0);
            pose.mulPose(Axis.XP.rotationDegrees(90));
            pose.translate(0.5f, 0.5f, 0.5f);
            pose.mulPose(Axis.ZP.rotationDegrees(180 + facing.toYRot()));
            pose.translate(-0.5f, -0.5f, -0.5f);
        });
    }

    private static Direction direction(String name) {
        var d = Direction.byName(name);
        if (d == null) throw new IllegalArgumentException(name);
        return d;
    }

    // ---- the model as rectangles

    /** Bakes the layer, places it with the moves given, and turns every face of every box of it into a rectangle with the texture {@code textures/entity/<texture>.png}. */
    private static VoxelOrbit.Shape make(ModelLayerLocation layer, String texture, boolean full, Consumer<PoseStack> place) {
        var picture = picture("entity/" + texture);
        if (picture == null) return null;
        ModelPart root = Minecraft.getInstance().getEntityModels().bakeLayer(layer);
        var pose = new PoseStack();
        place.accept(pose);
        var quads = new ArrayList<VoxelOrbit.Quad>();
        root.visit(pose, (at, path, index, cube) -> {
            for (var polygon : cube.polygons) {
                var v = polygon.vertices();
                if (v.length < 4) continue;
                Vector3f[] p = new Vector3f[4];
                for (int i = 0; i < 4; i++) p[i] = at.pose().transformPosition(v[i].worldX(), v[i].worldY(), v[i].worldZ(), new Vector3f());
                Vector3f normal = at.transformNormal(polygon.normal(), new Vector3f());
                float tw = picture.width(), th = picture.height();
                float[] o = {p[0].x, p[0].y, p[0].z};
                float[] a = {p[1].x - p[0].x, p[1].y - p[0].y, p[1].z - p[0].z};
                float[] b = {p[3].x - p[0].x, p[3].y - p[0].y, p[3].z - p[0].z};
                float[] n = {normal.x, normal.y, normal.z};
                quads.add(new VoxelOrbit.Quad(o, a, b, n, v[0].u() * tw, v[0].v() * th, (v[1].u() - v[0].u()) * tw, (v[1].v() - v[0].v()) * th, (v[3].u() - v[0].u()) * tw, (v[3].v() - v[0].v()) * th,
                        picture.pixels(), picture.width(), picture.height(), -1, shadeSide(n)));
            }
        });
        if (quads.isEmpty()) return null;
        return new VoxelOrbit.Shape(quads.toArray(new VoxelOrbit.Quad[0]), false);
    }

    /** Which side a direction mostly points at, for how light it is drawn. */
    private static int shadeSide(float[] n) {
        float ax = Math.abs(n[0]), ay = Math.abs(n[1]), az = Math.abs(n[2]);
        if (ay >= ax && ay >= az) return n[1] >= 0 ? VoxelOrbit.UP : VoxelOrbit.DOWN;
        if (az >= ax) return n[2] >= 0 ? VoxelOrbit.SOUTH : VoxelOrbit.NORTH;
        return n[0] >= 0 ? VoxelOrbit.EAST : VoxelOrbit.WEST;
    }

    private static Picture picture(String path) {
        return TEXTURES.computeIfAbsent(path, key -> {
            try (var stream = Minecraft.getInstance().getResourceManager().getResourceOrThrow(dev.craftium.compat.Compat.id("minecraft", "textures/" + key + ".png")).open()) {
                var image = ImageIO.read(stream);
                if (image == null) return Optional.empty();
                int w = image.getWidth(), h = image.getHeight();
                int[] pixels = new int[w * h];
                image.getRGB(0, 0, w, h, pixels, 0, w);
                return Optional.of(new Picture(pixels, w, h));
            } catch (Exception e) {
                return Optional.empty();
            }
        }).orElse(null);
    }
}
