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
    /** Clicks work as {@link #CLICK} does, and a click with Shift held adds an anchor point instead. Built in for the Gradient Painter. */
    SHIFT_POINTS(false),
    /** The first click sets an anchor; the next ones move a second point and send the request. Built in for the Slope tool. */
    ANCHOR_PAIR(false);

    /** Whether a tool that is not one of the editor's own can choose this kind. */
    public final boolean forPlugins;

    InteractionKind(boolean forPlugins) { this.forPlugins = forPlugins; }
}
