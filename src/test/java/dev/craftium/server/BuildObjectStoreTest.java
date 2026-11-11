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
}
