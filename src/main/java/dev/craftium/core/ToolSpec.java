package dev.craftium.core;

/**
 * Everything the editor needs to know about a tool in order to list it, label it, hint at it and bind a key to it, apart from what it does
 * to the world. One spec describes one tool, so the panel, the hint bar, the key bindings and the limits all read the same facts.
 *
 * @param id             stable, namespaced name such as {@code craftium:painter}; the part after the colon names the tool's key binding
 * @param label          the name the Tools panel shows
 * @param icon           two letters the panel falls back on where no icon is drawn
 * @param iconId         the {@link IconCatalog} icon drawn for the tool: its own, for the tools the editor ships with
 * @param section       the group the tool is listed under
 * @param description    one line shown when the tool is hovered
 * @param brushUse       what the tool makes of the brush under the cursor
 * @param interaction    how the tool takes input in the viewport: what the use button and a held button do
 * @param defaultKey    the key the tool starts on (an uppercase letter), or -1 when it starts unbound
 * @param useHint        what the use button does with this tool, for the hint bar
 * @param confirms       whether Enter applies the tool's work, so the hint bar says so
 * @param radiusShortcut whether the radius shortcuts apply to the tool
 * @param buildsOnRelease whether what a stroke stages is built the moment the button is let go, without Enter (the Paint and Sculpt tools); any tool does when Enter to build is off
 * @param placesBlocks   whether the tool builds, removes or moves blocks, so the block limit applies to it
 * @param selection      whether the tool selects blocks
 * @param transform      whether the tool moves, rotates or scales the selected blocks around a pivot
 * @param properties     the settings the tool declares for itself, which the options panel draws as a form instead of the built-in controls; none for the tools the editor ships with
 */
public record ToolSpec(String id, String label, String icon, String iconId, String section, String description, Tool.BrushUse brushUse, InteractionKind interaction, int defaultKey, String useHint,
                       boolean confirms, boolean radiusShortcut, boolean buildsOnRelease, boolean placesBlocks, boolean selection, boolean transform,
                       java.util.List<PropertySpec> properties) {
    public ToolSpec {
        properties = properties == null ? java.util.List.of() : java.util.List.copyOf(properties);
    }

    /** The part of the id after the namespace, for names that must be unique per tool, such as a key binding's. */
    public String path() { return id.substring(id.indexOf(':') + 1); }
    /** Whether the shape picker means anything for this tool. */
    public boolean picksShape() { return brushUse == Tool.BrushUse.SHAPE || brushUse == Tool.BrushUse.SMALL_SHAPE; }
}
