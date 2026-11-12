package dev.craftium.client.interaction;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.client.compat.Overlay;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows the tags of the block under the cursor, the ones the game itself looks at (what keeps fire burning on netherrack, what a pickaxe can mine,
 * and so on). The hover lists the first few above the block; a click lists every block and fluid tag in chat. Nothing is sent to the server.
 */
final class InspectInteraction implements Interaction {
    private static final int HOVER_LINES = 8;
    private static final float SCALE = .02f;
    private static final double LINE = .2;

    @Override public boolean use(EditorContext editor) {
        var hit = editor.target();
        var mc = Minecraft.getInstance();
        if (hit == null || mc.level == null) return true;
        var state = mc.level.getBlockState(hit.getBlockPos());
        var block = tags(state);
        var fluid = fluidTags(state);
        ClientCompat.chat(mc, Component.literal(id(state) + " - " + block.size() + " block tag" + (block.size() == 1 ? "" : "s")));
        for (String tag : block) ClientCompat.chat(mc, Component.literal("  #" + tag));
        if (!fluid.isEmpty()) {
            ClientCompat.chat(mc, Component.literal(fluid.size() + " fluid tag" + (fluid.size() == 1 ? "" : "s")));
            for (String tag : fluid) ClientCompat.chat(mc, Component.literal("  #" + tag));
        }
        return true;
    }

    @Override public boolean repeatsWhenDragged() { return false; }

    @Override public void drawWorld(EditorContext editor, Minecraft mc, BlockHitResult hit) {
        if (hit == null || !editor.toolActive() || mc.level == null) return;
        var pos = hit.getBlockPos();
        var state = mc.level.getBlockState(pos);
        var lines = new ArrayList<String>();
        lines.add(id(state));
        var tags = tags(state);
        for (int i = 0; i < Math.min(HOVER_LINES, tags.size()); i++) lines.add("#" + tags.get(i));
        if (tags.size() > HOVER_LINES) lines.add("+" + (tags.size() - HOVER_LINES) + " more, right-click to list");
        else if (tags.isEmpty()) lines.add("no block tags");
        // The first line is the highest, so the label grows upward from the block instead of into it.
        for (int i = 0; i < lines.size(); i++) {
            var at = new Vec3(pos.getX() + .5, pos.getY() + 1.3 + (lines.size() - 1 - i) * LINE, pos.getZ() + .5);
            Overlay.text(lines.get(i), at, i == 0 ? 0xffffffff : 0xffabefb7, 0xbb151c26, SCALE);
        }
    }

    private static String id(BlockState state) { return dev.craftium.compat.Compat.keyName(BuiltInRegistries.BLOCK.getResourceKey(state.getBlock()).orElseThrow()); }

    /** The tags of the block, sorted. */
    private static List<String> tags(BlockState state) {
        return BuiltInRegistries.BLOCK.wrapAsHolder(state.getBlock()).tags().map(dev.craftium.compat.Compat::tagName).sorted().toList();
    }

    /** The tags of the fluid in the block (water, lava), sorted; none for a block with no fluid. */
    private static List<String> fluidTags(BlockState state) {
        var fluid = state.getFluidState();
        if (fluid.isEmpty()) return List.of();
        return BuiltInRegistries.FLUID.wrapAsHolder(fluid.getType()).tags().map(dev.craftium.compat.Compat::tagName).sorted().toList();
    }
}
