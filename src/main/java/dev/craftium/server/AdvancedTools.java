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
                // A Gaussian blur that moves blocks about without changing how many there are: the cells of the brush are ranked by how much solid
                // surrounds them and the first ones, as many as the stroke began with times the block ratio, become solid. Melt and Grow lean the
                // ranking towards taking blocks away or adding them. Fix edges leaves the brush's outer layer alone, so it meets the terrain as it was.
                var brush=p.brush(center);var inside=new HashSet<>(brush);
                var candidates=new ArrayList<Point>();int filled=0;
                for(Point at:brush)if(p.accepted(at)&&!p.state(at).hasBlockEntity()){
                    if(o.smoothFixEdges){boolean edge=false;for(Point n:EditPlanner.NEIGHBOURS)if(!inside.contains(at.add(n.x(),n.y(),n.z()))){edge=true;break;}if(edge)continue;}
                    candidates.add(at);if(p.solid(at))filled++;
                }
                double solidBias=switch(o.smoothMode){case MELT->-.05;case GROW->.01;default->.01;},airBias=o.smoothMode==Options.SmoothMode.GROW?.05:0;
                candidates.sort(Comparator.<Point>comparingDouble(at->p.density(at)+(p.solid(at)?solidBias:airBias)).reversed());
                int keep=(int)Math.clamp(Math.round(filled*o.smoothRatio/100d),0,candidates.size());
                for(int i=0;i<candidates.size();i++){Point at=candidates.get(i);if(i<keep){if(!p.solid(at))p.put(at,p.nearestMaterial(at));}else if(p.solid(at))p.put(at,Blocks.AIR.defaultBlockState());}
            }
            case EXTRUDE -> {
                // The whole face the click lands on, from the same search the hover preview outlines. Expand adds layers in front of it, contract takes them off.
                // With a selection under the click, the face is found among the selected blocks only.
                var base=p.state(center);var within=p.builder?null:p.session.selection;if(within!=null&&!within.contains(center))within=null;var chosen=within;
                var face=SurfaceFill.face(new SurfaceFill.Cells(){
                    public boolean air(Point a){var s=p.state(a);return s.isAir()||o.extrudeReplaceable&&s.canBeReplaced();}
                    public boolean matches(Point a){return (chosen==null||chosen.contains(a))&&(o.smartExtrude?sameFamily(base,p.state(a)):AdvancedTools.matches(p,base,a));}
                },center,normal,o.selectLimit);
                int count=Math.max(1,o.extrudeCount);
                for(Point at:face)for(int step=1;step<=count;step++){int s=o.invert?1-step:step;p.put(at.add(normal.x()*s,normal.y()*s,normal.z()*s),o.invert?Blocks.AIR.defaultBlockState():p.state(at));}
            }
            case PATH -> {
                var path=Geometry.path(o.nodes,o);for(int index=0;index<path.size();index++){
                    Point at=path.get(index);Point next=path.get(Math.min(path.size()-1,index+1));
                    if(o.useStairsAndSlabs)p.put(at,pathMaterial(p,at,next));
                    else if(o.pathMode==Options.PathMode.BRESENHAM)p.put(at,p.material);
                    else for(Point v:BrushMath.brush(at,Math.min(o.radius,4),o.shape,o.hollow,o.outlineThickness,o.pixelPerfect))p.put(v,p.material);
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
                Point start=p.state(center).isAir()?center:center.add(normal.x(),normal.y(),normal.z());var matching=p.state(start);
                // With the start inside the selection, the fill spreads through selected cells only.
                var within=p.builder||!p.session.selection.contains(start)?null:p.session.selection;
                for(Point at:SurfaceFill.flood(new SurfaceFill.Cells(){
                    public boolean air(Point a){return p.state(a).isAir();}
                    public boolean matches(Point a){return (within==null||within.contains(a))&&p.state(a)==matching;}
                    public boolean solid(Point a){var s=p.state(a);return !s.isAir()&&s!=matching;}
                },start,Math.clamp(o.blockLimit,1,Selection.LIMIT),Selection.LIMIT*8,o.floodGap,o.floodUp,o.floodDown,o.floodHorizontal,o.floodCorners))p.put(at,p.material);
            }
            case FLUID_BALL -> fluid(p,center,normal);
            case FLUID_BLOCK -> powder(p,center,normal);
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
            case MODIFY -> modify(p,center);
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
    /** Fluid Ball's flow, but pouring the palette: blocks fall until something holds them, then spread sideways, piled up to three high where they land and a single layer at the edge. Works for gravity blocks and for ones that float. */
    private static void powder(EditPlanner p,Point center,Point normal){
        var o=p.o;var queue=new PriorityQueue<Flow>(Comparator.comparingInt(Flow::cost).thenComparingInt(Flow::distance));var visited=new HashMap<Point,Integer>();
        Point origin=center.add(normal.x(),normal.y(),normal.z());queue.add(new Flow(origin,0,0));int maxDistance=o.radius+o.flowLength,maxCost=Math.max(1,Math.min(7,o.flowLength));
        while(!queue.isEmpty()){
            var f=queue.remove();Point at=f.point;if(f.distance>maxDistance||at.y()>origin.y()+o.radius||at.y()<Math.max(p.level.getMinY(),origin.y()-maxDistance*2))continue;
            if(visited.getOrDefault(at,Integer.MAX_VALUE)<=f.cost)continue;visited.put(at,f.cost);if(visited.size()>Selection.LIMIT)throw new IllegalArgumentException("Fluid block pour exceeds limit");
            if(p.solid(at))continue;
            if(!p.solid(at.add(0,-1,0))){queue.add(new Flow(at.add(0,-1,0),0,f.distance+1));continue;} // nothing under it yet: keep falling
            int height=1+(maxCost-Math.min(maxCost,f.cost))*2/maxCost;
            for(int i=0;i<height;i++){Point cell=at.add(0,i,0);if(p.solid(cell))break;p.put(cell,p.palette(BrushMath.hash(cell.x(),cell.y(),cell.z(),o.seed),cell));}
            if(f.cost<Math.min(7,o.flowLength))for(Point n:EditPlanner.NEIGHBOURS)if(n.y()==0)queue.add(new Flow(at.add(n.x(),0,n.z()),f.cost+1,f.distance+1));
        }
    }
    private static BlockState pathMaterial(EditPlanner p,Point at,Point next){
        String base=BuiltInRegistries.BLOCK.getKey(p.material.getBlock()).getPath().replaceAll("(_planks|_block)$","").replaceAll("_bricks$","_brick").replaceAll("_tiles$","_tile");
        boolean slope=next.y()!=at.y();var block=BuiltInRegistries.BLOCK.getOptional(dev.craftium.compat.Compat.vanilla(base+(slope?"_stairs":"_slab")));if(block.isEmpty())return p.material;var state=block.get().defaultBlockState();
        if(slope&&state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)){int dx=next.x()-at.x(),dz=next.z()-at.z();var facing=Math.abs(dx)>Math.abs(dz)?(dx>=0?net.minecraft.core.Direction.EAST:net.minecraft.core.Direction.WEST):(dz>=0?net.minecraft.core.Direction.SOUTH:net.minecraft.core.Direction.NORTH);if(next.y()<at.y())facing=facing.getOpposite();state=state.setValue(BlockStateProperties.HORIZONTAL_FACING,facing);}return state;
    }
    /** For a block with no position of its own, such as the active material, so NBT has no data to compare and falls back to the same block. */
    static boolean matches(EditPlanner p,BlockState original,Point at){return p.o.compare==Options.Compare.NBT?p.state(at).is(original.getBlock()):matches(p,original,null,at);}
    /** {@code originData} is the block entity data of the block the search started on, used by the NBT comparison. */
    static boolean matches(EditPlanner p,BlockState original,net.minecraft.nbt.CompoundTag originData,Point at){
        var s=p.state(at);
        return switch(p.o.compare){
            case ANY->!s.isAir();
            case BLOCK->s.is(original.getBlock());
            case STATE->s==original;
            case TAG->s.is(original.getBlock())||net.minecraft.core.registries.BuiltInRegistries.BLOCK.wrapAsHolder(original.getBlock()).tags().anyMatch(s::is);
            case NBT->s.is(original.getBlock())&&Objects.equals(originData,data(p,at));
        };
    }
    static net.minecraft.nbt.CompoundTag data(EditPlanner p,Point at){var entity=p.level.getBlockEntity(EditPlanner.pos(at));return entity==null?null:entity.saveWithFullMetadata(p.level.registryAccess());}
    /** Modify: translate copies, revolve, rotate copies or twist the selection. The placement comes from {@link ModifyMath}, which the live preview shares. */
    static void modify(EditPlanner p,Point cursor){
        var o=p.o;var points=p.session.selection.points();if(points.isEmpty())throw new IllegalArgumentException("Create a selection first");
        var source=new LinkedHashMap<Point,BlockSnapshot>();for(Point at:points){var snap=BlockSnapshot.capture(p.level,EditPlanner.pos(at));if(o.pasteAir||!snap.state().isAir())source.put(at,snap);}
        if(source.isEmpty())throw new IllegalArgumentException("The selection holds no blocks");
        if(ModifyMath.usesCursor(o)&&cursor==null)throw new IllegalArgumentException("Point at a block to turn around");
        var placed=ModifyMath.plan(o,source.keySet(),p.session.selection.min(),p.session.selection.max(),cursor);
        if(o.modifyMode==Options.ModifyMode.TWIST)for(Point at:points)p.put(at,Blocks.AIR.defaultBlockState());
        for(var e:placed.entrySet())p.put(e.getKey(),source.get(e.getValue()));
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
    static String family(BlockState state){return BlockFamily.of(state);}
    static boolean sameFamily(BlockState original,BlockState other){return BlockFamily.same(original,other);}
    static BlockState typeReplace(EditPlanner p,BlockState old,BlockState target){
        String from=BuiltInRegistries.BLOCK.getKey(old.getBlock()).getPath(),to=BuiltInRegistries.BLOCK.getKey(target.getBlock()).getPath();
        for(String suffix:new String[]{"_stairs","_slab","_wall","_fence","_fence_gate","_button","_pressure_plate","_door","_trapdoor"})if(from.endsWith(suffix)){
            String base=to.replaceAll("(_planks|_block|_stairs|_slab|_wall|_fence|_fence_gate)$","").replaceAll("_bricks$","_brick").replaceAll("_tiles$","_tile");
            var id=dev.craftium.compat.Compat.vanilla(base+suffix);var block=BuiltInRegistries.BLOCK.getOptional(id);if(block.isPresent())return p.preserve(old,block.get().defaultBlockState());
            id=dev.craftium.compat.Compat.vanilla(to+suffix);block=BuiltInRegistries.BLOCK.getOptional(id);if(block.isPresent())return p.preserve(old,block.get().defaultBlockState());
        }return p.preserve(old,target);
    }
}
