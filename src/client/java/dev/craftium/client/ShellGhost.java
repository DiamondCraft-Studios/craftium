package dev.craftium.client;

import com.mojang.brigadier.StringReader;
import dev.craftium.client.compat.Overlay;
import dev.craftium.core.Point;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/**
 * A set of cells drawn as ghost blocks. Only the outer shell is drawn: a ghost face that touches another ghost is never
 * seen, so the inside costs time and adds nothing. The positions are built when the origin changes and kept between ticks.
 */
final class ShellGhost {
    /** Past this many shell blocks the ghost is too heavy to draw every tick, and callers draw an outline instead. */
    static final int MAX_BLOCKS=9000;

    private final Voxels voxels;private final int[] shell;
    private Point at;private List<BlockPos> places=List.of();private Set<BlockPos> placed=Set.of();

    private ShellGhost(Voxels voxels){this.voxels=voxels;this.shell=voxels.shell();}
    static ShellGhost of(Collection<Point> relative){return new ShellGhost(new Voxels(relative));}

    int count(){return voxels.count();}
    boolean tooLarge(){return shell.length>MAX_BLOCKS*3;}

    /** Draws the cells moved to {@code origin} as {@code state}, or in red over the world's own blocks when {@code state} is null (a carve). */
    void draw(Point origin,BlockState state){
        if(tooLarge()||shell.length==0)return;
        if(!origin.equals(at)){
            var list=new ArrayList<BlockPos>(shell.length/3);
            for(int i=0;i<shell.length;i+=3)list.add(new BlockPos(shell[i]+origin.x(),shell[i+1]+origin.y(),shell[i+2]+origin.z()));
            at=origin;places=list;placed=voxels.at(origin);
        }
        if(state==null)Overlay.blocks(places,ClientState.options.removedTint);
        else Overlay.blockStates(Collections.nCopies(places.size(),state),places,placed,ClientState.options.previewTint);
    }

    private static String blockName;private static BlockState block;
    /** The block named as a state, parsed once per change of block; stone when the name is not a block. */
    static BlockState state(Minecraft mc,String name){
        if(block!=null&&name.equals(blockName))return block;
        BlockState parsed;
        try{parsed=BlockStateParser.parseForBlock(mc.level.registryAccess().lookupOrThrow(Registries.BLOCK),new StringReader(name),false).blockState();}
        catch(Exception e){parsed=net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();}
        blockName=name;return block=parsed;
    }
}
