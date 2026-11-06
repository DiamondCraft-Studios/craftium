package dev.craftium.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SelectionCodecTest {
    @Test void preservesIrregularSelectionAcrossNegativeCoordinates(){
        var points=new LinkedHashSet<Point>();for(int x=-30;x<30;x++)for(int y=-20;y<20;y++)if((x+y)%3!=0)points.add(new Point(x,y,x*y));
        assertEquals(points,SelectionCodec.decode(SelectionCodec.encode(points)));
    }
    @Test void keepsTheOrderOfTheBlocks(){
        var points=new ArrayList<Point>();for(int i=0;i<500;i++)points.add(new Point(i*7%31-15,i%13,-i));
        assertEquals(points,new ArrayList<>(SelectionCodec.decode(SelectionCodec.encode(points))));
    }
    @Test void emptyAndSingleSelectionsRoundTrip(){
        assertTrue(SelectionCodec.decode(SelectionCodec.encode(List.of())).isEmpty());
        assertEquals(Set.of(new Point(Integer.MIN_VALUE,0,Integer.MAX_VALUE)),SelectionCodec.decode(SelectionCodec.encode(List.of(new Point(Integer.MIN_VALUE,0,Integer.MAX_VALUE)))));
    }
    /** A payload that claims more blocks than it holds must be refused, not decoded as far as it goes. */
    @Test void rejectsADataBlockShorterThanItsCount() throws Exception{
        var bytes=new java.io.ByteArrayOutputStream();
        try(var out=new java.io.DataOutputStream(new java.util.zip.GZIPOutputStream(bytes))){out.writeInt(3);for(int i=0;i<2*3;i++)out.writeInt(i);} // three blocks announced, two held
        assertThrows(IllegalArgumentException.class,()->SelectionCodec.decode(Base64.getEncoder().encodeToString(bytes.toByteArray())));
    }
    @Test void rejectsACountPastTheLimit() throws Exception{
        var bytes=new java.io.ByteArrayOutputStream();
        try(var out=new java.io.DataOutputStream(new java.util.zip.GZIPOutputStream(bytes))){out.writeInt(Selection.LIMIT+1);}
        assertThrows(IllegalArgumentException.class,()->SelectionCodec.decode(Base64.getEncoder().encodeToString(bytes.toByteArray())));
    }
    @Test void rejectsMalformedCompressedData(){assertThrows(IllegalArgumentException.class,()->SelectionCodec.decode("not gzip"));}
}
