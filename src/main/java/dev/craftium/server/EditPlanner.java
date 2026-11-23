package dev.craftium.server;

import com.mojang.brigadier.StringReader;
import dev.craftium.core.*;
import java.util.*;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

final class EditPlanner {
    final ServerLevel level;
    final Session session;
    final Options o;
    final LinkedHashMap<Point, Session.Change> changes = new LinkedHashMap<>();
    final LinkedHashMap<Point, Session.BiomeChange> biomes = new LinkedHashMap<>();
    final BlockState material, secondary, mask;
    final NoiseField noise;
    /** The world as the shared brush rules read it. */
    final BrushRules.Cells cells = new BrushRules.Cells() {
        public boolean solid(Point p) {
            return EditPlanner.this.solid(p);
        }

        public boolean air(Point p) {
            return state(p).isAir();
        }

        public int adjacent(Point p) {
            return EditPlanner.this.adjacent(p);
        }
    };

    final java.util.function.Predicate<Point> expression;
    final LuaBrush maskLua;
    final List<BlockState> materials;
    final List<BlockState> blockMask = new ArrayList<>();
    final List<Boolean> blockMaskExact = new ArrayList<>();
    /** Mask entries naming a block no mod provides, like {@code othermod:marble}; they pick the placeholders for that block. */
    final List<MissingBlockTable.Entry> missingMask = new ArrayList<>();
    /** The mask block when it names a missing block. */
    final MissingBlockTable.Entry maskMissing;

    final HeightmapImage heightmap;
    static final Point[] NEIGHBOURS = {
        new Point(1, 0, 0),
        new Point(-1, 0, 0),
        new Point(0, 1, 0),
        new Point(0, -1, 0),
        new Point(0, 0, 1),
        new Point(0, 0, -1)
    };

    /** Whether the player's block limit applies; off for actions on a selection or the clipboard, which only answer to {@link Selection#LIMIT}. */
    boolean limited = true;
    /** A planner for an action on the selection or the clipboard (paste, cut, fill, stack): the block limit is for the tools, so only the largest edit and the history budget bound it. */
    static EditPlanner forSelection(ServerLevel level, Session session, Options options) {
        var plan = new EditPlanner(level, session, options);
        plan.limited = false;
        return plan;
    }

    EditPlanner(ServerLevel level, Session session, Options options) {
        this.level = level;
        this.session = session;
        this.o = options;
        material = options.transparency ? Blocks.AIR.defaultBlockState() : parse(options.block);
        secondary = parse(options.secondary);
        maskMissing = isMissingName(options.mask) ? MissingBlockTable.Entry.parse(options.mask) : null;
        mask = options.mask.isBlank() || maskMissing != null ? null : parse(options.mask);
        if (!options.maskOff)
            for (String entry : options.maskActive) {
                if (isMissingName(entry)) {
                    missingMask.add(MissingBlockTable.Entry.parse(entry));
                    continue;
                }
                blockMask.add(parse(entry));
                blockMaskExact.add(entry.contains("["));
            }
        noise = new NoiseField(options);
        materials = new ArrayList<>();
        for (String block : MaterialList.split(options.materials)) materials.add(parse(block));
        if (materials.isEmpty()) materials.add(material);
        expression = MaskExpression.compile(options.maskExpression, new MaskExpression.Context() {
            private final Map<String, BlockState> parsed = new HashMap<>();
            private int queries;

            public boolean block(Point p, String s) {
                if (++queries > 2_000_000)
                    throw new IllegalArgumentException(
                            "Mask exceeds its query budget; reduce the region or near radius");
                var expected = parsed.computeIfAbsent(s, EditPlanner.this::parse);
                return s.contains("[") ? state(p) == expected : state(p).is(expected.getBlock());
            }

            public boolean selection(Point p) {
                return session.selection.contains(p);
            }

            public boolean sky(Point p) {
                return level.canSeeSky(pos(p));
            }

            public boolean surface(Point p) {
                return EditPlanner.this.surface(p);
            }

            public double angle(Point p) {
                double x = (solid(p.add(-1, 0, 0)) ? 1 : 0) - (solid(p.add(1, 0, 0)) ? 1 : 0),
                        y = (solid(p.add(0, -1, 0)) ? 1 : 0) - (solid(p.add(0, 1, 0)) ? 1 : 0),
                        z = (solid(p.add(0, 0, -1)) ? 1 : 0) - (solid(p.add(0, 0, 1)) ? 1 : 0);
                double len = Math.sqrt(x * x + y * y + z * z);
                return len == 0 ? 0 : Math.toDegrees(Math.acos(y / len));
            }
        });
        maskLua = options.maskScript.isBlank() ? null : new LuaBrush(this, options.maskScript, true);
        heightmap = options.tool != Tool.ELEVATION || options.heightmap.isBlank()
                ? null
                : HeightmapImage.load(options.heightmap);
    }
    /** Whether the text names a block that no installed mod provides, which only a placeholder can hold. */
    static boolean isMissingName(String text) {
        if (text == null || text.isBlank()) return false;
        int open = text.indexOf('[');
        return !MissingBlocks.exists(
                open < 0 ? text.trim() : text.substring(0, open).trim());
    }

    static BlockPos pos(Point p) {
        return new BlockPos(p.x(), p.y(), p.z());
    }

    static Point point(BlockPos p) {
        return new Point(p.getX(), p.getY(), p.getZ());
    }

    BlockState parse(String input) {
        try {
            return BlockStateParser.parseForBlock(
                            level.registryAccess().lookupOrThrow(Registries.BLOCK), new StringReader(input), false)
                    .blockState();
        } catch (Exception e) {
            throw new IllegalArgumentException("Unknown block or state: " + input);
        }
    }

    void validate(Point p) {
        BlockPos at = pos(p);
        if (!level.isInWorldBounds(at) || !level.getWorldBorder().isWithinBounds(at))
            throw new IllegalArgumentException("Edit crosses the world boundary");
        if (!level.hasChunk(p.x() >> 4, p.z() >> 4))
            throw new IllegalArgumentException("Edit reaches unloaded chunks; move closer first");
    }

    BlockState state(Point p) {
        if (staged != null) {
            var after = staged.get(p);
            if (after != null) return after.state();
        }
        if (!level.isInWorldBounds(pos(p)) || !level.hasChunk(p.x() >> 4, p.z() >> 4))
            return Blocks.AIR.defaultBlockState();
        return level.getBlockState(pos(p));
    }

    /** What the planner reads at {@code p}, with its contents: the staged block when it reads through a stroke, air outside the world. */
    BlockSnapshot snapshot(Point p) {
        if (staged != null) {
            var after = staged.get(p);
            if (after != null) return after;
        }
        if (!level.isInWorldBounds(pos(p)) || !level.hasChunk(p.x() >> 4, p.z() >> 4))
            return new BlockSnapshot(Blocks.AIR.defaultBlockState());
        return BlockSnapshot.capture(level, pos(p));
    }

    /** The edit a stroke has staged so far, read as if it were built; null to read the world. */
    private Map<Point, BlockSnapshot> staged;

    /**
     * Reads the world as if {@code changes}, the edit a stroke has staged, were built, and carries them in this plan, so a tool that works
     * on what it changed itself (Deform, moving a wall on from where its last dab left it) sees its own stroke. The finished plan then holds
     * the whole stroke and takes the staged edit's place; a block changed back to what the world holds drops out of it.
     */
    void readThrough(List<Session.Change> changes) {
        staged = new HashMap<>();
        for (var c : changes) {
            staged.put(c.pos(), c.after());
            this.changes.put(c.pos(), c);
        }
    }
    /** Set for the builder tools, which ignore the tool masks unless {@link Options#maskInBuilder} is on. */
    boolean builder;

    boolean accepted(Point p) {
        if (builder && !o.maskInBuilder) return true;
        if (p.y() < o.minY || p.y() > o.maxY) return false;
        if (o.inSelection && !session.selection.contains(p)) return false;
        if ((!blockMask.isEmpty() || !missingMask.isEmpty()) && inBlockMask(p) == o.maskExclude) return false;
        if (maskMissing != null && !sameMissing(maskMissing, p)) return false;
        return (mask == null || (o.mask.contains("[") ? state(p) == mask : state(p).is(mask.getBlock())))
                && expression.test(p)
                && (maskLua == null || maskLua.evaluate(p).toboolean());
    }
    /** Whether the block at {@code p} is one of the toggled mask blocks: an exact state where the entry names one, else any state of the block. */
    boolean inBlockMask(Point p) {
        var s = state(p);
        for (int i = 0; i < blockMask.size(); i++)
            if (blockMaskExact.get(i)
                    ? s == blockMask.get(i)
                    : s.is(blockMask.get(i).getBlock())) return true;
        for (var wanted : missingMask) if (sameMissing(wanted, p)) return true;
        return false;
    }
    /** Whether the block at {@code p} is a placeholder for the block {@code wanted} names; properties it lists must all match. */
    private boolean sameMissing(MissingBlockTable.Entry wanted, Point p) {
        var found = AdvancedTools.missingEntry(this, p);
        if (found == null || !found.name().equals(wanted.name())) return false;
        for (var property : wanted.properties().entrySet())
            if (!property.getValue().equals(found.properties().get(property.getKey()))) return false;
        return true;
    }

    boolean solid(Point p) {
        return !state(p).isAir() && state(p).getFluidState().isEmpty();
    }

    int adjacent(Point p) {
        int count = 0;
        for (Point n : NEIGHBOURS) if (solid(p.add(n.x(), n.y(), n.z()))) count++;
        return count;
    }

    boolean surface(Point p) {
        return adjacent(p) < 6;
    }

    void put(Point p, BlockState s) {
        put(p, new BlockSnapshot(s));
    }

    void putClipboard(Point p, BlockSnapshot value) {
        var pending = changes.get(p);
        var current = pending == null ? state(p) : pending.after().state();
        var desired = value.state();
        if (o.mergeBlocks && current.is(desired.getBlock()) && value.data() == null && !current.isAir()) {
            var union = net.minecraft.world.phys.shapes.Shapes.or(
                    current.getShape(level, pos(p)), desired.getShape(level, pos(p)));
            for (var candidate : desired.getBlock().getStateDefinition().getPossibleStates())
                if (!net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(
                        union, candidate.getShape(level, pos(p)), net.minecraft.world.phys.shapes.BooleanOp.NOT_SAME)) {
                    value = new BlockSnapshot(candidate);
                    break;
                }
        }
        put(p, value);
    }
    /**
     * Set by the transform tools: the blocks of a selection go exactly where the transform sends them, whatever the mask,
     * height range, keep-existing, survival and symmetry settings of the brush tools say. Otherwise those settings would
     * quietly leave parts of some selections behind.
     */
    boolean exact;

    void put(Point p, BlockSnapshot value) {
        // A missing block is a bug in whatever planned it; refused here, it fails that one request instead of every
        // tick that draws the edit.
        if (value == null || value.state() == null)
            throw new IllegalArgumentException("Could not work out the block at " + p.x() + " " + p.y() + " " + p.z()
                    + "; nothing was changed. Please report this with the request that caused it");
        putOne(p, value);
        if (exact) return;
        if (!o.symmetryX && !o.symmetryY && !o.symmetryZ && o.symmetryRotations == 1) return;
        var origin = o.symmetryOrigin;
        for (int bits = 0; bits < 8; bits++) {
            if (((bits & 1) != 0 && !o.symmetryX)
                    || ((bits & 2) != 0 && !o.symmetryY)
                    || ((bits & 4) != 0 && !o.symmetryZ)) continue;
            Point mirror = new Point(
                    (bits & 1) != 0 ? 2 * origin.x() + (o.symmetryHalfX ? 1 : 0) - p.x() : p.x(),
                    (bits & 2) != 0 ? 2 * origin.y() + (o.symmetryHalfY ? 1 : 0) - p.y() : p.y(),
                    (bits & 4) != 0 ? 2 * origin.z() + (o.symmetryHalfZ ? 1 : 0) - p.z() : p.z());
            var s = value.state();
            if ((bits & 1) != 0) s = StateTransforms.mirror(s, net.minecraft.world.level.block.Mirror.FRONT_BACK);
            if ((bits & 2) != 0) s = BuilderEdits.verticalMirror(s);
            if ((bits & 4) != 0) s = StateTransforms.mirror(s, net.minecraft.world.level.block.Mirror.LEFT_RIGHT);
            for (int rotation = 0; rotation < o.symmetryRotations; rotation++) {
                if (bits == 0 && rotation == 0) continue;
                double angle = 360d * rotation / o.symmetryRotations,
                        cx = origin.x() + (o.symmetryHalfX ? .5 : 0),
                        cz = origin.z() + (o.symmetryHalfZ ? .5 : 0);
                var delta = Geometry.rotate(new Geometry.Vector(mirror.x() - cx, 0, mirror.z() - cz), angle, 0, 0);
                Point at = new Point((int) Math.round(cx + delta.x()), mirror.y(), (int) Math.round(cz + delta.z()));
                putOne(
                        at,
                        new BlockSnapshot(
                                s.rotate(net.minecraft.world.level.block.Rotation.values()[(int) angle / 90]),
                                value.data()));
            }
        }
    }
    /** How many placements each check turned away, so an edit that changed nothing can say why. */
    final EnumMap<Skip, Integer> skipped = new EnumMap<>(Skip.class);

    enum Skip {
        HEIGHT("the blocks were outside the height range (Min Y / Max Y in Masks)"),
        SELECTION("the blocks were outside the selection (Restrict to selection is on)"),
        MASK("the mask turned the blocks away (block mask, mask block, rules or script)"),
        KEEP("Keep existing is on and those spots were already filled"),
        SURVIVE("the blocks could not stand there (turn on Force place in the Builder tab)"),
        SAME("those spots already hold the block"),
        OBJECT("the blocks belong to a BuildObject, which the Terrain tools leave alone");
        final String why;

        Skip(String why) {
            this.why = why;
        }
    }

    private void skip(Skip reason) {
        skipped.merge(reason, 1, Integer::sum);
    }
    /** Why nothing changed: the check that turned away the most placements, or null when none did. */
    String skipReason() {
        return skipped.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> e.getKey().why)
                .orElse(null);
    }

    private Skip rejection(Point p) {
        if (p.y() < o.minY || p.y() > o.maxY) return Skip.HEIGHT;
        if (o.inSelection && !session.selection.contains(p)) return Skip.SELECTION;
        return Skip.MASK;
    }

    private java.util.Set<Point> objectBlocks;
    /** Whether the block at {@code p} belongs to one of the player's BuildObjects; read once per edit. */
    private boolean inBuildObject(Point p) {
        if (objectBlocks == null) {
            objectBlocks = new java.util.HashSet<>();
            for (var e : session.saved.entries()) objectBlocks.addAll(e.points());
        }
        return objectBlocks.contains(p);
    }

    private void putOne(Point p, BlockSnapshot value) {
        validate(p);
        var planned = changes.get(p);
        if (!exact && o.tool.group.equals("Terrain") && inBuildObject(p)) {
            skip(Skip.OBJECT);
            return;
        }
        if (!exact && !accepted(p)) {
            skip(rejection(p));
            return;
        }
        if (!exact
                && o.keepExisting
                && !o.replaceMode
                && !value.state().isAir()
                && !state(p).isAir()
                && (planned == null || !planned.after().state().isAir())) {
            skip(Skip.KEEP);
            return;
        }
        if (!exact && !o.forcePlace && !survives(value.state(), p)) {
            skip(Skip.SURVIVE);
            return;
        }
        if (value.data() != null) {
            var tag = value.data().copy();
            tag.putInt("x", p.x());
            tag.putInt("y", p.y());
            tag.putInt("z", p.z());
            value = new BlockSnapshot(value.state(), tag);
        }
        var previous = changes.get(p);
        var before = previous == null ? BlockSnapshot.capture(level, pos(p)) : previous.before();
        if (value.data() == null
                && before.data() != null
                && before.state().is(value.state().getBlock())) value = new BlockSnapshot(value.state(), before.data());
        if (before.state() == value.state() && Objects.equals(before.data(), value.data())) {
            changes.remove(p);
            skip(Skip.SAME);
            return;
        }
        changes.put(p, new Session.Change(p, before, value));
        int limit = limited ? Math.clamp(o.blockLimit, 1, Selection.LIMIT) : Selection.LIMIT;
        if (changes.size() + biomes.size() > limit)
            throw new IllegalArgumentException(
                    limit < Selection.LIMIT
                            ? "Operation exceeds your block limit of " + String.format("%,d", limit)
                                    + " changes; raise the Block limit in the tool's options"
                            : "Operation exceeds " + String.format("%,d", Selection.LIMIT) + " changes");
    }
    /**
     * Whether a block may stand at {@code p}. The upper half of a door or tall plant is judged by its lower half standing one
     * below, since the real world has no lower half there yet while a whole door is being moved, cloned or pasted; judging the
     * upper half against the world would silently drop it and leave half a door.
     */
    private boolean survives(BlockState state, Point p) {
        var half = net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF;
        if (state.hasProperty(half)
                && state.getValue(half) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER)
            return state.setValue(half, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER)
                    .canSurvive(level, pos(p).below());
        return state.canSurvive(level, pos(p));
    }

    BlockState preserve(BlockState from, BlockState to) {
        for (var property : from.getProperties()) if (to.hasProperty(property)) to = copyProperty(from, to, property);
        return to;
    }

    private static <T extends Comparable<T>> BlockState copyProperty(
            BlockState from, BlockState to, net.minecraft.world.level.block.state.properties.Property<T> property) {
        return to.setValue(property, from.getValue(property));
    }

    List<Point> brush(Point center) {
        return Geometry.shape(center, o);
    }

    /**
     * The blocks of a use: for the tools that can ({@link Tool#screenBrush}) in screen-space mode, the blocks the client says the ring covers on its screen, kept to those
     * within reach of the block clicked so a request cannot name blocks anywhere in the world; otherwise the brush around {@code c}. What is
     * painted of them is still decided by the usual rules (solid, Surface only, masks, the selection and the block limit).
     */
    List<Point> screenBlocks(Point c) {
        if (!o.tool.screenBrush() || !o.screenSpace || o.screenBlocks.isEmpty()) return brush(c);
        int reach = Options.screenReach(o.radius);
        var named = BlockList.decode(o.screenBlocks, Options.MAX_SCREEN_BLOCKS);
        var kept = new ArrayList<Point>(named.size());
        for (Point p : named)
            if (Math.abs(p.x() - c.x()) <= reach
                    && Math.abs(p.y() - c.y()) <= reach
                    && Math.abs(p.z() - c.z()) <= reach) kept.add(p);
        return kept;
    }

    BlockState palette(double value, Point at) {
        return materials.get(MaterialList.index(
                value, o.materialWeights, materials.size(), BrushMath.hash(at.x(), at.y(), at.z(), o.seed), 1));
    }

    /**
     * The Gradient Painter's block at {@code value} along the gradient: the Materials list in order, in hard bands at Blend 0 and mixed over
     * a wider band between neighbours as Blend grows, in an even weave or, with Noise on, at random.
     */
    BlockState gradient(double value, Point at) {
        double threshold = o.gradientNoise
                ? BrushMath.hash(at.x(), at.y(), at.z(), o.seed)
                : MaterialList.ordered(at.x(), at.y(), at.z());
        return materials.get(
                MaterialList.index(value, o.materialWeights, materials.size(), threshold, o.gradientBlend));
    }

    /** The block the Material list gives at a point: one entry paints it, two split at the Blend slider, three or more use the weighted palette. */
    BlockState materialAt(double value, Point at) {
        return materials.size() > 2
                ? palette(value, at)
                : materials.size() == 2 ? (value < o.density ? materials.get(0) : materials.get(1)) : materials.get(0);
    }

    void tool(Point c, Point normal) {
        if (AdvancedTools.handle(this, c, normal)) return;
        if (o.tool == Tool.TEXT) {
            for (Point p : BlockFont.pixels(o.text)) {
                Point at = Math.abs(normal.x()) == 1
                        ? c.add(0, p.y(), p.x() * -normal.x())
                        : c.add(p.x() * (normal.z() > 0 ? -1 : 1), p.y(), 0);
                put(at, material);
            }
            return;
        }
        if (o.tool == Tool.ELEVATION || o.tool == Tool.FLATTEN || o.tool == Tool.SLOPE) {
            heightmap(c, normal);
            return;
        }
        // Text, the height tools and Tinker have no use for a brush shape, so none is built, or refused for being too
        // big, for them.
        var points = o.tool == Tool.TINKER ? List.of(c) : screenBlocks(c);
        for (Point p : points) validate(p);
        if (o.tool == Tool.DEFORM) {
            deform(c, normal);
            return;
        }
        if (o.tool == Tool.THICKEN) {
            thicken(c, normal);
            return;
        }
        if (o.tool == Tool.BIOME_PAINTER || o.tool == Tool.CLENTAMINATOR) paintBiomes(points);
        var inside = BrushRules.keepsToFront(o.tool) ? new HashSet<>(points) : null;
        var front = inside != null ? BrushRules.front(inside, c, normal, cells, BrushRules.reach(o)) : null;
        var measured = front == null ? cells : front.filled(cells);
        // One way in or out for the whole dab, so the brush's blocks move together instead of each its own way.
        Geometry.Vector sculptDirection = null;
        if (o.tool == Tool.SCULPT_DRAW || o.tool == Tool.EXTRUDE)
            sculptDirection = o.tool == Tool.SCULPT_DRAW
                    ? BrushRules.sculptDirection(
                            front == null
                                    ? points
                                    : points.stream().filter(front::contains).toList(),
                            normal,
                            o,
                            measured)
                    : Geometry.vector(normal);
        for (Point p : points) {
            if (front != null && !front.contains(p)) continue;
            BlockState old = state(p);
            boolean filled = solid(p);
            double n = noise.sample(p);
            switch (o.tool) {
                case PAINTER -> {
                    if (filled && (!o.surfaceOnly || surface(p))) {
                        BlockState target = o.paintSource == Options.PaintSource.MATERIAL ? materialAt(n, p) : material;
                        put(
                                p,
                                o.typeReplace
                                        ? AdvancedTools.typeReplace(this, old, target)
                                        : o.copyProperties ? preserve(old, target) : target);
                    }
                }
                case FREEHAND_DRAW, SHAPE -> put(p, o.invert ? Blocks.AIR.defaultBlockState() : material);
                case ERASE -> put(p, Blocks.AIR.defaultBlockState());
                case ROCK -> {
                    if (RockShape.keeps(p, c, o, noise, this::adjacent))
                        put(p, o.invert ? Blocks.AIR.defaultBlockState() : material);
                }
                case SCULPT_DRAW, EXTRUDE -> {
                    for (Point at : BrushRules.sculpt(p, c, sculptDirection, o, cells))
                        put(at, o.invert ? Blocks.AIR.defaultBlockState() : old);
                }
                case WELD -> {
                    if (BrushRules.weld(p, o, measured)) put(p, nearestMaterial(p));
                }
                case MELT -> {
                    if (BrushRules.melt(p, o, measured)) put(p, Blocks.AIR.defaultBlockState());
                }
                case SMOOTH -> {
                    double d = density(p);
                    if (filled && d < .38) put(p, Blocks.AIR.defaultBlockState());
                    if (!filled && d > .58) put(p, nearestMaterial(p));
                }
                case DISTORT -> {
                    Point source = BrushRules.distortSource(p, c, o, noise);
                    validate(source);
                    put(p, BlockSnapshot.capture(level, pos(source)));
                }
                case ROUGHEN -> {
                    Point target = BrushRules.roughen(p, o, cells, n);
                    // A block grown into the far side of a thin wall is left out, as the brush's other blocks there
                    // are.
                    if (target != null && (front == null || front.contains(target) || !inside.contains(target)))
                        put(target, target.equals(p) ? Blocks.AIR.defaultBlockState() : old);
                }
                case SHATTER -> {
                    if (BrushRules.shatter(p, o, cells))
                        put(p, o.fractureMaterial ? material : Blocks.AIR.defaultBlockState());
                }
                case CLENTAMINATOR -> {
                    if (old.is(Blocks.GRASS_BLOCK)
                            || old.is(Blocks.MYCELIUM)
                            || old.is(Blocks.PODZOL)
                            || old.is(Blocks.SAND)
                            || old.is(Blocks.RED_SAND)
                            || old.is(Blocks.SNOW_BLOCK)) put(p, material);
                }
                case TINKER -> {
                    if (p.equals(c) && !old.getProperties().isEmpty()) {
                        var props = new ArrayList<>(old.getProperties());
                        var property = o.property.isBlank()
                                ? props.get(o.invert ? props.size() - 1 : 0)
                                : old.getBlock().getStateDefinition().getProperty(o.property);
                        if (property == null)
                            throw new IllegalArgumentException("Block has no property: " + o.property);
                        put(p, old.cycle(property));
                    }
                }
                default -> {}
            }
        }
    }

    /**
     * One dab of Deform: the wall under a ball of the radius moves, each block taking the block {@link Deform#plan} brings to it. Every new
     * block is read before any is written, so the dab works from the world as it was.
     */
    private void deform(Point c, Point normal) {
        var ball = BrushMath.brush(c, o.radius, BrushMath.Shape.SPHERE, false);
        for (Point p : ball) validate(p);
        var taken = new LinkedHashMap<Point, BlockSnapshot>();
        for (var e : Deform.plan(ball, c, normal, o, cells, BrushRules.reach(o)).entrySet()) {
            validate(e.getKey());
            taken.put(e.getKey(), snapshot(e.getValue()));
        }
        for (var e : taken.entrySet()) put(e.getKey(), e.getValue());
    }

    /**
     * One dab of Thicken: the surface under a ball of the radius grows out (or, inverted, is thinned) by {@link Thicken#plan}, each new
     * block a copy of the surface block nearest it. Every copied block is read before any is written, so the dab works from the world as
     * it was.
     */
    private void thicken(Point c, Point normal) {
        var ball = BrushMath.brush(c, o.radius, BrushMath.Shape.SPHERE, false);
        for (Point p : ball) validate(p);
        var plan = Thicken.plan(ball, c, normal, o, cells);
        var copied = new LinkedHashMap<Point, BlockState>();
        for (var e : plan.added().entrySet()) {
            validate(e.getKey());
            copied.put(e.getKey(), state(e.getValue()));
        }
        for (var e : copied.entrySet()) put(e.getKey(), e.getValue());
        for (Point p : plan.removed()) put(p, Blocks.AIR.defaultBlockState());
    }

    double density(Point p) {
        return BrushRules.density(p, o, cells);
    }

    BlockState nearestMaterial(Point p) {
        for (Point n : NEIGHBOURS) {
            BlockState s = state(p.add(n.x(), n.y(), n.z()));
            if (!s.isAir() && !s.hasBlockEntity() && s.getFluidState().isEmpty()) return s;
        }
        return material;
    }

    /** How far above and below its line the Slope tool looks for the ground of a column, and the most it raises it. */
    static final int SLOPE_REACH = 64;

    /** The longest ramp the Slope tool builds, measured across the ground from its start to its end. */
    static final int MAX_SLOPE_LENGTH = 256;

    /**
     * Elevation, Flatten and Slope. Each works column by column on the ground: Elevation raises or lowers a mound with a smooth falloff,
     * Flatten brings the ground to {@link Options#flattenY}, and Slope builds a ramp from its first point to its second.
     */
    private void heightmap(Point c, Point normal) {
        if (o.tool == Tool.SLOPE) {
            slope();
            return;
        }
        // Level from click: the block the stroke's first click lands on, kept for the rest of the stroke; a click on
        // its own (stroke 0) is
        // a stroke of one.
        if (o.tool == Tool.FLATTEN
                && o.flattenPick
                && (session.requestStroke == 0 || session.requestStroke != session.flattenStroke)) {
            session.flattenStroke = session.requestStroke;
            session.flattenLevel = c.y();
        }
        int target = Math.clamp(o.flattenPick ? session.flattenLevel : o.flattenY, level.getMinY(), level.getMaxY());
        // Elevation's strength is the height, in blocks, the middle of the mound moves with each click.
        int height = Math.max(1, (int) Math.round(o.strength));
        for (int dx = -o.radius; dx <= o.radius; dx++)
            for (int dz = -o.radius; dz <= o.radius; dz++) {
                double distance = Math.sqrt(dx * dx + dz * dz) / o.radius;
                if (distance > 1) continue;
                double u = (dx / (double) o.radius + 1) / 2, v = (dz / (double) o.radius + 1) / 2;
                column(c.x() + dx, c.z() + dz, c.y(), o.radius + 8, top -> switch (o.tool) {
                    case ELEVATION -> {
                        double falloff =
                                heightmap == null ? smoothstep(1 - distance) : (1 - distance) * heightmap.sample(u, v);
                        yield top + (o.invert ? -1 : 1) * (int) Math.round(height * falloff);
                    }
                    case FLATTEN -> {
                        int gap = target - top;
                        if (gap == 0
                                || o.flattenMode == Options.FlattenMode.LOWER && gap > 0
                                || o.flattenMode == Options.FlattenMode.RAISE && gap < 0) yield top;
                        // A part of the way with each click, but always at least one block, so a low strength still
                        // gets there.
                        yield top
                                + Integer.signum(gap)
                                        * Math.max(1, (int) Math.round(Math.abs(gap) * Math.min(1, o.strength)));
                    }
                    default -> top;
                });
            }
    }

    /** A ramp from the first of the tool's points to the last, as wide as the brush's diameter, with its height running straight between theirs. */
    private void slope() {
        if (o.nodes.size() < 2)
            throw new IllegalArgumentException("Right-click where the ramp starts, then where it ends");
        Point a = o.nodes.getFirst(), b = o.nodes.getLast();
        double vx = b.x() - a.x(), vz = b.z() - a.z(), length2 = vx * vx + vz * vz;
        if (Math.sqrt(length2) > MAX_SLOPE_LENGTH)
            throw new IllegalArgumentException(
                    "A slope is at most " + MAX_SLOPE_LENGTH + " blocks long; put its end closer to its start");
        int r = o.radius;
        for (int x = Math.min(a.x(), b.x()) - r; x <= Math.max(a.x(), b.x()) + r; x++)
            for (int z = Math.min(a.z(), b.z()) - r; z <= Math.max(a.z(), b.z()) + r; z++) {
                // Where the column falls along the ramp (0 at its start, 1 at its end) and how far it is from the
                // ramp's middle line.
                double t = length2 == 0 ? 0 : ((x - a.x()) * vx + (z - a.z()) * vz) / length2;
                if (t < 0 || t > 1) continue;
                double side = Math.hypot(x - (a.x() + t * vx), z - (a.z() + t * vz));
                if (side > r + .5) continue;
                int y = (int) Math.round(a.y() + t * (b.y() - a.y()));
                column(x, z, y, SLOPE_REACH, SLOPE_REACH, SLOPE_REACH, top -> y);
            }
    }

    private static double smoothstep(double t) {
        t = Math.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    /**
     * Whether the height tools take a block as the ground: it blocks movement and holds no fluid. Plants, snow layers and the like stand
     * on the ground, and leaves and logs belong to trees, so none of them is where a column's height is measured.
     */
    private boolean ground(Point p) {
        BlockState s = state(p);
        return s.blocksMotion()
                && s.getFluidState().isEmpty()
                && !s.is(net.minecraft.tags.BlockTags.LEAVES)
                && !s.is(net.minecraft.tags.BlockTags.LOGS);
    }

    /**
     * Moves the ground of column {@code x}, {@code z} to the height {@code desired} gives for its current top. The top is looked for
     * from {@code above} blocks over {@code reference} down to a little under it. Raised ground is filled with the block under the
     * surface (dirt under grass), lowered ground keeps its surface block, and what grows on top moves with it. A column with a block
     * entity on top, or with a tree trunk or a build standing on it, is left alone.
     */
    private void column(int x, int z, int reference, int above, java.util.function.IntUnaryOperator desired) {
        column(x, z, reference, above, o.radius + 16, 24, desired);
    }

    /** {@link #column(int, int, int, int, java.util.function.IntUnaryOperator)} with the depth the ground is looked for to and the most it is raised. */
    private void column(
            int x, int z, int reference, int above, int depth, int raise, java.util.function.IntUnaryOperator desired) {
        int maxY = level.getMaxY(); // the top block layer: getMaxY is inclusive
        int top = Math.min(maxY, reference + above), bottom = Math.max(level.getMinY(), reference - depth);
        while (top >= bottom && !ground(new Point(x, top, z))) top--;
        if (top < bottom) return;
        Point at = new Point(x, top, z);
        BlockState surface = state(at);
        if (surface.hasBlockEntity()) return;
        var cover = new ArrayList<BlockState>(2);
        for (int y = top + 1; y <= Math.min(maxY, top + 2); y++) {
            BlockState s = state(new Point(x, y, z));
            if (s.isAir() || !s.getFluidState().isEmpty()) break;
            if (s.blocksMotion() || s.hasBlockEntity()) {
                if (cover.isEmpty()) return; // a trunk, a wall or a chest stands on this column
                break;
            }
            cover.add(s);
        }
        int to = Math.clamp(desired.applyAsInt(top), bottom, Math.min(maxY, top + raise));
        if (to == top) return;
        // The layers that travel with the surface: just its top block, or with Smear the top few ground blocks.
        var layers = new ArrayList<BlockState>(4);
        layers.add(surface);
        if (o.tool == Tool.ELEVATION && o.smear)
            for (int k = 1; k < o.smearDepth && top - k >= level.getMinY(); k++) {
                Point p = new Point(x, top - k, z);
                if (!ground(p) || state(p).hasBlockEntity()) break;
                layers.add(state(p));
            }
        int n = layers.size();
        // A column moves as a whole or not at all: if any block it would write belongs to a BuildObject, the Terrain
        // tools leave the column as it is,
        // instead of tearing it around the object's blocks.
        if (!exact && o.tool.group.equals("Terrain")) {
            int low = Math.max(level.getMinY(), Math.min(top - n + 1, to - n + 1)),
                    high = Math.min(maxY, Math.max(top, to) + cover.size());
            for (int y = low; y <= high; y++)
                if (inBuildObject(new Point(x, y, z))) {
                    skip(Skip.OBJECT);
                    return;
                }
        }
        Point below = at.add(0, -n, 0);
        BlockState under = state(below);
        BlockState fill = ground(below) && !under.hasBlockEntity() ? under : layers.get(n - 1);
        for (int i = 0; i < cover.size(); i++) put(at.add(0, i + 1, 0), Blocks.AIR.defaultBlockState());
        if (to < top) for (int y = top; y > to; y--) put(new Point(x, y, z), Blocks.AIR.defaultBlockState());
        // The layers hang from the new surface; anything under them that the move uncovers is filled.
        for (int y = Math.max(level.getMinY(), Math.min(top - n + 1, to - n + 1)); y <= to; y++) {
            int i = to - y;
            put(new Point(x, y, z), i < n ? layers.get(i) : fill);
        }
        for (int i = 0; i < cover.size() && to + i + 1 <= maxY; i++) {
            Point p = new Point(x, to + i + 1, z);
            if (to + i + 1 <= top + cover.size() || state(p).isAir()) put(p, cover.get(i));
        }
    }

    void paintBiomes(List<Point> points) {
        var registry = level.registryAccess().lookupOrThrow(Registries.BIOME);
        Holder<Biome> biome = registry.get(
                        ResourceKey.create(Registries.BIOME, dev.craftium.compat.Compat.parse(o.biome)))
                .orElseThrow(() -> new IllegalArgumentException("Unknown biome: " + o.biome));
        for (Point p : points) {
            if (!accepted(p)) continue;
            int start = o.fillVertically ? level.getMinY() >> 2 : p.y() >> 2,
                    end = o.fillVertically ? level.getMaxY() >> 2 : start;
            for (int y = start; y <= end; y++) {
                Point cell = new Point(p.x() >> 2, y, p.z() >> 2);
                var old = level.getNoiseBiome(cell.x(), cell.y(), cell.z());
                if (!old.equals(biome)) biomes.put(cell, new Session.BiomeChange(cell, old, biome));
            }
        }
    }

    Session.Edit finish(String name) {
        session.nothingChangedBecause = changes.isEmpty() && biomes.isEmpty() ? skipReason() : null;
        return new Session.Edit(
                name, List.copyOf(changes.values()), List.copyOf(biomes.values()), BlockSnapshot.NO_UPDATES);
    }
}
