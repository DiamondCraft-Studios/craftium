package dev.craftium.client.interaction;

import dev.craftium.client.ClientState;
import dev.craftium.client.ModifyPreview;
import dev.craftium.core.Point;

import java.util.List;

/**
 * What one kind of tool does with the mouse and keys in the viewport. One instance serves every tool of its {@link dev.craftium.core.InteractionKind};
 * the editor screen routes the input that depends on the tool here, and keeps the input that does not (the camera, the panels, the gizmos).
 */
public interface Interaction {
    /**
     * The use button went down on the world, or a held stroke is repeating, before the use interval and the busy check are applied: it sees every
     * click and every step of a drag. Returns true when the interaction dealt with it and nothing more should happen.
     */
    default boolean beforeUse(EditorContext editor) { return false; }

    /** The use button went down over the world: a stroke begins. */
    default void strokeBegan(EditorContext editor) {}

    /** The use button was let go after a stroke. */
    default void strokeEnded(EditorContext editor) {}

    /**
     * The use button went down on the world, or a held stroke is repeating. Returns true when the interaction dealt with it itself; false
     * lets the click go on to the server as a plain request for the block under the cursor.
     */
    boolean use(EditorContext editor);

    /** Whether holding the use button and moving the cursor repeats {@link #use} for each block it reaches. */
    default boolean repeatsWhenDragged() { return true; }

    /** The control points the handles and the gizmo edit, as the live list they are kept in, or null when the tool has none. */
    default List<Point> points(EditorContext editor) { return null; }

    /** The extrude-point key: adds a copy of the last control point and selects it. Returns true when it did something. */
    default boolean extrudePoint(EditorContext editor) { return false; }

    /** The left button went down at {@code x}, {@code y} in the viewport, before the gizmo sees it: a chance to select the handle that is under the pointer. */
    default void grabHandle(EditorContext editor, double x, double y) {}

    /** A handle was let go of after being dragged, so the points have moved. */
    default void handlesMoved(EditorContext editor) {}

    /**
     * The tool was chosen from the tool list. Choosing a tool drops what another tool left half done: a box selection with corners but no box
     * yet, and a Modify preview with its anchor. A tool that owns one of them keeps its own by overriding this.
     */
    default void chosen(EditorContext editor) { ClientState.boxCorners.clear(); ModifyPreview.edit(); }

    /** Whether the turn keys (rotate, tilt, flip) turn this tool's hover preview before anything is put down. A shape that is down is turned by them whatever tool is chosen. */
    default boolean turnsPlacement() { return false; }

    /** What Enter does when nothing else is waiting: the request that builds what the tool has gathered, or "clear" for a tool that gathers nothing. */
    default String confirmAction() { return "clear"; }

    /** Sends the tool's own request when the editor asks for "use" (Enter, or an Apply button), instead of the plain one. Returns true when it did. */
    default boolean sendUse(EditorContext editor) { return false; }
}
