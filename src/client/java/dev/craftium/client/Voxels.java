package dev.craftium.client;

import dev.craftium.core.Point;
import net.minecraft.core.BlockPos;
import java.util.*;

/** A set of cells relative to an origin, kept as bits over their bounding box so membership is a lookup and not a hash. */
final class Voxels {
    private final BitSet bits=new BitSet();
    private final int minX,minY,minZ,sizeX,sizeY,sizeZ,count;

    Voxels(Collection<Point> points){
        int x0=Integer.MAX_VALUE,y0=x0,z0=x0,x1=Integer.MIN_VALUE,y1=x1,z1=x1;
        for(Point p:points){x0=Math.min(x0,p.x());y0=Math.min(y0,p.y());z0=Math.min(z0,p.z());x1=Math.max(x1,p.x());y1=Math.max(y1,p.y());z1=Math.max(z1,p.z());}
        if(points.isEmpty()){minX=minY=minZ=0;sizeX=sizeY=sizeZ=0;count=0;return;}
        minX=x0;minY=y0;minZ=z0;sizeX=x1-x0+1;sizeY=y1-y0+1;sizeZ=z1-z0+1;
        for(Point p:points)bits.set(index(p.x(),p.y(),p.z()));
        count=bits.cardinality();
    }

    private int index(int x,int y,int z){return ((x-minX)*sizeY+(y-minY))*sizeZ+(z-minZ);}
    boolean has(int x,int y,int z){
        int dx=x-minX,dy=y-minY,dz=z-minZ;
        return dx>=0&&dy>=0&&dz>=0&&dx<sizeX&&dy<sizeY&&dz<sizeZ&&bits.get((dx*sizeY+dy)*sizeZ+dz);
    }
    int count(){return count;}

    /** The cells that have an open side, as x, y, z triples. A cell with all six neighbours in the set is buried and never seen. */
    int[] shell(){
        int[] out=new int[Math.min(3*count,192)];int n=0;
        for(int x=minX;x<minX+sizeX;x++)for(int y=minY;y<minY+sizeY;y++)for(int z=minZ;z<minZ+sizeZ;z++){
            if(!has(x,y,z))continue;
            if(has(x+1,y,z)&&has(x-1,y,z)&&has(x,y+1,z)&&has(x,y-1,z)&&has(x,y,z+1)&&has(x,y,z-1))continue;
            if(n+3>out.length)out=Arrays.copyOf(out,out.length*2);
            out[n++]=x;out[n++]=y;out[n++]=z;
        }
        return Arrays.copyOf(out,n);
    }

    /** These cells moved to {@code origin}, as a set that answers only {@code contains}, which is all the ghost drawing asks of it. */
    Set<BlockPos> at(Point origin){
        return new AbstractSet<>(){
            @Override public boolean contains(Object o){return o instanceof BlockPos p&&has(p.getX()-origin.x(),p.getY()-origin.y(),p.getZ()-origin.z());}
            @Override public int size(){return count;}
            @Override public Iterator<BlockPos> iterator(){throw new UnsupportedOperationException();}
        };
    }
}
