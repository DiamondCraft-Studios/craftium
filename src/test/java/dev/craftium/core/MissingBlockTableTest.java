package dev.craftium.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MissingBlockTableTest {
    @Test void parsesAndWritesStatesInOneOrder(){
        var entry=MissingBlockTable.Entry.parse("mod:marble[shape=slab, axis=y]");
        assertEquals("mod:marble",entry.name());
        assertEquals("mod:marble[axis=y,shape=slab]",entry.text());
        assertEquals(entry,MissingBlockTable.Entry.of("mod:marble",Map.of("axis","y","shape","slab")));
        assertEquals("mod:plain",MissingBlockTable.Entry.parse("mod:plain").text());
        assertThrows(IllegalArgumentException.class,()->MissingBlockTable.Entry.parse("mod:x[axis"));
    }
    @Test void numbersGrowAndNeverMove(){
        var table=new MissingBlockTable();
        int a=table.intern("mod:a"),b=table.intern("mod:b[x=1]");
        assertEquals(0,a);
        assertEquals(1,b);
        assertEquals(a,table.intern("mod:a"));
        assertEquals(a,table.intern("mod:a[]"));
        assertEquals(2,table.size());
        assertEquals("mod:b[x=1]",table.get(b).text());
        assertNull(table.get(5));
        assertNull(table.get(-1));
        assertEquals(-1,table.find(MissingBlockTable.Entry.parse("mod:c")));
        assertEquals(2,table.size(),"find never adds");
    }
    @Test void survivesARestartAndAppendsOnly(@TempDir Path dir)throws Exception{
        var file=dir.resolve("craftium/missing-blocks.txt");
        var first=new MissingBlockTable(file);
        first.intern("mod:a");
        first.intern("mod:b[x=1]");
        var again=new MissingBlockTable(file);
        assertEquals(2,again.size());
        assertEquals(1,again.intern("mod:b[x=1]"));
        assertEquals(2,again.intern("mod:c"));
        assertEquals(List.of("mod:a","mod:b[x=1]","mod:c"),Files.readAllLines(file));
    }
    @Test void aBlankLineKeepsItsNumber(@TempDir Path dir)throws Exception{
        var file=dir.resolve("t.txt");
        Files.writeString(file,"mod:a\n\nmod:c\n");
        var table=new MissingBlockTable(file);
        assertNull(table.get(1));
        assertEquals(2,table.intern("mod:c"));
        assertEquals(3,table.intern("mod:d"));
    }
}
