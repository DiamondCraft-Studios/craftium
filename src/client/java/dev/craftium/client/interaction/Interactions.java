package dev.craftium.client.interaction;

import dev.craftium.core.InteractionKind;
import dev.craftium.core.Tool;

/** The interaction for each {@link InteractionKind}, and for each tool by way of the kind its spec names. */
public final class Interactions {
    private static final Interaction CLICK = new ClickInteraction(), POINTS = new PointsInteraction(), BOX = new BoxInteraction(),
        SHIFT_POINTS = new ShiftPointsInteraction(), ANCHOR_PAIR = new AnchorPairInteraction();

    private Interactions() {}

    public static Interaction of(Tool tool) { return of(tool.spec().interaction()); }

    public static Interaction of(InteractionKind kind) {
        return switch (kind) {
            case CLICK -> CLICK;
            case POINTS -> POINTS;
            case BOX -> BOX;
            case SHIFT_POINTS -> SHIFT_POINTS;
            case ANCHOR_PAIR -> ANCHOR_PAIR;
        };
    }
}
