package dev.craftium.core;

import static dev.craftium.core.Geometry.*;

import dev.craftium.core.Geometry.Vector;
import java.util.*;

/** QuickHull with per-face outside sets, followed by bounded voxel rasterization. */
public final class ConvexHull {
    private static final double EPS = 1e-7;

    private record Edge(int a, int b) {}

    private static final class Face {
        int a, b, c;
        Vector origin, normal;
        final List<Integer> outside = new ArrayList<>();

        Face(int a, int b, int c, List<Point> points, Vector inside) {
            this.a = a;
            this.b = b;
            this.c = c;
            origin = vector(points.get(a));
            normal = vector(points.get(b))
                    .subtract(origin)
                    .cross(vector(points.get(c)).subtract(origin));
            normal = normal.scale(1 / normal.length());
            if (distance(inside) > 0) {
                int swap = this.b;
                this.b = this.c;
                this.c = swap;
                normal = normal.scale(-1);
            }
        }

        double distance(Vector p) {
            return p.subtract(origin).dot(normal);
        }
    }

    private ConvexHull() {}

    public static Set<Point> fill(Collection<Point> input) {
        var points = new ArrayList<>(new LinkedHashSet<>(input));
        if (points.size() < 2) return new LinkedHashSet<>(points);
        Point low = new Point(
                points.stream().mapToInt(Point::x).min().orElse(0),
                points.stream().mapToInt(Point::y).min().orElse(0),
                points.stream().mapToInt(Point::z).min().orElse(0));
        Point high = new Point(
                points.stream().mapToInt(Point::x).max().orElse(0),
                points.stream().mapToInt(Point::y).max().orElse(0),
                points.stream().mapToInt(Point::z).max().orElse(0));
        var box = Selection.box(low, high);
        int a = 0, b = 0, c = 0, d = 0;
        double far = -1;
        for (int i = 1; i < points.size(); i++) {
            Point p = points.get(i), q = points.get(a);
            if (p.x() < q.x() || p.x() == q.x() && (p.y() < q.y() || p.y() == q.y() && p.z() < q.z())) a = i;
        }
        for (int i = 0; i < points.size(); i++)
            if (points.get(a).distanceSquared(points.get(i)) > far) {
                far = points.get(a).distanceSquared(points.get(i));
                b = i;
            }
        Vector line = vector(points.get(b)).subtract(vector(points.get(a)));
        far = -1;
        for (int i = 0; i < points.size(); i++) {
            double distance = vector(points.get(i))
                    .subtract(vector(points.get(a)))
                    .cross(line)
                    .length();
            if (distance > far) {
                far = distance;
                c = i;
            }
        }
        if (far < EPS) return new LinkedHashSet<>(BrushMath.line(points.get(a), points.get(b)));
        Vector normal = vector(points.get(b))
                .subtract(vector(points.get(a)))
                .cross(vector(points.get(c)).subtract(vector(points.get(a))));
        normal = normal.scale(1 / normal.length());
        far = -1;
        for (int i = 0; i < points.size(); i++) {
            double distance = Math.abs(
                    vector(points.get(i)).subtract(vector(points.get(a))).dot(normal));
            if (distance > far) {
                far = distance;
                d = i;
            }
        }
        if (far < EPS) return planar(points, box, normal);
        Vector inside = vector(points.get(a))
                .add(vector(points.get(b)))
                .add(vector(points.get(c)))
                .add(vector(points.get(d)))
                .scale(.25);
        var faces = new ArrayList<Face>(List.of(
                new Face(a, b, c, points, inside),
                new Face(a, d, b, points, inside),
                new Face(b, d, c, points, inside),
                new Face(c, d, a, points, inside)));
        for (int i = 0; i < points.size(); i++) assign(i, points, faces);
        while (true) {
            Face face =
                    faces.stream().filter(f -> !f.outside.isEmpty()).findFirst().orElse(null);
            if (face == null) break;
            int eye = face.outside.getFirst();
            double maximum = face.distance(vector(points.get(eye)));
            for (int i : face.outside) {
                double distance = face.distance(vector(points.get(i)));
                if (distance > maximum) {
                    maximum = distance;
                    eye = i;
                }
            }
            var horizon = new LinkedHashMap<Long, Edge>();
            var orphaned = new HashSet<Integer>();
            Vector vertex = vector(points.get(eye));
            for (var iterator = faces.iterator(); iterator.hasNext(); ) {
                Face visible = iterator.next();
                if (visible.distance(vertex) <= EPS) continue;
                for (Edge edge : List.of(
                        new Edge(visible.a, visible.b),
                        new Edge(visible.b, visible.c),
                        new Edge(visible.c, visible.a))) {
                    long key = ((long) Math.min(edge.a, edge.b) << 32) | (Math.max(edge.a, edge.b) & 0xffffffffL);
                    if (horizon.containsKey(key)) horizon.remove(key);
                    else horizon.put(key, edge);
                }
                orphaned.addAll(visible.outside);
                iterator.remove();
            }
            var created = new ArrayList<Face>();
            for (Edge edge : horizon.values()) created.add(new Face(edge.a, edge.b, eye, points, inside));
            faces.addAll(created);
            orphaned.remove(eye);
            for (int i : orphaned) assign(i, points, created);
            if (faces.size() > 4096) throw new IllegalArgumentException("Convex hull is too complex");
        }
        var result = new LinkedHashSet<Point>();
        for (Point p : box) {
            Vector v = vector(p);
            boolean accept = true;
            for (Face face : faces)
                if (face.distance(v) > EPS) {
                    accept = false;
                    break;
                }
            if (accept) result.add(p);
        }
        return result;
    }

    private static void assign(int index, List<Point> points, List<Face> faces) {
        Face best = null;
        double distance = EPS;
        Vector p = vector(points.get(index));
        for (Face face : faces) {
            double candidate = face.distance(p);
            if (candidate > distance) {
                distance = candidate;
                best = face;
            }
        }
        if (best != null) best.outside.add(index);
    }

    private static Set<Point> planar(List<Point> points, Collection<Point> box, Vector normal) {
        int drop = Math.abs(normal.x()) > Math.abs(normal.y())
                ? (Math.abs(normal.x()) > Math.abs(normal.z()) ? 0 : 2)
                : (Math.abs(normal.y()) > Math.abs(normal.z()) ? 1 : 2);
        Comparator<Point> order =
                Comparator.comparingInt((Point p) -> u(p, drop)).thenComparingInt(p -> v(p, drop));
        var sorted = new ArrayList<>(points);
        sorted.sort(order);
        var hull = new ArrayList<Point>();
        for (Point p : sorted) {
            while (hull.size() > 1 && cross(hull.get(hull.size() - 2), hull.getLast(), p, drop) <= 0) hull.removeLast();
            hull.add(p);
        }
        int lower = hull.size();
        for (int i = sorted.size() - 2; i >= 0; i--) {
            Point p = sorted.get(i);
            while (hull.size() > lower && cross(hull.get(hull.size() - 2), hull.getLast(), p, drop) <= 0)
                hull.removeLast();
            hull.add(p);
        }
        hull.removeLast();
        var result = new LinkedHashSet<Point>();
        for (Point p : box) {
            if (Math.abs(vector(p).subtract(vector(points.getFirst())).dot(normal)) > EPS) continue;
            boolean inside = true;
            for (int i = 0; i < hull.size(); i++)
                if (cross(hull.get(i), hull.get((i + 1) % hull.size()), p, drop) < 0) {
                    inside = false;
                    break;
                }
            if (inside) result.add(p);
        }
        return result;
    }

    private static int u(Point p, int drop) {
        return drop == 0 ? p.y() : p.x();
    }

    private static int v(Point p, int drop) {
        return drop == 2 ? p.y() : p.z();
    }

    private static long cross(Point a, Point b, Point c, int drop) {
        return (long) (u(b, drop) - u(a, drop)) * (v(c, drop) - v(a, drop))
                - (long) (v(b, drop) - v(a, drop)) * (u(c, drop) - u(a, drop));
    }
}
