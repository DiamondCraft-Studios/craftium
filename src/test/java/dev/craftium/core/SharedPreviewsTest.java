package dev.craftium.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SharedPreviewsTest {
    private static final Object OVERWORLD = new Object(), NETHER = new Object();
    private static final UUID ME = UUID.randomUUID(), ALEX = UUID.randomUUID(), SAM = UUID.randomUUID();

    private static SharedPreviews.Entry entry(UUID owner, String name, Object world, boolean online, String data, long id) { return new SharedPreviews.Entry(owner, name, world, online, data, id); }

    @Test void aPlayerSeesOtherPlayersPreviewsInTheirOwnDimensionAndNotTheirOwn() {
        var shown = SharedPreviews.select(ME, OVERWORLD, List.of(entry(ME, "Me", OVERWORLD, true, "mine", 1), entry(ALEX, "Alex", OVERWORLD, true, "alex", 2), entry(SAM, "Sam", OVERWORLD, true, "sam", 3)));
        assertEquals(List.of("Alex", "Sam"), shown.stream().map(SharedPreviews.Shown::owner).toList());
    }

    @Test void aPreviewInAnotherDimensionOrFromSomeoneWhoLeftOrAnEmptyOneIsNotShown() {
        var shown = SharedPreviews.select(ME, OVERWORLD, List.of(
            entry(ALEX, "Alex", NETHER, true, "alex", 1), entry(SAM, "Sam", OVERWORLD, false, "sam", 2), entry(UUID.randomUUID(), "Kim", OVERWORLD, true, "", 3), entry(UUID.randomUUID(), "Lee", OVERWORLD, true, null, 4)));
        assertTrue(shown.isEmpty());
    }

    @Test void theSameSetIsAlwaysInTheSameOrderWhateverOrderItArrivesIn() {
        var a = List.of(entry(SAM, "Sam", OVERWORLD, true, "s", 5), entry(ALEX, "Alex", OVERWORLD, true, "a", 6));
        var b = List.of(a.get(1), a.get(0));
        assertEquals(SharedPreviews.key(SharedPreviews.select(ME, OVERWORLD, a)), SharedPreviews.key(SharedPreviews.select(ME, OVERWORLD, b)));
    }

    @Test void theKeyChangesWhenAPreviewIsReplacedOrGoesAndNotWhenNothingDid() {
        var before = SharedPreviews.key(SharedPreviews.select(ME, OVERWORLD, List.of(entry(ALEX, "Alex", OVERWORLD, true, "a", 6))));
        assertEquals(before, SharedPreviews.key(SharedPreviews.select(ME, OVERWORLD, List.of(entry(ALEX, "Alex", OVERWORLD, true, "a", 6)))), "nothing changed, nothing to send");
        assertNotEquals(before, SharedPreviews.key(SharedPreviews.select(ME, OVERWORLD, List.of(entry(ALEX, "Alex", OVERWORLD, true, "a2", 7)))), "a new preview is a change");
        assertNotEquals(before, SharedPreviews.key(SharedPreviews.select(ME, OVERWORLD, List.of())), "a preview that went is a change");
        assertNotEquals(before, SharedPreviews.key(SharedPreviews.select(ME, OVERWORLD, List.of(entry(ALEX, "Alex", NETHER, true, "a", 6)))), "an owner who changed dimension is a change");
    }

    @Test void nobodyElseMeansAnEmptyKeyThatDiffersFromNeverHavingBeenTold() {
        assertEquals("", SharedPreviews.key(List.of()));
    }
}
