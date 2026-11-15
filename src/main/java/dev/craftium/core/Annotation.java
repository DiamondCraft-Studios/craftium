package dev.craftium.core;

import java.util.UUID;

public record Annotation(
        UUID id,
        String mode,
        Point from,
        Point to,
        String text,
        int color,
        int width,
        java.util.List<Point> points,
        java.util.List<Integer> pixels,
        int imageWidth,
        int imageHeight,
        double imageScale,
        Point normal) {
    public Annotation(UUID id, String mode, Point from, Point to, String text, int color, int width) {
        this(
                id,
                mode,
                from,
                to,
                text,
                color,
                width,
                java.util.List.of(),
                java.util.List.of(),
                0,
                0,
                4,
                new Point(0, 0, 1));
    }

    public record Change(Annotation before, Annotation after) {
        public UUID id() {
            return before == null ? after.id() : before.id();
        }
    }
}
