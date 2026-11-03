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
        if("paper".equals(System.getenv("CRAFTIUM_TEST_SCOPE")))return; // the live Paper check runs instead
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
            ctx.runOnClient(mc->ClientCompat.setScreen(mc,null));
        }
        System.out.println("CRAFTIUM_SMOKE done");
    }
}
