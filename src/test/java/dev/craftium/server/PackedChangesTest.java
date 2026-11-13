package dev.craftium.server;

import dev.craftium.core.Point;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PackedChangesTest {
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static List<Session.Change> changes(int n, boolean withData) {
        var stone = new BlockSnapshot(Blocks.STONE.defaultBlockState());
        var air = new BlockSnapshot(Blocks.AIR.defaultBlockState());
        var chest = new CompoundTag(); chest.putString("id", "minecraft:chest");
        var chestSnapshot = new BlockSnapshot(Blocks.CHEST.defaultBlockState(), chest);
        var list = new ArrayList<Session.Change>();
        for (int i = 0; i < n; i++) {
            var before = i % 7 == 0 && withData ? chestSnapshot : i % 2 == 0 ? air : stone;
            var after = i % 3 == 0 ? stone : air;
            list.add(new Session.Change(new Point(i - 500, (i * 7) % 300 - 64, -i * 3), before, after));
        }
        return list;
    }

    @Test void roundTripsEveryChange() {
        var original = changes(5_000, true);
        var packed = PackedChanges.pack(original);
        assertInstanceOf(PackedChanges.class, packed);
        assertEquals(original, packed);
        assertEquals(original.getLast(), packed.getLast());
        assertEquals(original.subList(100, 612), packed.subList(100, 612));
        assertEquals(original.reversed(), packed.reversed());
    }

    @Test void keepsBlockEntityData() {
        var original = changes(1_000, true);
        var packed = PackedChanges.pack(original);
        assertEquals("minecraft:chest", packed.get(0).before().data().getStringOr("id", ""));
        assertNull(packed.get(1).before().data());
    }

    @Test void smallListsAreLeftAlone() {
        var small = changes(PackedChanges.MIN_SIZE - 1, false);
        assertSame(small, PackedChanges.pack(small));
    }

    @Test void packingTwiceKeepsThePackedList() {
        var packed = PackedChanges.pack(changes(1_000, false));
        assertSame(packed, PackedChanges.pack(packed));
    }

    @Test void costsAFractionOfTheObjects() {
        var original = changes(100_000, true);
        var edit = new Session.Edit("test", original, List.of(), 0);
        long loose = edit.estimatedBytes();
        var packed = edit.packed();
        assertInstanceOf(PackedChanges.class, packed.changes());
        long compact = packed.estimatedBytes();
        assertTrue(compact * 6 < loose, "packed " + compact + " bytes against " + loose);
        assertEquals(original, packed.changes());
    }

    @Test void historyKeepsBigEditsPacked() {
        var session = new Session();
        session.remember(new Session.Edit("big", changes(2_000, false), List.of(), 0));
        assertInstanceOf(PackedChanges.class, session.undo.peekFirst().changes());
        assertEquals(changes(2_000, false), session.undo.peekFirst().changes());
        assertTrue(session.bytes < 2_000 * 32, "history counts " + session.bytes + " bytes");
    }
}
