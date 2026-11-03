package dev.craftium.core;

import java.util.*;

public final class Selection {
    public static final int LIMIT = 131_072;
    public enum Mode { REPLACE, ADD, SUBTRACT, INTERSECT }
    private final LinkedHashSet<Point> points = new LinkedHashSet<>();
    private long revision;
    public long revision(){return revision;}
    public Set<Point> points() { return Collections.unmodifiableSet(points); }
    public int size() { return points.size(); }
    public void clear() { points.clear();revision++; }
    /** Replaces the selection outright, as when taking back a selection action. */
    public void restore(Collection<Point> before){points.clear();points.addAll(before);revision++;}
    public boolean contains(Point p) { return points.contains(p); }
    public void combine(Collection<Point> incoming, Mode mode) {
        var next = new LinkedHashSet<>(points);
        switch (mode) {
            case REPLACE -> { next.clear(); next.addAll(incoming); }
            case ADD -> next.addAll(incoming);
            case SUBTRACT -> next.removeAll(incoming);
            case INTERSECT -> next.retainAll(new HashSet<>(incoming));
        }
        if (next.size() > LIMIT) throw new IllegalArgumentException("Selection limit: 131,072 blocks");
        points.clear(); points.addAll(next);revision++;
    }
    public static List<Point> box(Point a, Point b) {
        long sx = Math.abs((long)a.x()-b.x())+1, sy = Math.abs((long)a.y()-b.y())+1, sz = Math.abs((long)a.z()-b.z())+1;
        if (sx > LIMIT || sy > LIMIT || sz > LIMIT || sx*sy*sz > LIMIT)
            throw new IllegalArgumentException("Box exceeds 131,072 blocks");
        var result = new ArrayList<Point>((int)(sx*sy*sz));
        for (int x = Math.min(a.x(),b.x()); x <= Math.max(a.x(),b.x()); x++)
            for (int y = Math.min(a.y(),b.y()); y <= Math.max(a.y(),b.y()); y++)
                for (int z = Math.min(a.z(),b.z()); z <= Math.max(a.z(),b.z()); z++) result.add(new Point(x,y,z));
        return result;
    }
    public Point min() { return bounds(true); }
    public Point max() { return bounds(false); }
    private Point bounds(boolean min) {
        if (points.isEmpty()) return new Point(0,0,0);
        int x = min ? Integer.MAX_VALUE : Integer.MIN_VALUE, y=x, z=x;
        for (Point p : points) {
            x = min ? Math.min(x,p.x()) : Math.max(x,p.x());
            y = min ? Math.min(y,p.y()) : Math.max(y,p.y());
            z = min ? Math.min(z,p.z()) : Math.max(z,p.z());
        }
        return new Point(x,y,z);
    }
}
