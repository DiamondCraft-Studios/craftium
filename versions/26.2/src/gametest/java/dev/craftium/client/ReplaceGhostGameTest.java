package dev.craftium.client;

import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

/** Replace mode's white tint: when the held block's shape lies wholly inside the replaced block's, which is the case a slab on a full block is. */
public final class ReplaceGhostGameTest {
    private ReplaceGhostGameTest(){}
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static BlockState slab(SlabType type){return Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE,type);}
    private static boolean hidden(net.minecraft.client.Minecraft mc,BlockState held,BlockState target){
        var pos=new BlockPos(0,10,0);
        return ReplaceGhost.hidden(held.getShape(mc.level,pos),target.getShape(mc.level,pos));
    }
    public static void run(ClientGameTestContext c,TestSingleplayerContext w){
        var stone=Blocks.STONE.defaultBlockState();
        var bottom=slab(SlabType.BOTTOM);
        var stair=Blocks.STONE_STAIRS.defaultBlockState();
        require(c.computeOnClient(mc->hidden(mc,bottom,stone)),"A bottom slab in a full block is not hidden");
        require(c.computeOnClient(mc->hidden(mc,slab(SlabType.TOP),stone)),"A top slab in a full block is not hidden");
        require(c.computeOnClient(mc->hidden(mc,Blocks.OAK_FENCE.defaultBlockState(),stone)),"A fence in a full block is not hidden");
        require(c.computeOnClient(mc->hidden(mc,Blocks.STONE_PRESSURE_PLATE.defaultBlockState(),stone)),"A pressure plate in a full block is not hidden");
        require(c.computeOnClient(mc->hidden(mc,bottom,stair)),"A slab in a stair, whose lower half it fills, is not hidden");
        require(c.computeOnClient(mc->hidden(mc,bottom,slab(SlabType.DOUBLE))),"A slab in a double slab is not hidden");
        require(c.computeOnClient(mc->!hidden(mc,stone,stone)),"A full block in a full block counts as hidden");
        require(c.computeOnClient(mc->!hidden(mc,stone,bottom)),"A full block in a slab counts as hidden");
        require(c.computeOnClient(mc->!hidden(mc,bottom,bottom)),"A slab in the same slab counts as hidden");
        require(c.computeOnClient(mc->!hidden(mc,bottom,slab(SlabType.TOP))),"A bottom slab in a top slab counts as hidden");
        require(c.computeOnClient(mc->!hidden(mc,stair,bottom)),"A stair in a slab counts as hidden, though its upper step stands out");
        require(c.computeOnClient(mc->!hidden(mc,bottom,Blocks.AIR.defaultBlockState())),"A slab in air counts as hidden");
        require(c.computeOnClient(mc->!hidden(mc,Blocks.AIR.defaultBlockState(),stone)),"Air in a full block counts as hidden");
    }
}
