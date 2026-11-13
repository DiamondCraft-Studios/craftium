package dev.craftium.server;

import dev.craftium.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildObjectStoreTest {
    private static Selection live(Point... points){var s=new Selection();s.combine(List.of(points),Selection.Mode.REPLACE);return s;}
    private static final Point A=new Point(0,0,0),B=new Point(1,-64,2),C=new Point(-30000000,319,30000000);

    @Test void whatWasWrittenComesBackAfterTheServerRestarts(@TempDir Path dir) throws Exception {
        var saved=new SavedSelections();
        var tower=saved.save(live(A,B));var roof=saved.save(live(C));saved.place(roof.id(),tower.id(),0);saved.rename(tower.id(),"Tower");saved.setColor(roof.id(),0xffaa3366);
        var file=dir.resolve("craftium/buildobjects/minecraft_overworld.nbt");
        BuildObjectStore.write(file,saved.tree().nextId(),saved.tree().saved());
        assertTrue(Files.exists(file));assertFalse(Files.exists(file.resolveSibling(file.getFileName()+".tmp")),"no half-written file is left");

        var back=new SavedSelections(BuildObjectStore.load(file));
        assertEquals(List.of("Tower","BuildObject (1)"),back.entries().stream().map(SavedSelections.Entry::name).toList());
        var t=back.byId(tower.id());var r=back.byId(roof.id());
        assertEquals(tower.guid(),t.guid());assertEquals(roof.guid(),r.guid());
        assertEquals(List.of(A,B),t.points());assertEquals(List.of(C),r.points(),"the far corners of the world survive");
        assertSame(t,r.parent());assertEquals(0xffaa3366,r.color());
        assertEquals(saved.tree().nextId(),back.tree().nextId());
    }
    @Test void dormantAndReplacedBlocksComeBackAfterTheServerRestarts(@TempDir Path dir) throws Exception {
        var saved=new SavedSelections();var tower=saved.save(live(A,B));
        var dormant=new PackedCells();dormant.put(B,"minecraft:oak_stairs[facing=north]");dormant.put(C,"minecraft:chest[facing=north]","{Items:[{id:\"minecraft:diamond\",count:3}]}");
        var replaced=new PackedCells();replaced.put(A,"minecraft:water[level=0]");
        assertTrue(saved.setHeld(tower.id(),dormant,replaced));assertFalse(saved.setHeld(tower.id(),dormant,replaced),"the same again is no change");
        var file=dir.resolve("held.nbt");BuildObjectStore.write(file,saved.tree().nextId(),saved.tree().saved());

        var back=new SavedSelections(BuildObjectStore.load(file)).byId(tower.id());
        assertEquals("minecraft:oak_stairs[facing=north]",back.dormant().get(B));assertEquals("minecraft:chest[facing=north]",back.dormant().get(C));assertEquals("{Items:[{id:\"minecraft:diamond\",count:3}]}",back.dormant().data(C),"a chest keeps its items");assertNull(back.dormant().data(B));
        assertEquals("minecraft:water[level=0]",back.replaced().get(A));assertEquals(2,back.dormant().size());assertEquals(1,back.replaced().size());
    }
    @Test void aWorldWithoutDormantBlocksStaysInTheFormatOlderBuildsRead(@TempDir Path dir) throws Exception {
        var saved=new SavedSelections();saved.save(live(A));
        var file=dir.resolve("plain.nbt");BuildObjectStore.write(file,saved.tree().nextId(),saved.tree().saved());
        assertEquals(1,net.minecraft.nbt.NbtIo.readCompressed(file,net.minecraft.nbt.NbtAccounter.unlimitedHeap()).getIntOr("format",0));
        var tower=saved.entries().getFirst();var dormant=new PackedCells();dormant.put(B,"minecraft:stone");saved.setHeld(tower.id(),dormant,PackedCells.EMPTY);
        BuildObjectStore.write(file,saved.tree().nextId(),saved.tree().saved());
        assertEquals(2,net.minecraft.nbt.NbtIo.readCompressed(file,net.minecraft.nbt.NbtAccounter.unlimitedHeap()).getIntOr("format",0));
    }
    @Test void aWorldWithNoFileStartsEmpty(@TempDir Path dir){
        assertTrue(new SavedSelections(BuildObjectStore.load(dir.resolve("none.nbt"))).entries().isEmpty());
    }
    @Test void aFileThatCannotBeReadIsKeptAsBadAndTheWorldStartsEmpty(@TempDir Path dir) throws Exception {
        var file=dir.resolve("minecraft_overworld.nbt");Files.writeString(file,"not an nbt file");
        assertTrue(new SavedSelections(BuildObjectStore.load(file)).entries().isEmpty());
        assertFalse(Files.exists(file),"moved aside, so the next write does not overwrite what might be recovered");
        assertTrue(Files.exists(dir.resolve("minecraft_overworld.nbt.bad")));
    }
    @Test void anEmptyTreeIsWrittenAndRead(@TempDir Path dir){
        var file=dir.resolve("a/b/empty.nbt");
        BuildObjectStore.write(file,1,List.of());
        assertTrue(new SavedSelections(BuildObjectStore.load(file)).entries().isEmpty());
    }

    @Test void groupsAndWhatIsInThemComeBackAfterTheServerRestarts(@TempDir Path dir){
        var saved=new SavedSelections();
        var a=saved.save(live(A));var b=saved.save(live(B));var walls=saved.createGroup("Walls");saved.placeIn(List.of(b.id()),0,walls.id(),0);
        var tree=saved.tree();var file=dir.resolve("groups.nbt");
        BuildObjectStore.write(file,tree.nextId(),tree.saved(),tree.nextGroupId(),tree.savedGroups());
        var back=new SavedSelections(BuildObjectStore.load(file));
        assertEquals(List.of("New Group","Walls"),back.groups().stream().map(SavedSelections.Group::name).toList());
        assertEquals(saved.groups().get(1).guid(),back.groups().get(1).guid());
        assertEquals(back.groups().getFirst().id(),back.byId(a.id()).groupId());assertEquals(walls.id(),back.byId(b.id()).groupId());
        assertEquals(tree.nextGroupId(),back.tree().nextGroupId());
    }
    @Test void aFileWrittenBeforeGroupsLoadsIntoOneGroup(@TempDir Path dir){
        var saved=new SavedSelections();saved.save(live(A));var file=dir.resolve("old.nbt");
        BuildObjectStore.write(file,saved.tree().nextId(),saved.tree().saved()); // the old shape: no groups
        var back=new SavedSelections(BuildObjectStore.load(file));
        assertEquals(List.of("New Group"),back.groups().stream().map(SavedSelections.Group::name).toList());
        assertSame(back.groups().getFirst(),back.entries().getFirst().group());
    }

    @Test void foldsComeBackAfterTheServerRestarts(@TempDir Path dir){
        var saved=new SavedSelections();var a=saved.save(live(A));var b=saved.save(live(B));saved.place(b.id(),a.id(),0);
        saved.setFolded(a.id(),true);saved.setGroupFolded(saved.groups().getFirst().id(),true);
        var tree=saved.tree();var file=dir.resolve("folds.nbt");
        BuildObjectStore.write(file,tree.nextId(),tree.saved(),tree.nextGroupId(),tree.savedGroups());
        var back=new SavedSelections(BuildObjectStore.load(file));
        assertTrue(back.byId(a.id()).folded());assertFalse(back.byId(b.id()).folded());assertTrue(back.groups().getFirst().folded());
    }
}
