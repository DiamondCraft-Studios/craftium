package dev.craftium.net;

import dev.craftium.core.IconCatalog;
import dev.craftium.core.InteractionKind;
import dev.craftium.core.PropertySpec;
import dev.craftium.core.Tool;
import dev.craftium.core.ToolSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Turns the tools plugins add into what is sent to a client and back. The server describes a {@link ToolSpec}; the client rebuilds one it can list
 * and draw a panel for, and leaves out any tool it cannot trust or understand (a malformed id, an interaction that is not for plugins, a setting that is
 * not valid, an icon it does not have gets a plain one), so one bad tool never costs the player the rest.
 */
public final class PluginToolCodec {
    private PluginToolCodec() {}

    /** The most tools a client takes in, and the most settings it takes for one: a list longer than anything a plugin set would reasonably be is cut. */
    public static final int MAX_TOOLS = 64, MAX_PROPERTIES = 32;
    /** The section a tool is listed under when its plugin did not say. */
    public static final String DEFAULT_SECTION = "Plugins";

    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_]*:[a-z][a-z0-9_]*");
    /** The icon of a tool whose own the client does not have. */
    private static final String FALLBACK_ICON = IconCatalog.tool(Tool.TINKER);

    /** What the server tells a client about a tool. */
    public static Packets.PluginTool toWire(ToolSpec spec) {
        var properties = new ArrayList<Packets.PluginProperty>();
        for (PropertySpec p : spec.properties()) properties.add(new Packets.PluginProperty(p.id(), p.label(), p.kind().name(), p.min(), p.max(), p.number(), p.choices()));
        return new Packets.PluginTool(spec.id(), spec.label(), spec.icon(), spec.iconId(), spec.section(), spec.description(), spec.useHint(), spec.interaction().name(), spec.confirms(), properties);
    }

    /** The tool a client rebuilds from what it was told, or empty when it cannot be trusted or understood. */
    public static Optional<ToolSpec> fromWire(Packets.PluginTool wire) {
        try {
            if (wire == null || wire.id() == null || !ID.matcher(wire.id()).matches() || wire.id().startsWith("craftium:")) return Optional.empty();
            if (wire.label() == null || wire.label().isBlank() || wire.interaction() == null) return Optional.empty();
            InteractionKind kind = InteractionKind.valueOf(wire.interaction());
            if (!kind.forPlugins) return Optional.empty();
            var properties = new ArrayList<PropertySpec>();
            if (wire.properties() != null) {
                if (wire.properties().size() > MAX_PROPERTIES) return Optional.empty();
                var seen = new java.util.HashSet<String>();
                for (Packets.PluginProperty p : wire.properties()) {
                    var spec = property(p);
                    if (spec == null || !seen.add(spec.id())) return Optional.empty();
                    properties.add(spec);
                }
            }
            String icon = wire.icon() == null || wire.icon().isBlank() ? initials(wire.label()) : wire.icon();
            String iconId = wire.iconId() != null && IconCatalog.exists(wire.iconId()) ? wire.iconId() : FALLBACK_ICON;
            String section = wire.section() == null || wire.section().isBlank() ? DEFAULT_SECTION : wire.section();
            return Optional.of(new ToolSpec(wire.id(), wire.label(), icon, iconId, section, wire.description() == null ? "" : wire.description(), Tool.BrushUse.NONE, kind, -1,
                wire.useHint() == null ? "" : wire.useHint(), wire.confirms(), false, false, true, false, false, properties));
        } catch (IllegalArgumentException e) {
            return Optional.empty(); // an interaction this build does not know, or a setting that is not valid
        }
    }

    /** The tools a client keeps out of a list it was told: those it understands, in order, up to {@link #MAX_TOOLS}, the first of a repeated id. */
    public static List<ToolSpec> fromWire(List<Packets.PluginTool> wire) {
        var out = new ArrayList<ToolSpec>();
        if (wire == null) return out;
        var seen = new java.util.HashSet<String>();
        for (Packets.PluginTool one : wire) {
            if (out.size() >= MAX_TOOLS) break;
            var spec = fromWire(one);
            if (spec.isPresent() && seen.add(spec.get().id())) out.add(spec.get());
        }
        return out;
    }

    private static PropertySpec property(Packets.PluginProperty p) {
        if (p == null || p.kind() == null) return null;
        PropertySpec.Kind kind = PropertySpec.Kind.valueOf(p.kind());
        return new PropertySpec(p.id(), p.label(), kind, p.min(), p.max(), p.number(), p.choices());
    }

    private static String initials(String label) {
        String trimmed = label.strip();
        return trimmed.length() <= 2 ? trimmed : trimmed.substring(0, 2);
    }
}
