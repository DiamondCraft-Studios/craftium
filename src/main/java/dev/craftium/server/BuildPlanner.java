package dev.craftium.server;

import dev.craftium.core.*;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/**
 * Turns an AI client's {@link BuildSpec} into an edit at the world coordinates the spec names, ready to be held as a ghost until it
 * is applied. It needs no selection and no player nearby: the chunks it reaches are loaded for it.
 */
final class BuildPlanner {
    private BuildPlanner() {}

    /**
     * @param selection the player's own selection, for the operations that work on it; it is only read, never changed
     */
    static Session.Edit plan(net.minecraft.server.level.ServerPlayer player, Session ai, Options o, java.util.Set<Point> selection) {
        var spec = BuildSpec.parse(o.buildSpec);
        var level = (net.minecraft.server.level.ServerLevel) player.level();
        var plan = new EditPlanner(level, ai, o);
        if (!spec.hasShapes()) {
            // Only operations on the selection: there is no area of its own, so the ground to load is the selection's.
            if (selection.isEmpty()) throw new IllegalArgumentException("replace_selection works on the player's selection, and nothing is selected. Ask the player to select the blocks, or give coordinates and use replace with from and to");
            var states = states(level, spec);
            plan.exact = true;
            loadAround(level, selection);
            applySelection(level, plan, spec, states, selection, new HashMap<>());
            return plan.finish("AI build");
        }
        var place = spec.place();
        var min = place.min();
        AiWorld.load(level, min.x(), min.z(), min.x() + place.bounds().sx() - 1, min.z() + place.bounds().sz() - 1);
        // Every block is looked up before anything is planned, so an unknown name fails the whole build and not half of it.
        var states = states(level, spec);
        var result = spec.build(place);
        var matches = new HashMap<String, Match>();
        var turned = new HashMap<String, Map<Turn, BlockState>>();
        // The spec says exactly where blocks go, so the player's brush mask, symmetry and height settings do not apply.
        plan.exact = true;
        for (var entry : result.cells().entrySet()) {
            var at = min.add(entry.getKey().x(), entry.getKey().y(), entry.getKey().z());
            var cell = entry.getValue();
            if (cell.only() != null && !match(level, matches, cell.only()).matches(plan.state(at))) continue;
            var state = states.get(cell.block());
            if (!cell.turn().equals(Turn.NONE))
                state = turned.computeIfAbsent(cell.block(), k -> new HashMap<>()).computeIfAbsent(cell.turn(), t -> turn(states.get(cell.block()), t));
            plan.put(at, state);
        }
        if (!spec.selectionOps().isEmpty()) {
            if (selection.isEmpty()) throw new IllegalArgumentException("replace_selection works on the player's selection, and nothing is selected. Ask the player to select the blocks, or give coordinates and use replace with from and to");
            loadAround(level, selection);
            applySelection(level, plan, spec, states, selection, matches);
        }
        return plan.finish("AI build");
    }

    /**
     * Every block name the spec uses, looked up. A name that is not a block, or a state the block does not have, is refused with what an AI
     * needs to fix it in one go: the operations that use it, why it failed, and the nearest real ids or the properties the block does take.
     */
    private static Map<String, BlockState> states(net.minecraft.server.level.ServerLevel level, BuildSpec spec) {
        var lookup = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK);
        var states = new HashMap<String, BlockState>();
        for (String name : spec.blocks()) {
            String real = concrete(name, spec);
            try {
                states.put(name, net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(lookup, new com.mojang.brigadier.StringReader(real), false).blockState());
            } catch (Exception e) {
                throw new IllegalArgumentException(explain(lookup, spec, name, real, e));
            }
        }
        return states;
    }

    /**
     * The block a name stands for: itself, or for a material reference such as {@code spruce@stairs} the first of its spellings that is a
     * block in this game ({@code spruce_stairs}), with any state written after it kept.
     */
    private static String concrete(String name, BuildSpec spec) {
        if (!MaterialNames.isReference(name)) return name;
        int bracket = name.indexOf('[');
        String state = bracket < 0 ? "" : name.substring(bracket);
        var known = blockIdSet();
        var tried = MaterialNames.candidates(name);
        for (String id : tried) if (known.contains(id)) return id + state;
        String plain = bracket < 0 ? name : name.substring(0, bracket);
        String material = plain.substring(0, plain.indexOf('@'));
        var message = new StringBuilder(spec == null ? "" : String.join(", ", spec.usesOf(name)) + ": ")
            .append('"').append(plain).append("\" is not a block in this game version (tried ").append(String.join(", ", tried)).append(')');
        var near = BlockNames.suggest(tried.getFirst(), blockIds(), 5);
        if (!near.isEmpty()) message.append(". Nearest: ").append(String.join(", ", near));
        message.append(". Name the block itself, or check that ").append(material).append(" has that kind");
        throw new IllegalArgumentException(message.toString());
    }

    /** A block state turned the way a group turned its cell: mirrored first, then rotated, as {@link Turn} reads. */
    static BlockState turn(BlockState state, Turn turn) {
        if (turn.mirrored()) state = state.mirror(net.minecraft.world.level.block.Mirror.FRONT_BACK);
        return state.rotate(net.minecraft.world.level.block.Rotation.values()[turn.quarterTurns()]);
    }

    private static String explain(net.minecraft.core.HolderLookup<net.minecraft.world.level.block.Block> lookup, BuildSpec spec, String name, String real, Exception failure) {
        var message = new StringBuilder(String.join(", ", spec.usesOf(name))).append(": ");
        name = real;
        String id = BlockNames.id(name);
        BlockState block = null;
        try { block = net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(lookup, new com.mojang.brigadier.StringReader(id), false).blockState(); }
        catch (Exception ignored) { /* the id itself is unknown */ }
        if (block == null) {
            message.append('"').append(id).append("\" is not a block in this game version");
            var near = BlockNames.suggest(id, blockIds(), 5);
            if (!near.isEmpty()) message.append(". Nearest: ").append(String.join(", ", near));
            return message.toString();
        }
        message.append('"').append(name).append("\" is not a valid state");
        if (failure.getMessage() != null) message.append(" (").append(failure.getMessage()).append(')');
        var properties = new ArrayList<String>();
        for (var property : block.getProperties()) properties.add(values(property));
        message.append(properties.isEmpty() ? ". " + id + " takes no properties" : ". " + id + " takes " + String.join(", ", properties));
        return message.toString();
    }

    /** "facing=north|east|south|west", the way a block state is written. */
    private static <T extends Comparable<T>> String values(net.minecraft.world.level.block.state.properties.Property<T> property) {
        var names = new ArrayList<String>();
        for (T value : property.getPossibleValues()) names.add(property.getName(value));
        return property.getName() + "=" + String.join("|", names);
    }

    private static List<String> blockIds;

    /** Every block id there is, worked out once: the list is fixed when the game starts. */
    private static synchronized List<String> blockIds() {
        if (blockIds == null) {
            var ids = new ArrayList<String>();
            for (var key : net.minecraft.core.registries.BuiltInRegistries.BLOCK.keySet()) ids.add(key.toString());
            blockIds = List.copyOf(ids);
        }
        return blockIds;
    }

    private static Set<String> blockIdSet;

    private static synchronized Set<String> blockIdSet() {
        if (blockIdSet == null) blockIdSet = Set.copyOf(blockIds());
        return blockIdSet;
    }

    /** Makes sure the ground under a selection is loaded; it is normally the player's own, but may reach a few unvisited chunks. */
    private static void loadAround(net.minecraft.server.level.ServerLevel level, java.util.Set<Point> selection) {
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (Point p : selection) { x0 = Math.min(x0, p.x()); x1 = Math.max(x1, p.x()); z0 = Math.min(z0, p.z()); z1 = Math.max(z1, p.z()); }
        AiWorld.load(level, x0, z0, x1, z1);
    }

    /**
     * Applies the spec's operations on the selection, one after another, each seeing what the ones before it planned. It walks the selection
     * once per operation and keeps nothing per block beyond the plan itself, so a selection of a million blocks costs what the plan costs.
     */
    private static void applySelection(net.minecraft.server.level.ServerLevel level, EditPlanner plan, BuildSpec spec, Map<String, BlockState> states, java.util.Set<Point> selection, Map<String, Match> matches) {
        for (var op : spec.selectionOps()) {
            var wanted = states.get(op.block());
            var found = op.find() == null ? null : match(level, matches, op.find());
            for (Point at : selection) {
                var planned = plan.changes.get(at);
                var current = planned == null ? plan.state(at) : planned.after().state();
                if (found == null ? current.isAir() : !found.matches(current)) continue;
                plan.put(at, keepProperties(current, wanted, op.keep()));
            }
        }
    }

    /**
     * A block to look for: the block, and only the properties that were written. "oak_stairs[facing=north]" is every north-facing oak
     * stair, whatever its half or shape, and "oak_stairs" is every oak stair. (A parsed state fills the properties that were not written with
     * their defaults, so comparing whole states would only ever find the default ones.)
     */
    record Match(net.minecraft.world.level.block.Block block, Map<net.minecraft.world.level.block.state.properties.Property<?>, Comparable<?>> written) {
        boolean matches(BlockState state) {
            if (!state.is(block)) return false;
            for (var entry : written.entrySet()) if (!sameValue(state, entry.getKey(), entry.getValue())) return false;
            return true;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static boolean sameValue(BlockState state, net.minecraft.world.level.block.state.properties.Property property, Comparable value) {
            return state.hasProperty(property) && state.getValue(property).equals(value);
        }
    }

    private static Match match(net.minecraft.server.level.ServerLevel level, Map<String, Match> cache, String name) {
        return cache.computeIfAbsent(name, n -> {
            try {
                var parsed = net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK), new com.mojang.brigadier.StringReader(concrete(n, null)), false);
                return new Match(parsed.blockState().getBlock(), parsed.properties());
            } catch (Exception e) {
                throw new IllegalArgumentException("Unknown block or state: " + n);
            }
        });
    }

    /**
     * {@code wanted} with the properties of {@code current} that both blocks have, so a stair turned into another stair faces the way it did.
     * {@code names} limits it to those properties; null keeps every one the two share. A property the new block does not have is left out,
     * and one it has with other values (a different kind of "facing") is not copied.
     */
    static BlockState keepProperties(BlockState current, BlockState wanted, List<String> names) {
        var result = wanted;
        for (var property : current.getProperties())
            if ((names == null || names.contains(property.getName())) && result.hasProperty(property)) result = copy(result, current, property);
        return result;
    }

    private static <T extends Comparable<T>> BlockState copy(BlockState to, BlockState from, net.minecraft.world.level.block.state.properties.Property<T> property) {
        return to.setValue(property, from.getValue(property));
    }
}
