package dev.craftium.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/**
 * NBT reads with a default, the shape Minecraft 1.21.5 and later use. Shared code reads tags through here so that
 * versions from before 1.21.5, where the getters return zero values instead of taking a default, can supply their
 * own copy of this class. On 1.21.5 and later each method is the tag's own getter.
 */
public final class Nbt {
    private Nbt() {}

    public static int getIntOr(CompoundTag tag, String key, int fallback) {
        return tag.getIntOr(key, fallback);
    }

    public static long getLongOr(CompoundTag tag, String key, long fallback) {
        return tag.getLongOr(key, fallback);
    }

    public static short getShortOr(CompoundTag tag, String key, short fallback) {
        return tag.getShortOr(key, fallback);
    }

    public static byte getByteOr(CompoundTag tag, String key, byte fallback) {
        return tag.getByteOr(key, fallback);
    }

    public static float getFloatOr(CompoundTag tag, String key, float fallback) {
        return tag.getFloatOr(key, fallback);
    }

    public static double getDoubleOr(CompoundTag tag, String key, double fallback) {
        return tag.getDoubleOr(key, fallback);
    }

    public static boolean getBooleanOr(CompoundTag tag, String key, boolean fallback) {
        return tag.getBooleanOr(key, fallback);
    }

    public static String getStringOr(CompoundTag tag, String key, String fallback) {
        return tag.getStringOr(key, fallback);
    }

    public static CompoundTag getCompoundOrEmpty(CompoundTag tag, String key) {
        return tag.getCompoundOrEmpty(key);
    }

    public static ListTag getListOrEmpty(CompoundTag tag, String key) {
        return tag.getListOrEmpty(key);
    }

    public static int getIntOr(ListTag list, int index, int fallback) {
        return list.getIntOr(index, fallback);
    }

    public static float getFloatOr(ListTag list, int index, float fallback) {
        return list.getFloatOr(index, fallback);
    }

    public static double getDoubleOr(ListTag list, int index, double fallback) {
        return list.getDoubleOr(index, fallback);
    }

    public static String getStringOr(ListTag list, int index, String fallback) {
        return list.getStringOr(index, fallback);
    }

    public static CompoundTag getCompoundOrEmpty(ListTag list, int index) {
        return list.getCompoundOrEmpty(index);
    }

    public static ListTag getListOrEmpty(ListTag list, int index) {
        return list.getListOrEmpty(index);
    }
}
