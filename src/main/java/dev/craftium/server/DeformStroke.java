package dev.craftium.server;

import dev.craftium.core.Deform;
import dev.craftium.core.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Deform stroke while it is staged. Every dab is worked out on the world as it was before the stroke (with whatever was staged before it),
 * so dabs over the same place push an even layer instead of piling up, and each dab builds only the blocks it changes, not the whole stroke
 * again.
 */
final class DeformStroke {
    /** The stroke the dabs belong to, as the client numbers it. */
    final long id;
    /** What was staged before the stroke, read as built, and its changes by place. */
    private final Session.Edit base;

    private final Map<Point, BlockSnapshot> view = new HashMap<>();
    private final Map<Point, Session.Change> before = new HashMap<>();
    /** The move so far. */
    final Deform.Stroke stroke = new Deform.Stroke();
    /** What the stroke does to each place it has touched: its change, or null for a place it puts back as the world has it. */
    private final LinkedHashMap<Point, Session.Change> changes = new LinkedHashMap<>();
    /** The staged edit the last dab left, so a stroke whose edit was built or cancelled meanwhile is not carried on. */
    Session.Edit made;

    DeformStroke(long id, Session.Edit base) {
        this.id = id;
        this.base = base;
        if (base != null)
            for (var c : base.changes()) {
                view.put(c.pos(), c.after());
                before.put(c.pos(), c);
            }
    }

    /** Whether a dab of stroke {@code id}, with {@code staged} waiting, carries this stroke on. */
    boolean continues(long id, Session.Edit staged) {
        return id != 0 && id == this.id && staged == made;
    }

    /** Adds a dab, put on {@code c} clicked on its {@code normal} side, and returns the whole staged edit: what came before and the stroke. */
    Session.Edit dab(EditPlanner plan, Point c, Point normal, String name) {
        plan.readThrough(view);
        // Each block the dab may build starts from what is staged for it, so it keeps the world's before-state.
        var touched = plan.deform(stroke, c, normal, this::staged);
        var made = plan.finish(name);
        for (var change : made.changes()) changes.put(change.pos(), change);
        for (Point p : touched) if (plan.planned(p) == null) changes.put(p, null);
        var all = new ArrayList<Session.Change>(before.size() + changes.size());
        if (base != null) for (var change : base.changes()) if (!changes.containsKey(change.pos())) all.add(change);
        for (var change : changes.values()) if (change != null) all.add(change);
        plan.checkLimit(all.size() + (base == null ? 0 : base.biomes().size()));
        return base == null
                ? new Session.Edit(name, all, List.of(), made.flags())
                : new Session.Edit(
                        base.name(),
                        all,
                        base.biomes(),
                        base.flags() | made.flags(),
                        base.entities(),
                        base.annotations(),
                        base.selection());
    }

    /** What is staged at {@code p} so far: the stroke's change, or the one staged before it; null for the world as it is. */
    private Session.Change staged(Point p) {
        return changes.containsKey(p) ? changes.get(p) : before.get(p);
    }
}
