package dev.craftium.client;

import dev.craftium.core.*;
import dev.craftium.client.compat.Overlay;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * The Modify tool's live preview: the blocks where they would land, drawn from the very placement the server applies, and for the
 * tools that turn around the cursor the red inner and green outer circle of the sweep with a line from the cursor to each.
 * It is rebuilt whenever a setting, the selection or the block under the cursor changes.
 */
final class ModifyPreview {
    private ModifyPreview() {}
    /** Past this many blocks the preview is a box around where they land, because a frame would have to draw every one. */
    private static final int LIMIT=4096;
    private static final int INNER=0xffff6b6b,OUTER=0xff6bff8a,AXIS=0xffffd66d;
    private static Object[] key;
    private static List<BlockPos> models=List.of(),places=List.of();
    private static OutlineShape shape=OutlineShape.EMPTY;
    private static AABB bounds;
    private static String problem;
    private static boolean anchored;
    private static Point anchorAt;
    /** Whether the preview is fixed where it was put, instead of following the cursor. */
    static boolean anchored(){return anchored;}
    static Point anchor(){return anchorAt;}
    /** Fixes the preview at the cursor. Once it is anchored another click does nothing: the settings change that same preview. */
    static void place(Point cursor){if(anchored)return;anchored=true;anchorAt=cursor;}
    /** Lets the preview follow the cursor again. */
    static void edit(){anchored=false;anchorAt=null;}

    static void draw(Minecraft mc,Point cursor){
        var o=ClientState.options;var selected=SelectionOverlay.points();
        if(o.tool!=Tool.MODIFY||selected.isEmpty()||mc.level==null)return;
        if(anchored&&anchorAt!=null)cursor=anchorAt;
        if(ClientState.hasPending())return; // a waiting edit shows its own blocks
        var c=ModifyMath.usesCursor(o)?cursor:null;
        Object[] now={o.modifyMode,o.modifyAxis,o.modifyType,o.modifyAngle,o.modifyRelative,o.modifyTranslate,o.modifyTranslateX,o.modifyTranslateY,o.modifyTranslateZ,o.copies,o.offsetX,o.offsetY,o.offsetZ,o.pasteAir,c,selected};
        if(key==null||!sameKey(key,now)){key=now;rebuild(mc,o,selected,c);}
        if(problem!=null){return;}
        if(places.isEmpty()&&ModifyMath.usesCursor(o)&&cursor==null)return; // nothing to turn around until the cursor is on a block
        if(shape!=OutlineShape.EMPTY){
            shape.draw(Vec3.ZERO,(a,b)->Overlay.line(a,b,o.previewColor,1),o.previewColor,o.previewFill,true);
            if(!places.isEmpty())Overlay.blocks(models,places,o.previewTint);
        }else if(bounds!=null)Overlay.box(bounds,o.previewColor,2,o.previewFill);
        if(c!=null)guides(o,selected,cursor);
        else if(o.modifyMode==Options.ModifyMode.TWIST)axisLine(o,selected);
    }
    private static boolean sameKey(Object[] a,Object[] b){
        for(int i=0;i<a.length;i++)if(i==a.length-1?a[i]!=b[i]:!Objects.equals(a[i],b[i]))return false;
        return true;
    }
    private static void rebuild(Minecraft mc,Options o,List<Point> selected,Point cursor){
        models=List.of();places=List.of();shape=OutlineShape.EMPTY;bounds=null;problem=null;
        var sources=new ArrayList<Point>();
        for(Point at:selected)if(o.pasteAir||!mc.level.getBlockState(new BlockPos(at.x(),at.y(),at.z())).isAir())sources.add(at);
        if(sources.isEmpty())return;
        try{
            var placed=ModifyMath.plan(o,sources,lowest(selected),highest(selected),cursor);
            if(placed.isEmpty())return;
            if(placed.size()>LIMIT){
                int x0=Integer.MAX_VALUE,y0=x0,z0=x0,x1=Integer.MIN_VALUE,y1=x1,z1=x1;
                for(Point at:placed.keySet()){x0=Math.min(x0,at.x());y0=Math.min(y0,at.y());z0=Math.min(z0,at.z());x1=Math.max(x1,at.x());y1=Math.max(y1,at.y());z1=Math.max(z1,at.z());}
                bounds=new AABB(x0,y0,z0,x1+1,y1+1,z1+1);return;
            }
            var from=new ArrayList<BlockPos>(placed.size());var to=new ArrayList<BlockPos>(placed.size());
            placed.forEach((dest,source)->{to.add(new BlockPos(dest.x(),dest.y(),dest.z()));from.add(new BlockPos(source.x(),source.y(),source.z()));});
            models=from;places=to;shape=OutlineShape.of(placed.keySet());
        }catch(IllegalArgumentException e){problem=e.getMessage();}
    }
    /** Where a point lies on the circle about {@code centre} in the plane the axis is the normal of. */
    private static Vec3 around(Vec3 centre,Options.ModifyAxis axis,double radius,double angle){
        double a=Math.cos(angle)*radius,b=Math.sin(angle)*radius;
        return switch(axis){case X->centre.add(0,a,b);case Y->centre.add(a,0,b);case Z->centre.add(a,b,0);};
    }
    private static void circle(Vec3 centre,Options.ModifyAxis axis,double radius,int colour){
        if(radius<=0)return;
        for(int i=0;i<96;i++)Overlay.line(around(centre,axis,radius,i*Math.PI/48),around(centre,axis,radius,(i+1)*Math.PI/48),colour,1.5f);
    }
    /** The circle the nearest block of the selection sweeps (red) and the one the farthest sweeps (green), with a line from the cursor to where each starts. */
    private static void guides(Options o,List<Point> selected,Point cursor){
        var axis=o.modifyAxis;var centre=Geometry.vector(cursor);
        double near=Double.MAX_VALUE,far=0;Point nearest=null,farthest=null;
        for(Point at:selected){double d=ModifyMath.away(Geometry.vector(at).subtract(centre),axis);if(d<near){near=d;nearest=at;}if(d>far){far=d;farthest=at;}}
        if(nearest==null)return;
        Vec3 middle=new Vec3(cursor.x()+.5,cursor.y()+.5,cursor.z()+.5);
        double inner=Math.max(0,near-.5),outer=far+.5;
        circle(middle,axis,inner,INNER);circle(middle,axis,outer,OUTER);
        // The lines run on the circle's plane, from the cursor to the closest and farthest point of the selection.
        Overlay.line(middle,onPlane(middle,axis,nearest),INNER,2);Overlay.line(middle,onPlane(middle,axis,farthest),OUTER,2);
        Overlay.text(String.format(Locale.ROOT,"%.1f",inner),pos(around(middle,axis,inner,0)),INNER,0xbb151c26,.02f);
        Overlay.text(String.format(Locale.ROOT,"%.1f",outer),pos(around(middle,axis,outer,0)),OUTER,0xbb151c26,.02f);
    }
    private static Vec3 onPlane(Vec3 middle,Options.ModifyAxis axis,Point at){
        double x=at.x()+.5,y=at.y()+.5,z=at.z()+.5;
        return switch(axis){case X->new Vec3(middle.x,y,z);case Y->new Vec3(x,middle.y,z);case Z->new Vec3(x,y,middle.z);};
    }
    private static Point lowest(List<Point> p){int x=Integer.MAX_VALUE,y=x,z=x;for(Point a:p){x=Math.min(x,a.x());y=Math.min(y,a.y());z=Math.min(z,a.z());}return new Point(x,y,z);}
    private static Point highest(List<Point> p){int x=Integer.MIN_VALUE,y=x,z=x;for(Point a:p){x=Math.max(x,a.x());y=Math.max(y,a.y());z=Math.max(z,a.z());}return new Point(x,y,z);}
    private static BlockPos pos(Vec3 v){return BlockPos.containing(v);}
    /** Twist turns about a line through the middle of the selection. */
    private static void axisLine(Options o,List<Point> selected){
        Point min=lowest(selected),max=highest(selected);
        Vec3 mid=new Vec3((min.x()+max.x())/2d+.5,(min.y()+max.y())/2d+.5,(min.z()+max.z())/2d+.5);
        Vec3 a=switch(o.modifyAxis){case X->new Vec3(min.x()-1,mid.y,mid.z);case Y->new Vec3(mid.x,min.y()-1,mid.z);case Z->new Vec3(mid.x,mid.y,min.z()-1);};
        Vec3 b=switch(o.modifyAxis){case X->new Vec3(max.x()+2,mid.y,mid.z);case Y->new Vec3(mid.x,max.y()+2,mid.z);case Z->new Vec3(mid.x,mid.y,max.z()+2);};
        Overlay.line(a,b,AXIS,2);
    }
}
