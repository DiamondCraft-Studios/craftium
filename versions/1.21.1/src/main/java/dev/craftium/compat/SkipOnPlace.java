package dev.craftium.compat;

/**
 * Minecraft 1.21.1 only: whether the block being written now is to skip its onPlace, set by
 * {@code SkipOnPlaceMixin} from the placement flags (see {@link Compat#UPDATE_SKIP_ON_PLACE}). onPlace runs on the
 * server thread only.
 */
public final class SkipOnPlace {
    private SkipOnPlace() {}

    public static boolean active;
}
