package dev.craftium.client;

import dev.craftium.core.Options;
import dev.craftium.core.Tool;
import java.lang.reflect.Field;
import java.util.*;

/**
 * Keeps the brush options of each tool apart. The options live in one {@link Options}, so a value set for one tool (Elevation's Lower, which is the
 * invert option) would otherwise still be there when another tool (Shape, which then carves) is chosen. Choosing a tool puts away the values of the
 * one that was in use and brings back the ones this tool last had, or the defaults the first time. The Magic Wand's settings are not here: they are
 * shared on purpose, with the Builder's magic select, and so are the other fields that are not listed.
 */
public final class ToolOptionMemory {
    /** The options a tool has of its own. Names of fields of {@link Options}. */
    static final List<String> OWN = List.of(
            "shape",
            "radius",
            "strength",
            "noiseScale",
            "density",
            "seed",
            "invert",
            "hollow",
            "outline",
            "surfaceOnly",
            "keepExisting",
            "pasteAir",
            "outlineThickness",
            "pixelPerfect",
            "strokeAlign",
            "threshold",
            "smoothing",
            "exposedFaces",
            "roughenRatio",
            "maskY",
            "shatterWidth",
            "shatterAxis",
            "meldStrength",
            "denoise",
            "noise",
            "noise3D",
            "distortSmoothEdges",
            "distortSeparate",
            "distortX",
            "distortY",
            "distortZ",
            "distortDistance",
            "fractureMaterial");

    private static final Map<String, Field> FIELDS = new LinkedHashMap<>();
    private static final Map<String, Map<String, Object>> KEPT = new HashMap<>();

    static {
        for (var name : OWN)
            try {
                var f = Options.class.getField(name);
                FIELDS.put(name, f);
            } catch (NoSuchFieldException e) {
                throw new IllegalStateException("Options has no field " + name, e);
            }
    }

    private ToolOptionMemory() {}

    /** The tools that keep the same options between them: none, each is its own (the Magic Wand's are not among the fields above). */
    static String groupOf(Tool tool) {
        return tool.name();
    }

    /** Puts away the options of the tool now in {@code o}, and brings back those of {@code to}. The caller sets the tool. */
    public static void switchTo(Options o, Tool to) {
        String from = held != null ? held : groupOf(o.tool);
        // A shape waiting to be built is made from the Shape tool's options, so Move, Rotate and Scale keep them
        // instead of bringing their own (which would turn the shape into whatever they last had, a sphere).
        String next = ShapeObject.active() && from.equals(groupOf(Tool.SHAPE)) && to.transform() && to != Tool.PIVOT
                ? from
                : groupOf(to);
        held = next;
        if (from.equals(next)) return;
        KEPT.put(from, read(o));
        var back = KEPT.get(next);
        write(o, back != null ? back : read(new Options()));
    }
    /** The tool whose options are in the {@link Options} now, when that is not the tool in use: a transform tool working on a waiting shape. */
    private static String held;

    private static Map<String, Object> read(Options o) {
        var values = new HashMap<String, Object>();
        try {
            for (var e : FIELDS.entrySet()) values.put(e.getKey(), e.getValue().get(o));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        return values;
    }

    private static void write(Options o, Map<String, Object> values) {
        try {
            for (var e : FIELDS.entrySet()) e.getValue().set(o, values.get(e.getKey()));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
