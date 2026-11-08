package dev.craftium.core;

/**
 * One icon the editor can draw.
 *
 * @param id       stable, namespaced name such as {@code craftium:tool/painter}: what a tool, a setting or a plugin names to choose this icon
 * @param label    what the icon is called when a person picks one
 * @param category where it is listed, as a path such as {@code Tools/Draw}
 * @param width    the size the icon is drawn at, in GUI pixels
 * @param height   the size the icon is drawn at, in GUI pixels
 */
public record IconSpec(String id, String label, String category, int width, int height) {
    /** The part of the id after the namespace, such as {@code tool/painter}. */
    public String path() { return id.substring(id.indexOf(':') + 1); }
}
