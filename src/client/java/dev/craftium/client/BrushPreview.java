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
    /** The blocks the brush would take away, drawn apart from the rest in a lighter red. */
    private static SceneShape removedShape = SceneShape.EMPTY;
    /** The alpha of the removed blocks' faces: a quarter, lighter than the destination's. */
    private static final int REMOVED_ALPHA = 0x40000000;

    private static final int REMOVED_COLOR = 0xff3030;
    private static boolean tooLarge;

    private BrushPreview() {}

    public static void draw(Minecraft mc, BlockPos at, Options o) {
        draw(mc, at, net.minecraft.core.Direction.UP, o);
    }

    /** {@code face} is the side of the block under the cursor that is aimed at, which the sculpting tools build out from. */
    public static void draw(Minecraft mc, BlockPos at, net.minecraft.core.Direction face, Options o) {
        if (mc.level == null) return;
        // Only the sculpting tools, which build out from the aimed face or keep to its side, need it. For the rest it
        // must not be in the key: with the camera inside a block the hit face follows the look direction, and every
        // turn would otherwise work the whole preview out again.
        var normal = dev.craftium.core.BrushRules.keepsToFront(o.tool)
                ? new Point(face.getStepX(), face.getStepY(), face.getStepZ())
                : new Point(0, 1, 0);
        lineEnd = lineView(o) == null ? null : GradientStroke.previewEnd(o);
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
                lineView(o) != null
                        ? List.of(ScreenFootprint.cameraKey(lineView(o)), o.gradientAnchors.getFirst(), lineEnd)
                        : screenView(o) == null ? at : screenView(o).key(),
                // A huge brush is not worked out again every second, nor is a Gradient Painter line being dragged:
                // its rays are kept for the drag, so the world is looked at when the next line begins.
                shownBlocks > Selection.LIMIT || lineEnd != null ? 0 : mc.level.getGameTime() / 20);
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
        shape.drawLean(
                fill,
                o.hoverColor,
                (float) o.selectionWidth,
                0); // dim faces on the ground, the selection's silhouette in the air
        removedShape.draw(REMOVED_COLOR | REMOVED_ALPHA, REMOVED_COLOR | 0xff000000, (float) o.selectionWidth, 0);
    }

    /** The ring on the screen when the Surface (Noise) Painter is in use, whose blocks are those the ring shows rather than a shape around the block under it. */
    private static ScreenFootprint.View screenView(Options o) {
        return ScreenFootprint.active(o);
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
            case ROCK, WELD, MELT, ROUGHEN, SHATTER, DISTORT, SCULPT_DRAW, SMOOTH -> true;
            default -> false;
        };
    }

    /** The blocks Weld, Melt, Roughen, Shatter, Distort or Sculpt Draw would change in the brush, by the rules the server builds with. */
    private static final Point[] SIDES = {
        new Point(1, 0, 0),
        new Point(-1, 0, 0),
        new Point(0, 1, 0),
        new Point(0, -1, 0),
        new Point(0, 0, 1),
        new Point(0, 0, -1)
    };

    private record Changes(List<Point> added, List<Point> removed) {}

    private static Changes sculpted(
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
        var gone = new LinkedHashSet<Point>();
        var inside = new HashSet<>(cells);
        var front = dev.craftium.core.BrushRules.keepsToFront(o.tool)
                ? dev.craftium.core.BrushRules.front(
                        inside, centre, normal, world, dev.craftium.core.BrushRules.reach(o))
                : null;
        var measured = front == null ? world : front.filled(world);
        if (o.tool == dev.craftium.core.Tool.SMOOTH) smoothed(level, cells, front, o, mask, world, changed, gone);
        else
            for (Point p : cells) {
                if (front != null && !front.contains(p)) continue;
                switch (o.tool) {
                    case WELD -> {
                        if (dev.craftium.core.BrushRules.weld(p, o, measured)) changed.add(p);
                    }
                    case MELT -> {
                        if (dev.craftium.core.BrushRules.melt(p, o, measured)) gone.add(p);
                    }
                    case SHATTER -> {
                        if (dev.craftium.core.BrushRules.shatter(p, o, world))
                            (o.fractureMaterial ? changed : gone).add(p);
                    }
                    case ROUGHEN -> {
                        var target = dev.craftium.core.BrushRules.roughen(p, o, world, noise.sample(p));
                        if (target != null && (front == null || front.contains(target) || !inside.contains(target)))
                            (target.equals(p) ? gone : changed).add(target);
                    }
                    case SCULPT_DRAW ->
                        (o.invert ? gone : changed)
                                .addAll(dev.craftium.core.BrushRules.sculpt(p, centre, normal, o, world));
                    case DISTORT -> {
                        var source = dev.craftium.core.BrushRules.distortSource(p, centre, o, noise);
                        if (level.getBlockState(new BlockPos(p.x(), p.y(), p.z()))
                                != level.getBlockState(new BlockPos(source.x(), source.y(), source.z())))
                            changed.add(p);
                    }
                    default -> {}
                }
            }
        return new Changes(keep(changed, o, mask), keep(gone, o, mask));
    }

    private static List<Point> keep(Collection<Point> points, Options o, PaintMask mask) {
        var kept = new ArrayList<Point>(points.size());
        for (Point p : points) {
            if (o.inSelection && !SelectionOverlay.contains(new BlockPos(p.x(), p.y(), p.z()))) continue;
            if (mask.allows(p)) kept.add(p);
        }
        return kept;
    }

    /** Smooth as the server runs it: the brush's blocks are ranked by how much solid surrounds them, and as many as the solid ones times the ratio stay (or become) solid. */
    private static void smoothed(
            net.minecraft.world.level.Level level,
            List<Point> cells,
            dev.craftium.core.BrushRules.Front front,
            Options o,
            PaintMask mask,
            dev.craftium.core.BrushRules.Cells world,
            Set<Point> added,
            Set<Point> removed) {
        var inside = new HashSet<>(cells);
        var candidates = new ArrayList<Point>();
        int filled = 0;
        for (Point at : cells) {
            if (front != null && !front.contains(at)) continue;
            if (!mask.allows(at)
                    || level.getBlockState(new BlockPos(at.x(), at.y(), at.z())).hasBlockEntity()) continue;
            if (o.inSelection && !SelectionOverlay.contains(new BlockPos(at.x(), at.y(), at.z()))) continue;
            if (o.smoothFixEdges) {
                boolean edge = false;
                for (Point n : SIDES)
                    if (!inside.contains(at.add(n.x(), n.y(), n.z()))) {
                        edge = true;
                        break;
                    }
                if (edge) continue;
            }
            candidates.add(at);
            if (world.solid(at)) filled++;
        }
        double solidBias = o.smoothMode == Options.SmoothMode.MELT ? -.05 : .01,
                airBias = o.smoothMode == Options.SmoothMode.GROW ? .05 : 0;
        var measured = front == null ? world : front.filled(world);
        var density = new HashMap<Point, Double>();
        for (Point at : candidates)
            density.put(
                    at,
                    dev.craftium.core.BrushRules.density(at, o, measured) + (world.solid(at) ? solidBias : airBias));
        candidates.sort(Comparator.<Point>comparingDouble(density::get).reversed());
        int count = (int) Math.clamp(Math.round(filled * o.smoothRatio / 100d), 0, candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            Point at = candidates.get(i);
            if (i < count) {
                if (!world.solid(at)) added.add(at);
            } else if (world.solid(at)) removed.add(at);
        }
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

    /** The end of the Gradient Painter's line the preview shows while it is dragged (see {@link GradientStroke#previewEnd}), or null. */
    private static Point lineEnd;

    /** The ring while a Gradient Painter line is being dragged in screen space, or null. */
    private static ScreenFootprint.View lineView(Options o) {
        return o.tool == dev.craftium.core.Tool.GRADIENT_PAINTER && o.gradientAnchors.size() == 2
                ? screenView(o)
                : null;
    }

    /** Where the shape was last built, and what it was, so a rebuild in the same place can keep its contour. */
    private static BlockPos builtAt;

    private static void rebuild(Minecraft mc, BlockPos at, Point normal, Options o) {
        var previous = shape;
        shape = SceneShape.EMPTY;
        removedShape = SceneShape.EMPTY;
        tooLarge = false;
        var view = screenView(o);
        if (view != null) { // the blocks the ring covers on the screen, whatever shape is picked
            // While the Gradient Painter's line is dragged: the ring swept along it, as letting go paints it, and the
            // ring at the pointer is not worked out at all. A line that does not fit on the screen shows the ring.
            List<Point> seen =
                    lineEnd == null ? null : GradientStroke.covered(o, o.gradientAnchors.getFirst(), lineEnd);
            if (seen == null) seen = ScreenFootprint.covered(mc.level, o, at, view);
            shownBlocks = seen.size();
            removedShape = SceneShape.EMPTY;
            shape = SceneShape.of(mc.level, paintable(mc.level, seen, o), true);
            builtAt = null; // the footprint moves with the pointer: no old contour to keep
            tooLarge = shape.tooLarge();
            return;
        }
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
        if (o.tool == dev.craftium.core.Tool.PAINTER)
            absolute = paintable(mc.level, absolute, o); // only what the Surface Painter would paint
        List<Point> removed = List.of();
        if (o.tool == dev.craftium.core.Tool.ROCK) {
            absolute = rock(mc.level, absolute, at, o);
            if (o.invert) { // an inverted rock only carves
                removed = absolute;
                absolute = List.of();
            }
        } else if (sculpts(o.tool)) {
            var changes = sculpted(mc.level, absolute, at, normal, o);
            absolute = changes.added();
            removed = changes.removed();
        }
        removedShape = SceneShape.of(mc.level, removed); // faces only, no outline
        var rebuilt = SceneShape.of(mc.level, absolute, true); // outlined all round like a selection, solid blocks too
        // Worked out again in the same place (the world changed under a held brush): keep the old contour until the new
        // one is ready.
        shape = at.equals(builtAt) ? rebuilt.carryOver(previous) : rebuilt;
        builtAt = at;
        tooLarge = shape.tooLarge();
    }
}
