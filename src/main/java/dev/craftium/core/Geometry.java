package dev.craftium.core;

import java.util.*;

/** Geometry shared by previews and the authoritative server planner. */
public final class Geometry {
    private Geometry() {}
    public record Vector(double x,double y,double z) {
        public Vector add(Vector p){return new Vector(x+p.x,y+p.y,z+p.z);}
        public Vector subtract(Vector p){return new Vector(x-p.x,y-p.y,z-p.z);}
        public Vector scale(double n){return new Vector(x*n,y*n,z*n);}
        public double dot(Vector p){return x*p.x+y*p.y+z*p.z;}
        public Vector cross(Vector p){return new Vector(y*p.z-z*p.y,z*p.x-x*p.z,x*p.y-y*p.x);}
        public double length(){return Math.sqrt(dot(this));}
        public Point voxel(){return new Point((int)Math.round(x),(int)Math.round(y),(int)Math.round(z));}
    }
    public static Vector vector(Point p){return new Vector(p.x(),p.y(),p.z());}
    public static Vector rotate(Vector p,double yaw,double pitch,double roll) {
        double a=Math.toRadians(yaw),b=Math.toRadians(pitch),c=Math.toRadians(roll);
        double x=p.x*Math.cos(a)-p.z*Math.sin(a),z=p.x*Math.sin(a)+p.z*Math.cos(a),y=p.y;
        double yy=y*Math.cos(b)-z*Math.sin(b);z=y*Math.sin(b)+z*Math.cos(b);y=yy;
        return new Vector(x*Math.cos(c)-y*Math.sin(c),x*Math.sin(c)+y*Math.cos(c),z);
    }
    public static Vector inverseRotate(Vector p,double yaw,double pitch,double roll) {
        double a=Math.toRadians(-yaw),b=Math.toRadians(-pitch),c=Math.toRadians(-roll);
        double x=p.x*Math.cos(c)-p.y*Math.sin(c),y=p.x*Math.sin(c)+p.y*Math.cos(c),z=p.z;
        double yy=y*Math.cos(b)-z*Math.sin(b);z=y*Math.sin(b)+z*Math.cos(b);y=yy;
        return new Vector(x*Math.cos(a)-z*Math.sin(a),y,x*Math.sin(a)+z*Math.cos(a));
    }
    /**
     * The point that a drag's transform sends to {@code image}. The transform scales a point by {@code size} about {@code about}, turns it
     * by yaw, pitch and roll (as {@link #rotate} does), puts it back about the same point and then adds {@code offset}. Null when the
     * transform squashes the shape flat along some axis, so there is no way back.
     */
    public static Vector untransform(Vector image,Vector about,Vector size,double yaw,double pitch,double roll,Vector offset){
        if(Math.abs(size.x)<1e-9||Math.abs(size.y)<1e-9||Math.abs(size.z)<1e-9)return null;
        var turned=inverseRotate(image.subtract(offset).subtract(about),yaw,pitch,roll);
        return new Vector(about.x+turned.x/size.x,about.y+turned.y/size.y,about.z+turned.z/size.z);
    }
    public static List<Point> shape(Point center,Options o) {
        if(o.shapeKind==Options.ShapeKind.BRUSH&&o.yaw==0&&o.pitch==0&&o.roll==0&&o.sizeX==1&&o.sizeY==1&&o.sizeZ==1)
            return BrushMath.brush(center,o.radius,o.shape,o.hollow,o.outlineThickness,o.pixelPerfect,o.outlineShift(),o.polygonSides,o.outline,Selection.LIMIT,o.form());
        if(o.shapeKind==Options.ShapeKind.BRUSH&&o.sizeX==1&&o.sizeY==1&&o.sizeZ==1)return turned(center,o);
        double scaled=o.yaw==0&&o.pitch==0&&o.roll==0?Math.max(o.sizeX,Math.max(o.sizeY,o.sizeZ)):Math.sqrt(o.sizeX*o.sizeX+o.sizeY*o.sizeY+o.sizeZ*o.sizeZ);
        double outer=o.outerRadius();
        int extent=(int)Math.ceil(outer*scaled);
        if(extent>BrushMath.MAX_REACH)throw new IllegalArgumentException("Scaled shape reaches "+extent+" blocks from its centre; the most is "+BrushMath.MAX_REACH);
        if((2L*extent+1)*(2L*extent+1)*(2L*extent+1)>ShapeLimits.SCAN_CELLS)throw new IllegalArgumentException("A scaled or turned shape this big cannot be built; make it smaller");var result=new ArrayList<Point>();
        // The turn is the same for every cell, so its sines and cosines are worked out once; this is inverseRotate unrolled.
        double ra=Math.toRadians(-o.yaw),rb=Math.toRadians(-o.pitch),rc=Math.toRadians(-o.roll);
        double ca=Math.cos(ra),sa=Math.sin(ra),cb=Math.cos(rb),sb=Math.sin(rb),cc=Math.cos(rc),sc=Math.sin(rc);
        for(int x=-extent;x<=extent;x++)for(int y=-extent;y<=extent;y++)for(int z=-extent;z<=extent;z++){
            double px=x*cc-y*sc,py=x*sc+y*cc,pz=z,qy=py*cb-pz*sb;pz=py*sb+pz*cb;py=qy;
            Vector v=new Vector(px*ca-pz*sa,py,px*sa+pz*ca);
            double a=v.x/(outer*o.sizeX),b=v.y/(outer*o.sizeY),c=v.z/(outer*o.sizeZ);
            if(inside(a,b,c,o,o.shapeKind,.5/(outer*o.sizeX),.5/(outer*o.sizeY),.5/(outer*o.sizeZ)) && (!o.hollow||o.pixelPerfect||shell(v,o))) {
                result.add(center.add(x,y,z));if(result.size()>Selection.LIMIT)throw new IllegalArgumentException("Shape exceeds block limit");
            }
        }
        if(o.outline)return BrushMath.edges(result);
        return o.hollow&&o.pixelPerfect?OutlineMath.thin(result,Math.max(1,o.outlineThickness)):result;
    }
    /**
     * A brush shape turned, without being scaled. It is the very shape the brush makes, blocks and all, turned about its centre: every cell
     * of the result looks back to the nearest cell of the unturned shape. Judging the cells against a formula instead loses the half block
     * around the edge that the blocks of an unturned shape have (a flat square comes out a whole row smaller and its corners round off),
     * and cannot know the wall, alignment and pixel perfect settings. This way a quarter turn is exact and no angle changes the shape.
     */
    private static List<Point> turned(Point center,Options o){
        // An outline and a pixel perfect wall are worked out on the turned solid, since a line of single blocks does not survive being turned.
        boolean afterwards=o.outline||o.hollow&&o.pixelPerfect;
        // A wall one block thick does not survive being turned either: looking each cell back to the nearest cell of a thin wall tears holes in it.
        // A closed shape gets the blocks that close them (below). A quarter turn puts every cell exactly on another, so it tears nothing and is left as it is.
        boolean tears=o.hollow&&!o.outline&&closed(o.shape)&&!(quarter(o.yaw)&&quarter(o.pitch)&&quarter(o.roll));
        boolean patch=tears&&!o.pixelPerfect,repair=tears&&o.pixelPerfect;
        var base=BrushMath.brush(new Point(0,0,0),o.radius,o.shape,o.hollow&&!afterwards,o.outlineThickness,o.pixelPerfect,o.outlineShift(),o.polygonSides,false,Selection.LIMIT,o.form());
        int e=0;double far=0;
        for(Point p:base){e=Math.max(e,Math.max(Math.abs(p.x()),Math.max(Math.abs(p.y()),Math.abs(p.z()))));far=Math.max(far,(double)p.x()*p.x()+(double)p.y()*p.y()+(double)p.z()*p.z());}
        int extent=(int)Math.ceil(Math.sqrt(far));
        if(extent>BrushMath.MAX_REACH)throw new IllegalArgumentException("Turned shape reaches "+extent+" blocks from its centre; the most is "+BrushMath.MAX_REACH);
        if((2L*extent+1)*(2L*extent+1)*(2L*extent+1)>ShapeLimits.SCAN_CELLS||(2L*e+1)*(2L*e+1)*(2L*e+1)>ShapeLimits.SCAN_CELLS)throw new IllegalArgumentException("A scaled or turned shape this big cannot be built; make it smaller");
        int n=2*e+1;var grid=new boolean[n*n*n];
        for(Point p:base)grid[((p.x()+e)*n+p.y()+e)*n+p.z()+e]=true;
        var back=new Back(o.yaw,o.pitch,o.roll);
        // The solid the wall belongs to, judged where a cell looks back to, as the brush judges it.
        int outer=Math.min(o.radius+Math.max(0,o.outlineShift()),BrushMath.MAX_REACH);
        List<Point> result=new ArrayList<>();
        for(int x=-extent;x<=extent;x++)for(int y=-extent;y<=extent;y++)for(int z=-extent;z<=extent;z++){
            back.at(x,y,z);
            int gx=back.x,gy=back.y,gz=back.z;
            boolean hit=!(gx<-e||gx>e||gy<-e||gy>e||gz<-e||gz>e)&&grid[((gx+e)*n+gy+e)*n+gz+e];
            if(!hit&&patch&&BrushMath.contains(o.shape,gx,gy,gz,outer,o.polygonSides,o.form())){
                // Inside the shape, and a face of this block meets a block that is not: it is part of the skin, which no path can pass.
                for(Point f:FACES){back.at(x+f.x(),y+f.y(),z+f.z());if(!BrushMath.contains(o.shape,back.x,back.y,back.z,outer,o.polygonSides,o.form())){hit=true;break;}}
            }
            if(!hit)continue;
            result.add(center.add(x,y,z));if(result.size()>Selection.LIMIT)throw new IllegalArgumentException("Shape exceeds block limit");
        }
        if(o.outline)return BrushMath.edges(result);
        if(!(o.hollow&&o.pixelPerfect))return result;
        var thin=OutlineMath.thin(result,Math.max(1,o.outlineThickness));
        return repair?OutlineMath.seal(result,thin):thin;
    }
    /** Where a cell of a turned shape looks back to in the unturned one: the nearest cell, as {@link #rotate} turns it. */
    private static final class Back{
        final double ca,sa,cb,sb,cc,sc;int x,y,z;
        Back(double yaw,double pitch,double roll){
            double ra=Math.toRadians(-yaw),rb=Math.toRadians(-pitch),rc=Math.toRadians(-roll);
            ca=Math.cos(ra);sa=Math.sin(ra);cb=Math.cos(rb);sb=Math.sin(rb);cc=Math.cos(rc);sc=Math.sin(rc);
        }
        void at(int cx,int cy,int cz){
            double px=cx*cc-cy*sc,py=cx*sc+cy*cc,pz=cz,qy=py*cb-pz*sb;pz=py*sb+pz*cb;py=qy;
            double vx=px*ca-pz*sa,vz=px*sa+pz*ca;
            x=(int)Math.floor(vx+.5+1e-9);y=(int)Math.floor(py+.5+1e-9);z=(int)Math.floor(vz+.5+1e-9);
        }
    }
    private static boolean quarter(double degrees){return Math.abs(degrees/90-Math.rint(degrees/90))<1e-9;}
    /** The brush shapes with no open side, whose hollow form is a sealed shell. */
    private static boolean closed(BrushMath.Shape shape){
        return switch(shape){case SPHERE,CUBE,CYLINDER,CONE,PYRAMID,OCTAHEDRON,TORUS->true;default->false;};
    }
    private static final Point[] FACES={new Point(1,0,0),new Point(-1,0,0),new Point(0,1,0),new Point(0,-1,0),new Point(0,0,1),new Point(0,0,-1)};
    private static boolean shell(Vector v,Options o){
        // The wall is as many blocks thick as the setting says whatever the size: each axis gives up that many blocks, not that share of its length.
        double t=Math.clamp(o.outlineThickness,1,Math.max(1,o.radius)),outer=o.outerRadius();
        double rx=Math.max(.25,outer*o.sizeX-t),ry=Math.max(.25,outer*o.sizeY-t),rz=Math.max(.25,outer*o.sizeZ-t);
        return !inside(v.x/rx,v.y/ry,v.z/rz,o,o.shapeKind,.5/rx,.5/ry,.5/rz);
    }
    /**
     * Whether a point, in units of the shape's own radius along each axis, is inside it. {@code px}, {@code py} and {@code pz} are half a
     * block in those units: the flat faces of a box reach that far past the radius, because the blocks along them cover half a block more,
     * and a scaled or turned shape would otherwise come out a row short around its edge.
     */
    private static boolean inside(double x,double y,double z,Options o,Options.ShapeKind kind,double px,double py,double pz) {
        double radial=Math.sqrt(x*x+z*z),max=Math.max(Math.abs(x),Math.max(Math.abs(y),Math.abs(z))),outer=o.outerRadius(),reach=(outer+.5)/outer;
        return switch(kind){
            case BRUSH -> switch(o.shape){
                // The round shapes reach half a block past the radius, as in BrushMath, so they have no nub at the poles.
                case SPHERE->x*x+y*y+z*z<=reach*reach;case CUBE->Math.abs(x)<=1+px&&Math.abs(y)<=1+py&&Math.abs(z)<=1+pz;case OCTAHEDRON->Math.abs(x)+Math.abs(y)+Math.abs(z)<=1;case CYLINDER->radial<=reach&&Math.abs(y)<=1+py;
                case HEMISPHERE->y>=0&&x*x+y*y+z*z<=reach*reach;
                case DISK->radial<=reach&&Math.abs(y)<=.5/outer;
                case CONE->Math.abs(y)<=1&&radial<=reach*(1-y)/2;
                case TORUS->Math.pow(radial-.75,2)+y*y<=Math.pow(.25+.5/outer,2);
                case TUBE->radial<=reach&&radial>=.5&&Math.abs(y)<=1+py;
                case POLYGON->radial<=reach&&Math.abs(y)<=.5/outer&&polygon(x,z,o.polygonSides,reach);
                case IRREGULAR_POLYGON,STAR->radial<=reach&&Math.abs(y)<=.5/outer&&BrushMath.fanContains(o.shape,Math.max(BrushMath.MIN_SIDES,o.polygonSides),o.form(),x,z,reach);
                case PLANE->inside(x,y,z,o,Options.ShapeKind.PLANE,px,py,pz);case PYRAMID->inside(x,y,z,o,Options.ShapeKind.PYRAMID,px,py,pz);
            };
            case SPHERE -> x*x+y*y+z*z<=1;
            case CUBOID -> Math.abs(x)<=1+px&&Math.abs(y)<=1+py&&Math.abs(z)<=1+pz;
            case OCTAHEDRON -> Math.abs(x)+Math.abs(y)+Math.abs(z)<=1;
            case SUPERSPHERE -> Math.pow(Math.abs(x),o.exponent)+Math.pow(Math.abs(y),o.exponent)+Math.pow(Math.abs(z),o.exponent)<=1;
            case CYLINDER -> radial<=1&&Math.abs(y)<=1;
            case TUBE -> radial<=1&&radial>=o.innerRadius&&Math.abs(y)<=1;
            case CONE -> Math.abs(y)<=1&&radial<=(1-y)/2;
            case PYRAMID -> Math.abs(y)<=1&&Math.max(Math.abs(x),Math.abs(z))<=(1-y)/2;
            case TORUS -> Math.pow(radial-(1+o.innerRadius)/2,2)+y*y<=Math.pow((1-o.innerRadius)/2,2);
            case DISK -> radial<=1&&Math.abs(y)<=.5/outer;
            case PLANE -> Math.abs(x)<=1+px&&Math.abs(z)<=1+pz&&Math.abs(y)<=.5/outer;
            case POLYGON -> polygon(x,z,o.polygonSides)&&Math.abs(y)<=.5/outer;
            case SPIRAL -> {double angle=(Math.atan2(z,x)+Math.PI)/(Math.PI*2);double phase=((radial*3-angle)%1+1)%1;yield radial<=1&&Math.abs(y)<=.5/outer&&(phase<.06||phase>.94);}
            case DODECAHEDRON -> polyhedron(x,y,z,true);
            case ICOSAHEDRON -> polyhedron(x,y,z,false);
        };
    }
    private static boolean polygon(double x,double z,int sides){return polygon(x,z,sides,1);}
    private static boolean polygon(double x,double z,int sides,double reach){
        for(int i=0;i<sides;i++){double a=2*Math.PI*i/sides;if(x*Math.cos(a)+z*Math.sin(a)>reach*Math.cos(Math.PI/sides))return false;}return true;
    }
    private static boolean polyhedron(double x,double y,double z,boolean dodeca) {
        double phi=(1+Math.sqrt(5))/2;
        if(dodeca){for(int a:new int[]{-1,1})for(int b:new int[]{-1,1})if(Math.abs(a*x+b*phi*y)>phi||Math.abs(a*y+b*phi*z)>phi||Math.abs(a*z+b*phi*x)>phi)return false;}
        else {
            if(Math.abs(x)+Math.abs(y)+Math.abs(z)>phi)return false;
            for(int a:new int[]{-1,1})for(int b:new int[]{-1,1})if(Math.abs(a*x/phi+b*phi*y)>phi||Math.abs(a*y/phi+b*phi*z)>phi||Math.abs(a*z/phi+b*phi*x)>phi)return false;
        }return true;
    }
    public static List<Point> path(List<Point> nodes,Options o) {
        if(nodes.size()<2)throw new IllegalArgumentException("Path needs at least two points");
        var out=new ArrayList<Point>();int segments=o.looped?nodes.size():nodes.size()-1;
        if(o.pathMode==Options.PathMode.BEZIER){
            for(int i=0;i<=512;i++){double t=i/512d;var row=nodes.stream().map(Geometry::vector).toArray(Vector[]::new);for(int n=row.length-1;n>0;n--)for(int j=0;j<n;j++)row[j]=row[j].scale(1-t).add(row[j+1].scale(t));out.add(row[0].voxel());}
        }else for(int n=0;n<segments;n++){
            Point a=nodes.get(n),b=nodes.get((n+1)%nodes.size());
            if(o.pathMode==Options.PathMode.BRESENHAM||o.pathMode==Options.PathMode.DDA){out.addAll(BrushMath.line(a,b));continue;}
            int samples=Math.clamp((int)Math.ceil(Math.sqrt(a.distanceSquared(b))*4),4,512);
            Vector p0=vector(nodes.get(Math.max(0,n-1))),p1=vector(a),p2=vector(b),p3=vector(nodes.get(Math.min(nodes.size()-1,n+2)));
            for(int i=0;i<=samples;i++){
                double t=(double)i/samples;Vector p;
                if(o.pathMode==Options.PathMode.CATENARY){double k=2;double dip=(Math.cosh(k)-Math.cosh((2*t-1)*k))/(Math.cosh(k)-1)*o.slack;p=p1.scale(1-t).add(p2.scale(t)).add(new Vector(0,-dip,0));}
                else {double t2=t*t,t3=t2*t;p=p1.scale(2).add(p2.subtract(p0).scale(t)).add(p0.scale(2).subtract(p1.scale(5)).add(p2.scale(4)).subtract(p3).scale(t2)).add(p0.scale(-1).add(p1.scale(3)).subtract(p2.scale(3)).add(p3).scale(t3)).scale(.5);}
                out.add(p.voxel());
            }
        }
        return List.copyOf(out);
    }
    public static Set<Point> triangle(Point a,Point b,Point c) {
        var result=new LinkedHashSet<Point>();Vector av=vector(a),bv=vector(b),cv=vector(c);
        int steps=Math.clamp((int)Math.ceil(Math.max(bv.subtract(av).length(),Math.max(cv.subtract(av).length(),cv.subtract(bv).length()))*2),1,512);
        for(int i=0;i<=steps;i++)for(int j=0;j<=steps-i;j++){
            double u=(double)i/steps,v=(double)j/steps;result.add(av.scale(1-u-v).add(bv.scale(u)).add(cv.scale(v)).voxel());
            if(result.size()>Selection.LIMIT)throw new IllegalArgumentException("Mesh exceeds block limit");
        }return result;
    }
    public static Set<Point> model(List<Point> nodes,Options o) {
        if(nodes.size()<3)throw new IllegalArgumentException("Modelling needs at least three points");
        if(nodes.size()==3)return triangle(nodes.get(0),nodes.get(1),nodes.get(2));
        var out=new LinkedHashSet<Point>();
        if(o.modelMode==Options.ModelMode.CATMULL_ROM||o.modelMode==Options.ModelMode.BEZIER){
            int columns=Math.min(o.modelColumns,nodes.size()/2);var paths=new ArrayList<List<Point>>();var pathOptions=new Options();pathOptions.pathMode=o.modelMode==Options.ModelMode.BEZIER?Options.PathMode.BEZIER:Options.PathMode.CATMULL_ROM;
            for(int column=0;column<columns;column++){var row=new ArrayList<Point>();for(int i=column;i<nodes.size();i+=columns)row.add(nodes.get(i));paths.add(path(row,pathOptions));}
            int steps=paths.stream().mapToInt(List::size).max().orElse(2)-1;
            for(int c=0;c<columns-1;c++)for(int i=0;i<steps;i++){
                var a=paths.get(c);var b=paths.get(c+1);Point a0=a.get(i*(a.size()-1)/steps),a1=a.get((i+1)*(a.size()-1)/steps),b0=b.get(i*(b.size()-1)/steps),b1=b.get((i+1)*(b.size()-1)/steps);
                out.addAll(triangle(a0,a1,b0));out.addAll(triangle(a1,b1,b0));if(out.size()>Selection.LIMIT)throw new IllegalArgumentException("Mesh exceeds block limit");
            }return out;
        }
        if(o.modelMode==Options.ModelMode.CONVEX_HULL||o.modelMode==Options.ModelMode.SMART){
            out.addAll(ConvexHull.fill(nodes));
        }else if(o.modelMode==Options.ModelMode.TRIANGLE_FAN){for(int i=1;i<nodes.size()-1;i++)out.addAll(triangle(nodes.getFirst(),nodes.get(i),nodes.get(i+1)));}
        else if(o.modelMode==Options.ModelMode.TRIANGLE_STRIP){for(int i=0;i<nodes.size()-2;i++)out.addAll(triangle(nodes.get(i),nodes.get(i+1),nodes.get(i+2)));}
        else {int columns=Math.min(o.modelColumns,nodes.size()/2);for(int i=0;i+columns+1<nodes.size();i++)if(i%columns<columns-1){out.addAll(triangle(nodes.get(i),nodes.get(i+1),nodes.get(i+columns)));out.addAll(triangle(nodes.get(i+1),nodes.get(i+columns+1),nodes.get(i+columns)));}}
        if(out.size()>Selection.LIMIT)throw new IllegalArgumentException("Mesh exceeds block limit");return out;
    }
}
