package dev.craftium.server;

import dev.craftium.core.BrushMath;
import dev.craftium.core.MagicWand;
import dev.craftium.core.Options;
import dev.craftium.core.Point;
import dev.craftium.core.Selection;

import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;

/**
 * What the tools of the editor that do more than plan the way most tools do, do with a click. Each is what {@code EditorServer.use} did for that
 * tool before tools had behaviours, moved as it was.
 */
final class BuiltinTools {
    private BuiltinTools() {}

    /** What every tool without a behaviour of its own does: plan what the tool makes where it was clicked, and hand it in. */
    static final ToolBehaviour PLAIN = u -> {
        u.plan().tool(u.point(), u.normal());
        u.submit(u.plan().finish(u.options().tool.label));
    };

    static final ToolBehaviour ANNOTATION = u -> u.submit(AnnotationEdits.plan("annotation_create", u.player().level(), u.options(), u.point()));

    static final ToolBehaviour BOX_SELECT = u -> {
        var s = u.session();
        Point p = u.point();
        if (s.corner == null) {
            s.corner = p;
            u.say("First corner: " + p.coordinates() + " · choose the opposite corner");
        } else {
            s.selection.combine(EditorServer.masked(u.plan(), u.options(), Selection.box(s.corner, p)), u.options().mode);
            s.corner = null;
            u.say("Selected " + s.selection.size() + " blocks");
        }
    };

    static final ToolBehaviour FREEHAND_SELECT = u -> {
        var s = u.session();
        s.selection.combine(EditorServer.masked(u.plan(), u.options(), u.plan().brush(u.point())), u.options().mode);
        u.say("Selected " + s.selection.size() + " blocks");
    };

    static final ToolBehaviour MAGIC_SELECT = u -> {
        var player = u.player();
        var s = u.session();
        var o = u.options();
        var plan = u.plan();
        Point p = u.point();
        var origin = plan.state(p);
        var originData = o.compare == Options.Compare.NBT ? AdvancedTools.data(plan, p) : null;
        var blockMask = BlockMask.of(o);
        var found = MagicWand.select(p, new MagicWand.World() {
            public boolean loaded(Point at) { return player.level().hasChunk(at.x() >> 4, at.z() >> 4); }
            public boolean air(Point at) { return plan.state(at).isAir(); }
            public boolean matches(Point at) { return (o.selectLiquids || !(plan.state(at).getBlock() instanceof net.minecraft.world.level.block.LiquidBlock)) && blockMask.allows(plan.state(at)) && AdvancedTools.matches(plan, origin, originData, at); }
            public boolean surface(Point at) { return plan.surface(at); }
        }, new MagicWand.Params(o.selectLimit, o.selectRange, o.selectCorners, o.selectUp, o.selectDown, o.selectHorizontal, o.surfaceOnly, 128, Selection.LIMIT * 4)).found();
        s.selection.combine(found, o.mode);
        u.say("Magic Wand: " + s.selection.size() + " blocks (128-block search radius)");
    };

    /** Fills what is connected to the clicked block, found as the Magic Wand finds it with its directions and select type, with the foreground block. */
    static final ToolBehaviour PAINT_BUCKET = u -> u.submit(bucketFill(u.player(), u.plan(), u.options(), u.point(), u.options().compare, "Paint Bucket"));

    /**
     * The blocks connected to {@code target} that the Magic Wand would pick with {@code compare}, replaced with the active block. Shared by the
     * Paint Bucket tool and by dropping a block in the world, which always compares by block.
     */
    static Session.Edit bucketFill(ServerPlayer player, EditPlanner plan, Options o, Point target, Options.Compare compare, String name) {
        var origin = plan.state(target);
        if (origin.isAir()) throw new IllegalArgumentException(name + " needs a block, not empty space");
        var originData = compare == Options.Compare.NBT ? AdvancedTools.data(plan, target) : null;
        var found = MagicWand.select(target, new MagicWand.World() {
            public boolean loaded(Point at) { return player.level().hasChunk(at.x() >> 4, at.z() >> 4); }
            public boolean air(Point at) { return plan.state(at).isAir(); }
            public boolean matches(Point at) { return compare == Options.Compare.BLOCK ? plan.state(at).is(origin.getBlock()) : AdvancedTools.matches(plan, origin, originData, at); }
            public boolean surface(Point at) { return plan.surface(at); }
        }, new MagicWand.Params(o.selectLimit, o.selectRange, o.selectCorners, o.selectUp, o.selectDown, o.selectHorizontal, o.surfaceOnly, 128, Selection.LIMIT * 4)).found();
        if (found.isEmpty()) throw new IllegalArgumentException(name + " found no connected blocks there; check the directions and surface settings");
        for (Point at : found) plan.put(at, plan.preserve(plan.state(at), plan.material));
        if (plan.changes.isEmpty()) {
            String why = origin.getBlock() == plan.material.getBlock() ? "those blocks are already that block"
                : !BlockMask.of(o).allows(origin) || !o.mask.isBlank() ? "the block mask excludes them"
                : o.inSelection ? "Only in selection is on" : "the Y range, or the block can't be placed there (enable force place)";
            throw new IllegalArgumentException(name + " changed nothing: " + why + " (" + found.size() + " blocks matched)");
        }
        return plan.finish(name);
    }

    static final ToolBehaviour RULER = u -> {
        var s = u.session();
        Point p = u.point();
        if (s.ruler == null) {
            s.ruler = p;
            u.say("Ruler start: " + p.coordinates());
        } else {
            Point a = s.ruler;
            double distance = Math.sqrt(a.distanceSquared(p));
            int manhattan = Math.abs(a.x() - p.x()) + Math.abs(a.y() - p.y()) + Math.abs(a.z() - p.z());
            String message = String.format(Locale.ROOT, "Ruler: %s blocks · Manhattan %d · Δ %s", dev.craftium.core.Numbers.trim(distance), manhattan, p.subtract(a).coordinates());
            s.event(message);
            s.ruler = null;
            u.say(message);
        }
    };

    static final ToolBehaviour LINE = u -> {
        var s = u.session();
        var o = u.options();
        var plan = u.plan();
        Point p = u.point();
        if (s.line == null) {
            s.line = p;
            u.say("Line start set · choose the endpoint");
        } else {
            for (Point point : BrushMath.line(s.line, p))
                for (Point at : BrushMath.brush(point, Math.min(o.radius, 4), o.shape, o.hollow, o.outlineThickness, o.pixelPerfect)) plan.put(at, plan.material);
            s.line = null;
            u.submit(plan.finish("Draw line"));
        }
    };

    static final ToolBehaviour SHAPE = u -> {
        var s = u.session();
        var o = u.options();
        var plan = u.plan();
        plan.tool(u.point(), u.normal());
        var edit = plan.finish(o.tool.label);
        // What was placed becomes the selection, so the transform tools can take hold of it. Carving leaves nothing to select.
        var placed = new LinkedHashSet<Point>();
        if (!o.invert) for (var change : edit.changes()) placed.add(change.pos());
        if (placed.isEmpty()) { u.submit(edit); return; }
        var before = new ArrayList<>(s.selection.points());
        if (s.confirmEdits && !s.bypassConfirm && s.pendingSelectionBefore == null) s.pendingSelectionBefore = before;
        s.selection.combine(placed, Selection.Mode.REPLACE);
        try { u.submit(edit.withSelection(new Session.SelectionChange(before, new ArrayList<>(placed)))); }
        catch (RuntimeException e) { s.selection.restore(before); s.pendingSelectionBefore = null; throw e; }
    };
}
