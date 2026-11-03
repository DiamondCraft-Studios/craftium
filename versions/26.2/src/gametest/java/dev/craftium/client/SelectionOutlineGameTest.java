package dev.craftium.client;

import dev.craftium.core.Point;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Selects an irregular (L-shaped) volume and captures how the editor draws it, for visual review. */
public final class SelectionOutlineGameTest {
    private static final AtomicInteger FRAMES=new AtomicInteger();

    private SelectionOutlineGameTest(){}

    public static void run(ClientGameTestContext c,TestSingleplayerContext w){
        ScreenEvents.AFTER_INIT.register((client,screen,width,height)->{if(screen instanceof EditorScreen)ScreenEvents.afterExtract(screen).register((sc,g,mx,my,delta)->FRAMES.incrementAndGet());});
        c.waitFor(mc->mc.player.onGround(),600); // the shape is placed at the player's feet, so let them land first
        // The shared test world turns the player to face north; the shape is placed to the south, so look at it.
        c.runOnClient(mc->{mc.player.setYRot(0f);mc.player.setXRot(12f);mc.player.setOldPosAndRot();});
        c.runOnClient(mc->CraftiumClient.open(false));
        c.waitTicks(10);
        BlockPos base=c.computeOnClient(mc->mc.player.blockPosition().offset(-3,0,6));
        int bx=base.getX(),by=base.getY(),bz=base.getZ();
        // Real blocks, so the selection has models to draw: a bar with a slab end, and a post topped by a stair.
        w.getServer().runCommand("fill "+bx+" "+by+" "+bz+" "+(bx+3)+" "+by+" "+(bz+1)+" stone_bricks");
        w.getServer().runCommand("fill "+(bx+4)+" "+by+" "+bz+" "+(bx+4)+" "+by+" "+(bz+1)+" stone_brick_slab");
        w.getServer().runCommand("fill "+bx+" "+(by+1)+" "+bz+" "+bx+" "+(by+2)+" "+(bz+1)+" oak_planks");
        w.getServer().runCommand("fill "+bx+" "+(by+3)+" "+bz+" "+bx+" "+(by+3)+" "+(bz+1)+" oak_stairs[facing=east]");
        c.waitTicks(10);
        c.runOnClient(mc->{
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

        // Cost of the real-model ghost at its largest size: frames rendered over 40 ticks with a small selection
        // and with a 16 x 16 x 16 cube (4096 blocks, the cap). Reported, not asserted, because it depends on the machine.
        int small=frames(c,40);
        BlockPos cube=c.computeOnClient(mc->mc.player.blockPosition().offset(-8,0,14));
        int cx=cube.getX(),cy=cube.getY(),cz=cube.getZ();
        w.getServer().runCommand("fill "+cx+" "+cy+" "+cz+" "+(cx+15)+" "+(cy+15)+" "+(cz+15)+" stone");
        c.waitTicks(20);
        c.runOnClient(mc->{
            var points=new ArrayList<Point>();
            for(int x=0;x<16;x++)for(int y=0;y<16;y++)for(int z=0;z<16;z++)points.add(new Point(cx+x,cy+y,cz+z));
            ClientState.options.selectionPoints.clear();ClientState.options.selectionPoints.addAll(points);
            CraftiumClient.send("lasso_select",null,new Point(0,1,0));
        });
        w.getConnection().waitForServerboundPackets();
        c.waitTicks(10);
        w.getConnection().waitForClientboundPackets();
        c.waitTicks(20);
        require(c.computeOnClient(mc->ClientState.status.selectionSize())==4096,"The 4096-block cube was not selected");
        int big=frames(c,40);
        System.out.printf(java.util.Locale.ROOT,"CRAFTIUM_SELECTION_PERF frames_in_40_ticks: small_selection=%d  cube_4096_blocks=%d  (%.0f%% of the small selection's frame rate)%n",small,big,100.0*big/small);
    }

    private static int frames(ClientGameTestContext c,int ticks){FRAMES.set(0);c.waitTicks(ticks);return FRAMES.get();}

    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
