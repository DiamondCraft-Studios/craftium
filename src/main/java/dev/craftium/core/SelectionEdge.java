package dev.craftium.core;

import java.util.*;

/**
 * The outline of a selection: the blocks within {@code thickness} of its edge. A flat selection (a disc, a square, a ring, any 2D shape) is
 * measured in its own plane, so its outline is the rim and not the whole sheet; that holds for a sheet at any angle, not just one lined up
 * with an axis. A selection with depth in every direction gives its shell. Free of Minecraft types so it can be tested on its own.
 */
public final class SelectionEdge {
    private SelectionEdge(){}

    /** How far a sheet may stray from its plane and still count as flat: a stair-stepped slope wanders about a block either side. */
    private static final double FLAT=1.75;
    /** A direction counts as in the plane when it is within about 20 degrees of it. */
    private static final double IN_PLANE=0.35;
    /** How far from a cell (across the plane, then along it) a selected block may be and still fill it, to bridge the gaps rounding leaves in a slanted sheet. */
    private static final double ACROSS=1.0,ALONG=0.75;

    /** The blocks of {@code source} within {@code thickness} (at least 1) blocks of an edge, in the source's order. */
    public static Set<Point> outline(Collection<Point> source,int thickness){return outline(source,thickness,false);}

    /**
     * As {@link #outline(Collection,int)}. Peeling inwards one face neighbour at a time makes a diagonal wall thinner than a straight one, so with
     * {@code pixelPerfect} every second layer is peeled by the diagonal neighbours too, which keeps the wall the same thickness all round. A
     * thickness of 1 is the same either way: the rim is already one block wide.
     */
    public static Set<Point> outline(Collection<Point> source,int thickness,boolean pixelPerfect){
        var all=new LinkedHashSet<>(source);if(all.isEmpty())return all;
        var tests=interior(all);var inside=new HashSet<>(all);
        for(int i=0;i<Math.max(1,thickness)&&!inside.isEmpty();i++){
            var now=inside;var next=new HashSet<Point>();var test=pixelPerfect&&i%2==1?tests.full:tests.face;
            for(Point at:now)if(test.is(at,now))next.add(at);
            inside=next;
        }
        var result=new LinkedHashSet<Point>();
        for(Point at:all)if(!inside.contains(at))result.add(at);
        return result;
    }

    private interface Interior{boolean is(Point at,Set<Point> set);}
    /** Whether a block is inside by its face neighbours alone, and by its diagonal ones as well. */
    private record Tests(Interior face,Interior full){}

    private static Interior contains(List<Point> steps){
        if(steps.isEmpty())return (at,set)->false; // a single block is all edge
        return (at,set)->{for(Point s:steps)if(!set.contains(at.add(s.x(),s.y(),s.z())))return false;return true;};
    }

    private static Tests interior(Set<Point> all){
        int[] low={Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE},high={Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE};
        for(Point at:all){int[] v={at.x(),at.y(),at.z()};for(int a=0;a<3;a++){low[a]=Math.min(low[a],v[a]);high[a]=Math.max(high[a],v[a]);}}
        // Flat along an axis: the neighbours that count are the ones along the axes it has extent in. Depth every way: all of them.
        var face=new ArrayList<Point>();var full=new ArrayList<Point>();
        for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++){
            if(x==0&&y==0&&z==0)continue;
            if(x!=0&&high[0]==low[0]||y!=0&&high[1]==low[1]||z!=0&&high[2]==low[2])continue;
            full.add(new Point(x,y,z));if(Math.abs(x)+Math.abs(y)+Math.abs(z)==1)face.add(new Point(x,y,z));
        }
        if(low[0]==high[0]||low[1]==high[1]||low[2]==high[2])return new Tests(contains(face),contains(full));
        double[] n=normal(all);
        if(n!=null){
            double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
            for(Point at:all){double d=at.x()*n[0]+at.y()*n[1]+at.z()*n[2];min=Math.min(min,d);max=Math.max(max,d);}
            if(max-min<=FLAT)return new Tests(tilted(n,1.45),tilted(n,2));
        }
        return new Tests(contains(face),contains(full));
    }

    /** A block of a tilted sheet is inside when, in every direction within the plane (up to {@code reach} blocks long), the sheet carries on one block over, give or take rounding. */
    private static Interior tilted(double[] n,double reach){
        var steps=new ArrayList<Point>();
        for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++){
            if(x==0&&y==0&&z==0)continue;
            double length=Math.sqrt(x*x+y*y+z*z);
            if(length<=reach&&Math.abs(x*n[0]+y*n[1]+z*n[2])<=IN_PLANE*length)steps.add(new Point(x,y,z));
        }
        if(steps.isEmpty())return (at,set)->false;
        return (at,set)->{
            for(Point s:steps)if(!filled(at.add(s.x(),s.y(),s.z()),set,n))return false;
            return true;
        };
    }

    /** Whether the sheet is present around {@code cell}: a selected block close to it along the plane and not far from it across the plane. */
    private static boolean filled(Point cell,Set<Point> set,double[] n){
        for(int x=-2;x<=2;x++)for(int y=-2;y<=2;y++)for(int z=-2;z<=2;z++){
            double across=x*n[0]+y*n[1]+z*n[2];if(Math.abs(across)>ACROSS)continue;
            double px=x-across*n[0],py=y-across*n[1],pz=z-across*n[2];
            if(Math.sqrt(px*px+py*py+pz*pz)>ALONG)continue;
            if(set.contains(cell.add(x,y,z)))return true;
        }
        return false;
    }

    /** The unit direction the selection is thinnest in (its plane's normal), or null when it has no usable spread. Found from the covariance by Jacobi rotations. */
    private static double[] normal(Set<Point> all){
        double cx=0,cy=0,cz=0;for(Point at:all){cx+=at.x();cy+=at.y();cz+=at.z();}
        int count=all.size();cx/=count;cy/=count;cz/=count;
        double[][] m=new double[3][3];
        for(Point at:all){double[] d={at.x()-cx,at.y()-cy,at.z()-cz};for(int i=0;i<3;i++)for(int j=0;j<3;j++)m[i][j]+=d[i]*d[j];}
        double[][] v={{1,0,0},{0,1,0},{0,0,1}};
        for(int sweep=0;sweep<24;sweep++){
            int p=0,q=1;double big=Math.abs(m[0][1]);
            if(Math.abs(m[0][2])>big){p=0;q=2;big=Math.abs(m[0][2]);}
            if(Math.abs(m[1][2])>big){p=1;q=2;big=Math.abs(m[1][2]);}
            if(big<1e-12)break;
            double theta=(m[q][q]-m[p][p])/(2*m[p][q]),t=Math.signum(theta)/(Math.abs(theta)+Math.sqrt(theta*theta+1));if(theta==0)t=1;
            double c=1/Math.sqrt(t*t+1),s=t*c;
            for(int k=0;k<3;k++){double a=m[k][p],b=m[k][q];m[k][p]=c*a-s*b;m[k][q]=s*a+c*b;}
            for(int k=0;k<3;k++){double a=m[p][k],b=m[q][k];m[p][k]=c*a-s*b;m[q][k]=s*a+c*b;}
            for(int k=0;k<3;k++){double a=v[k][p],b=v[k][q];v[k][p]=c*a-s*b;v[k][q]=s*a+c*b;}
        }
        int smallest=0;for(int i=1;i<3;i++)if(m[i][i]<m[smallest][smallest])smallest=i;
        double[] n={v[0][smallest],v[1][smallest],v[2][smallest]};double length=Math.sqrt(n[0]*n[0]+n[1]*n[1]+n[2]*n[2]);
        if(length<1e-9||Double.isNaN(length))return null;
        return new double[]{n[0]/length,n[1]/length,n[2]/length};
    }
}
