package dev.craftium.client.interaction;

import dev.craftium.client.compat.Overlay;
import dev.craftium.core.Tool;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;

/** The use button sends the request for the block under the cursor, and a held button repeats it. Most tools work this way. */
final class ClickInteraction implements Interaction {
    @Override
    public boolean use(EditorContext editor) {
        return false;
    }

    /** Flatten with "From click" on takes its level from the block the stroke starts on, and keeps that level while the button is held. */
    @Override
    public void strokeBegan(EditorContext editor) {
        var o = editor.options();
        var hit = editor.target();
        if (o.tool == Tool.FLATTEN && o.flattenPick && hit != null)
            o.flattenY = hit.getBlockPos().getY();
    }

    /** The biome under the cursor, named over the block, for the two tools that paint biomes when the option is on; then the brush outline. */
    @Override
    public void drawWorld(EditorContext editor, Minecraft mc, BlockHitResult hit) {
        var o = editor.options();
        if (hit != null && o.visualizeBiome && (o.tool == Tool.BIOME_PAINTER || o.tool == Tool.CLENTAMINATOR)) {
            String biome = mc.level
                    .getBiome(hit.getBlockPos())
                    .unwrapKey()
                    .map(k -> dev.craftium.compat.Compat.keyName(k))
                    .orElse("Unknown biome");
            Overlay.text(biome, hit.getBlockPos().above(2), 0xffabefb7, 0xbb151c26, .025f);
        }
        // Flatten with a level typed in: the layer the ground will be brought to, as a flat box over the brush.
        dev.craftium.client.TerrainPreview.draw(hit, o);
        Interaction.super.drawWorld(editor, mc, hit);
    }
}
