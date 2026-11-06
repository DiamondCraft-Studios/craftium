package dev.craftium.client;

import dev.craftium.core.BlockQuery;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/**
 * What the block chooser's search can ask of a block, found from the game's registries. Each part is looked up the first time a search
 * wants it and kept, so a plain name search never reads a tooltip or a tag.
 */
final class BlockSearch {
    private BlockSearch() {}
    private static final Map<String, Info> CACHE = new HashMap<>();
    private static final Map<String, String> MOD_NAMES = new HashMap<>();
    private static boolean tabsBuilt;

    /** The block as the search sees it. Air and blocks the registry does not know are still searchable by name. */
    static BlockQuery.Info of(String id, String name) {
        return CACHE.computeIfAbsent(id, key -> new Info(key, name));
    }

    /** Forgets what was found, for when the registries may have changed (a world with other data packs). */
    static void clear() { CACHE.clear(); MOD_NAMES.clear(); tabsBuilt = false; }

    private static String nameOfMod(String namespace) {
        return MOD_NAMES.computeIfAbsent(namespace, ns -> net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(ns).map(c -> c.getMetadata().getName()).orElse(ns));
    }

    /** The creative tabs hold nothing until the game has built them, which it does for the creative inventory; this does it once for the search. */
    private static void buildTabs() {
        if (tabsBuilt) return;
        tabsBuilt = true;
        var mc = Minecraft.getInstance();
        try {
            if (mc.level != null) net.minecraft.world.item.CreativeModeTabs.tryRebuildTabContents(mc.level.enabledFeatures(), true, mc.level.registryAccess());
        } catch (RuntimeException ignored) {
            // The tabs stay empty and a search by tab finds nothing, which is better than a search that fails.
        }
    }

    private static final class Info implements BlockQuery.Info {
        private final String id, name;
        private String mod, modName, tooltip, colour;
        private List<String> tags, tabs;
        Info(String id, String name) { this.id = id; this.name = name; }
        private net.minecraft.world.level.block.Block block() {
            var key = dev.craftium.compat.Compat.tryParse(id.split("\\[", 2)[0]);
            return key == null ? null : BuiltInRegistries.BLOCK.getOptional(key).orElse(null);
        }
        public String id() { return id; }
        public String name() { return name; }
        public String mod() { if (mod == null) { int colon = id.indexOf(':'); mod = colon < 0 ? "minecraft" : id.substring(0, colon); } return mod; }
        public String modName() { if (modName == null) modName = nameOfMod(mod()); return modName; }
        public String tooltip() {
            if (tooltip != null) return tooltip;
            var b = block();
            var lines = new StringBuilder(name);
            if (b != null) {
                ItemStack stack = b.asItem().getDefaultInstance();
                var mc = Minecraft.getInstance();
                if (!stack.isEmpty()) {
                    try {
                        for (var line : stack.getTooltipLines(net.minecraft.world.item.Item.TooltipContext.EMPTY, mc.player, net.minecraft.world.item.TooltipFlag.Default.NORMAL))
                            lines.append('\n').append(line.getString());
                    } catch (RuntimeException ignored) { /* the name alone is the tooltip */ }
                }
            }
            return tooltip = lines.toString();
        }
        public Collection<String> tags() {
            if (tags != null) return tags;
            var b = block();
            var found = new ArrayList<String>();
            if (b != null) BuiltInRegistries.BLOCK.wrapAsHolder(b).tags().forEach(tag -> found.add(tag.location().toString()));
            return tags = found;
        }
        public Collection<String> tabs() {
            if (tabs != null) return tabs;
            var b = block();
            var found = new ArrayList<String>();
            if (b != null) {
                ItemStack stack = b.asItem().getDefaultInstance();
                if (!stack.isEmpty()) {
                    buildTabs();
                    for (var tab : BuiltInRegistries.CREATIVE_MODE_TAB) if (tab.contains(stack)) found.add(tab.getDisplayName().getString());
                }
            }
            return tabs = found;
        }
        public String colour() {
            if (colour == null) { var entry = BlockColours.get(id); colour = entry == null ? "" : BlockQuery.colourName(entry.rgb()); }
            return colour;
        }
    }
}
