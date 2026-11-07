package dev.craftium.client;

import dev.craftium.core.Options;
import dev.craftium.core.Point;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/**
 * The server's side of edit prediction: builder commits sent while an edit is still applying wait their turn instead of
 * being refused, and a cell the server skips is sent again so a client that predicted it puts it back.
 */
public final class BuilderQueueGameTest {
    private BuilderQueueGameTest(){}
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static void pause(){try{Thread.sleep(120);}catch(InterruptedException e){throw new AssertionError(e);}}
    private static void settle(ClientGameTestContext c,TestSingleplayerContext w){w.getConnection().waitForServerboundPackets();c.waitTicks(5);c.waitFor(mc->!ClientState.status.busy(),600);w.getConnection().waitForClientboundPackets();c.waitTicks(5);}
    private static void commit(ClientGameTestContext c,Point a,Point b,Options.BuilderTool tool,Point offset){
        pause();
        c.runOnClient(mc->{var o=ClientState.options;o.builderTool=tool;o.builderCells="";o.builderKeepExisting=false;o.copyEntities=false;o.pasteAir=true;o.position1=a;o.position2=b;o.builderOffset=offset;CraftiumClient.send("builder_commit",a,new Point(0,1,0));});
    }

    public static void run(ClientGameTestContext c,TestSingleplayerContext w){
        c.waitFor(mc->mc.player.onGround(),600);
        c.runOnClient(mc->mc.player.getAbilities().flying=true);
        BlockPos base=c.computeOnClient(mc->mc.player.blockPosition().offset(-8,10,8));
        int bx=base.getX(),by=base.getY(),bz=base.getZ();

        // Two clones of a box too big to apply in one tick, the second sent while the first is still being applied.
        w.getServer().runCommand("fill "+bx+" "+by+" "+bz+" "+(bx+15)+" "+(by+31)+" "+(bz+15)+" stone");
        c.waitTicks(10);
        Point a=new Point(bx,by,bz),b=new Point(bx+15,by+31,bz+15);
        boolean[] refused={false};var seen=new java.util.LinkedHashSet<String>();
        commit(c,a,b,Options.BuilderTool.CLONE,new Point(0,0,20));
        commit(c,a,b,Options.BuilderTool.CLONE,new Point(0,0,40));
        for(int i=0;i<400&&c.computeOnClient(mc->ClientState.status.busy());i++){c.waitTick();if(c.computeOnClient(mc->ClientState.status.error()))refused[0]=true;seen.add(c.computeOnClient(mc->(ClientState.status.error()?"ERR ":"")+ClientState.status.message()+(ClientState.status.busy()?" [busy]":"")));}
        // The status that ends the first edit says "not busy" just before the queued one starts, so wait for the last block itself.
        BlockPos last=new BlockPos(bx+15,by+31,bz+55);
        for(int i=0;i<600&&!w.getServer().computeOnServer(sv->sv.overworld().getBlockState(last).is(Blocks.STONE));i++)c.waitTick();
        settle(c,w);
        require(!refused[0]&&c.computeOnClient(mc->!ClientState.status.error()),"A builder commit sent while another applied was refused: "+c.computeOnClient(mc->ClientState.status.message()));
        boolean[] landed=w.getServer().computeOnServer(s->{var l=s.overworld();return new boolean[]{
            l.getBlockState(new BlockPos(bx,by,bz+20)).is(Blocks.STONE),l.getBlockState(new BlockPos(bx+15,by+31,bz+35)).is(Blocks.STONE),
            l.getBlockState(new BlockPos(bx,by,bz+40)).is(Blocks.STONE),l.getBlockState(new BlockPos(bx+15,by+31,bz+55)).is(Blocks.STONE)};});
        require(landed[0]&&landed[1]&&landed[2]&&landed[3],"The queued clone did not apply after the first: "+java.util.Arrays.toString(landed)+" · "+seen);

        // A skipped cell is sent again: the client holds a stale block there, as after a prediction the server did not carry out.
        int sx=bx+30,sy=by,sz=bz;
        w.getServer().runCommand("fill "+sx+" "+sy+" "+sz+" "+(sx+2)+" "+sy+" "+sz+" stone");
        c.waitTicks(10);
        Point from=new Point(sx,sy,sz),to=new Point(sx+2,sy,sz);
        commit(c,from,to,Options.BuilderTool.CLONE,new Point(0,0,5));
        settle(c,w);
        BlockPos middle=new BlockPos(sx+1,sy,sz+5);
        require(w.getServer().computeOnServer(s->s.overworld().getBlockState(middle).is(Blocks.STONE)),"The clone did not land");
        w.getServer().runOnServer(s->s.overworld().setBlock(middle,Blocks.DIRT.defaultBlockState(),3)); // changed behind the edit's back: undoing it must skip this cell
        c.waitTicks(5);
        c.runOnClient(mc->mc.level.setBlock(middle,Blocks.GOLD_BLOCK.defaultBlockState(),18));
        require(c.computeOnClient(mc->mc.level.getBlockState(middle).is(Blocks.GOLD_BLOCK)),"The client's stale block was not set");
        pause();
        c.runOnClient(mc->CraftiumClient.send("builder_undo",null,null));
        settle(c,w);
        require(w.getServer().computeOnServer(s->s.overworld().getBlockState(middle).is(Blocks.DIRT)&&s.overworld().getBlockState(new BlockPos(sx,sy,sz+5)).isAir()),"The undo did not skip the changed cell and clear the others");
        require(c.computeOnClient(mc->mc.level.getBlockState(middle).is(Blocks.DIRT)),"A skipped cell was not sent again, so the client kept its stale block");

        // The same with an edit big enough to be written a chunk at a time: the skipped cell is still sent again, and the rest are still cleared.
        int lx=bx+40,ly=by,lz=bz;
        w.getServer().runCommand("fill "+lx+" "+ly+" "+lz+" "+(lx+7)+" "+(ly+3)+" "+(lz+7)+" stone");
        c.waitTicks(10);
        commit(c,new Point(lx,ly,lz),new Point(lx+7,ly+3,lz+7),Options.BuilderTool.CLONE,new Point(0,0,10));
        settle(c,w);
        BlockPos kept=new BlockPos(lx+4,ly+2,lz+14);
        require(w.getServer().computeOnServer(s->s.overworld().getBlockState(kept).is(Blocks.STONE)&&s.overworld().getBlockState(new BlockPos(lx,ly,lz+10)).is(Blocks.STONE)),"The large clone did not land");
        w.getServer().runOnServer(s->s.overworld().setBlock(kept,Blocks.DIRT.defaultBlockState(),3)); // changed behind the edit's back: undoing it must skip this cell
        c.waitTicks(5);
        c.runOnClient(mc->mc.level.setBlock(kept,Blocks.GOLD_BLOCK.defaultBlockState(),18));
        long written=dev.craftium.server.BulkPlace.blocksWritten;
        pause();
        c.runOnClient(mc->CraftiumClient.send("builder_undo",null,null));
        settle(c,w);
        require(dev.craftium.server.BulkPlace.blocksWritten-written>100,"The large undo was not written a chunk at a time, so this did not test that path");
        require(w.getServer().computeOnServer(s->s.overworld().getBlockState(kept).is(Blocks.DIRT)&&s.overworld().getBlockState(new BlockPos(lx,ly,lz+10)).isAir()&&s.overworld().getBlockState(new BlockPos(lx+7,ly+3,lz+17)).isAir()),"The large undo did not skip the changed cell and clear the others");
        require(c.computeOnClient(mc->mc.level.getBlockState(kept).is(Blocks.DIRT)),"A skipped cell in a large edit was not sent again, so the client kept its stale block");
    }
}
