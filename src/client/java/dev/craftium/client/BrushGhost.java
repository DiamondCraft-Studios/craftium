package dev.craftium.client;

import com.mojang.brigadier.StringReader;
import dev.craftium.client.compat.Overlay;
import dev.craftium.core.BrushMath;
import dev.craftium.core.Options;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/**
 * The Shape tool's hover preview: the blocks the brush would place, drawn as ghost blocks. Only the outer shell is drawn,
 * because a ghost face that touches another ghost is never shown, so the inside costs time and adds nothing. The shell and
 * its positions are kept between ticks and rebuilt only when the shape, radius or cursor block changes.
 */
final class BrushGhost {
    /** Past this many shell blocks the preview is too heavy to draw every tick and the caller falls back to a wireframe. */
    static final int MAX_BLOCKS=9000;
    private static final int[] NONE=new int[0];

    private static BrushMath.Shape shape;private static int radius=-1;private static boolean hollow;
    private static int[] offsets=NONE;
    private static BlockPos centre;private static List<BlockPos> places=List.of();private static Set<BlockPos> placed=Set.of();
    private static String blockName;private static BlockState state;

    private BrushGhost(){}

    /** Draws the preview at {@code at} and returns true, or returns false when it is too large to ghost. */
    static boolean draw(Minecraft mc,BlockPos at,Options o){
        if(mc.level==null)return false;
        rebuild(o);
        if(offsets.length>MAX_BLOCKS*3)return false;
        if(offsets.length==0)return true;
        if(!at.equals(centre)){
            var list=new ArrayList<BlockPos>(offsets.length/3);
            for(int i=0;i<offsets.length;i+=3)list.add(new BlockPos(at.getX()+offsets[i],at.getY()+offsets[i+1],at.getZ()+offsets[i+2]));
            centre=at.immutable();places=list;placed=new HashSet<>(list);
        }
        // Erasing shows the blocks that would go, in red, on the blocks that are really there.
        if(o.invert||o.tool==dev.craftium.core.Tool.ERASE)Overlay.blocks(places,OutlineShape.DELETE);
        else Overlay.blockStates(Collections.nCopies(places.size(),state(mc,o)),places,placed,OutlineShape.GHOST);
        return true;
    }

    /** Recomputes the shell offsets when the shape, radius or hollowness changed. */
    private static void rebuild(Options o){
        if(o.shape==shape&&o.radius==radius&&o.hollow==hollow)return;
        shape=o.shape;radius=o.radius;hollow=o.hollow;centre=null;
        int r=radius,n=2*r+1;
        boolean[] in=new boolean[n*n*n];
        for(int x=-r;x<=r;x++)for(int y=-r;y<=r;y++)for(int z=-r;z<=r;z++)
            in[index(x,y,z,r,n)]=BrushMath.contains(shape,x,y,z,r)&&(!hollow||!BrushMath.contains(shape,x,y,z,r-1));
        int count=0;int[] out=new int[64];
        for(int x=-r;x<=r;x++)for(int y=-r;y<=r;y++)for(int z=-r;z<=r;z++){
            if(!in[index(x,y,z,r,n)])continue;
            // A block with all six neighbours in the brush is buried; a hollow brush keeps its inner skin, which is not buried.
            if(inside(in,x+1,y,z,r,n)&&inside(in,x-1,y,z,r,n)&&inside(in,x,y+1,z,r,n)&&inside(in,x,y-1,z,r,n)&&inside(in,x,y,z+1,r,n)&&inside(in,x,y,z-1,r,n))continue;
            if(count+3>out.length)out=Arrays.copyOf(out,out.length*2);
            out[count++]=x;out[count++]=y;out[count++]=z;
        }
        offsets=Arrays.copyOf(out,count);
    }
    private static int index(int x,int y,int z,int r,int n){return ((x+r)*n+(y+r))*n+(z+r);}
    private static boolean inside(boolean[] in,int x,int y,int z,int r,int n){return Math.abs(x)<=r&&Math.abs(y)<=r&&Math.abs(z)<=r&&in[index(x,y,z,r,n)];}

    /** The active block as a state, parsed once per change of block. */
    private static BlockState state(Minecraft mc,Options o){
        if(state!=null&&o.block.equals(blockName))return state;
        BlockState parsed;
        try{parsed=BlockStateParser.parseForBlock(mc.level.registryAccess().lookupOrThrow(Registries.BLOCK),new StringReader(o.block),false).blockState();}
        catch(Exception e){parsed=net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();}
        blockName=o.block;return state=parsed;
    }
}
