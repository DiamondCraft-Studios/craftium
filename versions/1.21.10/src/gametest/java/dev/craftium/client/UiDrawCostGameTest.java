package dev.craftium.client;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import java.util.*;

/**
 * Measures what the editor's panels cost per frame: the frame rate with icons drawn line by line and as baked textures, and with no
 * panels at all, with a screenshot of each icon style to compare. Reported only, because it depends on the machine. Run with {@code CRAFTIUM_TEST_SCOPE=uiperf}.
 */
public final class UiDrawCostGameTest implements FabricClientGameTest {
    private static final List<Long> STAMPS=Collections.synchronizedList(new ArrayList<>());

    @Override public void runTest(ClientGameTestContext c){
        if(!"uiperf".equals(System.getenv("CRAFTIUM_TEST_SCOPE")))return;
        try(var world=c.worldBuilder().adjustSettings(st->st.setGameMode(net.minecraft.client.gui.screens.worldselection.WorldCreationUiState.SelectedGameMode.CREATIVE)).create()){
            c.waitTicks(40);
            world.getClientWorld().waitForChunksDownload();
            run(c);
        }
    }

    private static String fps(ClientGameTestContext c,String label){
        c.waitTicks(30);STAMPS.clear();c.waitTicks(150);
        var s=new ArrayList<>(STAMPS);var d=new ArrayList<Double>();
        for(int i=1;i<s.size();i++)d.add((s.get(i)-s.get(i-1))/1e6);
        Collections.sort(d);
        double seconds=s.size()<2?1:(s.get(s.size()-1)-s.get(0))/1e9;
        return String.format(Locale.ROOT,"%-34s %7.1f fps  median %.2f ms  p95 %.2f ms%n",label,(s.size()-1)/seconds,d.get(d.size()/2),d.get((int)(d.size()*.95)));
    }
    private static void panels(EditorScreen screen,boolean on){
        try{var f=EditorScreen.class.getDeclaredField("panels");f.setAccessible(true);f.setBoolean(screen,on);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }

    public static void run(ClientGameTestContext c){
        ScreenEvents.AFTER_INIT.register((client,screen,width,height)->{if(screen instanceof EditorScreen)ScreenEvents.afterRender(screen).register((sc,g,mx,my,delta)->STAMPS.add(System.nanoTime()));});
        c.waitFor(mc->mc.player.onGround(),600);
        c.runOnClient(mc->{mc.player.setYRot(0f);mc.player.setXRot(-8f);mc.player.setOldPosAndRot();});
        c.runOnClient(mc->{mc.options.enableVsync().set(false);mc.options.framerateLimit().set(260);mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);});
        c.runOnClient(mc->CraftiumClient.open(false));
        c.waitTicks(10);
        var report=new StringBuilder();
        c.runOnClient(mc->IconBake.enabled=false);
        report.append(fps(c,"drawn icons (before)"));
        c.takeScreenshot("ui-drawn");
        c.runOnClient(mc->IconBake.enabled=true);
        report.append(fps(c,"baked icons"));
        c.takeScreenshot("ui-baked");
        c.runOnClient(mc->panels((EditorScreen)mc.screen,false));
        report.append(fps(c,"panels off"));
        c.runOnClient(mc->panels((EditorScreen)mc.screen,true));
        report.append(fps(c,"baked icons again"));
        System.out.println("CRAFTIUM_UI_DRAW_COST\n"+report);
        try{java.nio.file.Files.writeString(java.nio.file.Path.of("ui-draw-cost.txt"),report.toString());}catch(java.io.IOException e){System.out.println("could not write report: "+e);}
    }
}
