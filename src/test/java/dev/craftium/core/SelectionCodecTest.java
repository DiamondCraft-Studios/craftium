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

    private static List<Point> box(int x0,int y0,int z0,int x1,int y1,int z1){ // in the order the editor makes a box: x outermost, z innermost
        var out=new ArrayList<Point>();for(int x=x0;x<=x1;x++)for(int y=y0;y<=y1;y++)for(int z=z0;z<=z1;z++)out.add(new Point(x,y,z));return out;
    }

    /** The reason for the run format: a box was about three characters a block, so a selection of a million blocks could not be sent. */
    @Test void aBoxOfAMillionBlocksIsAFewKilobytesAndComesBackExactlyInOrder(){
        var points=box(0,-64,0,51,319,51); // 52 x 384 x 52 = 1,038,336
        String encoded=SelectionCodec.encode(points);
        assertTrue(encoded.length()<100_000,"a box should shrink with its shape, was "+encoded.length()+" characters for "+points.size()+" blocks");
        assertEquals(points,new ArrayList<>(SelectionCodec.decode(encoded)));
    }

    @Test void runsAlongEveryAxisAndBrokenRunsRoundTrip(){
        var points=new ArrayList<Point>();
        for(int i=0;i<10;i++)points.add(new Point(5+i,2,2));      // along x
        for(int i=0;i<10;i++)points.add(new Point(-3,i-5,7));      // along y
        for(int i=0;i<10;i++)points.add(new Point(9,9,-i));        // backwards along z: no run, single points
        for(int i=0;i<10;i++)points.add(new Point(9,9,100+i));     // along z
        points.add(new Point(0,0,0));points.add(new Point(1,1,1)); // diagonal: single points
        assertEquals(points,new ArrayList<>(SelectionCodec.decode(SelectionCodec.encode(points))));
    }

    @Test void aSelectionThatIsNotRunsIsNoWorseThanTheOldFormatByMuch(){
        var rnd=new Random(3);var points=new LinkedHashSet<Point>();while(points.size()<20_000)points.add(new Point(rnd.nextInt(500),rnd.nextInt(60),rnd.nextInt(500)));
        String encoded=SelectionCodec.encode(points);
        assertEquals(points,SelectionCodec.decode(encoded));
        assertTrue(encoded.length()<20_000*16,"scattered blocks should still be a few bytes each, was "+encoded.length());
    }

    /** Servers and clients on older builds, and anything saved with them, wrote raw coordinates; they must still be read. */
    @Test void theOlderRawFormatIsStillRead() throws Exception{
        var bytes=new java.io.ByteArrayOutputStream();
        try(var out=new java.io.DataOutputStream(new java.util.zip.GZIPOutputStream(bytes))){out.writeInt(2);out.writeInt(1);out.writeInt(2);out.writeInt(3);out.writeInt(-4);out.writeInt(5);out.writeInt(-6);}
        assertEquals(List.of(new Point(1,2,3),new Point(-4,5,-6)),new ArrayList<>(SelectionCodec.decode(Base64.getEncoder().encodeToString(bytes.toByteArray()))));
    }

    @Test void aRunThatClaimsMoreBlocksThanTheCountIsRefused() throws Exception{
        var bytes=new java.io.ByteArrayOutputStream();
        try(var out=new java.io.DataOutputStream(new java.util.zip.GZIPOutputStream(bytes))){out.writeInt(-2);out.writeInt(5);out.writeByte(1);out.writeByte(100);out.writeByte(0);out.writeByte(0);out.writeByte(0);} // five blocks announced, one run of 100
        assertThrows(IllegalArgumentException.class,()->SelectionCodec.decode(Base64.getEncoder().encodeToString(bytes.toByteArray())));
    }

    @Test void aRunFormatPayloadWithMissingRecordsIsRefused() throws Exception{
        var bytes=new java.io.ByteArrayOutputStream();
        try(var out=new java.io.DataOutputStream(new java.util.zip.GZIPOutputStream(bytes))){out.writeInt(-2);out.writeInt(3);out.writeByte(0);out.writeByte(0);out.writeByte(0);out.writeByte(0);} // three announced, one held
        assertThrows(IllegalArgumentException.class,()->SelectionCodec.decode(Base64.getEncoder().encodeToString(bytes.toByteArray())));
    }
}
