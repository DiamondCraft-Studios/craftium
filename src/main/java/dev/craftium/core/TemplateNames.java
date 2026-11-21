package dev.craftium.core;

import java.util.ArrayList;
import java.util.Locale;

/** Names for jigsaw templates made from blueprint files. */
public final class TemplateNames {
    private TemplateNames() {}

    /**
     * A template name from a blueprint's path: lower case, each folder kept as a part of the name, the extension dropped
     * and anything a name cannot hold turned into "_". A path that leaves nothing is named "blueprint".
     */
    public static String fromPath(String path) {
        var parts = new ArrayList<String>();
        String[] segments = path.split("/");
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (i == segments.length - 1) {
                int dot = segment.lastIndexOf('.');
                if (dot > 0) segment = segment.substring(0, dot);
            }
            segment = segment.toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9_.-]+", "_")
                    .replaceAll("^[_.]+|_+$", "");
            if (!segment.isEmpty()) parts.add(segment);
        }
        return parts.isEmpty() ? "blueprint" : String.join("/", parts);
    }
}
