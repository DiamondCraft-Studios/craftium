package dev.craftium.core;

import java.util.*;

/** Mutable rule blocks for the visual editor; serialized to the same validated mask language. */
public final class MaskTree {
    public static final class Node {
        public String rule;
        public final List<Node> children = new ArrayList<>();

        public Node(String rule) {
            this.rule = rule;
        }

        public boolean group() {
            return Set.of("all", "any", "not").contains(rule) || rule.startsWith("offset(");
        }

        public String expression() {
            if (!group()) return rule;
            if (rule.startsWith("offset("))
                return rule.substring(0, rule.length() - 1) + ","
                        + (children.isEmpty() ? "true" : children.getFirst().expression()) + ")";
            if (children.isEmpty()) return rule.equals("any") ? "false" : rule.equals("not") ? "false" : "true";
            return rule + "("
                    + String.join(",", children.stream().map(Node::expression).toList()) + ")";
        }

        public boolean contains(Node other) {
            return this == other || children.stream().anyMatch(child -> child.contains(other));
        }

        public boolean acceptsChild() {
            return group() && (!(rule.equals("not") || rule.startsWith("offset(")) || children.isEmpty());
        }
    }

    private MaskTree() {}

    public static Node parse(String expression) {
        return parse(expression.isBlank() ? "all(true)" : expression, 0);
    }

    private static Node parse(String expression, int depth) {
        if (depth > 32) throw new IllegalArgumentException("Mask nesting limit: 32");
        String text = expression.trim();
        int opening = text.indexOf('(');
        if (opening < 0) return new Node(text);
        String name = text.substring(0, opening).toLowerCase(Locale.ROOT);
        if (!Set.of("all", "and", "any", "or", "not", "offset").contains(name)) return new Node(text);
        if (!text.endsWith(")")) throw new IllegalArgumentException("Unbalanced rule block");
        var parts = split(text.substring(opening + 1, text.length() - 1));
        if (name.equals("offset")) {
            if (parts.size() != 4) throw new IllegalArgumentException("Offset needs three coordinates and a rule");
            var result = new Node("offset(" + String.join(",", parts.subList(0, 3)) + ")");
            result.children.add(parse(parts.get(3), depth + 1));
            return result;
        }
        var result = new Node(name.equals("and") ? "all" : name.equals("or") ? "any" : name);
        if (name.equals("not") && parts.size() != 1) throw new IllegalArgumentException("NOT needs one rule");
        for (String part : parts) result.children.add(parse(part, depth + 1));
        return result;
    }

    private static List<String> split(String text) {
        var result = new ArrayList<String>();
        int parentheses = 0, brackets = 0, start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') parentheses++;
            if (c == ')') parentheses--;
            if (c == '[') brackets++;
            if (c == ']') brackets--;
            if (parentheses < 0 || brackets < 0) throw new IllegalArgumentException("Unbalanced rule");
            if (c == ',' && parentheses == 0 && brackets == 0) {
                result.add(text.substring(start, i).trim());
                start = i + 1;
            }
        }
        if (parentheses != 0 || brackets != 0) throw new IllegalArgumentException("Unbalanced rule");
        if (start < text.length()) result.add(text.substring(start).trim());
        return result;
    }

    public static boolean move(Node root, Node source, Node destination) {
        if (source == root || source.contains(destination) || !destination.acceptsChild()) return false;
        Node parent = parent(root, source);
        if (parent == null) return false;
        parent.children.remove(source);
        destination.children.add(source);
        return true;
    }

    public static Node parent(Node root, Node child) {
        for (Node node : root.children) {
            if (node == child) return root;
            Node result = parent(node, child);
            if (result != null) return result;
        }
        return null;
    }
}
