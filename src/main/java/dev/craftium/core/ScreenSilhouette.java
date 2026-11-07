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

    /**
     * Past this many faces looked at in total, summed over the segments, the work is too much to redo as the camera moves. A segment
     * only looks at the faces near it on the screen, so this allows far more shape than testing every segment against every face did.
     */
    private static final long WORK_BUDGET=1_500_000L;
    private static final double EPS=1e-6,NEAR=1e-3;
    /** A face's stretch along a line is widened by this, so rounding can never make a face that covers a point look as if it ended just short of it. */
    private static final double SPAN_SLACK=1e-7;

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
        // The plane first faces the middle of the outline.
        double cx=0,cy=0,cz=0;int n=0;
        double minX=Double.MAX_VALUE,minY=Double.MAX_VALUE,minZ=Double.MAX_VALUE,maxX=-Double.MAX_VALUE,maxY=-Double.MAX_VALUE,maxZ=-Double.MAX_VALUE;
        for(var c:outline.creases()){
            var s=c.segment();cx+=s.x0()+s.x1();cy+=s.y0()+s.y1();cz+=s.z0()+s.z1();n+=2;
            minX=Math.min(minX,Math.min(s.x0(),s.x1()));maxX=Math.max(maxX,Math.max(s.x0(),s.x1()));
            minY=Math.min(minY,Math.min(s.y0(),s.y1()));maxY=Math.max(maxY,Math.max(s.y0(),s.y1()));
            minZ=Math.min(minZ,Math.min(s.z0(),s.z1()));maxZ=Math.max(maxZ,Math.max(s.z0(),s.z1()));
        }
        var result=project(outline,ex,ey,ez,cx/n-ex,cy/n-ey,cz/n-ez);
        if(result!=null)return result;
        // Part of a wide shape can lie beside or behind the plane through the eye. The direction to the nearest point of the box around the shape
        // always has the whole shape in front of it, as long as the eye is outside that box.
        double nx=Math.max(minX,Math.min(ex,maxX))-ex,ny=Math.max(minY,Math.min(ey,maxY))-ey,nz=Math.max(minZ,Math.min(ez,maxZ))-ez;
        if(nx*nx+ny*ny+nz*nz<1e-12)return null;
        return project(outline,ex,ey,ez,nx,ny,nz);
    }

    private static List<Line> project(SelectionOutline.Result outline,double ex,double ey,double ez,double fx,double fy,double fz){
        double fl=Math.sqrt(fx*fx+fy*fy+fz*fz);
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
        var grid=new Grid(polys);
        var near=new ArrayList<Poly>();
        var sweep=new Sweep();
        long work=0;

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
            near.clear();
            work+=grid.query(minX,minY,maxX,maxY,near)+1;
            if(work>WORK_BUDGET)return null;
            // Where along the line the covered area can change: wherever a face's edge crosses it or touches it. Each face also gets the
            // stretch of the line its picture spans: it can only cover a point of the line inside that stretch.
            sweep.begin(near.size());
            sweep.cut(0);sweep.cut(1);
            for(int n=0;n<near.size();n++){
                var p=near.get(n);
                double low=Double.MAX_VALUE,high=-Double.MAX_VALUE;
                for(int i=0;i<4;i++){
                    int j=(i+1)&3;
                    double d0=side(ax,ay,dx,dy,len,p.x[i],p.y[i]),d1=side(ax,ay,dx,dy,len,p.x[j],p.y[j]);
                    double at=along(ax,ay,dx,dy,len,p.x[i],p.y[i]);
                    low=Math.min(low,at);high=Math.max(high,at);
                    if(Math.abs(d0)<=EPS)sweep.cut(at);
                    if(d0*d1<0){
                        double k=d0/(d0-d1);
                        sweep.cut(along(ax,ay,dx,dy,len,p.x[i]+k*(p.x[j]-p.x[i]),p.y[i]+k*(p.y[j]-p.y[i])));
                    }
                }
                sweep.span(n,low-SPAN_SLACK,high+SPAN_SLACK);
            }
            sweep.sort();
            double previous=Double.NaN;boolean open=false;double openFrom=0;
            for(int c=0;c<sweep.cuts;c++){
                double t=sweep.cut[c];
                if(t<0||t>1)continue;
                if(!Double.isNaN(previous)&&t-previous>1e-9){
                    double mid=(previous+t)/2,qx=ax+dx*mid,qy=ay+dy*mid;
                    sweep.sweepTo(mid);
                    boolean contour=sweep.covered(near,qx+nx,qy+ny)!=sweep.covered(near,qx-nx,qy-ny);
                    if(contour&&!open){open=true;openFrom=previous;}
                    if(!contour&&open){open=false;add(result,s,za,zb,openFrom,previous,ex,ey,ez);}
                }
                previous=t;
            }
            if(open)add(result,s,za,zb,openFrom,1,ex,ey,ez);
        }
        return result;
    }

    /**
     * The projected faces sorted into a grid of cells over the picture, so a stretch of line is only tested against the faces whose box
     * touches a cell it passes through. It finds exactly the faces the plain scan of every face finds.
     */
    private static final class Grid {
        private static final int MAX_CELLS_PER_SIDE=128;
        private final List<Poly> polys;
        private final double minX,minY,cellW,cellH;
        private final int across;
        private final int[][] cells;
        private final int[] seen;
        private int visit;

        Grid(List<Poly> polys){
            this.polys=polys;
            double lowX=Double.MAX_VALUE,lowY=Double.MAX_VALUE,highX=-Double.MAX_VALUE,highY=-Double.MAX_VALUE;
            for(var p:polys){lowX=Math.min(lowX,p.minX);lowY=Math.min(lowY,p.minY);highX=Math.max(highX,p.maxX);highY=Math.max(highY,p.maxY);}
            across=Math.max(1,Math.min(MAX_CELLS_PER_SIDE,(int)Math.sqrt(polys.size())));
            minX=lowX;minY=lowY;
            cellW=Math.max((highX-lowX)/across,1e-12);cellH=Math.max((highY-lowY)/across,1e-12);
            seen=new int[polys.size()];
            // Count the faces in each cell first, so the lists are made at their exact size.
            int[] counts=new int[across*across];
            for(var p:polys)for(int y=cellY(p.minY);y<=cellY(p.maxY);y++)for(int x=cellX(p.minX);x<=cellX(p.maxX);x++)counts[y*across+x]++;
            cells=new int[across*across][];
            for(int i=0;i<cells.length;i++)cells[i]=new int[counts[i]];
            int[] filled=new int[across*across];
            for(int i=0;i<polys.size();i++){var p=polys.get(i);
                for(int y=cellY(p.minY);y<=cellY(p.maxY);y++)for(int x=cellX(p.minX);x<=cellX(p.maxX);x++){int c=y*across+x;cells[c][filled[c]++]=i;}}
        }
        private int cellX(double x){return Math.max(0,Math.min(across-1,(int)((x-minX)/cellW)));}
        private int cellY(double y){return Math.max(0,Math.min(across-1,(int)((y-minY)/cellH)));}

        /** Adds to {@code out} the faces whose box overlaps the given box, and returns how many faces it had to look at to find them. */
        long query(double x0,double y0,double x1,double y1,List<Poly> out){
            visit++;
            long looked=0;
            for(int y=cellY(y0);y<=cellY(y1);y++)for(int x=cellX(x0);x<=cellX(x1);x++)
                for(int i:cells[y*across+x]){
                    if(seen[i]==visit)continue;
                    seen[i]=visit;looked++;
                    var p=polys.get(i);
                    if(p.maxX>=x0&&p.minX<=x1&&p.maxY>=y0&&p.minY<=y1)out.add(p);
                }
            return looked;
        }
    }

    /**
     * Working space for one segment, reused from segment to segment. It holds the points along the segment where the picture can change,
     * and sweeps along it with the set of faces whose stretch of the line includes the point being asked about, so a point is only
     * tested against the few faces that can reach it and not every face near the segment.
     */
    private static final class Sweep {
        double[] cut=new double[64];int cuts;
        private double[] low=new double[64],high=new double[64];
        private int[] order=new int[64],spare=new int[64],active=new int[64];
        private int count,added,live;

        void begin(int faces){
            cuts=0;count=faces;added=0;live=0;
            if(low.length<faces){int n=Math.max(faces,low.length*2);low=new double[n];high=new double[n];order=new int[n];spare=new int[n];active=new int[n];}
        }
        void cut(double t){if(cuts==cut.length)cut=java.util.Arrays.copyOf(cut,cuts*2);cut[cuts++]=t;}
        void span(int face,double from,double to){low[face]=from;high[face]=to;}
        /** Puts the cuts in order, and the faces in order of where their stretch starts. */
        void sort(){
            java.util.Arrays.sort(cut,0,cuts);
            for(int i=0;i<count;i++)order[i]=i;
            // Bottom-up merge sort of the face numbers by where their stretch starts.
            int[] from=order,to=spare;
            for(int width=1;width<count;width*=2){
                for(int start=0;start<count;start+=2*width){
                    int mid=Math.min(start+width,count),end=Math.min(start+2*width,count),l=start,r=mid,o=start;
                    while(l<mid&&r<end)to[o++]=low[from[l]]<=low[from[r]]?from[l++]:from[r++];
                    while(l<mid)to[o++]=from[l++];
                    while(r<end)to[o++]=from[r++];
                }
                int[] swap=from;from=to;to=swap;
            }
            if(from!=order){System.arraycopy(from,0,order,0,count);}
        }
        /** Makes the active faces those whose stretch includes {@code t}. Asked for in rising order. */
        void sweepTo(double t){
            while(added<count&&low[order[added]]<=t)active[live++]=order[added++];
            int kept=0;
            for(int i=0;i<live;i++)if(high[active[i]]>=t)active[kept++]=active[i];
            live=kept;
        }
        boolean covered(List<Poly> polys,double x,double y){
            for(int i=0;i<live;i++){
                var p=polys.get(active[i]);
                if(x>=p.minX&&x<=p.maxX&&y>=p.minY&&y<=p.maxY&&p.covers(x,y))return true;
            }
            return false;
        }
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
