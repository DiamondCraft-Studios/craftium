package dev.craftium.client.interaction;

/**
 * What one kind of tool does with the mouse and keys in the viewport. One instance serves every tool of its {@link dev.craftium.core.InteractionKind};
 * the editor screen routes the input that depends on the tool here, and keeps the input that does not (the camera, the panels, the gizmos).
 */
public interface Interaction {
    /**
     * The use button went down on the world, or a held stroke is repeating. Returns true when the interaction dealt with it itself; false
     * lets the click go on to the server as a plain request for the block under the cursor.
     */
    boolean use(EditorContext editor);

    /** Whether holding the use button and moving the cursor repeats {@link #use} for each block it reaches. */
    default boolean repeatsWhenDragged() { return true; }

    /** Whether the tool's control points are the options' point list, which the handles and the gizmo edit. */
    default boolean usesPoints() { return false; }

    /** The extrude-point key: adds a copy of the last control point and selects it. Returns true when it did something. */
    default boolean extrudePoint(EditorContext editor) { return false; }
}
