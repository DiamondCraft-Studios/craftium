package dev.craftium.client;

import dev.craftium.core.Point;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import java.util.ArrayList;

/** Selects an irregular (L-shaped) volume and captures how the editor draws it, for visual review. */
public final class SelectionOutlineGameTest {
    private SelectionOutlineGameTest(){}

    public static void run(ClientGameTestContext c,TestSingleplayerContext w){
        c.waitFor(mc->mc.player.onGround(),600); // the shape is placed at the player's feet, so let them land first
        // The shared test world turns the player to face north; the shape is placed to the south, so look at it.
        c.runOnClient(mc->{mc.player.setYRot(0f);mc.player.setXRot(12f);mc.player.setOldPosAndRot();});
        c.runOnClient(mc->CraftiumClient.open(false));
        c.waitTicks(10);
        c.runOnClient(mc->{
            BlockPos base=mc.player.blockPosition().offset(-3,0,6);
            var points=new ArrayList<Point>();
            for(int x=0;x<5;x++)for(int z=0;z<2;z++)points.add(new Point(base.getX()+x,base.getY(),base.getZ()+z));
            for(int y=1;y<4;y++)for(int z=0;z<2;z++)points.add(new Point(base.getX(),base.getY()+y,base.getZ()+z));
            ClientState.options.selectionPoints.clear();ClientState.options.selectionPoints.addAll(points);
            CraftiumClient.send("lasso_select",null,new Point(0,1,0));
        });
        w.getConnection().waitForServerboundPackets();
        c.waitTicks(10);
        w.getConnection().waitForClientboundPackets();
        c.waitTicks(20);
        require(c.computeOnClient(mc->ClientState.status.selectionSize())==16,"L-shaped selection was not created");
        c.takeScreenshot("selection-l-shape");
        System.out.println("CRAFTIUM_SELECTION_OUTLINE_PASS l_shape_selected");
    }

    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
