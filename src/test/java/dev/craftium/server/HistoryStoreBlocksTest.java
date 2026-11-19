package dev.craftium.server;

import static org.junit.jupiter.api.Assertions.*;

import dev.craftium.core.Point;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class HistoryStoreBlocksTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static List<Session.Change> changes(int n) {
        var stone = new BlockSnapshot(Blocks.STONE.defaultBlockState());
        var air = new BlockSnapshot(Blocks.AIR.defaultBlockState());
        var stairs = new BlockSnapshot(Blocks.OAK_STAIRS.defaultBlockState());
        var chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        var chestSnapshot = new BlockSnapshot(Blocks.CHEST.defaultBlockState(), chest);
        var list = new ArrayList<Session.Change>();
        for (int i = 0; i < n; i++) {
            var before = i % 11 == 0 ? chestSnapshot : i % 2 == 0 ? air : stone;
            var after = i % 3 == 0 ? stairs : air;
            list.add(new Session.Change(new Point(i - 500, (i * 7) % 300 - 64, -30_000_000 + i), before, after));
        }
        return list;
    }

    @Test
    void aPackedEditSurvivesBeingWrittenAndReadAndStaysPacked() {
        var original = changes(5_000);
        var packed = PackedChanges.pack(original);
        assertInstanceOf(PackedChanges.class, packed);
        var tag = HistoryStore.blocks(packed);
        assertEquals(4, tag.getListOrEmpty("palette").size(), "each distinct block is written once");
        var back = HistoryStore.readBlocks(tag, BuiltInRegistries.BLOCK);
        assertInstanceOf(PackedChanges.class, back, "read back packed, without a change object per block");
        assertEquals(original, back);
    }

    @Test
    void anUnpackedEditSurvivesBeingWrittenAndRead() {
        var original = changes(40);
        var back = HistoryStore.readBlocks(HistoryStore.blocks(original), BuiltInRegistries.BLOCK);
        assertEquals(original, back);
    }

    @Test
    void anEditWithNoBlocksReadsBackEmpty() {
        assertEquals(List.of(), HistoryStore.readBlocks(HistoryStore.blocks(List.of()), BuiltInRegistries.BLOCK));
    }

    @Test
    void anIndexOutsideThePaletteIsRefused() {
        var tag = HistoryStore.blocks(changes(10));
        var after = tag.getIntArray("after").orElseThrow();
        after[3] = 99;
        tag.putIntArray("after", after);
        assertThrows(IllegalArgumentException.class, () -> HistoryStore.readBlocks(tag, BuiltInRegistries.BLOCK));
    }

    @Test
    void arraysOfDifferentLengthsAreRefused() {
        var tag = HistoryStore.blocks(changes(10));
        tag.putIntArray("y", new int[3]);
        assertThrows(IllegalArgumentException.class, () -> HistoryStore.readBlocks(tag, BuiltInRegistries.BLOCK));
    }
}
