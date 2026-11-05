package dev.craftium.core;

import java.util.*;

/**
 * The cells a set of blocks lands in after one scale, rotate and move about a pivot: the same sampling the server's
 * Move, Rotate and Scale use, so a preview drawn from it is the shape that will be built, with square edges, not the
 * original shape turned into diagonal lines. Nothing here touches the world.
 */
public final class VoxelTransform {
    private VoxelTransform(){}

    /** Pivot shifts to try, smallest first, when a quarter turn would lose cells whose centres fall between blocks. */
    private static final double[][] SHIFTS;
    static{
        var list=new ArrayList<double[]>();
        for(double x:new double[]{0,.5,-.5})for(double y:new double[]{0,.5,-.5})for(double z:new double[]{0,.5,-.5})if(x!=0||y!=0||z!=0)list.add(new double[]{x,y,z});
        list.sort(Comparator.comparingDouble(d->d[0]*d[0]+d[1]*d[1]+d[2]*d[2]));
        SHIFTS=list.toArray(new double[0][]);
    }

    /**
     * The cells {@code source} lands in, or null when the result would cover more than {@code cellLimit} cells of space.
     * Sizes are scale factors with a negative one meaning a flip, as in the Options.
     */
    public static Set<Point> landed(Collection<Point> source,Geometry.Vector pivot,double yaw,double pitch,double roll,double sx,double sy,double sz,int ox,int oy,int oz,long cellLimit){
        if(source.isEmpty())return Set.of();
        sx=scale(sx);sy=scale(sy);sz=scale(sz);
        var cells=new HashSet<>(source);
        int x0=Integer.MAX_VALUE,y0=x0,z0=x0,x1=Integer.MIN_VALUE,y1=x1,z1=x1;
        for(Point p:source){x0=Math.min(x0,p.x());y0=Math.min(y0,p.y());z0=Math.min(z0,p.z());x1=Math.max(x1,p.x());y1=Math.max(y1,p.y());z1=Math.max(z1,p.z());}
        var min=new Point(x0,y0,z0);var max=new Point(x1,y1,z1);
        var landed=place(cells,min,max,pivot,yaw,pitch,roll,sx,sy,sz,ox,oy,oz,cellLimit);
        if(landed==null)return null;
        boolean quarter=sx==1&&sy==1&&sz==1&&quarter(yaw)&&quarter(pitch)&&quarter(roll);
        if(quarter&&landed.size()<cells.size()){
            for(var shift:SHIFTS){
                var trial=place(cells,min,max,new Geometry.Vector(pivot.x()+shift[0],pivot.y()+shift[1],pivot.z()+shift[2]),yaw,pitch,roll,sx,sy,sz,ox,oy,oz,cellLimit);
                if(trial!=null&&trial.size()>=cells.size())return trial;
            }
        }
        return landed;
    }

    private static double scale(double size){return (size<0?-1:1)*Math.max(.05,Math.abs(size));}
    private static boolean quarter(double angle){return Math.abs(angle/90-Math.rint(angle/90))<=1e-9;}

    private static Set<Point> place(Set<Point> cells,Point min,Point max,Geometry.Vector pivot,double yaw,double pitch,double roll,double sx,double sy,double sz,int ox,int oy,int oz,long cellLimit){
        double lx=Double.POSITIVE_INFINITY,ly=lx,lz=lx,hx=-lx,hy=-lx,hz=-lx;
        for(double x:new double[]{min.x(),max.x()+1})for(double y:new double[]{min.y(),max.y()+1})for(double z:new double[]{min.z(),max.z()+1}){
            var r=Geometry.rotate(new Geometry.Vector((x-pivot.x())*sx,(y-pivot.y())*sy,(z-pivot.z())*sz),yaw,pitch,roll);
            double vx=pivot.x()+r.x()+ox,vy=pivot.y()+r.y()+oy,vz=pivot.z()+r.z()+oz;
            lx=Math.min(lx,vx);ly=Math.min(ly,vy);lz=Math.min(lz,vz);hx=Math.max(hx,vx);hy=Math.max(hy,vy);hz=Math.max(hz,vz);
        }
        int a0=(int)Math.floor(lx+1e-9),b0=(int)Math.floor(ly+1e-9),c0=(int)Math.floor(lz+1e-9);
        int a1=(int)Math.ceil(hx-1e-9),b1=(int)Math.ceil(hy-1e-9),c1=(int)Math.ceil(hz-1e-9);
        if((long)(a1-a0)*(b1-b0)*(c1-c0)>cellLimit)return null;
        var landed=new HashSet<Point>();
        for(int x=a0;x<a1;x++)for(int y=b0;y<b1;y++)for(int z=c0;z<c1;z++){
            var r=Geometry.inverseRotate(new Geometry.Vector(x+.5-ox-pivot.x(),y+.5-oy-pivot.y(),z+.5-oz-pivot.z()),yaw,pitch,roll);
            var from=new Point((int)Math.floor(pivot.x()+r.x()/sx+1e-9),(int)Math.floor(pivot.y()+r.y()/sy+1e-9),(int)Math.floor(pivot.z()+r.z()/sz+1e-9));
            if(cells.contains(from))landed.add(new Point(x,y,z));
        }
        return landed;
    }
}
