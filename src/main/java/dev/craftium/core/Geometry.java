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
    public static List<Point> shape(Point center,Options o) {
        if(o.shapeKind==Options.ShapeKind.BRUSH&&o.yaw==0&&o.pitch==0&&o.roll==0&&o.sizeX==1&&o.sizeY==1&&o.sizeZ==1)
            return BrushMath.brush(center,o.radius,o.shape,o.hollow);
        double scaled=o.yaw==0&&o.pitch==0&&o.roll==0?Math.max(o.sizeX,Math.max(o.sizeY,o.sizeZ)):Math.sqrt(o.sizeX*o.sizeX+o.sizeY*o.sizeY+o.sizeZ*o.sizeZ);
        int extent=(int)Math.ceil(o.radius*scaled);
        if(extent>64)throw new IllegalArgumentException("Scaled shape radius exceeds 64 blocks");var result=new ArrayList<Point>();
        // The turn is the same for every cell, so its sines and cosines are worked out once; this is inverseRotate unrolled.
        double ra=Math.toRadians(-o.yaw),rb=Math.toRadians(-o.pitch),rc=Math.toRadians(-o.roll);
        double ca=Math.cos(ra),sa=Math.sin(ra),cb=Math.cos(rb),sb=Math.sin(rb),cc=Math.cos(rc),sc=Math.sin(rc);
        for(int x=-extent;x<=extent;x++)for(int y=-extent;y<=extent;y++)for(int z=-extent;z<=extent;z++){
            double px=x*cc-y*sc,py=x*sc+y*cc,pz=z,qy=py*cb-pz*sb;pz=py*sb+pz*cb;py=qy;
            Vector v=new Vector(px*ca-pz*sa,py,px*sa+pz*ca);
            double a=v.x/(o.radius*o.sizeX),b=v.y/(o.radius*o.sizeY),c=v.z/(o.radius*o.sizeZ);
            if(inside(a,b,c,o) && (!o.hollow||shell(v,o))) {
                result.add(center.add(x,y,z));if(result.size()>Selection.LIMIT)throw new IllegalArgumentException("Shape exceeds block limit");
            }
        }
        return result;
    }
    private static boolean shell(Vector v,Options o){
        double r=Math.max(.25,o.radius-1);
        return !inside(v.x/(r*o.sizeX),v.y/(r*o.sizeY),v.z/(r*o.sizeZ),o);
    }
    private static boolean inside(double x,double y,double z,Options o) {return inside(x,y,z,o,o.shapeKind);}
    private static boolean inside(double x,double y,double z,Options o,Options.ShapeKind kind) {
        double radial=Math.sqrt(x*x+z*z),max=Math.max(Math.abs(x),Math.max(Math.abs(y),Math.abs(z))),reach=(o.radius+.5)/o.radius;
        return switch(kind){
            case BRUSH -> switch(o.shape){
                // The round shapes reach half a block past the radius, as in BrushMath, so they have no nub at the poles.
                case SPHERE->x*x+y*y+z*z<=reach*reach;case CUBE->max<=1;case OCTAHEDRON->Math.abs(x)+Math.abs(y)+Math.abs(z)<=1;case CYLINDER->radial<=reach&&Math.abs(y)<=1;
                case HEMISPHERE->y>=0&&x*x+y*y+z*z<=reach*reach;
                case DISK->radial<=reach&&Math.abs(y)<=.5/o.radius;
                case CONE->Math.abs(y)<=1&&radial<=reach*(1-y)/2;
                case TORUS->Math.pow(radial-.75,2)+y*y<=Math.pow(.25+.5/o.radius,2);
                case TUBE->radial<=reach&&radial>=.5&&Math.abs(y)<=1;
                case PLANE->inside(x,y,z,o,Options.ShapeKind.PLANE);case PYRAMID->inside(x,y,z,o,Options.ShapeKind.PYRAMID);
            };
            case SPHERE -> x*x+y*y+z*z<=1;
            case CUBOID -> max<=1;
            case OCTAHEDRON -> Math.abs(x)+Math.abs(y)+Math.abs(z)<=1;
            case SUPERSPHERE -> Math.pow(Math.abs(x),o.exponent)+Math.pow(Math.abs(y),o.exponent)+Math.pow(Math.abs(z),o.exponent)<=1;
            case CYLINDER -> radial<=1&&Math.abs(y)<=1;
            case TUBE -> radial<=1&&radial>=o.innerRadius&&Math.abs(y)<=1;
            case CONE -> Math.abs(y)<=1&&radial<=(1-y)/2;
            case PYRAMID -> Math.abs(y)<=1&&Math.max(Math.abs(x),Math.abs(z))<=(1-y)/2;
            case TORUS -> Math.pow(radial-(1+o.innerRadius)/2,2)+y*y<=Math.pow((1-o.innerRadius)/2,2);
            case DISK -> radial<=1&&Math.abs(y)<=.5/o.radius;
            case PLANE -> Math.abs(x)<=1&&Math.abs(z)<=1&&Math.abs(y)<=.5/o.radius;
            case POLYGON -> polygon(x,z,o.polygonSides)&&Math.abs(y)<=.5/o.radius;
            case SPIRAL -> {double angle=(Math.atan2(z,x)+Math.PI)/(Math.PI*2);double phase=((radial*3-angle)%1+1)%1;yield radial<=1&&Math.abs(y)<=.5/o.radius&&(phase<.06||phase>.94);}
            case DODECAHEDRON -> polyhedron(x,y,z,true);
            case ICOSAHEDRON -> polyhedron(x,y,z,false);
        };
    }
    private static boolean polygon(double x,double z,int sides){
        for(int i=0;i<sides;i++){double a=2*Math.PI*i/sides;if(x*Math.cos(a)+z*Math.sin(a)>Math.cos(Math.PI/sides))return false;}return true;
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
