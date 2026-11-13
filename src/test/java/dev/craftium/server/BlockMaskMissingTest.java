package dev.craftium.server;

import static org.junit.jupiter.api.Assertions.*;
import dev.craftium.core.MissingBlockTable.Entry;
import dev.craftium.core.Options;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** A mask entry naming a block no mod provides picks the placeholders for that block, and only those. */
class BlockMaskMissingTest {
    @BeforeAll static void bootstrap(){
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }
    private static BlockMask mask(boolean exclude,String... blocks){
        var o=new Options();
        o.maskOff=false;
        o.maskExclude=exclude;
        o.maskActive.addAll(java.util.List.of(blocks));
        return BlockMask.of(o);
    }
    private static final Entry MARBLE=Entry.parse("fakemod:marble[axis=y]");
    private static final Entry SLAB=Entry.parse("fakemod:slab");

    @Test void aMissingNameMatchesOnlyItsOwnPlaceholders(){
        var mask=mask(false,"fakemod:marble");
        var stone=Blocks.STONE.defaultBlockState();
        assertTrue(mask.allows(stone,MARBLE),"the placeholder for marble");
        assertFalse(mask.allows(stone,SLAB),"the placeholder for another missing block");
        assertFalse(mask.allows(stone,null),"an ordinary block");
    }
    @Test void propertiesNarrowTheMatch(){
        var stone=Blocks.STONE.defaultBlockState();
        assertTrue(mask(false,"fakemod:marble[axis=y]").allows(stone,MARBLE));
        assertFalse(mask(false,"fakemod:marble[axis=x]").allows(stone,MARBLE));
        assertTrue(mask(false,"fakemod:marble").allows(stone,Entry.parse("fakemod:marble[axis=x]")),"no property named matches any");
    }
    @Test void excludingKeepsEverythingElse(){
        var mask=mask(true,"fakemod:marble");
        var stone=Blocks.STONE.defaultBlockState();
        assertFalse(mask.allows(stone,MARBLE));
        assertTrue(mask.allows(stone,SLAB));
        assertTrue(mask.allows(stone,null));
    }
    @Test void ordinaryBlocksStillMaskAsBefore(){
        var mask=mask(false,"minecraft:stone");
        assertTrue(mask.allows(Blocks.STONE.defaultBlockState()));
        assertFalse(mask.allows(Blocks.DIRT.defaultBlockState()));
        assertTrue(mask(false).allows(Blocks.DIRT.defaultBlockState()),"nothing toggled allows everything");
    }
}
