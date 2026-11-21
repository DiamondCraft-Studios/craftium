package dev.craftium.server;

import dev.craftium.core.*;
import java.util.*;

final class SelectionTransforms {
    private SelectionTransforms() {}

    static Set<Point> apply(String action, EditPlanner p) {
        var source = p.session.selection.points();
        if (source.isEmpty()) throw new IllegalArgumentException("Create a selection first");
        var o = p.o;
        var result = new LinkedHashSet<Point>();
        Point min = p.session.selection.min(), max = p.session.selection.max();
        switch (action) {
            case "selection_filter" -> {
                for (Point at : source) if (AdvancedTools.matches(p, p.material, at) && p.accepted(at)) result.add(at);
            }
            case "selection_bounds" -> result.addAll(Selection.box(min, max));
            case "selection_trim" -> {
                for (Point at : source) if (!p.state(at).isAir()) result.add(at);
                if (result.isEmpty()) throw new IllegalArgumentException("The selection is only air");
            }
            case "selection_scale" -> {
                Point end = new Point(
                        min.x() + (int) Math.ceil((max.x() - min.x() + 1) * o.sizeX) - 1,
                        min.y() + (int) Math.ceil((max.y() - min.y() + 1) * o.sizeY) - 1,
                        min.z() + (int) Math.ceil((max.z() - min.z() + 1) * o.sizeZ) - 1);
                for (Point at : Selection.box(min, end)) {
                    Point original = new Point(
                            min.x() + (int) ((at.x() - min.x()) / o.sizeX),
                            min.y() + (int) ((at.y() - min.y()) / o.sizeY),
                            min.z() + (int) ((at.z() - min.z()) / o.sizeZ));
                    if (source.contains(original)) result.add(at);
                }
            }
            case "selection_gizmo" -> {
                var local = new HashSet<Point>();
                for (Point at : source) local.add(at.subtract(min));
                var pivot = new Geometry.Vector(
                        (max.x() - min.x()) / 2d, (max.y() - min.y()) / 2d, (max.z() - min.z()) / 2d);
                double lx = Double.POSITIVE_INFINITY, ly = lx, lz = lx, hx = -lx, hy = -lx, hz = -lx;
                for (double x : new double[] {-.5, max.x() - min.x() + .5})
                    for (double y : new double[] {-.5, max.y() - min.y() + .5})
                        for (double z : new double[] {-.5, max.z() - min.z() + .5}) {
                            var v = Geometry.rotate(
                                    new Geometry.Vector(
                                            (x - pivot.x()) * o.sizeX,
                                            (y - pivot.y()) * o.sizeY,
                                            (z - pivot.z()) * o.sizeZ),
                                    o.yaw,
                                    o.pitch,
                                    o.roll);
                            lx = Math.min(lx, v.x());
                            ly = Math.min(ly, v.y());
                            lz = Math.min(lz, v.z());
                            hx = Math.max(hx, v.x());
                            hy = Math.max(hy, v.y());
                            hz = Math.max(hz, v.z());
                        }
                Point end = new Point(
                        (int) Math.ceil(hx - lx - 1e-8) - 1,
                        (int) Math.ceil(hy - ly - 1e-8) - 1,
                        (int) Math.ceil(hz - lz - 1e-8) - 1);
                for (Point at : Selection.box(new Point(0, 0, 0), end)) {
                    var v = Geometry.inverseRotate(
                            new Geometry.Vector(lx + at.x() + .5, ly + at.y() + .5, lz + at.z() + .5),
                            o.yaw,
                            o.pitch,
                            o.roll);
                    Point sample = new Point(
                            (int) Math.round(v.x() / o.sizeX + pivot.x()),
                            (int) Math.round(v.y() / o.sizeY + pivot.y()),
                            (int) Math.round(v.z() / o.sizeZ + pivot.z()));
                    if (local.contains(sample))
                        result.add(min.add(at.x() + o.offsetX, at.y() + o.offsetY, at.z() + o.offsetZ));
                }
            }
            case "selection_distort" -> {
                var noise = new NoiseField(o);
                for (Point at : source) {
                    int x = (int) Math.round(noise.signed(at) * o.strength),
                            y = (int) Math.round(noise.signed(at.add(137, 19, 71)) * o.strength),
                            z = (int) Math.round(noise.signed(at.add(31, 151, 43)) * o.strength);
                    result.add(at.add(x, y, z));
                }
            }
            case "selection_smooth" -> {
                int radius = Math.min(3, (int) Math.ceil(o.smoothing * 2));
                double total = 0;
                var weights = new LinkedHashMap<Point, Double>();
                for (int x = -radius; x <= radius; x++)
                    for (int y = -radius; y <= radius; y++)
                        for (int z = -radius; z <= radius; z++) {
                            double weight = Math.exp(-(x * x + y * y + z * z) / (2 * o.smoothing * o.smoothing));
                            weights.put(new Point(x, y, z), weight);
                            total += weight;
                        }
                for (Point at : Selection.box(min.add(-radius, -radius, -radius), max.add(radius, radius, radius))) {
                    double sum = 0;
                    for (var e : weights.entrySet()) {
                        Point d = e.getKey();
                        if (source.contains(at.add(d.x(), d.y(), d.z()))) sum += e.getValue();
                    }
                    if (sum / total >= o.threshold) result.add(at);
                }
            }
            default -> throw new IllegalArgumentException("Unknown selection transform");
        }
        for (Point at : result) p.validate(at);
        return result;
    }

    static String analyze(EditPlanner p) {
        if (p.session.selection.size() == 0) throw new IllegalArgumentException("Create a selection first");
        var counts = new HashMap<String, Integer>();
        for (Point at : p.session.selection.points()) {
            p.validate(at);
            String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(p.state(at).getBlock())
                    .toString();
            counts.merge(id, 1, Integer::sum);
        }
        var rows = new ArrayList<>(counts.entrySet());
        rows.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        var report = new StringBuilder("Block type                         Count      Distribution\n\n");
        for (var row : rows) {
            report.append(String.format(
                    Locale.ROOT,
                    "%-38s %8d   %6.2f%%\n",
                    row.getKey(),
                    row.getValue(),
                    row.getValue() * 100d / p.session.selection.size()));
            if (report.length() > 60_000) {
                report.append("Additional block types omitted from this display.\n");
                break;
            }
        }
        return report.append("\nTotal: ")
                .append(p.session.selection.size())
                .append(" blocks, ")
                .append(counts.size())
                .append(" block types")
                .toString();
    }
}
