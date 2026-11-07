package dev.craftium.client.interaction;

import dev.craftium.core.Options;
import net.minecraft.world.phys.BlockHitResult;

/**
 * What an {@link Interaction} may use of the editor: the options, the block under the cursor, whether Shift is down, and which control point
 * is selected. An interaction is given this and never the editor screen itself, so it can only do what a tool is meant to be able to do.
 */
public interface EditorContext {
    Options options();
    /** The block face under the cursor, or null when the cursor is over sky or over a panel. */
    BlockHitResult target();
    boolean shiftDown();
    /** Selects the control point at {@code index} of the tool's point list, so its gizmo is shown. */
    void selectNode(int index);
}
