package dev.craftium.client.interaction;

import dev.craftium.client.compat.Overlay;
import dev.craftium.core.Tool;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;

/** The use button sends the request for the block under the cursor, and a held button repeats it. Most tools work this way. */
final class ClickInteraction implements Interaction {
    @Override public boolean use(EditorContext editor) { return false; }

    /** The biome under the cursor, named over the block, for the two tools that paint biomes when the option is on; then the brush outline. */
    @Override public void drawWorld(EditorContext editor, Minecraft mc, BlockHitResult hit) {
        var o = editor.options();
        if (hit != null && o.visualizeBiome && (o.tool == Tool.BIOME_PAINTER || o.tool == Tool.CLENTAMINATOR)) {
            String biome = mc.level.getBiome(hit.getBlockPos()).unwrapKey().map(k -> dev.craftium.compat.Compat.keyName(k)).orElse("Unknown biome");
            Overlay.text(biome, hit.getBlockPos().above(2), 0xffabefb7, 0xbb151c26, .025f);
        }
        Interaction.super.drawWorld(editor, mc, hit);
    }
}
