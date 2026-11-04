package dev.craftium.runtime;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import java.util.List;
/** The properties Tinker can cycle on a block, in the order they are picked. Halves are left out, since turning one half of a door or bed would tear it apart. */
public final class TinkerProperties {
    private TinkerProperties(){}
    public static List<Property<?>> of(BlockState state){
        return state.getProperties().stream().filter(p->p!=BlockStateProperties.DOUBLE_BLOCK_HALF&&p!=BlockStateProperties.BED_PART).<Property<?>>map(p->p).toList();
    }
    /** The chosen property of the state, wrapped around the list, or null when it has none. */
    public static Property<?> pick(BlockState state,int index){var all=of(state);return all.isEmpty()?null:all.get(Math.floorMod(index,all.size()));}
    public static BlockState cycle(BlockState state,Property<?> property){return state.cycle(property);}
    /** "facing = east" for a state's value of a property. */
    public static String describe(BlockState state,Property<?> property){return describeValue(state,property);}
    private static <T extends Comparable<T>> String describeValue(BlockState state,Property<T> property){return property.getName()+" = "+property.getName(state.getValue(property));}
}
