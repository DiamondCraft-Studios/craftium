package dev.craftium.runtime;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
/**
 * What Tinker does to a block: the change that is most likely meant, picked from the block and from where on it the player aims.
 * Full blocks, stairs and slabs turn into one another; walls, fences and the like change the arm that is aimed at; blocks with a
 * direction or a switch cycle those. The client previews the result and the server applies it, so both must be given the same input.
 */
public final class TinkerRules {
    private TinkerRules(){}
    /** Where the block was hit: the face, and the point on the block as fractions from 0 to 1. */
    public record Aim(Direction face,double x,double y,double z){
        /** The aim for a face given as its step, and the point as fractions of the block. */
        public static Aim of(int stepX,int stepY,int stepZ,double x,double y,double z){
            for(var direction:Direction.values())if(direction.getStepX()==stepX&&direction.getStepY()==stepY&&direction.getStepZ()==stepZ)return new Aim(direction,x,y,z);
            return new Aim(Direction.UP,x,y,z);
        }
    }
    public record Result(BlockState state,String message){}

    /** A block's properties as Tinker uses them: the first on a side face, the second (when there is one) on the top and bottom faces. "~swap" turns the block into the next of its chain. */
    private record Rule(Predicate<String> match,String... properties){}
    private static Rule exact(String path,String... properties){return new Rule(path::equals,properties);}
    private static Rule suffix(String end,String... properties){return new Rule(p->p.endsWith(end),properties);}
    private static final List<Rule> RULES=List.of(
        exact("furnace","facing","lit"),exact("blast_furnace","facing","lit"),exact("smoker","facing","lit"),
        exact("observer","facing","powered"),exact("piston","facing","extended"),exact("sticky_piston","facing","extended"),
        exact("carved_pumpkin","facing","~swap"),exact("jack_o_lantern","facing","~swap"),
        exact("end_portal_frame","facing","eye"),exact("bamboo_block","axis","~swap"),exact("stripped_bamboo_block","axis","~swap"),
        exact("chest","facing"),exact("trapped_chest","facing"),exact("ender_chest","facing"),exact("barrel","facing"),exact("bell","facing"),
        suffix("_glazed_terracotta","facing"),
        exact("attached_melon_stem","facing"),exact("attached_pumpkin_stem","facing"),
        suffix("_fence_gate","facing","in_wall"),
        exact("iron_trapdoor","open"),exact("farmland","moisture"),exact("scaffolding","bottom"),exact("lectern","has_book"),
        exact("cave_vines","berries"),exact("cave_vines_plant","berries"),exact("bamboo","leaves"),
        exact("water_cauldron","level"),exact("powder_snow_cauldron","level"),exact("lava_cauldron","level"),
        exact("composter","level"),exact("beehive","honey_level"),exact("bee_nest","honey_level"),
        exact("cake","bites"),exact("snow","layers"),exact("turtle_egg","eggs"),exact("water","level"),exact("lava","level"),
        suffix("lantern","hanging"),
        exact("rail","shape"),exact("powered_rail","shape"),exact("detector_rail","shape"),exact("activator_rail","shape")
    );
    /** Blocks that turn into one another, in the order a click goes through them. */
    private static final String[][] CHAINS={
        {"small_amethyst_bud","medium_amethyst_bud","large_amethyst_bud","amethyst_cluster"},
        {"carved_pumpkin","jack_o_lantern"},{"suspicious_sand","suspicious_gravel"},{"grass_block","dirt_path"},
        {"azalea","flowering_azalea"},{"potted_azalea_bush","potted_flowering_azalea_bush"},{"bamboo_block","stripped_bamboo_block"}
    };
    private static final String[] GENERIC={"age","lit","powered"};

    /** The change for a click, or null when the block has nothing to change. {@code manual} is a property index chosen by sneaking, or -1 for the smart behaviour. */
    public static Result apply(BlockState state,Aim aim,int manual){
        if(manual>=0){
            var property=TinkerProperties.pick(state,manual);
            return property==null?null:cycled(state,property);
        }
        var block=state.getBlock();String path=BuiltInRegistries.BLOCK.getKey(block).getPath();
        boolean vertical=aim.face().getAxis().isVertical();
        var shape=shape(state,aim,vertical);if(shape!=null)return shape;
        if(path.endsWith("_wall")||path.endsWith("_fence")||path.equals("iron_bars")||path.endsWith("_pane")){
            var side=property(state,edge(aim).getName());
            if(side!=null){var out=cycled(state,side);var up=property(state,"up");return up!=null&&path.endsWith("_wall")?new Result(set(out.state(),up,"true"),out.message()):out;}
        }
        if(path.equals("chorus_plant")){var side=property(state,aim.face().getName());if(side!=null)return cycled(state,side);}
        if(path.equals("brewing_stand")){
            int bits=0;for(int i=0;i<3;i++){var bottle=property(state,"has_bottle_"+i);if(bottle==null)return null;if(state.getValue(bottle).equals(true))bits|=1<<i;}
            bits=(bits+1)%8;var out=state;for(int i=0;i<3;i++)out=set(out,property(state,"has_bottle_"+i),String.valueOf((bits>>i&1)==1));
            return new Result(out,"bottles = "+Integer.bitCount(bits));
        }
        for(var rule:RULES)if(rule.match().test(path)){
            var result=ruled(state,rule.properties(),vertical);if(result!=null)return result;
        }
        var rotation=property(state,"rotation");if(rotation!=null)return cycled(state,rotation);
        var swapped=swap(state);if(swapped!=null)return swapped;
        // Anything with an age, or something that is lit or powered, changes that.
        for(var name:GENERIC){var property=property(state,name);if(property!=null)return cycled(state,property);}
        var any=TinkerProperties.pick(state,0);
        return any==null?null:cycled(state,any);
    }
    private static Result ruled(BlockState state,String[] properties,boolean vertical){
        String name=vertical&&properties.length>1?properties[1]:properties[0];
        if(name.equals("~swap"))return swap(state);
        var property=property(state,name);
        return property==null?null:cycled(state,property);
    }

    // ---- full blocks, stairs and slabs
    private record Family(Block full,Block slab,Block stairs){}
    private static Map<String,Block> blocks;
    private static synchronized Map<String,Block> blocks(){
        if(blocks==null){var map=new HashMap<String,Block>();for(var block:BuiltInRegistries.BLOCK)map.put(BuiltInRegistries.BLOCK.getKey(block).toString(),block);blocks=map;}
        return blocks;
    }
    /** The full block, slab and stairs a block belongs to, or null when it is not one of a set. */
    private static Family family(Block block){
        var key=BuiltInRegistries.BLOCK.getKey(block);String space=key.getNamespace()+":",path=key.getPath(),base=path;
        var all=blocks();
        if(path.endsWith("_slab"))base=path.substring(0,path.length()-5);else if(path.endsWith("_stairs"))base=path.substring(0,path.length()-7);
        boolean full=base.equals(path);
        // Planks, bricks and the like name their slabs after the singular or the material: oak_planks, oak_slab; bricks, brick_slab.
        for(var name:full?List.of(base,strip(base,"_planks",""),strip(base,"s",""),strip(base,"_block","")):List.of(base)){
            var slab=all.get(space+name+"_slab");var stairs=all.get(space+name+"_stairs");
            if(slab==null||stairs==null)continue;
            var whole=all.get(space+(full?base:name));
            if(!full)for(var candidate:List.of(base,base+"_planks",base+"s",base+"_block")){whole=all.get(space+candidate);if(whole!=null)break;}
            if(whole!=null)return new Family(whole,slab,stairs);
        }
        return null;
    }
    private static String strip(String text,String end,String with){return text.endsWith(end)?text.substring(0,text.length()-end.length())+with:text;}
    private static Result shape(BlockState state,Aim aim,boolean vertical){
        var block=state.getBlock();
        var family=family(block);if(family==null)return null;
        if(block==family.full()){
            var out=copy(state,family.stairs().defaultBlockState());
            out=set(out,property(out,"facing"),edge(aim).getOpposite().getName());
            boolean top=aim.face()==Direction.DOWN||!vertical&&aim.y()>=.5;
            return new Result(set(out,property(out,"half"),top?"top":"bottom"),name(out)+" · "+(top?"upside down":"standing")+", facing "+edge(aim).getOpposite().getName());
        }
        if(block==family.stairs()){
            if(!vertical){var facing=property(state,"facing");var out=set(state.cycle(facing),property(state,"shape"),"straight");return new Result(out,"facing = "+out.getValue(facing));}
            var out=copy(state,family.slab().defaultBlockState());
            out=set(out,property(out,"type"),value(state,"half"));
            return new Result(out,name(out));
        }
        var type=value(state,"type");
        if(!vertical&&!type.equals("double")){var out=set(state,property(state,"type"),type.equals("top")?"bottom":"top");return new Result(out,"type = "+value(out,"type"));}
        var out=copy(state,family.full().defaultBlockState());
        return new Result(out,name(out));
    }

    // ---- the next block of a chain
    private static Result swap(BlockState state){
        String space=BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace()+":",path=BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        for(var chain:CHAINS)for(int i=0;i<chain.length;i++)if(chain[i].equals(path)){
            var next=blocks().get(space+chain[(i+1)%chain.length]);
            if(next==null)return null;
            var out=copy(state,next.defaultBlockState());
            return new Result(out,name(out));
        }
        return null;
    }

    // ---- helpers
    /** The side of the block that was aimed at: the face itself near the middle of a side face, otherwise the nearer edge. */
    static Direction edge(Aim aim){
        double dx=aim.x()-.5,dz=aim.z()-.5;var face=aim.face();
        if(face.getAxis().isHorizontal()){
            boolean alongX=face.getAxis()==Direction.Axis.X;double offset=alongX?dz:dx;
            if(Math.abs(offset)>.3)return alongX?(dz>0?Direction.SOUTH:Direction.NORTH):(dx>0?Direction.EAST:Direction.WEST);
            return face;
        }
        return Math.abs(dx)>Math.abs(dz)?(dx>0?Direction.EAST:Direction.WEST):(dz>0?Direction.SOUTH:Direction.NORTH);
    }
    private static Property<?> property(BlockState state,String name){return state.getBlock().getStateDefinition().getProperty(name);}
    private static String value(BlockState state,String name){var property=property(state,name);return property==null?"":valueOf(state,property);}
    private static <T extends Comparable<T>> String valueOf(BlockState state,Property<T> property){return property.getName(state.getValue(property));}
    private static String name(BlockState state){return state.getBlock().getName().getString();}
    private static Result cycled(BlockState state,Property<?> property){var out=TinkerProperties.cycle(state,property);return new Result(out,TinkerProperties.describe(out,property));}
    private static BlockState set(BlockState state,Property<?> property,String value){return property==null?state:setValue(state,property,value);}
    private static <T extends Comparable<T>> BlockState setValue(BlockState state,Property<T> property,String value){return property.getValue(value).map(v->state.setValue(property,v)).orElse(state);}
    /** The properties both blocks have, taken over from one to the other where the value fits. */
    private static BlockState copy(BlockState from,BlockState to){
        var out=to;
        for(var property:to.getProperties()){var old=from.getBlock().getStateDefinition().getProperty(property.getName());if(old!=null)out=set(out,property,valueOf(from,old));}
        return out;
    }
}
