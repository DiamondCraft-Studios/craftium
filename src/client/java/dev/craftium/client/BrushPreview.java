package dev.craftium.client;

import dev.craftium.core.BrushMath;
import dev.craftium.core.Options;
import dev.craftium.core.Point;
import dev.craftium.core.Selection;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The brush under the cursor, as the blocks it would reach, depth-tested so terrain in front of it hides it. Where the brush sits in
 * solid blocks it colours the faces of those blocks you can see, with no outline; where it sits in open air, in front of the terrain,
 * it is the outline of those blocks, drawn like the selection's silhouette, as it is and without its extra edges. Uses the hover preview's colours.
 * It is worked out again when the shape, the block under the cursor or, once a second, the world changes.
 */
public final class BrushPreview {
    /** The preview shows brushes well past what can be built (see Selection.LIMIT), since it is only drawn; beyond this it falls back to the box around the brush. */
    private static final int MAX_BLOCKS = 600_000;
    /** The most cells the preview looks at to draw a shape: it is worked out on the frame that needs it, so it gets a good deal less than a build is allowed. A bigger shape is drawn as its box. */
    private static final double PREVIEW_CELLS = 24e6;

    private static int shownBlocks;
    private static List<Object> key = List.of();
    private static SceneShape shape = SceneShape.EMPTY;
    private static boolean tooLarge;

    private BrushPreview() {}

    public static void draw(Minecraft mc, BlockPos at, Options o) {
        draw(mc, at, net.minecraft.core.Direction.UP, o);
    }

    /** {@code face} is the side of the block under the cursor that is aimed at, which the sculpting tools build out from. */
    public static void draw(Minecraft mc, BlockPos at, net.minecraft.core.Direction face, Options o) {
        if (mc.level == null) return;
        var normal = new Point(face.getStepX(), face.getStepY(), face.getStepZ());
        var now = List.<Object>of(
                o.tool.brushUse(),
                o.shape,
                o.radius,
                o.hollow,
                o.outlineThickness,
                o.pixelPerfect,
                o.strokeAlign,
                o.polygonSides,
                o.polygonSeed,
                o.starInner,
                o.outline,
                o.surfaceOnly,
                o.inSelection,
                List.of(o.mask, List.copyOf(o.maskActive), o.maskExclude, o.maskOff, o.maskExpression, o.minY, o.maxY),
                o.sizeX,
                o.sizeY,
                o.sizeZ,
                sculpts(o.tool) ? rockKey(o) : 0,
                normal,
                at,
                shownBlocks > Selection.LIMIT
                        ? 0
                        : mc.level.getGameTime() / 20); // a huge brush is not worked out again every second
        if (!now.equals(key)) {
            key = now;
            rebuild(mc, at, normal, o);
        }
        if (tooLarge) { // too large to work out: the box it fits in, as a selection's edges are drawn
            var box = new AABB(at).inflate(o.radius);
            OutlineShape.box(
                            new Point(
                                    (int) Math.floor(box.minX), (int) Math.floor(box.minY), (int) Math.floor(box.minZ)),
                            new Point((int) Math.ceil(box.maxX), (int) Math.ceil(box.maxY), (int) Math.ceil(box.maxZ)))
                    .drawPlain(Vec3.ZERO, o.hoverColor, (float) o.selectionWidth, 0, false, true);
            return;
        }
        int fill = o.hoverColor & 0xffffff | SceneShape.FACE_ALPHA;
        shape.draw(
                fill,
                o.hoverColor,
                (float) o.selectionWidth,
                0); // dim faces on the ground, the selection's silhouette in the air
    }

    /** The blocks of {@code cells} that the Surface Painter would paint, as the server picks them: solid blocks, only those touching a non-solid block when Surface only is on, only inside the selection when the tool is restricted to it, and those the masks let through. */
    private static List<Point> paintable(net.minecraft.world.level.Level level, List<Point> cells, Options o) {
        var found = new ArrayList<Point>();
        var mask = new PaintMask(level, o);
        for (Point p : cells) {
            if (!mask.solid(p)) continue;
            if (o.inSelection && !SelectionOverlay.contains(new BlockPos(p.x(), p.y(), p.z()))) continue;
            if (o.surfaceOnly && mask.allSolidAround(p)) continue;
            if (!mask.allows(p)) continue;
            found.add(p);
        }
        return found;
    }

    /** The tools whose blocks are picked by a rule, so the preview draws only what changes. */
    private static boolean sculpts(dev.craftium.core.Tool tool) {
        return switch (tool) {
            case ROCK, WELD, MELT, ROUGHEN, SHATTER, DISTORT, SCULPT_DRAW -> true;
            default -> false;
        };
    }

    /** The blocks Weld, Melt, Roughen, Shatter, Distort or Sculpt Draw would change in the brush, by the rules the server builds with. */
    private static List<Point> sculpted(
            net.minecraft.world.level.Level level, List<Point> cells, BlockPos at, Point normal, Options o) {
        var mask = new PaintMask(level, o);
        var memo = new HashMap<Point, Boolean>();
        var world = new dev.craftium.core.BrushRules.Cells() {
            public boolean solid(Point p) {
                return memo.computeIfAbsent(p, mask::solid);
            }

            public boolean air(Point p) {
                return level.getBlockState(new BlockPos(p.x(), p.y(), p.z())).isAir();
            }
        };
        var noise = new dev.craftium.core.NoiseField(o);
        var centre = new Point(at.getX(), at.getY(), at.getZ());
        var changed = new LinkedHashSet<Point>();
        for (Point p : cells) {
            switch (o.tool) {
                case WELD -> {
                    if (dev.craftium.core.BrushRules.weld(p, o, world)) changed.add(p);
                }
                case MELT -> {
                    if (dev.craftium.core.BrushRules.melt(p, o, world)) changed.add(p);
                }
                case SHATTER -> {
                    if (dev.craftium.core.BrushRules.shatter(p, o, world)) changed.add(p);
                }
                case ROUGHEN -> {
                    var target = dev.craftium.core.BrushRules.roughen(p, o, world, noise.sample(p));
                    if (target != null) changed.add(target);
                }
                case SCULPT_DRAW -> changed.addAll(dev.craftium.core.BrushRules.sculpt(p, centre, normal, o, world));
                case DISTORT -> {
                    var source = dev.craftium.core.BrushRules.distortSource(p, centre, o, noise);
                    if (level.getBlockState(new BlockPos(p.x(), p.y(), p.z()))
                            != level.getBlockState(new BlockPos(source.x(), source.y(), source.z()))) changed.add(p);
                }
                default -> {}
            }
        }
        var kept = new ArrayList<Point>(changed.size());
        for (Point p : changed) {
            if (o.inSelection && !SelectionOverlay.contains(new BlockPos(p.x(), p.y(), p.z()))) continue;
            if (mask.allows(p)) kept.add(p);
        }
        return kept;
    }

    /** Everything the boulder's shape depends on besides the brush, so changing any of it redraws the preview. */
    private static List<Object> rockKey(Options o) {
        return List.of(
                o.seed,
                o.noise,
                o.noiseScale,
                o.scaleX,
                o.scaleY,
                o.scaleZ,
                o.noise3D,
                o.octaves,
                o.lacunarity,
                o.gain,
                o.jitter,
                o.strength,
                o.denoise,
                o.meldStrength,
                o.invert,
                o.threshold,
                o.smoothing,
                o.exposedFaces,
                o.roughenRatio,
                o.shatterWidth,
                o.shatterAxis,
                o.distortDistance,
                o.distortSeparate,
                o.distortX,
                o.distortY,
                o.distortZ,
                o.distortSmoothEdges,
                o.maskY);
    }

    /** The blocks the boulder would take, as the server decides them: the brush's blocks the noise-shaped rock keeps, melded into the solid blocks around it. */
    private static List<Point> rock(net.minecraft.world.level.Level level, List<Point> cells, BlockPos at, Options o) {
        var noise = new dev.craftium.core.NoiseField(o);
        var mask = new PaintMask(level, o);
        var centre = new Point(at.getX(), at.getY(), at.getZ());
        var kept = new ArrayList<Point>();
        for (Point p : cells) {
            if (o.inSelection && !SelectionOverlay.contains(new BlockPos(p.x(), p.y(), p.z()))) continue;
            if (!mask.allows(p)) continue;
            if (!dev.craftium.core.RockShape.keeps(p, centre, o, noise, q -> solidAround(mask, q))) continue;
            if (o.invert && !mask.solid(p)) continue; // an inverted rock only carves what is there
            kept.add(p);
        }
        return kept;
    }

    private static int solidAround(PaintMask mask, Point p) {
        int count = 0;
        if (mask.solid(p.add(1, 0, 0))) count++;
        if (mask.solid(p.add(-1, 0, 0))) count++;
        if (mask.solid(p.add(0, 1, 0))) count++;
        if (mask.solid(p.add(0, -1, 0))) count++;
        if (mask.solid(p.add(0, 0, 1))) count++;
        if (mask.solid(p.add(0, 0, -1))) count++;
        return count;
    }

    private static void rebuild(Minecraft mc, BlockPos at, Point normal, Options o) {
        shape = SceneShape.EMPTY;
        tooLarge = false;
        List<Point> relative;
        // A brush over the block limit is refused by the server too, so there is no set of blocks to show.
        // Each tool is shown the way it builds: the picked shape, a line or path's small shape, or just a disc or a
        // ball of the radius.
        var origin = new Point(0, 0, 0);
        var use = o.tool.brushUse();
        double cells = use == dev.craftium.core.Tool.BrushUse.SMALL_SHAPE
                ? 0
                : dev.craftium.core.ShapeSizes.isDefault(o)
                                || use == dev.craftium.core.Tool.BrushUse.DISC
                                || use == dev.craftium.core.Tool.BrushUse.SPHERE
                        ? dev.craftium.core.ShapeLimits.cells(
                                use == dev.craftium.core.Tool.BrushUse.DISC
                                        ? BrushMath.Shape.DISK
                                        : use == dev.craftium.core.Tool.BrushUse.SPHERE
                                                ? BrushMath.Shape.SPHERE
                                                : o.shape,
                                Math.min(o.outerRadius(), BrushMath.MAX_REACH))
                        : Math.pow(
                                2 * Math.ceil(o.outerRadius() * Math.max(o.sizeX, Math.max(o.sizeY, o.sizeZ))) + 1, 3);
        if (cells > PREVIEW_CELLS) {
            tooLarge = true;
            shownBlocks = MAX_BLOCKS;
            return;
        }
        try {
            relative = switch (o.tool.brushUse()) {
                case SMALL_SHAPE ->
                    BrushMath.brush(
                            origin, Math.min(o.radius, 4), o.shape, o.hollow, o.outlineThickness, o.pixelPerfect);
                case DISC ->
                    BrushMath.brush(
                            origin,
                            o.radius,
                            BrushMath.Shape.DISK,
                            false,
                            1,
                            false,
                            0,
                            BrushMath.DEFAULT_SIDES,
                            false,
                            MAX_BLOCKS);
                case SPHERE ->
                    o.tool == dev.craftium.core.Tool.ROCK
                            ? dev.craftium.core.Geometry.shape(
                                    origin, o) // the brush the rock is cut from, as the server makes it
                            : BrushMath.brush(
                                    origin,
                                    o.radius,
                                    BrushMath.Shape.SPHERE,
                                    false,
                                    1,
                                    false,
                                    0,
                                    BrushMath.DEFAULT_SIDES,
                                    false,
                                    MAX_BLOCKS);
                default ->
                    dev.craftium.core.ShapeSizes.isDefault(o)
                            ? BrushMath.brush(
                                    origin,
                                    o.radius,
                                    o.shape,
                                    o.hollow && o.tool != dev.craftium.core.Tool.ROCK,
                                    o.outlineThickness,
                                    o.pixelPerfect,
                                    o.outlineShift(),
                                    o.polygonSides,
                                    o.outline && o.tool != dev.craftium.core.Tool.ROCK,
                                    MAX_BLOCKS,
                                    o.form())
                            : dev.craftium.core.Geometry.shape(
                                    origin, o); // a shape with its own sizes is made the way the server makes it
            };
        } catch (IllegalArgumentException e) {
            tooLarge = true;
            shownBlocks = MAX_BLOCKS;
            return;
        }
        shownBlocks = relative.size();
        List<Point> absolute = new ArrayList<>(relative.size());
        for (Point r : relative) absolute.add(new Point(at.getX() + r.x(), at.getY() + r.y(), at.getZ() + r.z()));
        if (o.tool == dev.craftium.core.Tool.PAINTER || o.tool == dev.craftium.core.Tool.NOISE_PAINTER)
            absolute = paintable(mc.level, absolute, o); // only what the Surface (Noise) Painter would paint
        if (o.tool == dev.craftium.core.Tool.ROCK) absolute = rock(mc.level, absolute, at, o);
        else if (sculpts(o.tool)) absolute = sculpted(mc.level, absolute, at, normal, o);
        shape = SceneShape.of(mc.level, absolute, true); // outlined all round like a selection, solid blocks too
        tooLarge = shape.tooLarge();
    }
}
