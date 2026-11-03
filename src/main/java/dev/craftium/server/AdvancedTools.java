package dev.craftium.server;

import dev.craftium.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.*;

final class AdvancedTools {
    private AdvancedTools() {}
    static boolean handle(EditPlanner p,Point center,Point normal) {
        var o=p.o;
        switch(o.tool) {
            case SMOOTH -> {
                var candidates=new ArrayList<Point>();int filled=0;
                for(Point at:p.brush(center))if(p.accepted(at)&&!p.state(at).hasBlockEntity()){candidates.add(at);if(p.solid(at))filled++;}
                candidates.sort(Comparator.<Point>comparingDouble(at->p.density(at)+(p.solid(at)?.01/o.strength:0)).reversed());
                for(int i=0;i<candidates.size();i++){Point at=candidates.get(i);if(i<filled){if(!p.solid(at))p.put(at,p.nearestMaterial(at));}else if(p.solid(at))p.put(at,Blocks.AIR.defaultBlockState());}
            }
            case EXTRUDE -> {
                var todo=new ArrayDeque<Point>();var seen=new HashSet<Point>();todo.add(center);seen.add(center);var base=p.state(center);
                while(!todo.isEmpty()&&seen.size()<=o.selectLimit){
                    Point at=todo.removeFirst();if(at.distanceSquared(center)>128*128||!matches(p,base,at)||!p.state(at.add(normal.x(),normal.y(),normal.z())).isAir())continue;
                    for(int step=1;step<=Math.max(1,(int)o.strength);step++)p.put(at.add(normal.x()*(o.invert?1-step:step),normal.y()*(o.invert?1-step:step),normal.z()*(o.invert?1-step:step)),o.invert?Blocks.AIR.defaultBlockState():p.state(at));
                    for(Point n:EditPlanner.NEIGHBOURS)if(n.x()*normal.x()+n.y()*normal.y()+n.z()*normal.z()==0){Point next=at.add(n.x(),n.y(),n.z());if(seen.add(next))todo.add(next);}
                }
            }
            case PATH -> {
                var path=Geometry.path(o.nodes,o);for(int index=0;index<path.size();index++){
                    Point at=path.get(index);Point next=path.get(Math.min(path.size()-1,index+1));
                    if(o.useStairsAndSlabs)p.put(at,pathMaterial(p,at,next));
                    else if(o.pathMode==Options.PathMode.BRESENHAM)p.put(at,p.material);
                    else for(Point v:BrushMath.brush(at,Math.min(o.radius,4),o.shape,o.hollow))p.put(v,p.material);
                    if(o.extendToGround){int y=at.y()-1;while(y>=p.level.getMinY()&&p.state(new Point(at.x(),y,at.z())).isAir())p.put(new Point(at.x(),y--,at.z()),p.material);}
                }
            }
            case MODELLING -> {for(Point at:Geometry.model(o.nodes,o))p.put(at,p.material);}
            case SCRIPT_BRUSH -> {var script=new LuaBrush(p,o.script);if(script.once){if(p.accepted(center))script.paint(center);}else for(Point at:p.brush(center))if(p.accepted(at))script.paint(at);}
            case GRADIENT_PAINTER -> {
                if(o.nodes.size()<2)throw new IllegalArgumentException("Set two gradient endpoints");
                Point a=o.nodes.getFirst(),b=o.nodes.getLast();var vector=Geometry.vector(b).subtract(Geometry.vector(a));double length=vector.dot(vector);
                for(Point at:p.brush(center))if(p.solid(at)&&(!o.surfaceOnly||p.surface(at))){double t=length==0?0:Geometry.vector(at).subtract(Geometry.vector(a)).dot(vector)/length;p.put(at,p.preserve(p.state(at),p.palette(t,at)));}
            }
            case FLOODFILL -> {
                Point start=p.state(center).isAir()?center:center.add(normal.x(),normal.y(),normal.z());var matching=p.state(start);var seen=new HashSet<Point>();var todo=new ArrayDeque<Point>();todo.add(start);seen.add(start);int filled=0;
                while(!todo.isEmpty()&&filled<o.floodLimit&&seen.size()<Selection.LIMIT*8){Point at=todo.removeFirst();if(at.distanceSquared(start)>128*128||p.state(at)!=matching)continue;
                    if(!o.floodUp&&at.y()>start.y()||!o.floodDown&&at.y()<start.y()||!o.floodHorizontal&&(at.x()!=start.x()||at.z()!=start.z()))continue;p.put(at,p.material);filled++;
                    for(Point n:EditPlanner.NEIGHBOURS){Point next=at.add(n.x(),n.y(),n.z());if(seen.add(next))todo.add(next);}if(o.floodCorners)for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++){Point next=at.add(x,0,z);if(seen.add(next))todo.add(next);}
                }
            }
            case FLUID_BALL -> fluid(p,center,normal);
            case SMEAR -> {
                var source=new LinkedHashMap<Point,BlockSnapshot>();
                if(!p.session.selection.points().isEmpty()){for(Point at:p.session.selection.points())if(!p.state(at).isAir())source.put(at,BlockSnapshot.capture(p.level,EditPlanner.pos(at)));}
                else {for(var e:p.session.clipboard.entrySet())if(!e.getValue().state().isAir())source.put(center.add(e.getKey().x(),e.getKey().y(),e.getKey().z()),e.getValue());}
                if(source.isEmpty())throw new IllegalArgumentException("Select blocks or copy a clipboard first");
                var line=BrushMath.line(new Point(0,0,0),new Point(o.offsetX,o.offsetY,o.offsetZ));for(var e:source.entrySet())for(int i=1;i<line.size();i++){Point delta=line.get(i),at=e.getKey().add(delta.x(),delta.y(),delta.z());if(!source.containsKey(at)&&p.solid(at))break;p.put(at,e.getValue());}
            }
            case STAMP -> {
                if(p.session.clipboard.isEmpty())throw new IllegalArgumentException("Copy or load a blueprint first");
                int spacing=Math.max(1,(int)Math.round(o.minSpacing*o.radius));int i=0;
                for(int dx=-o.radius;dx<=o.radius;dx+=spacing)for(int dz=-o.radius;dz<=o.radius;dz+=spacing){
                    if(dx*dx+dz*dz>o.radius*o.radius||BrushMath.hash(center.x()+dx,center.y(),center.z()+dz,o.seed)>o.baseChance)continue;
                    int rotation=o.randomYaw?(int)(BrushMath.hash(dx,dz,i++,o.seed+1)*4):0;
                    for(var e:p.session.clipboard.entrySet()){
                        Point q=e.getKey();for(int r=0;r<rotation;r++)q=q.rotateY();if(o.randomFlip&&BrushMath.hash(dx,0,dz,o.seed+4)>.5)q=q.mirrorX();
                        if(!o.pasteAir&&e.getValue().state().isAir())continue;
                        var state=e.getValue().state().rotate(net.minecraft.world.level.block.Rotation.values()[rotation]);p.put(center.add(dx+q.x(),q.y()+1,dz+q.z()),new BlockSnapshot(state,e.getValue().data()));
                    }
                }
            }
            case MODIFY -> modify(p);
            case TEXT -> {if(o.useBuiltinFont)return false;text(p,center,normal);}
            default -> {return false;}
        }return true;
    }
    private record Flow(Point point,int cost,int distance){}
    private static void fluid(EditPlanner p,Point center,Point normal){
        var o=p.o;var queue=new PriorityQueue<Flow>(Comparator.comparingInt(Flow::cost).thenComparingInt(Flow::distance));var visited=new HashMap<Point,Integer>();
        Point origin=center.add(normal.x(),normal.y(),normal.z());queue.add(new Flow(origin,0,0));int maxDistance=o.radius+o.flowLength;
        while(!queue.isEmpty()){
            var f=queue.remove();Point at=f.point;if(f.distance>maxDistance||at.y()>origin.y()+o.radius||at.y()<Math.max(p.level.getMinY(),origin.y()-maxDistance*2))continue;
            if(visited.getOrDefault(at,Integer.MAX_VALUE)<=f.cost)continue;visited.put(at,f.cost);if(visited.size()>Selection.LIMIT)throw new IllegalArgumentException("Fluid simulation exceeds limit");
            if(p.solid(at))continue;boolean supported=p.solid(at.add(0,-1,0));if(o.requireSupport&&!supported){queue.add(new Flow(at.add(0,-1,0),0,f.distance+1));continue;}
            if(o.fluidType==Options.FluidType.SNOW){if(supported)p.put(at,Blocks.SNOW.defaultBlockState().setValue(BlockStateProperties.LAYERS,Math.clamp(8-f.cost,1,8)));}
            else {var state=(o.fluidType==Options.FluidType.LAVA?Blocks.LAVA:Blocks.WATER).defaultBlockState();int level=o.infiniteSources&&at.distanceSquared(origin)<=o.radius*o.radius?0:Math.clamp(f.cost,0,7);if(o.removeFluidsWithoutSources&&f.distance>o.flowLength)continue;p.put(at,state.setValue(BlockStateProperties.LEVEL,level));}
            if(!supported&&at.y()>p.level.getMinY())queue.add(new Flow(at.add(0,-1,0),0,f.distance+1));
            if(supported||o.fillEdges)for(Point n:EditPlanner.NEIGHBOURS)if(n.y()==0&&f.cost<Math.min(7,o.flowLength))queue.add(new Flow(at.add(n.x(),0,n.z()),f.cost+1,f.distance+1));
            if(o.fluidQuality>1&&o.fillEdges&&f.cost<6)for(int x:new int[]{-1,1})for(int z:new int[]{-1,1})queue.add(new Flow(at.add(x,0,z),f.cost+2,f.distance+1));
        }
    }
    private static BlockState pathMaterial(EditPlanner p,Point at,Point next){
        String base=BuiltInRegistries.BLOCK.getKey(p.material.getBlock()).getPath().replaceAll("(_planks|_block)$","").replaceAll("_bricks$","_brick").replaceAll("_tiles$","_tile");
        boolean slope=next.y()!=at.y();var block=BuiltInRegistries.BLOCK.getOptional(dev.craftium.compat.Compat.vanilla(base+(slope?"_stairs":"_slab")));if(block.isEmpty())return p.material;var state=block.get().defaultBlockState();
        if(slope&&state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)){int dx=next.x()-at.x(),dz=next.z()-at.z();var facing=Math.abs(dx)>Math.abs(dz)?(dx>=0?net.minecraft.core.Direction.EAST:net.minecraft.core.Direction.WEST):(dz>=0?net.minecraft.core.Direction.SOUTH:net.minecraft.core.Direction.NORTH);if(next.y()<at.y())facing=facing.getOpposite();state=state.setValue(BlockStateProperties.HORIZONTAL_FACING,facing);}return state;
    }
    static boolean matches(EditPlanner p,BlockState original,Point at){var s=p.state(at);return switch(p.o.compare){case BLOCK->s.is(original.getBlock());case STATE->s==original;case SOLID->p.solid(at);case ANY->!s.isAir();};}
    static void modify(EditPlanner p){
        var o=p.o;var points=p.session.selection.points();if(points.isEmpty())throw new IllegalArgumentException("Create a selection first");
        Point pivot=p.session.selection.min();var source=new LinkedHashMap<Point,BlockSnapshot>();for(Point at:points)source.put(at,BlockSnapshot.capture(p.level,EditPlanner.pos(at)));
        if(o.modifyMode==Options.ModifyMode.TWIST)for(Point at:points)p.put(at,Blocks.AIR.defaultBlockState());
        int copies=o.modifyMode==Options.ModifyMode.TWIST?1:o.copies;
        for(int i=1;i<=copies;i++)for(var entry:source.entrySet()){
            Point relative=entry.getKey().subtract(pivot);double angle=o.modifyMode==Options.ModifyMode.TWIST?o.yaw*relative.y()/Math.max(1,p.session.selection.max().y()-pivot.y()):o.yaw*i;
            var v=o.modifyMode==Options.ModifyMode.TRANSLATE_COPIES?Geometry.vector(relative):Geometry.rotate(Geometry.vector(relative),angle,o.pitch*i,o.roll*i);
            Point at=v.voxel().add(pivot.x()+o.offsetX*i,pivot.y()+o.offsetY*i,pivot.z()+o.offsetZ*i);if(!o.pasteAir&&entry.getValue().state().isAir())continue;p.put(at,entry.getValue());
        }
    }
    private static void text(EditPlanner p,Point c,Point normal){
        var o=p.o;Font font=new Font(o.fontName,Font.PLAIN,o.fontSize);
        if(!o.fontFile.isBlank()){
            if(!o.fontFile.matches("[A-Za-z0-9_. -]+\\.(ttf|otf)"))throw new IllegalArgumentException("Use a font filename from config/craftium/fonts");
            Path path=dev.craftium.runtime.ServerPlatform.assets().resolve("fonts").resolve(o.fontFile);
            try{font=Font.createFont(Font.TRUETYPE_FONT,path.toFile()).deriveFont((float)o.fontSize);}catch(Exception e){throw new IllegalArgumentException("Cannot load font: "+o.fontFile);}
        }
        var measure=new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB);var mg=measure.createGraphics();mg.setFont(font);var fm=mg.getFontMetrics();int width=Math.clamp(fm.stringWidth(o.text),1,1024),height=fm.getHeight();mg.dispose();
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();g.setFont(font);g.setColor(java.awt.Color.WHITE);g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);g.drawString(o.text,0,fm.getAscent());g.dispose();
        if(!o.fullBlocks){
            BlockState slab=pathMaterial(p,c,c);if(!slab.hasProperty(BlockStateProperties.SLAB_TYPE))throw new IllegalArgumentException("Choose a material with a slab variant for half-block text");
            for(int x=0;x<width;x++)for(int y=0;y<height;y+=2){boolean upper=(image.getRGB(x,y)>>>24)>127,lower=y+1<height&&(image.getRGB(x,y+1)>>>24)>127;if(!upper&&!lower)continue;var state=slab.setValue(BlockStateProperties.SLAB_TYPE,upper&&lower?net.minecraft.world.level.block.state.properties.SlabType.DOUBLE:upper?net.minecraft.world.level.block.state.properties.SlabType.TOP:net.minecraft.world.level.block.state.properties.SlabType.BOTTOM);
                for(int z=0;z<o.textThickness;z++){var v=Geometry.rotate(new Geometry.Vector(x,(height-1-y)/2,z),o.yaw,o.pitch,o.roll).voxel();Point at=Math.abs(normal.x())==1?c.add(v.z(),v.y(),-v.x()*normal.x()):c.add(v.x(),v.y(),v.z());p.put(at,state);}
            }return;
        }
        for(int x=0;x<width;x++)for(int y=0;y<height;y++)if((image.getRGB(x,y)>>>24)>127)for(int z=0;z<o.textThickness;z++){
            var v=Geometry.rotate(new Geometry.Vector(x,height-1-y,z),o.yaw,o.pitch,o.roll).voxel();
            Point at=Math.abs(normal.x())==1?c.add(v.z(),v.y(),-v.x()*normal.x()):c.add(v.x(),v.y(),v.z());p.put(at,p.material);
        }
    }
    static BlockState typeReplace(EditPlanner p,BlockState old,BlockState target){
        String from=BuiltInRegistries.BLOCK.getKey(old.getBlock()).getPath(),to=BuiltInRegistries.BLOCK.getKey(target.getBlock()).getPath();
        for(String suffix:new String[]{"_stairs","_slab","_wall","_fence","_fence_gate","_button","_pressure_plate","_door","_trapdoor"})if(from.endsWith(suffix)){
            String base=to.replaceAll("(_planks|_block|_stairs|_slab|_wall|_fence|_fence_gate)$","").replaceAll("_bricks$","_brick").replaceAll("_tiles$","_tile");
            var id=dev.craftium.compat.Compat.vanilla(base+suffix);var block=BuiltInRegistries.BLOCK.getOptional(id);if(block.isPresent())return p.preserve(old,block.get().defaultBlockState());
            id=dev.craftium.compat.Compat.vanilla(to+suffix);block=BuiltInRegistries.BLOCK.getOptional(id);if(block.isPresent())return p.preserve(old,block.get().defaultBlockState());
        }return p.preserve(old,target);
    }
}
