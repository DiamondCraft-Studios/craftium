package dev.craftium.server;

import dev.craftium.core.*;
import java.util.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

final class SelectionOperations {
    private SelectionOperations() {}

    static void apply(String action, EditPlanner p) {
        Set<Point> selection = p.session.selection.points();
        if (selection.isEmpty()) throw new IllegalArgumentException("Create a selection first");
        if (action.equals("set_biome")) {
            p.paintBiomes(new ArrayList<>(selection));
            return;
        }
        if (action.equals("fill_nearest")) {
            nearest(p, selection);
            return;
        }
        if (action.equals("fill_gaps")) {
            gaps(p, selection);
            return;
        }
        if (action.equals("gravity")) {
            gravity(p, selection);
            return;
        }
        if (action.equals("autoshade")) throw new IllegalArgumentException("Use the lighting planner for Autoshade");
        for (Point at : selection) {
            var old = p.state(at);
            switch (action) {
                case "fill_outline" -> {
                    if (boundary(at, selection, 0)) p.put(at, p.material);
                }
                case "fill_walls" -> {
                    if (boundary(at, selection, 1)) p.put(at, p.material);
                }
                case "fill_top" -> {
                    if (!selection.contains(at.add(0, 1, 0))) p.put(at, p.material);
                }
                case "fill_bottom" -> {
                    if (!selection.contains(at.add(0, -1, 0))) p.put(at, p.material);
                }
                case "replace" -> {
                    if (p.solid(at))
                        p.put(
                                at,
                                p.o.typeReplace
                                        ? AdvancedTools.typeReplace(p, old, p.material)
                                        : p.preserve(old, p.material));
                }
                case "drain" -> {
                    if (old.hasProperty(BlockStateProperties.WATERLOGGED))
                        p.put(at, old.setValue(BlockStateProperties.WATERLOGGED, false));
                    else if (!old.getFluidState().isEmpty()) p.put(at, Blocks.AIR.defaultBlockState());
                }
                case "waterlog" -> {
                    if (old.hasProperty(BlockStateProperties.WATERLOGGED))
                        p.put(at, old.setValue(BlockStateProperties.WATERLOGGED, true));
                    else if (old.isAir()) p.put(at, Blocks.WATER.defaultBlockState());
                }
                case "hollow" -> {
                    if (p.solid(at) && p.adjacent(at) == 6) p.put(at, Blocks.AIR.defaultBlockState());
                }
                case "trigger_updates" -> {
                    if (!old.canSurvive(p.level, EditPlanner.pos(at))) p.put(at, Blocks.AIR.defaultBlockState());
                }
                case "smooth_snow" -> {
                    if (p.solid(at) && p.state(at.add(0, 1, 0)).isAir()) {
                        int exposed = 0;
                        for (Point n : EditPlanner.NEIGHBOURS)
                            if (n.y() == 0 && !p.solid(at.add(n.x(), 0, n.z()))) exposed++;
                        p.put(
                                at.add(0, 1, 0),
                                Blocks.SNOW
                                        .defaultBlockState()
                                        .setValue(BlockStateProperties.LAYERS, Math.clamp(8 - exposed * 2, 1, 8)));
                    }
                }
                default -> throw new IllegalArgumentException("Unknown selection operation: " + action);
            }
        }
    }

    private static boolean boundary(Point at, Set<Point> selection, int horizontal) {
        for (Point n : EditPlanner.NEIGHBOURS)
            if ((horizontal == 0 || n.y() == 0) && !selection.contains(at.add(n.x(), n.y(), n.z()))) return true;
        return false;
    }

    private static void nearest(EditPlanner p, Set<Point> selection) {
        var queue = new ArrayDeque<Point>();
        var values = new HashMap<Point, net.minecraft.world.level.block.state.BlockState>();
        for (Point at : selection) {
            if (p.solid(at)) {
                queue.add(at);
                values.put(at, p.state(at));
            } else
                for (Point n : EditPlanner.NEIGHBOURS) {
                    Point next = at.add(n.x(), n.y(), n.z());
                    if (!selection.contains(next) && p.solid(next)) {
                        queue.add(at);
                        values.put(at, p.state(next));
                        break;
                    }
                }
        }
        while (!queue.isEmpty()) {
            Point at = queue.removeFirst();
            for (Point n : EditPlanner.NEIGHBOURS) {
                Point next = at.add(n.x(), n.y(), n.z());
                if (selection.contains(next) && !values.containsKey(next)) {
                    values.put(next, values.get(at));
                    queue.add(next);
                }
            }
        }
        for (var e : values.entrySet()) if (p.state(e.getKey()).isAir()) p.put(e.getKey(), e.getValue());
    }

    private static void gaps(EditPlanner p, Set<Point> selection) {
        var outside = new HashSet<Point>();
        var queue = new ArrayDeque<Point>();
        for (Point at : selection)
            if (!p.solid(at) && boundary(at, selection, 0)) {
                outside.add(at);
                queue.add(at);
            }
        while (!queue.isEmpty()) {
            Point at = queue.removeFirst();
            for (Point n : EditPlanner.NEIGHBOURS) {
                Point next = at.add(n.x(), n.y(), n.z());
                if (selection.contains(next) && !p.solid(next) && outside.add(next)) queue.add(next);
            }
        }
        for (Point at : selection) if (!p.solid(at) && !outside.contains(at)) p.put(at, p.material);
    }

    private static void gravity(EditPlanner p, Set<Point> selection) {
        var source = new LinkedHashMap<Point, BlockSnapshot>();
        for (Point at : selection) if (p.solid(at)) source.put(at, BlockSnapshot.capture(p.level, EditPlanner.pos(at)));
        var ordered = new ArrayList<>(source.keySet());
        ordered.sort(Comparator.comparingInt(Point::y));
        var settled = new HashSet<Point>();
        for (Point at : ordered) p.put(at, Blocks.AIR.defaultBlockState());
        for (Point at : ordered) {
            int y = at.y();
            while (y > p.level.getMinY() && at.y() - y < 128) {
                Point below = new Point(at.x(), y - 1, at.z());
                if (settled.contains(below) || (!source.containsKey(below) && p.solid(below))) break;
                y--;
            }
            Point destination = new Point(at.x(), y, at.z());
            p.put(destination, source.get(at));
            settled.add(destination);
        }
    }
}
