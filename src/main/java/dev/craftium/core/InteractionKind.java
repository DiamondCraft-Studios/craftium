package dev.craftium.core;

/**
 * How a tool takes input in the viewport: what the use button does, and how a held button and the keys carry on from there. Every tool has
 * one, named in its {@link ToolSpec}, and the editor looks up the interaction of that kind instead of asking which tool it is.
 */
public enum InteractionKind {
    /** The use button sends the tool's request for the block under the cursor, and a held button repeats it as a stroke. */
    CLICK(true),
    /** Each click adds a control point (up to 64) and Enter builds from them; the extrude key adds a copy of the last point. */
    POINTS(true),
    /** Two clicks mark the opposite corners of a box and each corner can be dragged by its handle; a third click starts over. */
    BOX(true),
    /** Each click sends the request for the block under the cursor, which takes it as an end point; a held button does not send more. */
    LINE(true),
    /** Each click adds an end point to the rulers, and nothing is sent to the server. Built in for the Ruler. */
    RULER(false),
    /** A click sets the pivot at the middle of the block clicked, and a held button keeps moving it. Built in for the Pivot tool. */
    PIVOT(false),
    /** A click notes the face it lands on; in Draw mode a held button collects the blocks it passes over and letting go sends them. Built in for Annotation. */
    ANNOTATION(false),
    /** A click does nothing: the tool works through its handles on the selection. Built in for Move, Rotate and Scale. */
    TRANSFORM(false),
    /** Clicks work as {@link #CLICK} does, and a click with Shift held adds an anchor point instead. Built in for the Gradient Painter. */
    SHIFT_POINTS(false),
    /** The first click sets an anchor; the next ones move a second point and send the request. Built in for the Slope tool. */
    ANCHOR_PAIR(false);

    /** Whether a tool that is not one of the editor's own can choose this kind. */
    public final boolean forPlugins;

    InteractionKind(boolean forPlugins) { this.forPlugins = forPlugins; }
}
