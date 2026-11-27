package dev.craftium.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;

/**
 * Minecraft 1.21.1 copy of the shared NBT reads. 1.21.1 getters return zero values for a missing or mistyped entry,
 * so each read checks the entry first and returns the default the way 1.21.5 and later do.
 */
public final class Nbt {
    private Nbt() {}

    private static NumericTag number(CompoundTag tag, String key) {
        return tag.get(key) instanceof NumericTag n ? n : null;
    }

    private static NumericTag number(ListTag list, int index) {
        return index >= 0 && index < list.size() && list.get(index) instanceof NumericTag n ? n : null;
    }

    public static int getIntOr(CompoundTag tag, String key, int fallback) {
        NumericTag n = number(tag, key);
        return n == null ? fallback : n.getAsInt();
    }

    public static long getLongOr(CompoundTag tag, String key, long fallback) {
        NumericTag n = number(tag, key);
        return n == null ? fallback : n.getAsLong();
    }

    public static short getShortOr(CompoundTag tag, String key, short fallback) {
        NumericTag n = number(tag, key);
        return n == null ? fallback : n.getAsShort();
    }

    public static byte getByteOr(CompoundTag tag, String key, byte fallback) {
        NumericTag n = number(tag, key);
        return n == null ? fallback : n.getAsByte();
    }

    public static float getFloatOr(CompoundTag tag, String key, float fallback) {
        NumericTag n = number(tag, key);
        return n == null ? fallback : n.getAsFloat();
    }

    public static double getDoubleOr(CompoundTag tag, String key, double fallback) {
        NumericTag n = number(tag, key);
        return n == null ? fallback : n.getAsDouble();
    }

    public static boolean getBooleanOr(CompoundTag tag, String key, boolean fallback) {
        NumericTag n = number(tag, key);
        return n == null ? fallback : n.getAsByte() != 0;
    }

    public static String getStringOr(CompoundTag tag, String key, String fallback) {
        return tag.get(key) instanceof StringTag s ? s.getAsString() : fallback;
    }

    public static CompoundTag getCompoundOrEmpty(CompoundTag tag, String key) {
        return tag.get(key) instanceof CompoundTag c ? c : new CompoundTag();
    }

    public static ListTag getListOrEmpty(CompoundTag tag, String key) {
        return tag.get(key) instanceof ListTag l ? l : new ListTag();
    }

    public static int getIntOr(ListTag list, int index, int fallback) {
        NumericTag n = number(list, index);
        return n == null ? fallback : n.getAsInt();
    }

    public static float getFloatOr(ListTag list, int index, float fallback) {
        NumericTag n = number(list, index);
        return n == null ? fallback : n.getAsFloat();
    }

    public static double getDoubleOr(ListTag list, int index, double fallback) {
        NumericTag n = number(list, index);
        return n == null ? fallback : n.getAsDouble();
    }

    public static String getStringOr(ListTag list, int index, String fallback) {
        return index >= 0 && index < list.size() && list.get(index) instanceof StringTag s ? s.getAsString() : fallback;
    }

    public static CompoundTag getCompoundOrEmpty(ListTag list, int index) {
        return index >= 0 && index < list.size() && list.get(index) instanceof CompoundTag c ? c : new CompoundTag();
    }

    public static ListTag getListOrEmpty(ListTag list, int index) {
        return index >= 0 && index < list.size() && list.get(index) instanceof ListTag l ? l : new ListTag();
    }

    public static java.util.Optional<CompoundTag> getCompound(CompoundTag tag, String key) {
        return tag.get(key) instanceof CompoundTag c ? java.util.Optional.of(c) : java.util.Optional.empty();
    }

    public static java.util.Optional<Short> getShort(CompoundTag tag, String key) {
        return tag.get(key) instanceof NumericTag n
                ? java.util.Optional.of(n.getAsShort())
                : java.util.Optional.empty();
    }

    public static java.util.Optional<int[]> getIntArray(CompoundTag tag, String key) {
        return tag.get(key) instanceof net.minecraft.nbt.IntArrayTag a
                ? java.util.Optional.of(a.getAsIntArray())
                : java.util.Optional.empty();
    }

    public static java.util.Optional<long[]> getLongArray(CompoundTag tag, String key) {
        return tag.get(key) instanceof net.minecraft.nbt.LongArrayTag a
                ? java.util.Optional.of(a.getAsLongArray())
                : java.util.Optional.empty();
    }

    public static java.util.Optional<byte[]> getByteArray(CompoundTag tag, String key) {
        return tag.get(key) instanceof net.minecraft.nbt.ByteArrayTag a
                ? java.util.Optional.of(a.getAsByteArray())
                : java.util.Optional.empty();
    }

    public static java.util.Set<String> keySet(CompoundTag tag) {
        return tag.getAllKeys();
    }

    public static String value(StringTag tag) {
        return tag.getAsString();
    }

    /** Reads SNBT text, such as {@code {Name:"x"}}, into a compound tag. */
    public static CompoundTag parseCompoundFully(String text)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return net.minecraft.nbt.TagParser.parseTag(text);
    }
}
