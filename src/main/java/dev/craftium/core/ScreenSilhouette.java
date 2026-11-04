package dev.craftium.core;

import java.util.*;

/**
 * The outline of a selection as one eye sees it: the contour of everything the selection covers on the screen, with no
 * edge that lies inside it. Where {@link SelectionOutline.Result#creases()} also keeps the creases that bound a front
 * face against a back face inside a concave shape, this keeps only the stretches where the picture ends.
 * <p>
 * The contour of a projection does not depend on which plane it is projected onto, so a plane facing the middle of the
 * shape is used; that needs no knowledge of the real camera. Every point must lie in front of that plane, otherwise
 * there is no single picture and the result is null.
 */
public final class ScreenSilhouette {
    /** A stretch of the contour, in the outline's own coordinates. */
    public record Line(double x0,double y0,double z0,double x1,double y1,double z1){}

    /** Past this many segment-and-face pairs the work is too much to redo as the camera moves. */
    private static final long BUDGET=3_000_000L;
    private static final double EPS=1e-6,NEAR=1e-3;

    private ScreenSilhouette(){}

    /** A front-facing exterior rectangle, projected: its four corners and the box around them. */
    private record Poly(double[] x,double[] y,double minX,double minY,double maxX,double maxY){
        boolean covers(double px,double py){
            boolean neg=false,pos=false;
            for(int i=0;i<4;i++){
                int j=(i+1)&3;
                double c=(x[j]-x[i])*(py-y[i])-(y[j]-y[i])*(px-x[i]);
                if(c<0)neg=true;else if(c>0)pos=true;
                if(neg&&pos)return false;
            }
            return true;
        }
    }

    /** The contour seen from the eye, or null when it cannot be worked out (the eye is inside or beside the shape, or the shape is too detailed). */
    public static List<Line> of(SelectionOutline.Result outline,double ex,double ey,double ez){
        if(outline.creases().isEmpty())return null;
        // The plane faces the middle of the outline.
        double cx=0,cy=0,cz=0;int n=0;
        for(var c:outline.creases()){var s=c.segment();cx+=s.x0()+s.x1();cy+=s.y0()+s.y1();cz+=s.z0()+s.z1();n+=2;}
        cx/=n;cy/=n;cz/=n;
        double fx=cx-ex,fy=cy-ey,fz=cz-ez,fl=Math.sqrt(fx*fx+fy*fy+fz*fz);
        if(fl<1e-6)return null;
        fx/=fl;fy/=fl;fz/=fl;
        double hx=Math.abs(fy)<.99?0:1,hy=Math.abs(fy)<.99?1:0;
        // right = forward x helper, up = right x forward
        double rx=fy*0-fz*hy,ry=fz*hx-fx*0,rz=fx*hy-fy*hx,rl=Math.sqrt(rx*rx+ry*ry+rz*rz);
        rx/=rl;ry/=rl;rz/=rl;
        double ux=ry*fz-rz*fy,uy=rz*fx-rx*fz,uz=rx*fy-ry*fx;

        var polys=new ArrayList<Poly>();
        for(var f:outline.faces()){
            int axis=f.axis();
            double[] eye={ex,ey,ez};
            int[] lo={f.x0(),f.y0(),f.z0()},hi={f.x1(),f.y1(),f.z1()};
            if(f.sign()*(eye[axis]-lo[axis])<=0)continue; // faces away
            int u=(axis+1)%3,v=(axis+2)%3;
            double[] px=new double[4],py=new double[4];
            int[][] ring={{lo[u],lo[v]},{hi[u],lo[v]},{hi[u],hi[v]},{lo[u],hi[v]}};
            for(int i=0;i<4;i++){
                double[] p=new double[3];p[axis]=lo[axis];p[u]=ring[i][0];p[v]=ring[i][1];
                double dx=p[0]-ex,dy=p[1]-ey,dz=p[2]-ez,z=dx*fx+dy*fy+dz*fz;
                if(z<=NEAR)return null;
                px[i]=(dx*rx+dy*ry+dz*rz)/z;py[i]=(dx*ux+dy*uy+dz*uz)/z;
            }
            polys.add(new Poly(px,py,Math.min(Math.min(px[0],px[1]),Math.min(px[2],px[3])),Math.min(Math.min(py[0],py[1]),Math.min(py[2],py[3])),
                    Math.max(Math.max(px[0],px[1]),Math.max(px[2],px[3])),Math.max(Math.max(py[0],py[1]),Math.max(py[2],py[3]))));
        }
        if(polys.isEmpty())return null;

        // Only creases between a face toward the eye and one away from it can be on the contour.
        var candidates=new ArrayList<SelectionOutline.Segment>();
        for(var c:outline.creases()){
            var s=c.segment();
            double mx=(s.x0()+s.x1())/2d,my=(s.y0()+s.y1())/2d,mz=(s.z0()+s.z1())/2d;
            if(towards(c.normalA(),ex-mx,ey-my,ez-mz)!=towards(c.normalB(),ex-mx,ey-my,ez-mz))candidates.add(s);
        }
        if((long)candidates.size()*polys.size()>BUDGET)return null;

        var result=new ArrayList<Line>();
        for(var s:candidates){
            double[] a={s.x0()-ex,s.y0()-ey,s.z0()-ez},b={s.x1()-ex,s.y1()-ey,s.z1()-ez};
            double za=a[0]*fx+a[1]*fy+a[2]*fz,zb=b[0]*fx+b[1]*fy+b[2]*fz;
            if(za<=NEAR||zb<=NEAR)return null;
            double ax=(a[0]*rx+a[1]*ry+a[2]*rz)/za,ay=(a[0]*ux+a[1]*uy+a[2]*uz)/za;
            double bx=(b[0]*rx+b[1]*ry+b[2]*rz)/zb,by=(b[0]*ux+b[1]*uy+b[2]*uz)/zb;
            double dx=bx-ax,dy=by-ay,len=Math.hypot(dx,dy);
            if(len<1e-9)continue;
            double nx=-dy/len*EPS,ny=dx/len*EPS; // a step off the line, to one side
            double minX=Math.min(ax,bx)-EPS*2,maxX=Math.max(ax,bx)+EPS*2,minY=Math.min(ay,by)-EPS*2,maxY=Math.max(ay,by)+EPS*2;
            var near=new ArrayList<Poly>();
            for(var p:polys)if(p.maxX>=minX&&p.minX<=maxX&&p.maxY>=minY&&p.minY<=maxY)near.add(p);
            // Where along the line the covered area can change: wherever a face's edge crosses it or touches it.
            var cuts=new TreeSet<Double>();cuts.add(0d);cuts.add(1d);
            for(var p:near)for(int i=0;i<4;i++){
                int j=(i+1)&3;
                double d0=side(ax,ay,dx,dy,len,p.x[i],p.y[i]),d1=side(ax,ay,dx,dy,len,p.x[j],p.y[j]);
                if(Math.abs(d0)<=EPS)cuts.add(along(ax,ay,dx,dy,len,p.x[i],p.y[i]));
                if(d0*d1<0){
                    double k=d0/(d0-d1);
                    cuts.add(along(ax,ay,dx,dy,len,p.x[i]+k*(p.x[j]-p.x[i]),p.y[i]+k*(p.y[j]-p.y[i])));
                }
            }
            Double previous=null;boolean open=false;double openFrom=0;
            for(double t:cuts){
                if(t<0||t>1)continue;
                if(previous!=null&&t-previous>1e-9){
                    double mid=(previous+t)/2,qx=ax+dx*mid,qy=ay+dy*mid;
                    boolean contour=covered(near,qx+nx,qy+ny)!=covered(near,qx-nx,qy-ny);
                    if(contour&&!open){open=true;openFrom=previous;}
                    if(!contour&&open){open=false;add(result,s,za,zb,openFrom,previous,ex,ey,ez);}
                }
                previous=t;
            }
            if(open)add(result,s,za,zb,openFrom,1,ex,ey,ez);
        }
        return result;
    }

    private static boolean covered(List<Poly> polys,double x,double y){
        for(var p:polys)if(x>=p.minX&&x<=p.maxX&&y>=p.minY&&y<=p.maxY&&p.covers(x,y))return true;
        return false;
    }
    /** Signed distance from the line through (ax,ay) with direction (dx,dy). */
    private static double side(double ax,double ay,double dx,double dy,double len,double x,double y){return (dx*(y-ay)-dy*(x-ax))/len;}
    /** How far along the line, from 0 at its start to 1 at its end, the point projects. */
    private static double along(double ax,double ay,double dx,double dy,double len,double x,double y){return ((x-ax)*dx+(y-ay)*dy)/(len*len);}

    /** Adds the part of the segment from screen fraction {@code s0} to {@code s1}, converted to the fraction along the real segment. */
    private static void add(List<Line> out,SelectionOutline.Segment s,double za,double zb,double s0,double s1,double ex,double ey,double ez){
        double u0=real(s0,za,zb),u1=real(s1,za,zb);
        out.add(new Line(s.x0()+(s.x1()-s.x0())*u0,s.y0()+(s.y1()-s.y0())*u0,s.z0()+(s.z1()-s.z0())*u0,
                s.x0()+(s.x1()-s.x0())*u1,s.y0()+(s.y1()-s.y0())*u1,s.z0()+(s.z1()-s.z0())*u1));
    }
    /** A perspective picture does not step evenly along a line: the nearer end takes more of it. */
    private static double real(double s,double za,double zb){return s*za/((1-s)*zb+s*za);}

    private static boolean towards(int direction,double dx,double dy,double dz){
        int[] n=SelectionOutline.normal(direction);
        return n[0]*dx+n[1]*dy+n[2]*dz>0;
    }
}
