package dev.craftium.server;

import dev.craftium.core.*;
import java.util.*;

final class ClipboardTransforms {
    private ClipboardTransforms(){}
    static void transform(Session session,Options o){
        if(session.clipboard.isEmpty())throw new IllegalArgumentException("Copy a selection first");
        // Everything below measures from 0,0,0, so a clipboard that starts elsewhere is moved there first.
        int minX=session.clipboard.keySet().stream().mapToInt(Point::x).min().orElse(0),minY=session.clipboard.keySet().stream().mapToInt(Point::y).min().orElse(0),minZ=session.clipboard.keySet().stream().mapToInt(Point::z).min().orElse(0);
        if(minX!=0||minY!=0||minZ!=0){var moved=new LinkedHashMap<Point,BlockSnapshot>();for(var e:session.clipboard.entrySet())moved.put(e.getKey().add(-minX,-minY,-minZ),e.getValue());session.clipboard.clear();session.clipboard.putAll(moved);for(var tag:session.clipboardEntities)ClipboardEntities.offset(tag,-minX,-minY,-minZ);}
        int maxX=session.clipboard.keySet().stream().mapToInt(Point::x).max().orElse(0),maxY=session.clipboard.keySet().stream().mapToInt(Point::y).max().orElse(0),maxZ=session.clipboard.keySet().stream().mapToInt(Point::z).max().orElse(0);
        var pivot=new Geometry.Vector(maxX/2d,maxY/2d,maxZ/2d);var low=new Geometry.Vector(Double.MAX_VALUE,Double.MAX_VALUE,Double.MAX_VALUE);var high=new Geometry.Vector(-Double.MAX_VALUE,-Double.MAX_VALUE,-Double.MAX_VALUE);
        for(double x:new double[]{-.5,maxX+.5})for(double y:new double[]{-.5,maxY+.5})for(double z:new double[]{-.5,maxZ+.5}){
            var p=new Geometry.Vector((x-pivot.x())*o.sizeX,(y-pivot.y())*o.sizeY,(z-pivot.z())*o.sizeZ);p=Geometry.rotate(p,o.yaw,o.pitch,o.roll);low=new Geometry.Vector(Math.min(low.x(),p.x()),Math.min(low.y(),p.y()),Math.min(low.z(),p.z()));high=new Geometry.Vector(Math.max(high.x(),p.x()),Math.max(high.y(),p.y()),Math.max(high.z(),p.z()));
        }
        Point max=new Point((int)Math.ceil(high.x()-low.x()-1e-8)-1,(int)Math.ceil(high.y()-low.y()-1e-8)-1,(int)Math.ceil(high.z()-low.z()-1e-8)-1);var result=new LinkedHashMap<Point,BlockSnapshot>();
        for(Point at:Selection.box(new Point(0,0,0),max)){
            var sample=Geometry.inverseRotate(new Geometry.Vector(low.x()+at.x()+.5,low.y()+at.y()+.5,low.z()+at.z()+.5),o.yaw,o.pitch,o.roll);Point source=new Point((int)Math.round(sample.x()/o.sizeX+pivot.x()),(int)Math.round(sample.y()/o.sizeY+pivot.y()),(int)Math.round(sample.z()/o.sizeZ+pivot.z()));var value=session.clipboard.get(source);
            if(value!=null)result.put(at,new BlockSnapshot(StateTransforms.rotate(value.state(),o.yaw,o.pitch,o.roll),value.data()));
        }
        if(result.isEmpty())throw new IllegalArgumentException("Transform produced an empty clipboard");session.clipboard.clear();session.clipboard.putAll(result);session.clipboardDirty=true;
        final var minimum=low;ClipboardEntities.transform(session,v->{var scaled=new Geometry.Vector((v.x()-.5-pivot.x())*o.sizeX,(v.y()-.5-pivot.y())*o.sizeY,(v.z()-.5-pivot.z())*o.sizeZ);return Geometry.rotate(scaled,o.yaw,o.pitch,o.roll).subtract(minimum);},o.yaw,false);
    }
}
