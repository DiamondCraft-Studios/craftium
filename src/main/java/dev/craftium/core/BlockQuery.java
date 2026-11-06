package dev.craftium.core;

import java.util.*;
import java.util.regex.Pattern;

/**
 * The search the block chooser understands, written the way JEI's is. Words are separated by spaces and must all match; a word can
 * hold alternatives joined by {@code |}; a leading {@code -} turns a word around; quotes keep spaces in a word; {@code *} stands for any
 * run of characters and {@code ?} for one. A prefix says what a word is looked for in:
 * <ul>
 * <li>none: the block's name or id</li>
 * <li>{@code @} the mod, by id or name</li>
 * <li>{@code #} the tooltip</li>
 * <li>{@code $} the block tags</li>
 * <li>{@code %} the creative tab</li>
 * <li>{@code &} the resource id</li>
 * <li>{@code ^} the colour, by name</li>
 * </ul>
 * Nothing here knows about the game: what a block is looked up as is given through {@link Info}, and only for the parts a search uses.
 */
public final class BlockQuery {
    private BlockQuery() {}

    /** What can be asked of a block. The parts that cost something to find are only asked for when a search uses them. */
    public interface Info {
        String id();
        String name();
        String mod();
        String modName();
        String tooltip();
        Collection<String> tags();
        Collection<String> tabs();
        String colour();
    }

    /** A search ready to be run on blocks. */
    public static final class Query {
        private final List<Term> terms;
        private final String problem;
        private Query(List<Term> terms, String problem) { this.terms = terms; this.problem = problem; }
        /** Whether the text was a whole search; false when a quote was left open, which is still searched as if it were closed. */
        public boolean valid() { return problem == null; }
        public String problem() { return problem == null ? "" : problem; }
        public boolean isEmpty() { return terms.isEmpty(); }
        public boolean matches(Info block) {
            for (Term term : terms) if (!term.matches(block)) return false;
            return true;
        }
        /** Whether any word of the search looks in the given prefix's place and has something to look for. */
        public boolean uses(char prefix) {
            for (Term term : terms) for (Alternative a : term.alternatives()) if (a.prefix() == prefix && !a.text().isEmpty()) return true;
            return false;
        }
    }

    private record Alternative(char prefix, Pattern pattern, String text) {
        boolean matches(Info b) {
            if (text.isEmpty()) return true; // a prefix with nothing after it yet matches everything, so typing "@" does not empty the list
            return switch (prefix) {
                case '@' -> find(b.mod()) || find(b.modName());
                case '#' -> find(b.tooltip());
                case '$' -> any(b.tags());
                case '%' -> any(b.tabs());
                case '&' -> find(b.id());
                case '^' -> find(b.colour());
                default -> find(b.name()) || find(b.id());
            };
        }
        private boolean find(String value) { return value != null && pattern.matcher(value).find(); }
        private boolean any(Collection<String> values) { if (values != null) for (String v : values) if (find(v)) return true; return false; }
    }

    private record Term(boolean negate, List<Alternative> alternatives) {
        boolean matches(Info b) {
            boolean any = false;
            for (Alternative a : alternatives) if (a.matches(b)) { any = true; break; }
            return any != negate;
        }
    }

    private static final String PREFIXES = "@#$%&^";

    public static Query parse(String text) {
        var terms = new ArrayList<Term>();
        String problem = null;
        if (text == null) return new Query(terms, null);
        int i = 0, n = text.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(text.charAt(i))) i++;
            if (i >= n) break;
            // One word: up to the next space that is not inside quotes.
            var word = new StringBuilder();
            boolean quoted = false;
            for (; i < n && (quoted || !Character.isWhitespace(text.charAt(i))); i++) {
                char c = text.charAt(i);
                if (c == '"') quoted = !quoted;
                word.append(c);
            }
            if (quoted) problem = "A quote is not closed";
            boolean negate = word.length() > 1 && word.charAt(0) == '-';
            String body = negate ? word.substring(1) : word.toString();
            var alternatives = new ArrayList<Alternative>();
            for (String part : splitOr(body)) alternatives.add(alternative(part));
            if (!alternatives.isEmpty()) terms.add(new Term(negate, alternatives));
        }
        return new Query(terms, problem);
    }

    /** Splits on {@code |} outside quotes. */
    private static List<String> splitOr(String body) {
        var parts = new ArrayList<String>();
        var current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '"') quoted = !quoted;
            if (c == '|' && !quoted) { parts.add(current.toString()); current.setLength(0); } else current.append(c);
        }
        parts.add(current.toString());
        parts.removeIf(String::isEmpty);
        return parts;
    }

    private static Alternative alternative(String part) {
        char prefix = 0;
        if (!part.isEmpty() && PREFIXES.indexOf(part.charAt(0)) >= 0) { prefix = part.charAt(0); part = part.substring(1); }
        String text = part.replace("\"", "").toLowerCase(Locale.ROOT).trim();
        return new Alternative(prefix, pattern(text), text);
    }

    /** The text as a pattern that finds it anywhere, ignoring case, with {@code *} and {@code ?} as wildcards. */
    private static Pattern pattern(String text) {
        var regex = new StringBuilder();
        var literal = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '*' || c == '?') {
                if (!literal.isEmpty()) { regex.append(Pattern.quote(literal.toString())); literal.setLength(0); }
                regex.append(c == '*' ? ".*" : ".");
            } else literal.append(c);
        }
        if (!literal.isEmpty()) regex.append(Pattern.quote(literal.toString()));
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    }

    /** The colour's name, as a search finds it: a hue for a colourful block, and white, grey or black for the rest. */
    public static String colourName(int rgb) {
        double r = (rgb >> 16 & 255) / 255.0, g = (rgb >> 8 & 255) / 255.0, b = (rgb & 255) / 255.0;
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), delta = max - min;
        if (max < .16) return "black";
        if (delta < .1) return max > .85 ? "white" : "gray grey";
        double hue = max == r ? ((g - b) / delta + 6) % 6 : max == g ? (b - r) / delta + 2 : (r - g) / delta + 4;
        hue *= 60;
        double saturation = delta / max;
        if (hue >= 15 && hue < 45 && max < .7) return "brown";
        if (hue < 15 || hue >= 345) return saturation < .45 && max > .75 ? "pink" : "red";
        if (hue < 45) return "orange";
        if (hue < 70) return "yellow";
        if (hue < 165) return "green";
        if (hue < 200) return "cyan";
        if (hue < 255) return "blue";
        if (hue < 290) return "purple";
        return saturation < .5 ? "pink" : "magenta pink";
    }
}
