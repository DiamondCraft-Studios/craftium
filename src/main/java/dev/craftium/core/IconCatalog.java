package dev.craftium.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Every icon the editor draws, with an id and a category each, so a tool, a setting or a plugin can say which icon it wants by name instead
 * of by how it is drawn. The ids are the contract: once an id is listed it keeps meaning the same icon.
 * <p>
 * Two kinds of icon are not listed here because they are not a fixed set: the item icon of any block, named {@code block:<block id>}
 * (for example {@code block:minecraft:stone}), which is drawn from the game's own item for it.
 */
public final class IconCatalog {
    /** What the item icon of a block is called: this prefix, then the block's id. */
    public static final String BLOCK_PREFIX = "block:";

    public static final String CATEGORY_MATCHING = "Block matching", CATEGORY_SHAPES = "Shapes", CATEGORY_INTERFACE = "Interface";
    /** The icons that are not a tool, a block comparison or a shape. */
    public static final String HARD_HAT = "craftium:ui/hard_hat", CHAIN_LINKED = "craftium:ui/chain_linked", CHAIN_BROKEN = "craftium:ui/chain_broken",
        PIPETTE = "craftium:ui/pipette", CHECK = "craftium:ui/check";

    private static final Map<String, IconSpec> ICONS = build();

    private IconCatalog() {}

    /** Every icon in the catalog, in the order they are listed: the tools by section, the block comparisons, the shapes, then the interface icons. */
    public static List<IconSpec> all() { return List.copyOf(ICONS.values()); }

    public static Optional<IconSpec> find(String id) { return Optional.ofNullable(ICONS.get(id)); }

    /** The categories in listing order. Tool icons are under {@code Tools/<section>}. */
    public static List<String> categories() {
        var categories = new ArrayList<String>();
        for (IconSpec icon : ICONS.values()) if (!categories.contains(icon.category())) categories.add(icon.category());
        return categories;
    }

    public static List<IconSpec> inCategory(String category) { return ICONS.values().stream().filter(icon -> icon.category().equals(category)).toList(); }

    /** Whether {@code id} names an icon that can be drawn: one in the catalog, or the item icon of a block. */
    public static boolean exists(String id) { return ICONS.containsKey(id) || isBlock(id); }

    /** Whether {@code id} names the item icon of a block. The block itself is not checked here: an unknown one is drawn as a label. */
    public static boolean isBlock(String id) { return id != null && id.startsWith(BLOCK_PREFIX) && id.matches("block:[a-z0-9_.-]+:[a-z0-9_./-]+"); }

    /** The block id inside a {@link #isBlock block icon id}. */
    public static String blockId(String id) { return id.substring(BLOCK_PREFIX.length()); }

    public static String block(String blockId) { return BLOCK_PREFIX + blockId; }
    public static String tool(Tool tool) { return "craftium:tool/" + tool.name().toLowerCase(Locale.ROOT); }
    public static String compare(Options.Compare compare) { return "craftium:compare/" + compare.name().toLowerCase(Locale.ROOT); }
    public static String shape(BrushMath.Shape shape) { return "craftium:shape/" + shape.name().toLowerCase(Locale.ROOT); }

    private static Map<String, IconSpec> build() {
        var icons = new LinkedHashMap<String, IconSpec>();
        // The tools, by the section of the Tools panel they are listed under, then the transform tools that are pinned over the viewport.
        for (var section : Tool.SECTIONS.entrySet()) for (Tool tool : section.getValue()) add(icons, tool);
        // Read from the group, not tool.transform(): that asks the ToolCatalog, which asks this class for the tools' icon ids while it is being built.
        for (Tool tool : Tool.values()) if (tool.group.equals("Transform")) add(icons, tool);
        for (Options.Compare compare : Options.Compare.values()) {
            String label = switch (compare) { case ANY -> "Any block"; case BLOCK -> "Same block"; case STATE -> "Same block state"; case TAG -> "Shared block tag"; case NBT -> "Same block entity data"; };
            put(icons, new IconSpec(compare(compare), label, CATEGORY_MATCHING, 24, 24));
        }
        for (BrushMath.Shape shape : BrushMath.Shape.values()) put(icons, new IconSpec(shape(shape), capitalise(shape.name()), CATEGORY_SHAPES, 24, 24));
        put(icons, new IconSpec(HARD_HAT, "Hard hat", CATEGORY_INTERFACE, 19, 19));
        put(icons, new IconSpec(CHAIN_LINKED, "Chain, linked", CATEGORY_INTERFACE, 17, 9));
        put(icons, new IconSpec(CHAIN_BROKEN, "Chain, broken", CATEGORY_INTERFACE, 17, 9));
        put(icons, new IconSpec(PIPETTE, "Pipette", CATEGORY_INTERFACE, 30, 30));
        put(icons, new IconSpec(CHECK, "Check mark", CATEGORY_INTERFACE, 12, 12));
        return java.util.Collections.unmodifiableMap(icons);
    }

    private static void add(Map<String, IconSpec> icons, Tool tool) { put(icons, new IconSpec(tool(tool), tool.label, "Tools/" + tool.group, 24, 24)); }

    private static void put(Map<String, IconSpec> icons, IconSpec icon) {
        if (icons.putIfAbsent(icon.id(), icon) != null) throw new IllegalStateException("Duplicate icon " + icon.id());
    }

    private static String capitalise(String name) { return name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT); }
}
