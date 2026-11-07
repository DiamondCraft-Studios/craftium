package dev.craftium.client.interaction;

import dev.craftium.core.InteractionKind;
import dev.craftium.core.Tool;

/** The interaction for each {@link InteractionKind}, and for each tool by way of the kind its spec names. */
public final class Interactions {
    private static final Interaction CLICK = new ClickInteraction(), POINTS = new PointsInteraction(), BOX = new BoxInteraction(), LINE = new LineInteraction(),
        SHAPE_PLACEMENT = new ShapeInteraction(), MODIFY_ANCHOR = new ModifyInteraction(), RULER = new RulerInteraction(), PIVOT = new PivotInteraction(), ANNOTATION = new AnnotationInteraction(), TRANSFORM = new TransformInteraction(),
        SHIFT_POINTS = new ShiftPointsInteraction(), ANCHOR_PAIR = new AnchorPairInteraction();

    private Interactions() {}

    public static Interaction of(Tool tool) { return of(tool.spec().interaction()); }

    public static Interaction of(InteractionKind kind) {
        return switch (kind) {
            case CLICK -> CLICK;
            case POINTS -> POINTS;
            case BOX -> BOX;
            case SHAPE_PLACEMENT -> SHAPE_PLACEMENT;
            case MODIFY_ANCHOR -> MODIFY_ANCHOR;
            case LINE -> LINE;
            case RULER -> RULER;
            case PIVOT -> PIVOT;
            case ANNOTATION -> ANNOTATION;
            case TRANSFORM -> TRANSFORM;
            case SHIFT_POINTS -> SHIFT_POINTS;
            case ANCHOR_PAIR -> ANCHOR_PAIR;
        };
    }
}
