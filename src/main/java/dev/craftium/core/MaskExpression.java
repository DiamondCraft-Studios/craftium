package dev.craftium.core;

import java.util.*;
import java.util.function.Predicate;

/** Parsed once per operation; nested predicates avoid evaluating arbitrary host code. */
public final class MaskExpression {
    public interface Context {
        boolean block(Point p, String state);

        boolean selection(Point p);

        boolean sky(Point p);

        boolean surface(Point p);

        double angle(Point p);
    }

    private final String input;
    private final Context context;
    private int cursor, depth;

    private MaskExpression(String input, Context context) {
        this.input = input;
        this.context = context;
    }

    public static Predicate<Point> compile(String text, Context context) {
        if (text.isBlank()) return p -> true;
        var parser = new MaskExpression(text, context);
        var result = parser.expression();
        parser.space();
        if (parser.cursor != text.length()) throw parser.error();
        return result;
    }

    private Predicate<Point> expression() {
        if (++depth > 32) throw error();
        String name = word().toLowerCase(Locale.ROOT);
        space();
        Predicate<Point> result;
        if (Set.of("all", "and", "any", "or", "not").contains(name)) {
            take('(');
            var children = new ArrayList<Predicate<Point>>();
            do {
                children.add(expression());
                space();
            } while (accept(','));
            take(')');
            if (name.equals("not") && children.size() != 1) throw error();
            result = switch (name) {
                case "all", "and" -> p -> children.stream().allMatch(c -> c.test(p));
                case "any", "or" -> p -> children.stream().anyMatch(c -> c.test(p));
                default -> p -> !children.getFirst().test(p);
            };
        } else if (name.equals("offset")) {
            take('(');
            int x = integer();
            take(',');
            int y = integer();
            take(',');
            int z = integer();
            take(',');
            var child = expression();
            take(')');
            result = p -> child.test(p.add(x, y, z));
        } else if (Set.of("block", "above", "below", "near", "neighbor", "neighbour", "adjacent")
                .contains(name)) {
            take('(');
            String raw = argument();
            take(')');
            int range = 1;
            String block = raw;
            if (name.equals("near")) {
                int bracket = raw.lastIndexOf(']'), comma = raw.lastIndexOf(',');
                if (comma > bracket) {
                    try {
                        range = Integer.parseInt(raw.substring(comma + 1).trim());
                        block = raw.substring(0, comma).trim();
                    } catch (NumberFormatException e) {
                        throw error();
                    }
                    if (range < 1 || range > 8) throw new IllegalArgumentException("Near-mask radius must be 1–8");
                }
            }
            final String state = block;
            final int radius = range;
            result = p -> {
                if (name.equals("block")) return context.block(p, state);
                if (name.equals("above")) return context.block(p.add(0, 1, 0), state);
                if (name.equals("below")) return context.block(p.add(0, -1, 0), state);
                for (int x = -radius; x <= radius; x++)
                    for (int y = -radius; y <= radius; y++)
                        for (int z = -radius; z <= radius; z++) {
                            if (x == 0 && y == 0 && z == 0) continue;
                            if (!name.equals("near") && Math.abs(x) + Math.abs(y) + Math.abs(z) != 1) continue;
                            if (name.equals("adjacent") && y != 0) continue;
                            if (context.block(p.add(x, y, z), state)) return true;
                        }
                return false;
            };
        } else if (name.equals("y")) {
            take('(');
            int min = integer();
            take(',');
            int max = integer();
            take(')');
            result = p -> p.y() >= min && p.y() <= max;
        } else if (name.equals("angle")) {
            take('(');
            int angle = integer();
            take(',');
            int range = integer();
            take(')');
            result = p -> Math.abs(context.angle(p) - angle) <= range;
        } else
            result = switch (name) {
                case "selection", "in_selection" -> context::selection;
                case "sky", "can_see_sky" -> context::sky;
                case "surface" -> context::surface;
                case "true" -> p -> true;
                case "false" -> p -> false;
                default -> throw error();
            };
        depth--;
        return result;
    }

    private String argument() {
        space();
        int start = cursor, brackets = 0;
        while (cursor < input.length()) {
            char c = input.charAt(cursor);
            if (c == '[') brackets++;
            if (c == ']') brackets--;
            if (c == ')' && brackets == 0) break;
            cursor++;
        }
        String result = input.substring(start, cursor).trim();
        if (result.isEmpty()) throw error();
        return result;
    }

    private String word() {
        space();
        int start = cursor;
        while (cursor < input.length()
                && (Character.isLetterOrDigit(input.charAt(cursor)) || input.charAt(cursor) == '_')) cursor++;
        if (start == cursor) throw error();
        return input.substring(start, cursor);
    }

    private int integer() {
        space();
        int start = cursor;
        if (cursor < input.length() && input.charAt(cursor) == '-') cursor++;
        while (cursor < input.length() && Character.isDigit(input.charAt(cursor))) cursor++;
        try {
            return Math.clamp(Integer.parseInt(input.substring(start, cursor)), -4096, 4096);
        } catch (Exception e) {
            throw error();
        }
    }

    private void space() {
        while (cursor < input.length() && Character.isWhitespace(input.charAt(cursor))) cursor++;
    }

    private boolean accept(char c) {
        space();
        if (cursor < input.length() && input.charAt(cursor) == c) {
            cursor++;
            return true;
        }
        return false;
    }

    private void take(char c) {
        if (!accept(c)) throw error();
    }

    private IllegalArgumentException error() {
        return new IllegalArgumentException("Invalid mask near character " + cursor);
    }
}
