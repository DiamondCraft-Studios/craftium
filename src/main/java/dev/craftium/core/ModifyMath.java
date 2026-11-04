package dev.craftium.core;

import java.util.*;

/**
 * Where the Modify tool puts a selection's blocks. The server builds the edit from this and the client draws the live preview from it,
 * so what is shown is exactly what is applied. The result maps each destination to the selected block it is copied from.
 */
public final class ModifyMath {
    private ModifyMath() {}
    public static Geometry.Vector turn(Geometry.Vector v,Options.ModifyAxis axis,double degrees){
        double r=Math.toRadians(degrees),c=Math.cos(r),s=Math.sin(r);
        return switch(axis){
            case X->new Geometry.Vector(v.x(),v.y()*c-v.z()*s,v.y()*s+v.z()*c);
            case Y->new Geometry.Vector(v.x()*c+v.z()*s,v.y(),-v.x()*s+v.z()*c);
            case Z->new Geometry.Vector(v.x()*c-v.y()*s,v.x()*s+v.y()*c,v.z());
        };
    }
    public static double along(Geometry.Vector v,Options.ModifyAxis axis){return switch(axis){case X->v.x();case Y->v.y();case Z->v.z();};}
    public static double away(Geometry.Vector v,Options.ModifyAxis axis){return switch(axis){case X->Math.hypot(v.y(),v.z());case Y->Math.hypot(v.x(),v.z());case Z->Math.hypot(v.x(),v.y());};}
    /** Whether the mode turns around the block under the cursor, which is what the guide circles are drawn about. */
    public static boolean usesCursor(Options o){
        return o.modifyMode==Options.ModifyMode.REVOLVE||o.modifyMode==Options.ModifyMode.ROTATE_COPIES&&o.modifyType==Options.RotateType.EQUIDISTANT;
    }
    /**
     * @param sources the selected blocks that are copied (air is left out unless it is to be pasted)
     * @param min the corner of the whole selection
     * @param max the opposite corner of the whole selection
     * @param cursor the block under the cursor, or null with none
     */
    public static LinkedHashMap<Point,Point> plan(Options o,Collection<Point> sources,Point min,Point max,Point cursor){
        var out=new LinkedHashMap<Point,Point>();
        if(sources.isEmpty())return out;
        var axis=o.modifyAxis;var translation=new Geometry.Vector(o.modifyTranslateX,o.modifyTranslateY,o.modifyTranslateZ);
        var middle=Geometry.vector(min).add(Geometry.vector(max)).scale(.5);
        switch(o.modifyMode){
            case TRANSLATE_COPIES -> {
                // Relative offsets are multiplied by the size of the selection; absolute offsets are plain block offsets.
                int sx=o.modifyRelative?max.x()-min.x()+1:1,sy=o.modifyRelative?max.y()-min.y()+1:1,sz=o.modifyRelative?max.z()-min.z()+1:1;
                if((long)o.copies*sources.size()>Selection.LIMIT*4L)throw new IllegalArgumentException("Too many copies");
                for(int i=1;i<=o.copies;i++)for(Point at:sources)out.put(at.add(o.offsetX*sx*i,o.offsetY*sy*i,o.offsetZ*sz*i),at);
            }
            case REVOLVE -> {
                if(cursor==null)return out;
                var centre=Geometry.vector(cursor);double reach=1;
                for(Point at:sources)reach=Math.max(reach,away(Geometry.vector(at).subtract(centre),axis)+1);
                int steps=(int)Math.clamp(Math.ceil(Math.toRadians(Math.abs(o.modifyAngle))*reach*3),1,8192);
                if((long)steps*sources.size()>Selection.LIMIT*4L)throw new IllegalArgumentException("Revolve is too large");
                for(int k=0;k<=steps;k++){
                    double t=(double)k/steps;
                    for(Point at:sources){
                        var v=turn(Geometry.vector(at).subtract(centre),axis,o.modifyAngle*t);
                        out.put((o.modifyTranslate?v.add(translation.scale(t)):v).add(centre).voxel(),at);
                    }
                }
            }
            case ROTATE_COPIES -> {
                boolean equidistant=o.modifyType==Options.RotateType.EQUIDISTANT;
                if(equidistant&&cursor==null)return out;
                // Equidistant copies turn around the cursor and share the circle with the original; custom angles turn each copy about itself.
                var centre=equidistant?Geometry.vector(cursor):middle;
                double step=equidistant?360d/(o.copies+1):o.modifyAngle;
                var lookup=new HashSet<>(sources);
                double reach=1;for(Point at:sources)reach=Math.max(reach,Geometry.vector(at).subtract(centre).length()+1);
                int r=(int)Math.ceil(reach);if((long)(2*r+1)*(2*r+1)*(2*r+1)*o.copies>Selection.LIMIT*8L)throw new IllegalArgumentException("Rotate copies is too large");
                for(int i=1;i<=o.copies;i++){
                    var shift=o.modifyTranslate?translation.scale((double)i/o.copies):new Geometry.Vector(0,0,0);
                    var base=centre.add(shift);int cx=(int)Math.round(base.x()),cy=(int)Math.round(base.y()),cz=(int)Math.round(base.z());
                    // Built from the destination back to the source so a turn by any angle leaves no gaps.
                    for(int x=cx-r;x<=cx+r;x++)for(int y=cy-r;y<=cy+r;y++)for(int z=cz-r;z<=cz+r;z++){
                        var back=turn(new Geometry.Vector(x,y,z).subtract(shift).subtract(centre),axis,-step*i).add(centre).voxel();
                        if(lookup.contains(back))out.put(new Point(x,y,z),back);
                    }
                }
            }
            case TWIST -> {
                var lookup=new HashSet<>(sources);
                double lo=along(Geometry.vector(min),axis),span=Math.max(1,along(Geometry.vector(max),axis)-lo);
                double reach=1;for(Point at:sources)reach=Math.max(reach,away(Geometry.vector(at).subtract(middle),axis)+1);
                int r=(int)Math.ceil(reach)+1;
                int rx=axis==Options.ModifyAxis.X?0:r,ry=axis==Options.ModifyAxis.Y?0:r,rz=axis==Options.ModifyAxis.Z?0:r;
                for(int x=min.x()-rx;x<=max.x()+rx;x++)for(int y=min.y()-ry;y<=max.y()+ry;y++)for(int z=min.z()-rz;z<=max.z()+rz;z++){
                    var d=new Geometry.Vector(x,y,z);double angle=o.modifyAngle*(along(d,axis)-lo)/span;
                    var back=turn(d.subtract(middle),axis,-angle).add(middle).voxel();
                    if(lookup.contains(back))out.put(new Point(x,y,z),back);
                }
            }
        }
        return out;
    }
}
