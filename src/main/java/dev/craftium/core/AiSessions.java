package dev.craftium.core;

/**
 * The rules for the AI sessions of one player: several AI apps (or several Claude sessions) can build at once, each with a workspace of its
 * own. A session is named by the id its AI app made up; an id of "" is the one session of an app that predates sessions. Pure, so it needs no game.
 */
public final class AiSessions {
    private AiSessions() {}

    /** The most sessions one player can have at once. A further one is refused until another is forgotten, stopped or has expired. */
    public static final int MAX_SESSIONS = 6;
    /** How long a session may go without a request before it is dropped, in milliseconds: one hour. */
    public static final long IDLE_MS = 60L * 60L * 1000L;
    /** How long a session is kept in mind as having built somewhere, for telling a later build that it overlaps, in milliseconds: ten minutes. */
    public static final long RECENT_MS = 10L * 60L * 1000L;
    public static final int MAX_ID = 40;
    public static final int MAX_LABEL = 60;

    /** The id as it is kept: letters, digits, '-' and '_' only, at most {@link #MAX_ID} of them. Anything else, or nothing, is the default session, "". */
    public static String cleanId(String id) {
        if (id == null) return "";
        var out = new StringBuilder();
        for (int i = 0; i < id.length() && out.length() < MAX_ID; i++) {
            char c = id.charAt(i);
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_') out.append(c);
            else return ""; // an id that is not plain is not trusted to be anyone's
        }
        return out.toString();
    }

    /** A label as it is shown: control characters dropped, runs of blanks made one, trimmed, and cut to {@link #MAX_LABEL}. */
    public static String cleanLabel(String label) {
        if (label == null) return "";
        var out = new StringBuilder();
        boolean blank = true;
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (Character.isISOControl(c) || c == '§') continue; // '§' would start a colour code in the game's text
            if (Character.isWhitespace(c)) { if (!blank) out.append(' '); blank = true; continue; }
            out.append(c);
            blank = false;
        }
        String text = out.toString().strip();
        return text.length() > MAX_LABEL ? text.substring(0, MAX_LABEL).strip() : text;
    }

    /** Whether a session whose last request was at {@code lastActive} has been quiet for too long at {@code now}. A busy session never expires. */
    public static boolean expired(long lastActive, long now, boolean busy) {
        return !busy && now - lastActive > IDLE_MS;
    }

    /** The box a build fills, corners included. */
    public record Region(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public boolean intersects(Region o) {
            return minX <= o.maxX && o.minX <= maxX && minY <= o.maxY && o.minY <= maxY && minZ <= o.maxZ && o.minZ <= maxZ;
        }
        public int sizeX() { return maxX - minX + 1; }
        public int sizeY() { return maxY - minY + 1; }
        public int sizeZ() { return maxZ - minZ + 1; }
        public Point center() { return new Point((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2); }
        /** "x1,y1,z1 to x2,y2,z2" */
        public String text() { return minX + "," + minY + "," + minZ + " to " + maxX + "," + maxY + "," + maxZ; }
    }

    /** The smallest box around these points, or null when there are none. */
    public static Region around(Iterable<Point> points) {
        int x1 = Integer.MAX_VALUE, y1 = Integer.MAX_VALUE, z1 = Integer.MAX_VALUE, x2 = Integer.MIN_VALUE, y2 = Integer.MIN_VALUE, z2 = Integer.MIN_VALUE;
        boolean any = false;
        for (var p : points) {
            any = true;
            x1 = Math.min(x1, p.x()); y1 = Math.min(y1, p.y()); z1 = Math.min(z1, p.z());
            x2 = Math.max(x2, p.x()); y2 = Math.max(y2, p.y()); z2 = Math.max(z2, p.z());
        }
        return any ? new Region(x1, y1, z1, x2, y2, z2) : null;
    }
}
