package dev.craftium.client;

/** How text is drawn at a fractional interface scale (1.5x), where a font pixel covers a whole number of screen pixels in some places and not in others. */
public final class TextMode {
    private TextMode(){}

    public enum Mode {
        /** As the game draws it: nearest-pixel, so some font pixels are one screen pixel wide and some two. */
        VANILLA,
        /** The font texture is sampled with linear filtering, so every edge is blended. */
        LINEAR,
        /** The text is drawn at several times the size and averaged down to the screen pixels, so each edge is blended by how much of the pixel it covers. */
        SUPERSAMPLED
    }

    /** The way the player chose in the options. */
    public static Mode mode(){
        return switch(ClientState.options.textSmoothing){case "vanilla"->Mode.VANILLA;case "supersampled"->Mode.SUPERSAMPLED;default->Mode.LINEAR;};
    }

    /** Chooses how text is drawn, as the options do, and tells whatever has to follow (the font textures, in LINEAR). */
    public static void set(Mode next){ClientState.options.textSmoothing=next.name().toLowerCase(java.util.Locale.ROOT);InterfaceScale.listener.run();}
}
