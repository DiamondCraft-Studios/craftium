package dev.craftium.core;

import java.util.*;

/**
 * The outer surface of a voxel selection: one outline around the whole shape plus merged face rectangles
 * for the fill. A plain cuboid gives exactly its 12 edges and 6 faces. Edges are only kept where the
 * surface changes direction, so nothing is drawn through flat areas or the inside, and cavities that are
 * sealed off from the outside are ignored.
 */
public final class SelectionOutline {
    /** Largest air volume that is flood-filled to find sealed cavities; beyond it every boundary face counts. */
    private static final long FLOOD_LIMIT=4_000_000L;

    /** A straight outline segment on the block grid, along exactly one axis; coordinates are block corners. */
    public record Segment(int x0,int y0,int z0,int x1,int y1,int z1){}
    /**
     * An axis-aligned rectangle on the block grid with zero thickness along {@code axis} (0=x, 1=y, 2=z).
     * It faces outward along {@code axis} in the direction of {@code sign}. Corners are block corners.
     */
    public record Face(int axis,int sign,int x0,int y0,int z0,int x1,int y1,int z1){}
    public record Result(List<Segment> edges,List<Face> faces){
        public static final Result EMPTY=new Result(List.of(),List.of());
    }

    private static final int[][] DIRECTIONS={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};

    private SelectionOutline(){}

    public static Result of(Collection<Point> blocks){
        if(blocks.isEmpty())return Result.EMPTY;
        Set<Point> solid=blocks instanceof Set<Point> set?set:new HashSet<>(blocks);
        int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
        for(Point p:solid){minX=Math.min(minX,p.x());minY=Math.min(minY,p.y());minZ=Math.min(minZ,p.z());maxX=Math.max(maxX,p.x());maxY=Math.max(maxY,p.y());maxZ=Math.max(maxZ,p.z());}
        var air=new Air(solid,minX-1,minY-1,minZ-1,maxX+1,maxY+1,maxZ+1);

        // Exterior faces, and the unit edges where the surface bends.
        var faces=new ArrayList<int[]>();                  // {x, y, z, direction index}
        var edges=new HashSet<Segment>();
        for(Point p:solid)for(int d=0;d<6;d++){
            int[] n=DIRECTIONS[d];
            Point outside=new Point(p.x()+n[0],p.y()+n[1],p.z()+n[2]);
            if(solid.contains(outside)||!air.exterior(outside))continue;
            faces.add(new int[]{p.x(),p.y(),p.z(),d});
            int axis=d/2,sign=n[axis];
            for(int step=1;step<=2;step++){
                int sideAxis=(axis+step)%3,edgeAxis=(axis+3-step)%3;
                for(int side=-1;side<=1;side+=2){
                    int[] u=new int[3];u[sideAxis]=side;
                    // The cell beside this face in its plane, and the cell beside the outside air cell.
                    Point beside=new Point(p.x()+u[0],p.y()+u[1],p.z()+u[2]);
                    Point diagonal=new Point(beside.x()+n[0],beside.y()+n[1],beside.z()+n[2]);
                    if(solid.contains(beside)&&!solid.contains(diagonal))continue; // the surface carries straight on
                    edges.add(unitEdge(p,axis,sign,sideAxis,side,edgeAxis));
                }
            }
        }
        return new Result(mergeEdges(edges),mergeFaces(faces));
    }

    private static Segment unitEdge(Point p,int axis,int sign,int sideAxis,int side,int edgeAxis){
        int[] start={p.x(),p.y(),p.z()};
        if(sign>0)start[axis]++;
        if(side>0)start[sideAxis]++;
        int[] end=start.clone();end[edgeAxis]++;
        return new Segment(start[0],start[1],start[2],end[0],end[1],end[2]);
    }

    /** Joins end-to-end unit segments that lie on the same line into single long segments. */
    private static List<Segment> mergeEdges(Set<Segment> units){
        var lines=new HashMap<List<Integer>,TreeSet<Integer>>();
        for(Segment s:units){
            int axis=s.x1()!=s.x0()?0:s.y1()!=s.y0()?1:2;
            int[] a={s.x0(),s.y0(),s.z0()};
            var key=new ArrayList<Integer>(3);key.add(axis);
            for(int i=0;i<3;i++)if(i!=axis)key.add(a[i]);
            lines.computeIfAbsent(key,k->new TreeSet<>()).add(a[axis]);
        }
        var result=new ArrayList<Segment>();
        for(var entry:lines.entrySet()){
            int axis=entry.getKey().get(0);
            int first=-1,length=0;
            for(int start:entry.getValue()){
                if(length>0&&start==first+length){length++;continue;}
                if(length>0)result.add(segment(axis,entry.getKey(),first,first+length));
                first=start;length=1;
            }
            if(length>0)result.add(segment(axis,entry.getKey(),first,first+length));
        }
        result.sort(Comparator.comparingInt(Segment::x0).thenComparingInt(Segment::y0).thenComparingInt(Segment::z0).thenComparingInt(Segment::x1).thenComparingInt(Segment::y1).thenComparingInt(Segment::z1));
        return List.copyOf(result);
    }

    private static Segment segment(int axis,List<Integer> key,int from,int to){
        int[] a=new int[3],b=new int[3];
        int fixed=1;
        for(int i=0;i<3;i++){
            if(i==axis){a[i]=from;b[i]=to;}
            else{a[i]=b[i]=key.get(fixed++);}
        }
        return new Segment(a[0],a[1],a[2],b[0],b[1],b[2]);
    }

    /** Merges coplanar exterior faces that face the same way into the fewest rectangles, greedily. */
    private static List<Face> mergeFaces(List<int[]> faces){
        // Group by direction and plane, holding each face's two in-plane coordinates.
        var planes=new HashMap<List<Integer>,Set<Long>>();
        for(int[] f:faces){
            int d=f[3],axis=d/2,sign=DIRECTIONS[d][axis];
            int plane=f[axis]+(sign>0?1:0);
            int u=f[(axis+1)%3],v=f[(axis+2)%3];
            planes.computeIfAbsent(List.of(d,plane),k->new HashSet<>()).add(pack(u,v));
        }
        var result=new ArrayList<Face>();
        for(var entry:planes.entrySet()){
            int d=entry.getKey().get(0),plane=entry.getKey().get(1),axis=d/2,sign=DIRECTIONS[d][axis];
            var remaining=entry.getValue();
            var order=new ArrayList<>(remaining);
            order.sort(Comparator.comparingLong(SelectionOutline::unpackU).thenComparingLong(SelectionOutline::unpackV));
            for(long cell:order){
                if(!remaining.contains(cell))continue;
                int u0=unpackU(cell),v0=unpackV(cell),u1=u0,v1=v0;
                while(remaining.contains(pack(u1+1,v0)))u1++;
                boolean grow=true;
                while(grow){
                    for(int u=u0;u<=u1;u++)if(!remaining.contains(pack(u,v1+1))){grow=false;break;}
                    if(grow)v1++;
                }
                for(int u=u0;u<=u1;u++)for(int v=v0;v<=v1;v++)remaining.remove(pack(u,v));
                int[] a=new int[3],b=new int[3];
                a[axis]=b[axis]=plane;
                a[(axis+1)%3]=u0;b[(axis+1)%3]=u1+1;
                a[(axis+2)%3]=v0;b[(axis+2)%3]=v1+1;
                result.add(new Face(axis,sign,a[0],a[1],a[2],b[0],b[1],b[2]));
            }
        }
        result.sort(Comparator.comparingInt(Face::axis).thenComparingInt(Face::sign).thenComparingInt(Face::x0).thenComparingInt(Face::y0).thenComparingInt(Face::z0));
        return List.copyOf(result);
    }

    private static long pack(int u,int v){return ((long)u<<32)|(v&0xffffffffL);}
    private static int unpackU(long cell){return (int)(cell>>32);}
    private static int unpackV(long cell){return (int)cell;}

    /** Which cells are air connected to the outside; sealed cavities are not. */
    private static final class Air {
        private final int minX,minY,minZ,maxX,maxY,maxZ,sizeX,sizeY;
        private final BitSet reached;

        Air(Set<Point> solid,int minX,int minY,int minZ,int maxX,int maxY,int maxZ){
            this.minX=minX;this.minY=minY;this.minZ=minZ;this.maxX=maxX;this.maxY=maxY;this.maxZ=maxZ;
            sizeX=maxX-minX+1;sizeY=maxY-minY+1;int sizeZ=maxZ-minZ+1;
            long volume=(long)sizeX*sizeY*sizeZ;
            if(volume>FLOOD_LIMIT){reached=null;return;} // too large to fill: treat every boundary face as outside
            reached=new BitSet((int)volume);
            var queue=new ArrayDeque<int[]>();
            reached.set(0);queue.add(new int[]{minX,minY,minZ});
            while(!queue.isEmpty()){
                int[] c=queue.poll();
                for(int[] n:DIRECTIONS){
                    int x=c[0]+n[0],y=c[1]+n[1],z=c[2]+n[2];
                    if(x<minX||y<minY||z<minZ||x>maxX||y>maxY||z>maxZ)continue;
                    int index=index(x,y,z);
                    if(reached.get(index)||solid.contains(new Point(x,y,z)))continue;
                    reached.set(index);queue.add(new int[]{x,y,z});
                }
            }
        }
        private int index(int x,int y,int z){return (x-minX)+sizeX*((y-minY)+sizeY*(z-minZ));}
        boolean exterior(Point p){
            if(reached==null)return true;
            if(p.x()<minX||p.y()<minY||p.z()<minZ||p.x()>maxX||p.y()>maxY||p.z()>maxZ)return true;
            return reached.get(index(p.x(),p.y(),p.z()));
        }
    }
}
