package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.core.Options;
import dev.craftium.core.Point;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;

/**
 * Launches the 1.21.10 client and checks that it starts with every mixin applied, that the editor
 * renders, and that each overlay primitive draws. Screenshots are written for visual review.
 */
public final class SmokeGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext ctx) {
        String scope=System.getenv("CRAFTIUM_TEST_SCOPE");
        if("paper".equals(scope)||"viewport".equals(scope))return; // a focused scope runs instead
        try(var world=ctx.worldBuilder().adjustSettings(s->{s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);s.setAllowCommands(true);}).create()) {
            ctx.waitTicks(40);
            world.getClientWorld().waitForChunksDownload();
            System.out.println("CRAFTIUM_SMOKE world_ready");

            // Drive the overlay through the mod's own features: the mod rebuilds the batch every tick.
            ctx.runOnClient(mc->{
                BlockPos base=mc.player.blockPosition().offset(0,0,5);
                ClientState.rulerPoints.clear();
                ClientState.rulerPoints.add(new Point(base.getX()-3,base.getY()+1,base.getZ()));
                ClientState.rulerPoints.add(new Point(base.getX()+3,base.getY()+3,base.getZ()+2));
                BuilderMode.select(Options.BuilderTool.CLONE);
                BuilderMode.corners(new Point(base.getX()-1,base.getY(),base.getZ()+1),new Point(base.getX()+1,base.getY()+2,base.getZ()+3));
            });
            ctx.waitTicks(5);
            ctx.takeScreenshot("overlay");
            System.out.println("CRAFTIUM_SMOKE overlay_drawn");

            // Minimum brightness and liquid opacity exercise the lightmap and fluid mixins.
            ctx.runOnClient(mc->{ClientState.options.minimumBrightness=.5;ClientState.options.liquidOpacity=.5;});
            ctx.waitTicks(10);
            ctx.takeScreenshot("brightness");
            System.out.println("CRAFTIUM_SMOKE mixins_ran");

            ctx.runOnClient(mc->{
                BlockPos base=mc.player.blockPosition().offset(0,0,5);
                ClientState.options.nodes.clear();
                ClientState.options.nodes.add(new Point(base.getX()-2,base.getY()+1,base.getZ()+1));
                ClientState.options.nodes.add(new Point(base.getX()+2,base.getY()+3,base.getZ()+1));
                CraftiumClient.open(false);
            });
            ctx.waitTicks(10);
            ctx.takeScreenshot("editor");
            System.out.println("CRAFTIUM_SMOKE editor_open="+ctx.computeOnClient(mc->ClientCompat.screen(mc) instanceof EditorScreen));
            System.out.println("CRAFTIUM_SMOKE editor_spectator="+ctx.computeOnClient(mc->mc.player.isSpectator()));
            // A real selection draws filled boxes and the transform handles.
            ctx.runOnClient(mc->{
                BlockPos base=mc.player.blockPosition().offset(0,0,5);
                mc.getConnection().sendCommand("craftium pos1 "+(base.getX()-1)+" "+base.getY()+" "+(base.getZ()+1));
            });
            ctx.waitTicks(3);
            ctx.runOnClient(mc->{
                BlockPos base=mc.player.blockPosition().offset(0,0,5);
                mc.getConnection().sendCommand("craftium pos2 "+(base.getX()+1)+" "+(base.getY()+2)+" "+(base.getZ()+3));
            });
            world.getClientWorld().waitForChunksDownload();
            ctx.waitTicks(20);
            ctx.takeScreenshot("selection");
            System.out.println("CRAFTIUM_SMOKE selection_drawn");

            // An irregular selection (an L-shaped prism) must be one outline around its silhouette, nothing inside.
            ctx.runOnClient(mc->{
                BlockPos base=mc.player.blockPosition().offset(-3,0,6);
                int bx=base.getX(),by=base.getY(),bz=base.getZ();
                // Real blocks, so the selection has models to draw: a bar with a slab end, and a post topped by a stair.
                mc.getConnection().sendCommand("fill "+bx+" "+by+" "+bz+" "+(bx+3)+" "+by+" "+(bz+1)+" stone_bricks");
                mc.getConnection().sendCommand("fill "+(bx+4)+" "+by+" "+bz+" "+(bx+4)+" "+by+" "+(bz+1)+" stone_brick_slab");
                mc.getConnection().sendCommand("fill "+bx+" "+(by+1)+" "+bz+" "+bx+" "+(by+2)+" "+(bz+1)+" oak_planks");
                mc.getConnection().sendCommand("fill "+bx+" "+(by+3)+" "+bz+" "+bx+" "+(by+3)+" "+(bz+1)+" oak_stairs[facing=east]");
                var points=new java.util.ArrayList<dev.craftium.core.Point>();
                for(int x=0;x<5;x++)for(int z=0;z<2;z++)points.add(new dev.craftium.core.Point(base.getX()+x,base.getY(),base.getZ()+z));
                for(int y=1;y<4;y++)for(int z=0;z<2;z++)points.add(new dev.craftium.core.Point(base.getX(),base.getY()+y,base.getZ()+z));
                ClientState.options.selectionPoints.clear();ClientState.options.selectionPoints.addAll(points);
                CraftiumClient.send("lasso_select",null,new dev.craftium.core.Point(0,1,0));
            });
            world.getClientWorld().waitForChunksDownload();
            ctx.waitTicks(30);
            ctx.takeScreenshot("selection-l-shape");
            System.out.println("CRAFTIUM_SMOKE l_selection_drawn");
            ctx.runOnClient(mc->mc.screen.onClose()); // the editor gives the game mode back as it closes
            ctx.waitTicks(5);
            System.out.println("CRAFTIUM_SMOKE mode_restored="+ctx.computeOnClient(mc->mc.player.isCreative()));

            // The builder's clone preview is the same single outline, with the real blocks drawn where they would land.
            ctx.runOnClient(mc->{
                BlockPos base=mc.player.blockPosition().offset(-3,0,6);
                BuilderMode.select(Options.BuilderTool.CLONE);
                BuilderMode.corners(new Point(base.getX(),base.getY(),base.getZ()),new Point(base.getX()+4,base.getY()+3,base.getZ()+1));
                BuilderMode.nudge(new Point(0,0,1),4);
            });
            ctx.waitTicks(10);
            ctx.takeScreenshot("builder-preview");
            System.out.println("CRAFTIUM_SMOKE builder_preview_drawn");
            ctx.runOnClient(mc->BuilderMode.reset());
        }
        System.out.println("CRAFTIUM_SMOKE done");
    }
}
