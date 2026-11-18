package dev.craftium.server;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The index over the real vanilla registry: Mojang's families for the odd names, the name algorithm for logs and leaves. */
class MaterialFamiliesTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void sameKindInAnotherWoodMaterial() {
        var index = MaterialFamilies.index();
        var oak = index.slot(Blocks.OAK_STAIRS);
        var spruce = index.slot(Blocks.SPRUCE_PLANKS);
        assertEquals("minecraft:oak", oak.material());
        assertEquals("stairs", oak.kind());
        assertEquals("minecraft:spruce", spruce.material());
        assertSame(Blocks.SPRUCE_STAIRS, index.member(spruce.material(), oak.kind()));
        assertSame(Blocks.SPRUCE_SLAB, index.member("minecraft:spruce", "slab"));
        assertSame(Blocks.SPRUCE_FENCE_GATE, index.member("minecraft:spruce", "fence_gate"));
    }

    @Test
    void logsAndLeavesJoinTheirWoodsMaterial() {
        var index = MaterialFamilies.index();
        assertEquals("minecraft:oak", index.slot(Blocks.OAK_LOG).material());
        assertEquals("minecraft:oak", index.slot(Blocks.STRIPPED_OAK_LOG).material());
        assertSame(Blocks.STRIPPED_BIRCH_LOG, index.member("minecraft:birch", "stripped_log"));
        assertSame(Blocks.BIRCH_LEAVES, index.member("minecraft:birch", "leaves"));
    }

    @Test
    void irregularStoneNames() {
        var index = MaterialFamilies.index();
        var bricks = index.slot(Blocks.STONE_BRICKS);
        assertEquals("base", bricks.kind());
        assertSame(Blocks.STONE_BRICK_STAIRS, index.member(bricks.material(), "stairs"));
        assertSame(Blocks.STONE_BRICK_WALL, index.member(bricks.material(), "wall"));
        // oak stairs to stone bricks: a material of another sort has the kind too
        assertSame(
                Blocks.STONE_BRICK_STAIRS,
                index.member(bricks.material(), index.slot(Blocks.OAK_STAIRS).kind()));
    }

    @Test
    void aMaterialWithoutAKindHasNoBlockForIt() {
        var index = MaterialFamilies.index();
        assertNull(index.member("minecraft:oak", "wall")); // no oak wall
        assertNull(index.member("minecraft:stone_brick", "log"));
    }

    @Test
    void blocksThatAreNoMaterialAreNotApplicable() {
        assertFalse(MaterialFamilies.applicable(Blocks.GLASS));
        assertFalse(MaterialFamilies.applicable(Blocks.OAK_SIGN)); // signs hold text and are left out
        assertTrue(MaterialFamilies.applicable(Blocks.OAK_STAIRS));
    }
}
