package dev.craftium.server;

import com.mojang.brigadier.StringReader;
import dev.craftium.core.*;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

final class EditPlanner {
    final ServerLevel level;
    final Session session;
    final Options o;
    final LinkedHashMap<Point,Session.Change> changes = new LinkedHashMap<>();
    final LinkedHashMap<Point,Session.BiomeChange> biomes = new LinkedHashMap<>();
    final BlockState material, secondary, mask;
    final NoiseField noise;
    final java.util.function.Predicate<Point> expression;
    final LuaBrush maskLua;
    final List<BlockState> materials;
    final HeightmapImage heightmap;
    static final Point[] NEIGHBOURS = {new Point(1,0,0),new Point(-1,0,0),new Point(0,1,0),new Point(0,-1,0),new Point(0,0,1),new Point(0,0,-1)};

    EditPlanner(ServerLevel level, Session session, Options options) {
        this.level=level;this.session=session;this.o=options;
        material=parse(options.block); secondary=parse(options.secondary);
        mask=options.mask.isBlank()?null:parse(options.mask);
        noise=new NoiseField(options);
        materials=new ArrayList<>();for(String block:MaterialList.split(options.materials))materials.add(parse(block));
        if(materials.isEmpty())materials.add(material);
        expression=MaskExpression.compile(options.maskExpression,new MaskExpression.Context(){
            private final Map<String,BlockState> parsed=new HashMap<>();private int queries;
            public boolean block(Point p,String s){if(++queries>2_000_000)throw new IllegalArgumentException("Mask exceeds its query budget; reduce the region or near radius");var expected=parsed.computeIfAbsent(s,EditPlanner.this::parse);return s.contains("[")?state(p)==expected:state(p).is(expected.getBlock());}
            public boolean selection(Point p){return session.selection.contains(p);}
            public boolean sky(Point p){return level.canSeeSky(pos(p));}
            public boolean surface(Point p){return EditPlanner.this.surface(p);}
            public double angle(Point p){double x=(solid(p.add(-1,0,0))?1:0)-(solid(p.add(1,0,0))?1:0),y=(solid(p.add(0,-1,0))?1:0)-(solid(p.add(0,1,0))?1:0),z=(solid(p.add(0,0,-1))?1:0)-(solid(p.add(0,0,1))?1:0);double len=Math.sqrt(x*x+y*y+z*z);return len==0?0:Math.toDegrees(Math.acos(y/len));}
        });
        maskLua=options.maskScript.isBlank()?null:new LuaBrush(this,options.maskScript,true);
        heightmap=options.tool!=Tool.ELEVATION||options.heightmap.isBlank()?null:HeightmapImage.load(options.heightmap);
    }
    static BlockPos pos(Point p) { return new BlockPos(p.x(),p.y(),p.z()); }
    static Point point(BlockPos p) { return new Point(p.getX(),p.getY(),p.getZ()); }
    BlockState parse(String input) {
        try { return BlockStateParser.parseForBlock(level.registryAccess().lookupOrThrow(Registries.BLOCK),new StringReader(input),false).blockState(); }
        catch(Exception e) { throw new IllegalArgumentException("Unknown block or state: "+input); }
    }
    void validate(Point p) {
        BlockPos at=pos(p);
        if(!level.isInWorldBounds(at) || !level.getWorldBorder().isWithinBounds(at))
            throw new IllegalArgumentException("Edit crosses the world boundary");
        if(!level.hasChunk(p.x()>>4,p.z()>>4)) throw new IllegalArgumentException("Edit reaches unloaded chunks; move closer first");
    }
    BlockState state(Point p) {
        if(!level.isInWorldBounds(pos(p)) || !level.hasChunk(p.x()>>4,p.z()>>4)) return Blocks.AIR.defaultBlockState();
        return level.getBlockState(pos(p));
    }
    boolean accepted(Point p) {
        if(p.y()<o.minY || p.y()>o.maxY) return false;
        if(o.inSelection && !session.selection.contains(p)) return false;
        return (mask==null || (o.mask.contains("[")?state(p)==mask:state(p).is(mask.getBlock())))&&expression.test(p)&&(maskLua==null||maskLua.evaluate(p).toboolean());
    }
    boolean solid(Point p) { return !state(p).isAir() && state(p).getFluidState().isEmpty(); }
    int adjacent(Point p) { int count=0;for(Point n:NEIGHBOURS)if(solid(p.add(n.x(),n.y(),n.z())))count++;return count; }
    boolean surface(Point p) { return adjacent(p)<6; }
    void put(Point p, BlockState s) { put(p,new BlockSnapshot(s)); }
    void putClipboard(Point p,BlockSnapshot value){
        var pending=changes.get(p);var current=pending==null?state(p):pending.after().state();var desired=value.state();
        if(o.mergeBlocks&&current.is(desired.getBlock())&&value.data()==null&&!current.isAir()){
            var union=net.minecraft.world.phys.shapes.Shapes.or(current.getShape(level,pos(p)),desired.getShape(level,pos(p)));
            for(var candidate:desired.getBlock().getStateDefinition().getPossibleStates())if(!net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(union,candidate.getShape(level,pos(p)),net.minecraft.world.phys.shapes.BooleanOp.NOT_SAME)){value=new BlockSnapshot(candidate);break;}
        }
        put(p,value);
    }
    void put(Point p, BlockSnapshot value) {
        putOne(p,value);
        if(!o.symmetryX&&!o.symmetryY&&!o.symmetryZ&&o.symmetryRotations==1)return;
        var origin=o.symmetryOrigin;
        for(int bits=0;bits<8;bits++){
            if(((bits&1)!=0&&!o.symmetryX)||((bits&2)!=0&&!o.symmetryY)||((bits&4)!=0&&!o.symmetryZ))continue;
            Point mirror=new Point((bits&1)!=0?2*origin.x()+(o.symmetryHalfX?1:0)-p.x():p.x(),(bits&2)!=0?2*origin.y()+(o.symmetryHalfY?1:0)-p.y():p.y(),(bits&4)!=0?2*origin.z()+(o.symmetryHalfZ?1:0)-p.z():p.z());
            var s=value.state();if((bits&1)!=0)s=s.mirror(net.minecraft.world.level.block.Mirror.FRONT_BACK);if((bits&2)!=0)s=BuilderEdits.verticalMirror(s);if((bits&4)!=0)s=s.mirror(net.minecraft.world.level.block.Mirror.LEFT_RIGHT);
            for(int rotation=0;rotation<o.symmetryRotations;rotation++){
                if(bits==0&&rotation==0)continue;double angle=360d*rotation/o.symmetryRotations,cx=origin.x()+(o.symmetryHalfX?.5:0),cz=origin.z()+(o.symmetryHalfZ?.5:0);var delta=Geometry.rotate(new Geometry.Vector(mirror.x()-cx,0,mirror.z()-cz),angle,0,0);Point at=new Point((int)Math.round(cx+delta.x()),mirror.y(),(int)Math.round(cz+delta.z()));
                putOne(at,new BlockSnapshot(s.rotate(net.minecraft.world.level.block.Rotation.values()[(int)angle/90]),value.data()));
            }
        }
    }
    private void putOne(Point p,BlockSnapshot value) {
        validate(p);
        var planned=changes.get(p);
        if(!accepted(p) || (o.keepExisting && !value.state().isAir() && !state(p).isAir() && (planned==null||!planned.after().state().isAir()))) return;
        if(!o.forcePlace && !o.noUpdates && !value.state().canSurvive(level,pos(p))) return;
        if(value.data()!=null) {
            var tag=value.data().copy();tag.putInt("x",p.x());tag.putInt("y",p.y());tag.putInt("z",p.z());
            value=new BlockSnapshot(value.state(),tag);
        }
        var previous=changes.get(p);
        var before=previous==null?BlockSnapshot.capture(level,pos(p)):previous.before();
        if(value.data()==null&&before.data()!=null&&before.state().is(value.state().getBlock()))value=new BlockSnapshot(value.state(),before.data());
        if(before.state()==value.state() && Objects.equals(before.data(),value.data())) { changes.remove(p); return; }
        changes.put(p,new Session.Change(p,before,value));
        if(changes.size()+biomes.size()>Selection.LIMIT) throw new IllegalArgumentException("Operation exceeds 131,072 changes");
    }
    BlockState preserve(BlockState from, BlockState to) {
        for(var property: from.getProperties()) if(to.hasProperty(property)) to=copyProperty(from,to,property);
        return to;
    }
    private static <T extends Comparable<T>> BlockState copyProperty(BlockState from,BlockState to,net.minecraft.world.level.block.state.properties.Property<T> property) {
        return to.setValue(property,from.getValue(property));
    }
    List<Point> brush(Point center) { return Geometry.shape(center,o); }
    BlockState palette(double value,Point at){return materials.get(MaterialList.index(value,o.materialWeights,materials.size(),BrushMath.hash(at.x(),at.y(),at.z(),o.seed),1));}
    void tool(Point c, Point normal) {
        if(AdvancedTools.handle(this,c,normal))return;
        var points=brush(c);
        for(Point p: points) validate(p);
        if(o.tool==Tool.BIOME_PAINTER || o.tool==Tool.CLENTAMINATOR) paintBiomes(points);
        if(o.tool==Tool.TEXT) {
            for(Point p: BlockFont.pixels(o.text)) {
                Point at=Math.abs(normal.x())==1 ? c.add(0,p.y(),p.x()*-normal.x()) : c.add(p.x()* (normal.z()>0?-1:1),p.y(),0);
                put(at,material);
            }
            return;
        }
        if(o.tool==Tool.ELEVATION || o.tool==Tool.FLATTEN || o.tool==Tool.SLOPE) { heightmap(c,normal); return; }
        for(Point p:points) {
            BlockState old=state(p); boolean filled=solid(p);
            double n=noise.sample(p);
            switch(o.tool) {
                case PAINTER -> { if(filled && (!o.surfaceOnly || surface(p))) put(p,o.typeReplace?AdvancedTools.typeReplace(this,old,material):o.copyProperties?preserve(old,material):material); }
                case NOISE_PAINTER -> { if(filled && (!o.surfaceOnly || surface(p))) put(p,preserve(old,materials.size()>2?palette(n,p):n<o.density?material:secondary)); }
                case FREEHAND_DRAW,SHAPE -> put(p,o.invert?Blocks.AIR.defaultBlockState():material);
                case ERASE -> put(p,Blocks.AIR.defaultBlockState());
                case ROCK -> {
                    double distance=Math.sqrt(p.distanceSquared(c))/o.radius;
                    double rockNoise=n;if(o.denoise){for(Point direction:NEIGHBOURS)rockNoise+=noise.sample(p.add(direction.x(),direction.y(),direction.z()));rockNoise/=7;}
                    if(distance < .72+(rockNoise-.5)*o.strength*.35+adjacent(p)/6d*.1*o.meldStrength) put(p,o.invert?Blocks.AIR.defaultBlockState():material);
                }
                case SCULPT_DRAW,EXTRUDE -> {
                    if(filled && state(p.add(normal.x(),normal.y(),normal.z())).isAir()) {
                        int length=o.tool==Tool.EXTRUDE?Math.max(1,(int)o.strength):Math.max(1,(int)Math.ceil(o.strength*(1-Math.sqrt(p.distanceSquared(c))/(o.radius+1))));
                        Geometry.Vector direction=new Geometry.Vector(normal.x(),normal.y(),normal.z());
                        if(o.tool==Tool.SCULPT_DRAW){Geometry.Vector estimated=new Geometry.Vector(0,0,0);for(Point neighbour:NEIGHBOURS)if(!solid(p.add(neighbour.x(),neighbour.y(),neighbour.z())))estimated=estimated.add(Geometry.vector(neighbour));if(estimated.length()>.01)direction=estimated.scale(1/estimated.length());if(o.maskY)direction=new Geometry.Vector(0,1,0);}
                        for(int i=1;i<=length;i++){Point delta=direction.scale(o.invert?1-i:i).voxel();put(p.add(delta.x(),delta.y(),delta.z()),o.invert?Blocks.AIR.defaultBlockState():old);}
                    }
                }
                case WELD -> { if(!filled && density(p)>=o.threshold) put(p,nearestMaterial(p)); }
                case MELT -> { if(filled && density(p)<o.threshold) put(p,Blocks.AIR.defaultBlockState()); }
                case SMOOTH -> {
                    double d=density(p);
                    if(filled && d<.38) put(p,Blocks.AIR.defaultBlockState());
                    if(!filled && d>.58) put(p,nearestMaterial(p));
                }
                case DISTORT -> {
                    int dx=(int)Math.round((n-.5)*o.strength*2);
                    int dy=(int)Math.round((noise.sample(p.add(37,71,19))-.5)*o.strength*2),dz=(int)Math.round((noise.sample(p.add(101,13,53))-.5)*o.strength*2);
                    Point source=p.add(dx,dy,dz);validate(source);put(p,BlockSnapshot.capture(level,pos(source)));
                }
                case ROUGHEN -> {
                    if(filled && 6-adjacent(p)>=o.exposedFaces && BrushMath.hash(p.x(),p.y(),p.z(),o.seed)<o.density*.35) {
                        if(n<.5) put(p,Blocks.AIR.defaultBlockState());
                        else for(Point direction:NEIGHBOURS) { Point at=p.add(direction.x(),direction.y(),direction.z());if(state(at).isAir()) {put(at,old);break;} }
                    }
                }
                case SHATTER -> { if(filled && fracture(p)<.075*o.strength) put(p,o.fractureMaterial?material:Blocks.AIR.defaultBlockState()); }
                case CLENTAMINATOR -> {
                    if(old.is(Blocks.GRASS_BLOCK)||old.is(Blocks.MYCELIUM)||old.is(Blocks.PODZOL)||old.is(Blocks.SAND)||old.is(Blocks.RED_SAND)||old.is(Blocks.SNOW_BLOCK)) put(p,material);
                }
                case TINKER -> {
                    if(p.equals(c) && !old.getProperties().isEmpty()) {
                        var props=new ArrayList<>(old.getProperties());
                        var property=o.property.isBlank()?props.get(o.invert?props.size()-1:0):old.getBlock().getStateDefinition().getProperty(o.property);
                        if(property==null)throw new IllegalArgumentException("Block has no property: "+o.property);put(p,old.cycle(property));
                    }
                }
                default -> {}
            }
        }
    }
    double density(Point p) {
        double total=0,weight=0;
        int radius=Math.clamp((int)Math.ceil(o.smoothing),1,3);
        for(int x=-radius;x<=radius;x++)for(int y=-radius;y<=radius;y++)for(int z=-radius;z<=radius;z++) {
            double w=Math.exp(-(x*x+y*y+z*z)/(2*o.smoothing*o.smoothing));weight+=w;
            if(solid(p.add(x,y,z)))total+=w;
        }
        return total/weight;
    }
    BlockState nearestMaterial(Point p) {
        for(Point n:NEIGHBOURS) { BlockState s=state(p.add(n.x(),n.y(),n.z()));if(!s.isAir()&&!s.hasBlockEntity()&&s.getFluidState().isEmpty())return s; }
        return material;
    }
    private double fracture(Point p) {
        double scale=o.noiseScale,x=p.x()/scale,y=p.y()/scale,z=p.z()/scale;
        int ix=(int)Math.floor(x),iy=(int)Math.floor(y),iz=(int)Math.floor(z);
        double first=Double.MAX_VALUE,second=Double.MAX_VALUE;
        for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++) {
            int a=ix+dx,b=iy+dy,c=iz+dz;
            double px=a+BrushMath.hash(a,b,c,o.seed),py=b+BrushMath.hash(a,b,c,o.seed+1),pz=c+BrushMath.hash(a,b,c,o.seed+2);
            double d=(px-x)*(px-x)+(py-y)*(py-y)+(pz-z)*(pz-z);
            if(d<first){second=first;first=d;}else if(d<second)second=d;
        }
        return Math.sqrt(second)-Math.sqrt(first);
    }
    private void heightmap(Point c,Point normal) {
        for(int dx=-o.radius;dx<=o.radius;dx++)for(int dz=-o.radius;dz<=o.radius;dz++) {
            double distance=Math.sqrt(dx*dx+dz*dz)/o.radius;if(distance>1)continue;
            int x=c.x()+dx,z=c.z()+dz;
            int top=Math.min(level.getMaxY()-1,c.y()+o.radius+8),bottom=Math.max(level.getMinY(),c.y()-o.radius-16);
            while(top>=bottom && !solid(new Point(x,top,z)))top--;
            if(top<bottom)continue;
            BlockState surface=state(new Point(x,top,z));
            if(surface.hasBlockEntity())continue;
            int desired=switch(o.tool) {
                case ELEVATION -> top+(o.invert?-1:1)*Math.max(heightmap==null?1:0,(int)Math.round(o.strength*(1-distance)*(heightmap==null?1:heightmap.sample((dx/(double)o.radius+1)/2,(dz/(double)o.radius+1)/2))));
                case FLATTEN -> top+(int)Math.round((c.y()-top)*Math.min(1,o.strength/2));
                default -> {
                    if(o.nodes.size()>=2){Point a=o.nodes.getFirst(),b=o.nodes.getLast();double vx=b.x()-a.x(),vz=b.z()-a.z(),length=vx*vx+vz*vz,t=length==0?0:((x-a.x())*vx+(z-a.z())*vz)/length;yield (int)Math.round(a.y()+t*(b.y()-a.y()));}
                    yield c.y()+(int)Math.round((Math.abs(normal.x())>0?dx:dz)*o.strength*.25)*(o.invert?-1:1);
                }
            };
            desired=Math.clamp(desired,bottom,Math.min(level.getMaxY()-1,top+24));
            if(desired>top) for(int y=top+1;y<=desired;y++)put(new Point(x,y,z),surface);
            else if(desired<top) {
                for(int y=top;y>desired;y--)put(new Point(x,y,z),Blocks.AIR.defaultBlockState());
                put(new Point(x,desired,z),surface);
            }
        }
    }
    void paintBiomes(List<Point> points) {
        var registry=level.registryAccess().lookupOrThrow(Registries.BIOME);
        Holder<Biome> biome=registry.get(ResourceKey.create(Registries.BIOME,dev.craftium.compat.Compat.parse(o.biome)))
            .orElseThrow(()->new IllegalArgumentException("Unknown biome: "+o.biome));
        for(Point p:points) {
            if(!accepted(p))continue;
            int start=o.fillVertically?level.getMinY()>>2:p.y()>>2,end=o.fillVertically?(level.getMaxY()-1)>>2:start;
            for(int y=start;y<=end;y++){
                Point cell=new Point(p.x()>>2,y,p.z()>>2);var old=level.getNoiseBiome(cell.x(),cell.y(),cell.z());
                if(!old.equals(biome))biomes.put(cell,new Session.BiomeChange(cell,old,biome));
            }
        }
    }
    Session.Edit finish(String name) {
        return new Session.Edit(name,List.copyOf(changes.values()),List.copyOf(biomes.values()),o.noUpdates?2|16:3);
    }
}
