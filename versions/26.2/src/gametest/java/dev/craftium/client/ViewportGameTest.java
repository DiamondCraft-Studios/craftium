package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Measures how often the editor viewport's camera actually changes while flying. The camera is sampled
 * once per rendered frame, so a camera driven only by game ticks shows about 20 distinct positions per
 * second however fast the game renders, while a camera updated every frame changes on nearly every frame.
 */
public final class ViewportGameTest {
    private static final List<double[]> FRAMES=Collections.synchronizedList(new ArrayList<>());
    private static volatile boolean recording;

    private ViewportGameTest(){}

    public static void run(ClientGameTestContext c,TestSingleplayerContext w){
        ScreenEvents.AFTER_INIT.register((client,screen,width,height)->{
            if(screen instanceof EditorScreen)ScreenEvents.afterExtract(screen).register((s,g,mx,my,delta)->{
                if(!recording)return;
                var p=ClientCompat.camera(client).position();
                FRAMES.add(new double[]{System.nanoTime(),p.x,p.y,p.z});
            });
        });
        c.runOnClient(mc->CraftiumClient.open(false));
        c.waitTicks(20);
        require(c.computeOnClient(mc->ClientCompat.screen(mc) instanceof EditorScreen),"Editor did not open");
        c.runOnClient(mc->{mc.player.getAbilities().mayfly=true;mc.player.getAbilities().flying=true;EditorScreen.flightInput=()->new double[]{1,0,0};});
        c.waitTicks(10); // let flight start so only steady movement is measured
        recording=true;
        c.waitTicks(40);
        recording=false;
        c.runOnClient(mc->EditorScreen.flightInput=null);
        c.waitTicks(5);

        List<double[]> frames=new ArrayList<>(FRAMES);
        require(frames.size()>10,"No frames were rendered while measuring ("+frames.size()+")");
        int changes=0;double distance=0;
        for(int i=1;i<frames.size();i++){
            double[] a=frames.get(i-1),b=frames.get(i);
            double step=Math.sqrt(Math.pow(b[1]-a[1],2)+Math.pow(b[2]-a[2],2)+Math.pow(b[3]-a[3],2));
            if(step>1e-9){changes++;distance+=step;}
        }
        double seconds=(frames.get(frames.size()-1)[0]-frames.get(0)[0])/1e9;
        System.out.printf(java.util.Locale.ROOT,"CRAFTIUM_VIEWPORT ticks=40 frames=%d frames_per_tick=%.2f seconds=%.2f camera_changes=%d changed_frames=%.0f%% blocks_total=%.2f%n",
            frames.size(),frames.size()/40.0,seconds,changes,100.0*changes/(frames.size()-1),distance);
        // A tick-driven camera changes about once per tick, which is under 20% of frames at this render rate.
        require(changes>=0.9*(frames.size()-1),"Viewport camera updated on only "+changes+" of "+(frames.size()-1)+" frames");
        // Flying keeps the speed it had on ticks: 0.35 blocks per tick, i.e. 7 blocks per second at fly speed 1.
        double expected=7*c.computeOnClient(mc->ClientState.options.flySpeed)*seconds;
        require(Math.abs(distance-expected)<0.15*expected,"Fly speed changed: moved "+distance+" blocks, expected about "+expected);
        System.out.println("CRAFTIUM_VIEWPORT_PASS per_frame_camera fly_speed");
    }

    private static void pause(long millis){try{Thread.sleep(millis);}catch(InterruptedException e){throw new AssertionError(e);}}
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
