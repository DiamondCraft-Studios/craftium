package dev.craftium.server;

import dev.craftium.core.*;
import java.util.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

final class SelectionOperations {
    private SelectionOperations() {}
    /** The start of the action that replaces one kind of block in the selection with another: {@code replace_block:<from>><to>}. */
    static final String REPLACE_BLOCK = "replace_block:";
    /** The start of the action that changes the material of the blocks in the selection: {@code change_material:<block of the old material>><block of the new one>}. */
    static final String CHANGE_MATERIAL = "change_material:";

    /**
     * Changes the material of the selection's blocks: every block that is the same material as the first block (oak: its planks, stairs, slabs, fences, logs and so
     * on) becomes the same kind of the second block's material (spruce). Properties are kept, so stairs keep their facing. A block whose new material has no
     * such kind (a wall, where the new material has none) is left as it is and counted in the name. Blocks of other materials are not touched.
     */
    static String changeMaterial(EditPlanner p, String action) {
        var ids = action.substring(CHANGE_MATERIAL.length()).split(">", 2);
        if (ids.length != 2)
            throw new IllegalArgumentException("Choose a block of the material to change and one of the new material");
        var index = MaterialFamilies.index();
        var fromSlot = index.slot(block(ids[0]));
        var toSlot = index.slot(block(ids[1]));
        if (fromSlot == null || index.kinds(fromSlot.material()).size() < 2)
            throw new IllegalArgumentException(ids[0] + " has no other kinds of block to change to another material");
        if (toSlot == null || index.kinds(toSlot.material()).size() < 2)
            throw new IllegalArgumentException(
                    ids[1] + " is not a material with stairs, slabs or other kinds of block");
        if (fromSlot.material().equals(toSlot.material()))
            throw new IllegalArgumentException("That is the same material");
        Set<Point> selection = p.session.selection.points();
        if (selection.isEmpty()) throw new IllegalArgumentException("Create a selection first");
        var skipped = new TreeSet<String>();
        int changed = 0;
        for (Point at : selection) {
            var old = p.state(at);
            var slot = index.slot(old.getBlock());
            if (slot == null || !slot.material().equals(fromSlot.material())) continue;
            var target = index.member(toSlot.material(), slot.kind());
            if (target == null) {
                skipped.add(slot.kind().replace('_', ' '));
                continue;
            }
            p.put(at, p.preserve(old, target.defaultBlockState()));
            changed++;
            // a door is two blocks: its other half changes with it, even when the selection cuts through the door
            var half = old.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                    ? old.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)
                    : null;
            if (half != null) {
                Point other = at.add(
                        0, half == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER ? 1 : -1, 0);
                var otherOld = p.state(other);
                if (otherOld.is(old.getBlock())) p.put(other, p.preserve(otherOld, target.defaultBlockState()));
            }
        }
        if (changed == 0)
            throw new IllegalArgumentException(
                    skipped.isEmpty()
                            ? "The selection has no " + shortName(fromSlot.material()) + " blocks to change"
                            : shortName(toSlot.material()) + " has no " + String.join(", ", skipped)
                                    + " to change the selection to");
        String name = "Change material " + shortName(fromSlot.material()) + " to " + shortName(toSlot.material());
        return skipped.isEmpty()
                ? name
                : name + " (no " + toSlot.material().replaceFirst("^minecraft:", "") + " " + String.join(", ", skipped)
                        + ")";
    }
    /** A material without the {@code minecraft:} that vanilla's carry. */
    private static String shortName(String material) {
        return material.replaceFirst("^minecraft:", "");
    }

    /**
     * Turns every block of one kind in the selection into another, keeping the properties the two share (a stair keeps its facing, a slab its half). Returns the name
     * the edit is given in the history. Blocks are matched by kind, whatever their state. The block to replace them with may name states, {@code minecraft:oak_stairs[half=top]}:
     * these are set on top of what the old block had, so the same block with states is a change to those states only and every other state stays as it was.
     */
    static String replaceBlock(EditPlanner p, String action) {
        var ids = action.substring(REPLACE_BLOCK.length()).split(">", 2);
        if (ids.length != 2)
            throw new IllegalArgumentException("Choose the block to replace and the block to replace it with");
        var from = block(ids[0]);
        int open = ids[1].indexOf('['), close = ids[1].lastIndexOf(']');
        String toId = open < 0 ? ids[1] : ids[1].substring(0, open);
        var to = block(toId);
        var states = new LinkedHashMap<String, String>();
        if (open >= 0 && close > open)
            for (String pair : ids[1].substring(open + 1, close).split(",")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) states.put(kv[0].trim(), kv[1].trim());
            }
        if (from == to && states.isEmpty()) throw new IllegalArgumentException("That is the same block");
        Set<Point> selection = p.session.selection.points();
        if (selection.isEmpty()) throw new IllegalArgumentException("Create a selection first");
        var replacement = to.defaultBlockState();
        for (Point at : selection) {
            var old = p.state(at);
            if (!old.is(from)) continue;
            var next = withStates(p.preserve(old, replacement), states);
            if (!next.equals(old)) p.put(at, next);
        }
        String name = ids[0].replaceFirst("^minecraft:", "");
        return from == to
                ? "Set " + String.join(", ", states.keySet()) + " of " + name
                : "Replace " + name + " with " + toId.replaceFirst("^minecraft:", "");
    }
    /** The state with each named property set to the named value; a property the block does not have, or a value it cannot take, is skipped. */
    private static net.minecraft.world.level.block.state.BlockState withStates(
            net.minecraft.world.level.block.state.BlockState state, Map<String, String> states) {
        for (var property : state.getProperties()) {
            String value = states.get(property.getName());
            if (value != null) state = withState(state, property, value);
        }
        return state;
    }

    private static <T extends Comparable<T>> net.minecraft.world.level.block.state.BlockState withState(
            net.minecraft.world.level.block.state.BlockState state,
            net.minecraft.world.level.block.state.properties.Property<T> property,
            String value) {
        return property.getValue(value).map(v -> state.setValue(property, v)).orElse(state);
    }

    private static net.minecraft.world.level.block.Block block(String id) {
        var key = dev.craftium.compat.Compat.tryParse(id);
        var block = key == null
                ? java.util.Optional.<net.minecraft.world.level.block.Block>empty()
                : net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(key);
        return block.orElseThrow(() -> new IllegalArgumentException("Unknown block: " + id));
    }

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
