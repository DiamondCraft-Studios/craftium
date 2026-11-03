package dev.craftium.server;

import dev.craftium.core.*;
import dev.craftium.vendor.FastNoiseLite;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.*;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.*;
import java.util.*;

final class LuaFunctions {
    private record Colour(BlockState state,int rgb,int flags,double[] lab){}
    private final EditPlanner plan;
    private final Map<String,BlockState> states=new HashMap<>();
    private final Map<String,Integer> rgb=new HashMap<>();
    private final Map<Long,FastNoiseLite> noises=new HashMap<>();
    private final Map<Long,Integer> matches=new HashMap<>();
    private final int randomSeed=java.util.concurrent.ThreadLocalRandom.current().nextInt();
    private List<Colour> colours;
    private Point position=new Point(0,0,0);
    LuaFunctions(Globals globals,EditPlanner plan){
        this.plan=plan;var blocks=new LuaTable();var meta=new LuaTable();meta.set("__index",new TwoArgFunction(){public LuaValue call(LuaValue table,LuaValue key){String name=key.checkjstring();LuaValue value=id(parse(name.contains(":")?name:"minecraft:"+name));table.rawset(key,value);return value;}});blocks.setmetatable(meta);globals.set("blocks",blocks);
        bind(globals,"getBlock",args->id(plan.state(at(args)).getBlock().defaultBlockState()));
        bind(globals,"getBlockState",args->id(plan.state(at(args))));
        bind(globals,"setBlock",args->{plan.put(at(args),state(args.arg(4)));return LuaValue.NIL;});
        bind(globals,"isSolid",args->LuaValue.valueOf(!state(args.arg1()).getCollisionShape(plan.level,EditPlanner.pos(position)).isEmpty()));
        bind(globals,"getHighestBlockYAt",args->{int x=args.arg(1).checkint(),z=args.arg(2).checkint();plan.validate(new Point(x,plan.level.getMinY(),z));return LuaValue.valueOf(plan.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,x,z)-1);});
        bind(globals,"withBlockProperty",args->{BlockState current=state(args.arg1());for(String property:args.arg(2).checkjstring().split(",")){String[] pair=property.trim().split("=",2);if(pair.length!=2)throw new LuaError("Use property=value");Property<?> definition=current.getBlock().getStateDefinition().getProperty(pair[0]);if(definition==null)throw new LuaError("Unknown block property "+pair[0]);current=with(current,definition,pair[1]);}return id(current);});
        bind(globals,"getBlockProperty",args->{var current=state(args.arg1());var property=current.getBlock().getStateDefinition().getProperty(args.arg(2).checkjstring());return property==null?LuaValue.NIL:LuaValue.valueOf(property(current,property));});
        bind(globals,"isBlockTagged",args->{String tag=args.arg(2).checkjstring().replaceFirst("^#","");return LuaValue.valueOf(state(args.arg1()).is(TagKey.create(Registries.BLOCK,dev.craftium.compat.Compat.parse(tag))));});
        bind(globals,"getBlockRGB",args->{var value=state(args.arg1());if(value.isAir())return LuaValue.NIL;prepareColours();Integer color=rgb.get(BuiltInRegistries.BLOCK.getKey(value.getBlock()).toString());return color==null?LuaValue.NIL:LuaValue.valueOf(color);});
        bind(globals,"findClosestBlockToRGB",args->closest(args.arg1().checkint()&0xffffff,args.arg(2).optint(0)&127,args.arg(3).optint(1)));
        bind(globals,"getSimplexNoise",args->noise(args,false));bind(globals,"getVoroniEdgeNoise",args->noise(args,true));bind(globals,"getVoronoiEdgeNoise",args->noise(args,true));
    }
    private interface Function {LuaValue apply(Varargs args);}
    private static void bind(Globals globals,String name,Function function){globals.set(name,new VarArgFunction(){public Varargs invoke(Varargs args){return function.apply(args);}});}
    void position(Point at){position=at;}
    private Point at(Varargs args){Point at=new Point(args.arg(1).checkint(),args.arg(2).checkint(),args.arg(3).checkint());plan.validate(at);return at;}
    private BlockState parse(String value){return states.computeIfAbsent(value,plan::parse);}
    BlockState state(LuaValue value){if(value.isnumber()){int id=value.checkint();var state=Block.stateById(id);if(id<0||Block.getId(state)!=id)throw new LuaError("Invalid block state ID");return state;}return parse(value.checkjstring());}
    private static LuaValue id(BlockState state){return LuaValue.valueOf(Block.getId(state));}
    private static <T extends Comparable<T>> BlockState with(BlockState state,Property<T> property,String value){return state.setValue(property,property.getValue(value).orElseThrow(()->new LuaError("Invalid property value "+value)));}
    private static <T extends Comparable<T>> String property(BlockState state,Property<T> property){return property.getName(state.getValue(property));}
    private LuaValue noise(Varargs args,boolean voronoi){
        double x=args.arg(1).checkdouble(),y=args.arg(2).checkdouble(),z=args.arg(3).checkdouble();if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||Math.max(Math.abs(x),Math.max(Math.abs(y),Math.abs(z)))>10_000_000)throw new LuaError("Noise coordinates must be finite and within ±10,000,000");
        int seed=args.arg(4).isnil()?randomSeed:args.arg(4).isnumber()?args.arg(4).toint():0;long key=((long)seed<<1)|(voronoi?1:0);
        FastNoiseLite field=noises.computeIfAbsent(key,ignored->{if(noises.size()>=64)throw new LuaError("Maximum 64 noise seeds per operation");var noise=new FastNoiseLite(seed);noise.SetFrequency(1);noise.SetNoiseType(voronoi?FastNoiseLite.NoiseType.Cellular:FastNoiseLite.NoiseType.OpenSimplex2);noise.SetCellularReturnType(FastNoiseLite.CellularReturnType.Distance2Sub);return noise;});
        return LuaValue.valueOf(Math.clamp(field.GetNoise((float)x,(float)y,(float)z)*.5+.5,0,1));
    }
    private void prepareColours(){
        if(colours!=null)return;var result=new ArrayList<Colour>();
        if(!plan.o.scriptColours.isEmpty())for(var entry:plan.o.scriptColours){var state=parse(entry.block());int color=entry.rgb()&0xffffff;result.add(new Colour(state,color,entry.flags()&127,ColourMath.lab(color)));rgb.put(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),color);}
        else for(var block:BuiltInRegistries.BLOCK){var state=block.defaultBlockState();if(state.isAir()||block.asItem()==net.minecraft.world.item.Items.AIR)continue;int color=state.getMapColor(plan.level,EditPlanner.pos(position)).col;String name=BuiltInRegistries.BLOCK.getKey(block).toString();int flags=(!state.getCollisionShape(plan.level,EditPlanner.pos(position)).isEmpty()?1:0)|(state.canOcclude()?2:0)|(state.isCollisionShapeFullBlock(plan.level,EditPlanner.pos(position))?4:0)|(!name.contains("_ore")&&!name.contains("ancient_debris")?16:0)|(!name.contains("glazed_terracotta")?32:0)|(!state.hasBlockEntity()?64:0);result.add(new Colour(state,color,flags,ColourMath.lab(color)));rgb.put(name,color);}
        colours=List.copyOf(result);
    }
    private LuaValue closest(int color,int flags,int index){
        index=Math.clamp(index,1,256);long key=((long)color<<16)|((long)flags<<9)|index;Integer cached=matches.get(key);if(cached!=null)return LuaValue.valueOf(cached);if(matches.size()>=2048)throw new LuaError("Colour search budget exceeded");prepareColours();double[] lab=ColourMath.lab(color);var candidates=colours.stream().filter(c->(c.flags&flags)==flags).sorted(Comparator.comparingDouble(c->ColourMath.distance(lab,c.lab))).limit(index).toList();if(candidates.isEmpty())return LuaValue.NIL;int chosen=Block.getId(candidates.getLast().state);matches.put(key,chosen);return LuaValue.valueOf(chosen);
    }
}
