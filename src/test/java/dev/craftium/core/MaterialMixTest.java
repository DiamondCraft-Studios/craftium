package dev.craftium.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class MaterialMixTest {
    private static int[] counts(int size,boolean independent){
        var counts=new int[size];
        for(int x=0;x<40;x++)for(int y=0;y<5;y++)for(int z=0;z<40;z++){
            double random=BrushMath.hash(x,y,z,7),value=independent?BrushMath.hash(x,y,z,7+0x9E3779B9L):random;
            counts[MaterialList.index(value,List.of(),size,random,1)]++;
        }
        return counts;
    }
    /** Fluid Block picks a block per cell with a position along the palette and a dither threshold; they must come from different hashes, or the first or middle block wins almost everywhere. */
    @Test void independentHashesUseEveryBlockEvenly() {
        for(int size:new int[]{2,3,5})for(int n:counts(size,true))assertTrue(n>8000/size/2,"size "+size+" skewed: "+n);
    }
    @Test void sameHashCollapsesThePalette() {
        assertEquals(0,counts(3,false)[0]);
        assertEquals(0,counts(3,false)[2]);
    }
}
