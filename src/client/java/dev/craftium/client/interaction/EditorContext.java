package dev.craftium.client.interaction;

import dev.craftium.client.LassoSelection;
import dev.craftium.core.Options;
import dev.craftium.core.Point;
import net.minecraft.world.phys.BlockHitResult;

import java.util.List;

/**
 * What an {@link Interaction} may use of the editor: the options, the block under the cursor, whether Shift is down, and which control point
 * is selected. An interaction is given this and never the editor screen itself, so it can only do what a tool is meant to be able to do.
 */
public interface EditorContext {
    Options options();
    /** The block face under the cursor, or null when the cursor is over sky or over a panel. */
    BlockHitResult target();
    boolean shiftDown();
    /** Sends the editor's own request of that name for the block under the cursor, as a menu item or a shortcut would. */
    void request(String action);
    /** The outline a lasso is drawing on the screen, as the live list the screen also draws it from. */
    List<LassoSelection.Pixel> lasso();
    /** The control point whose gizmo is shown, as an index into the tool's point list, or -1. */
    int selectedNode();
    /** Selects the control point at {@code index} of the tool's point list, so its gizmo is shown. */
    void selectNode(int index);
    /** Whether the pointer at {@code x}, {@code y} is on one of the handles of a point gizmo at {@code at}, so a gizmo that is only shown can still be grabbed. */
    boolean hitsPointHandles(Point at, double x, double y);
}
