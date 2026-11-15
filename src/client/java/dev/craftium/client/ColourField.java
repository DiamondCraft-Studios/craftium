package dev.craftium.client;

import dev.craftium.core.*;
import java.util.*;

public final class ColourField {
    private ColourField() {}

    public static void generate() {
        var selection = new ArrayList<>(SelectionCodec.decode(ClientState.status.selectionData()));
        if (selection.isEmpty()) throw new IllegalArgumentException("Create a selection first");
        var blocks = BlockColours.snapshot().stream()
                .filter(e -> (e.flags() & 6) == 6)
                .sorted(Comparator.comparing(BlockColour::block))
                .toList();
        if (selection.size() < blocks.size())
            throw new IllegalArgumentException("Colour field needs at least " + blocks.size() + " selected cells");
        Point min = ClientState.status.min(), max = ClientState.status.max();
        var placements = new ArrayList<ColourPlacement>();
        for (var block : blocks) {
            double[] lab = ColourMath.cielab(block.rgb());
            double x = min.x() + Math.clamp(lab[0] / 100, 0, 1) * (max.x() - min.x()),
                    y = min.y() + Math.clamp((lab[1] + 128) / 255, 0, 1) * (max.y() - min.y()),
                    z = min.z() + Math.clamp((lab[2] + 128) / 255, 0, 1) * (max.z() - min.z());
            int nearest = 0;
            double distance = Double.POSITIVE_INFINITY;
            for (int i = 0; i < selection.size(); i++) {
                Point at = selection.get(i);
                double dx = at.x() - x, dy = at.y() - y, dz = at.z() - z, score = dx * dx + dy * dy + dz * dz;
                if (score < distance) {
                    distance = score;
                    nearest = i;
                    if (score == 0) break;
                }
            }
            Point at = selection.get(nearest);
            selection.set(nearest, selection.getLast());
            selection.removeLast();
            placements.add(new ColourPlacement(at, block.block()));
        }
        ClientState.options.colourField = placements;
        CraftiumClient.send("colour_field", null, null);
        ClientState.options.colourField = new ArrayList<>();
    }
}
