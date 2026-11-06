package dev.craftium.client;

import dev.craftium.core.Point;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import java.util.Map;

/** Predicted edits show on the client at once and are taken back when the server rejects them, but stay once it confirms. */
public final class PredictionGameTest {
    private PredictionGameTest(){}
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}

    public static void run(ClientGameTestContext c,TestSingleplayerContext w){
        c.waitFor(mc->mc.player.onGround(),600);
        BlockPos base=c.computeOnClient(mc->mc.player.blockPosition().offset(-3,0,6));
        Point origin=new Point(base.getX(),base.getY(),base.getZ());
        BlockPos second=base.east();
        var stone=Blocks.STONE.defaultBlockState();var gold=Blocks.GOLD_BLOCK.defaultBlockState();
        c.waitTicks(10);
        // The server's world is untouched: only the client is predicting.
        c.runOnClient(mc->{
            ClientState.options.pasteAir=false;ClientState.options.mergeBlocks=false;ClientState.options.keepExisting=false;
            BuilderPrediction.paste(ClientState.options,origin,Map.of(new Point(0,0,0),stone,new Point(1,0,0),gold));
        });
        require(c.computeOnClient(mc->mc.level.getBlockState(base).is(Blocks.STONE)&&mc.level.getBlockState(second).is(Blocks.GOLD_BLOCK)),"A predicted paste did not show on the client at once");
        require(w.getServer().computeOnServer(s->s.overworld().getBlockState(base).isAir()),"Prediction changed the server's world");
        // The server turns the edit down: the client puts its blocks back.
        c.runOnClient(mc->BuilderPrediction.rejected());
        require(c.computeOnClient(mc->mc.level.getBlockState(base).isAir()&&mc.level.getBlockState(second).isAir()),"A rejected edit was not taken back");
        // The server confirms: a later rejection no longer touches what it settled.
        c.runOnClient(mc->{BuilderPrediction.paste(ClientState.options,origin,Map.of(new Point(0,0,0),stone));BuilderPrediction.confirmed("Applied Paste clipboard · 1 changes");BuilderPrediction.rejected();});
        require(c.computeOnClient(mc->mc.level.getBlockState(base).is(Blocks.STONE)),"A confirmed edit was undone by a later rejection");
        // Two edits in flight over the same block: the older is rejected, and the newer stays on screen until it is answered itself.
        BlockPos over=base.south(6);Point overAt=origin.add(0,0,6);
        c.runOnClient(mc->{BuilderPrediction.paste(ClientState.options,overAt,Map.of(new Point(0,0,0),stone));BuilderPrediction.paste(ClientState.options,overAt,Map.of(new Point(0,0,0),gold));});
        c.runOnClient(mc->BuilderPrediction.rejected());
        require(c.computeOnClient(mc->mc.level.getBlockState(over).is(Blocks.GOLD_BLOCK)),"Rejecting the older of two edits took the newer one's blocks away");
        c.runOnClient(mc->BuilderPrediction.rejected());
        require(c.computeOnClient(mc->mc.level.getBlockState(over).isAir()),"Rejecting the newer edit did not restore the original block, only the older edit's");
        // Anything the client cannot work out exactly is left to the server.
        c.runOnClient(mc->{ClientState.options.pasteAir=true;BuilderPrediction.paste(ClientState.options,origin.add(0,0,3),Map.of(new Point(0,0,0),gold));ClientState.options.pasteAir=false;});
        require(c.computeOnClient(mc->mc.level.getBlockState(base.south(3)).isAir()),"A paste with Copy air on was predicted");
    }
}
