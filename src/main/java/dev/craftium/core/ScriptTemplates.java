package dev.craftium.core;

import java.util.*;
import java.util.regex.*;

/** Expands documented brush variables without evaluating injected Lua source. */
public final class ScriptTemplates {
    public record Variable(String kind, String title, String value, double min, double max) {}

    public record Result(String source, boolean once, List<Variable> variables) {}

    private static final Pattern VARIABLE =
            Pattern.compile("\\$(once|int|float|boolean|blockState)(?:\\(([^$]*?)\\))?\\$");

    private ScriptTemplates() {}

    public static Result expand(String source, Map<String, String> overrides) {
        Matcher matcher = VARIABLE.matcher(source);
        var output = new StringBuilder();
        var variables = new LinkedHashMap<String, Variable>();
        boolean once = false;
        while (matcher.find()) {
            String kind = matcher.group(1), replacement = "";
            if (kind.equals("once")) once = true;
            else {
                if (matcher.group(2) == null) throw new IllegalArgumentException("Template variable needs arguments");
                String[] args = arguments(matcher.group(2));
                int required = kind.equals("int") || kind.equals("float") ? 4 : 2;
                if (args.length != required)
                    throw new IllegalArgumentException("Invalid " + kind + " template arguments");
                String title = args[0].trim();
                if (title.isBlank() || title.length() > 80)
                    throw new IllegalArgumentException("Invalid template title");
                String value = overrides.getOrDefault(title, args[1].trim());
                double min = 0, max = 1;
                switch (kind) {
                    case "int", "float" -> {
                        min = number(args[2]);
                        max = number(args[3]);
                        if (min > max) throw new IllegalArgumentException("Template minimum exceeds maximum");
                        double numeric = Math.clamp(number(value), min, max);
                        value = kind.equals("int") ? Long.toString(Math.round(numeric)) : Double.toString(numeric);
                        replacement = value;
                    }
                    case "boolean" -> {
                        if (!value.equals("true") && !value.equals("false"))
                            throw new IllegalArgumentException("Template boolean must be true or false");
                        replacement = value;
                    }
                    case "blockState" -> {
                        if (!value.matches("[a-z0-9_:.\\[\\]=,-]{1,256}"))
                            throw new IllegalArgumentException("Invalid template block identifier");
                        replacement = "blocks[\"" + value + "\"]";
                    }
                    default -> throw new IllegalArgumentException("Unknown template variable");
                }
                var variable = new Variable(kind, title, value, min, max);
                var previous = variables.putIfAbsent(title, variable);
                if (previous != null && !previous.equals(variable))
                    throw new IllegalArgumentException("Template title has conflicting definitions: " + title);
                if (variables.size() > 64) throw new IllegalArgumentException("Maximum 64 script variables");
            }
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return new Result(output.toString(), once, List.copyOf(variables.values()));
    }

    private static String[] arguments(String source) {
        var result = new ArrayList<String>();
        int depth = 0, start = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '[') depth++;
            else if (c == ']' && --depth < 0) throw new IllegalArgumentException("Unbalanced block state");
            else if (c == ',' && depth == 0) {
                result.add(source.substring(start, i));
                start = i + 1;
            }
        }
        if (depth != 0) throw new IllegalArgumentException("Unbalanced block state");
        result.add(source.substring(start));
        return result.toArray(String[]::new);
    }

    private static double number(String value) {
        try {
            double number = Double.parseDouble(value.trim());
            if (!Double.isFinite(number) || Math.abs(number) > 1_000_000) throw new NumberFormatException();
            return number;
        } catch (Exception e) {
            throw new IllegalArgumentException("Template values must be finite numbers within ±1,000,000");
        }
    }
}
