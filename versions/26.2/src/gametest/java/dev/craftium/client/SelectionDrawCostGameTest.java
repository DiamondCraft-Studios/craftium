package dev.craftium.client;

import dev.craftium.core.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import java.util.*;
import java.util.function.Supplier;

/**
 * Measures what drawing a big selection costs per frame, to judge where OutlineShape's caps (8192 edges, 4096 faces) can be
 * raised to. Each shape is shown three ways: as the game draws it now (the box once a cap is passed), as its full outline with
 * the cap lifted, and the same with every edge drawn. Reported as frame times; nothing is asserted, because it depends on the
 * machine. Run with {@code CRAFTIUM_TEST_SCOPE=selectionperf}.
 */
public final class SelectionDrawCostGameTest {
    private static final List<Long> STAMPS=Collections.synchronizedList(new ArrayList<>());
    private static final int SETTLE_TICKS=20,MEASURE_TICKS=100;

    private record Shape(String name,Supplier<List<Point>> make){}

    private SelectionDrawCostGameTest(){}

    private static List<Point> sphere(int r,boolean hollow){
        var s=new ArrayList<Point>();
        for(int x=-r;x<=r;x++)for(int y=-r;y<=r;y++)for(int z=-r;z<=r;z++){int d=x*x+y*y+z*z;if(d<=r*r&&(!hollow||d>(r-2)*(r-2)))s.add(new Point(x,y,z));}
        return s;
    }
    private static List<Point> terrain(int side,long seed){
        var rnd=new Random(seed);var s=new ArrayList<Point>();
        for(int x=0;x<side;x++)for(int z=0;z<side;z++){int h=8+(int)(6*Math.sin(x/9.0)+6*Math.cos(z/7.0)+rnd.nextInt(3));for(int y=0;y<h;y++)s.add(new Point(x-side/2,y,z-side/2));}
        return s;
    }
    /** Rolling ground with noise, rougher than {@link #terrain}: the busiest heightfield that still fits under both caps. */
    private static List<Point> roughTerrain(int side,int base,int amp,int noise,long seed){
        var rnd=new Random(seed);var s=new ArrayList<Point>();
        for(int x=0;x<side;x++)for(int z=0;z<side;z++){int h=base+(int)(amp*Math.sin(x/4.0)+amp*Math.cos(z/3.0))+rnd.nextInt(noise);for(int y=0;y<Math.max(1,h);y++)s.add(new Point(x-side/2,y,z-side/2));}
        return s;
    }
    private static List<Point> scatter(int side,double density,long seed){
        var rnd=new Random(seed);var s=new ArrayList<Point>();
        for(int x=0;x<side;x++)for(int y=0;y<side;y++)for(int z=0;z<side;z++)if(rnd.nextDouble()<density)s.add(new Point(x-side/2,y,z-side/2));
        return s;
    }

    private static long gcMillis(){long sum=0;for(var b:java.lang.management.ManagementFactory.getGarbageCollectorMXBeans())sum+=Math.max(0,b.getCollectionTime());return sum;}

    public static void run(ClientGameTestContext c,TestSingleplayerContext w){
        ScreenEvents.AFTER_INIT.register((client,screen,width,height)->{if(screen instanceof EditorScreen)ScreenEvents.afterExtract(screen).register((sc,g,mx,my,delta)->STAMPS.add(System.nanoTime()));});
        c.waitFor(mc->mc.player.onGround(),600);
        c.runOnClient(mc->{mc.player.setYRot(0f);mc.player.setXRot(-8f);mc.player.setOldPosAndRot();});
        c.runOnClient(mc->CraftiumClient.open(false));
        c.waitTicks(10);
        var base=c.computeOnClient(mc->mc.player.blockPosition());
        int ox=base.getX(),oy=base.getY()+6,oz=base.getZ()+70; // in front of the player, and clear of the camera so no shape (the widest is 100 blocks) surrounds it

        var shapes=List.of(
            new Shape("cube 16^3 (reference)",()->scatter(16,1,0)),
            new Shape("sphere r=10",()->sphere(10,false)),
            new Shape("sphere r=31",()->sphere(31,false)),
            new Shape("hollow sphere r=40",()->sphere(40,true)),
            new Shape("terrain 100x100",()->terrain(100,2)),
            new Shape("scatter 50^3 @80%",()->scatter(50,.8,6)),
            new Shape("rough terrain (both caps)",()->roughTerrain(100,10,7,5,9)), // 36,800 edges and 23,900 faces: about 90% and 80% of the caps
            new Shape("scatter 36^3 @50% (at cap)",()->scatter(36,.5,8)),     // close to the default edge cap: the busiest shape still drawn in full
            new Shape("scatter 100^3 @10% (box)",()->scatter(100,.1,7)));    // 650,000 edges: far past the caps, so the box is what is left
        var report=new StringBuilder();
        report.append(String.format(Locale.ROOT,"%-24s %8s %8s %8s | %-26s %8s %8s %8s %8s %8s%n","shape","blocks","edges","faces","mode","fps","med ms","p95 ms","heap MB","gc ms"));
        c.runOnClient(mc->{mc.options.enableVsync().set(false);mc.options.framerateLimit().set(260);mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);}); // vsync would round every frame to a multiple of 16.7 ms, and the AFK limit drops the game to 30 fps after a minute without input
        String settings=c.computeOnClient(mc->"framerateLimit="+mc.options.framerateLimit().get()+" vsync="+mc.options.enableVsync().get()+" windowActive="+mc.isWindowActive());
        report.append("options: ").append(settings).append("\n");
        // An outline shown once, such as the ghost of an edit waiting to be confirmed: worked out here it freezes the game, so it is worked out on another thread.
        var bigPoints=roughTerrain(100,10,7,5,9);
        OutlineShape[] made=new OutlineShape[2];long[] spent=new long[2];
        c.runOnClient(mc->{
            long t=System.nanoTime();made[0]=OutlineShape.of(bigPoints);spent[0]=System.nanoTime()-t;
            t=System.nanoTime();made[1]=OutlineShape.ofLater(bigPoints);spent[1]=System.nanoTime()-t;
        });
        long begun=System.nanoTime();
        c.waitFor(mc->made[1].ready(),1200);
        report.append(String.format(Locale.ROOT,"OutlineShape for %d blocks: of() takes %.1f ms on the client thread, ofLater() %.1f ms and is ready %.0f ms later, same outline: %s%n",bigPoints.size(),spent[0]/1e6,spent[1]/1e6,(System.nanoTime()-begun)/1e6,made[0].edgeCount()==made[1].edgeCount()));
        // The order is the shapes, then the two small ones again: if the small ones come back slow too, it is the machine or the game's frame cap, not the shape.
        var order=new ArrayList<>(shapes);order.add(new Shape("CONTROL cube 16^3",()->scatter(16,1,0)));order.add(new Shape("CONTROL sphere r=10",()->sphere(10,false)));
        String only=System.getenv("CRAFTIUM_PERF_ONLY"); // optional: only the shapes whose name contains one of these comma-separated words, to repeat a measurement
        if(only!=null&&!only.isBlank())order.removeIf(sh->!sh.name().startsWith("CONTROL")&&Arrays.stream(only.split(",")).noneMatch(sh.name()::contains));
        for(var shape:order){
            var points=new ArrayList<Point>();
            for(Point p:shape.make().get())points.add(new Point(p.x()+ox,p.y()+oy,p.z()+oz));
            String encoded=SelectionCodec.encode(points);
            var counted=SelectionOutline.of(points); // here, on the test thread, so it does not stall the client while frames are timed
            int[] counts={counted.edges().size(),counted.faces().size()};long[] update=new long[1];
            c.runOnClient(mc->{OutlineShape.maxEdges=OutlineShape.DEFAULT_MAX_EDGES;OutlineShape.maxFaces=OutlineShape.DEFAULT_MAX_FACES;});
            // Selecting it: the frames over the next 30 ticks show whether building the outline freezes the game. Then wait for it to be ready.
            STAMPS.clear();
            c.runOnClient(mc->{
                long t=System.nanoTime();
                SelectionOverlay.update(encoded);
                update[0]=System.nanoTime()-t;
            });
            c.waitTicks(30);
            double worst=0;{var s=new ArrayList<>(STAMPS);for(int i=1;i<s.size();i++)worst=Math.max(worst,(s.get(i)-s.get(i-1))/1e6);}
            c.waitFor(mc->!SelectionOverlay.building(),2400);
            // A shape this far past the caps would only be drawn in full by lifting them, which would stall the game, so it is only shown as it is.
            String[] modes=counts[0]>200_000?new String[]{"default caps"}:new String[]{"default caps","full outline, silhouette","full outline, all edges"};
            for(String mode:modes){
                boolean lift=!mode.startsWith("default");
                c.runOnClient(mc->{
                    OutlineShape.maxEdges=lift?Integer.MAX_VALUE:OutlineShape.DEFAULT_MAX_EDGES;OutlineShape.maxFaces=lift?Integer.MAX_VALUE:OutlineShape.DEFAULT_MAX_FACES;
                    ClientState.options.selectionStyle=mode.contains("all edges")?Options.SelectionStyle.ALL_EDGES:Options.SelectionStyle.SILHOUETTE;
                });
                c.waitTicks(SETTLE_TICKS);
                long gc0=gcMillis();
                STAMPS.clear();
                c.waitTicks(MEASURE_TICKS);
                long gc=gcMillis()-gc0;long heap=(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())>>20;
                var stamps=new ArrayList<>(STAMPS);
                var deltas=new ArrayList<Double>();
                for(int i=1;i<stamps.size();i++)deltas.add((stamps.get(i)-stamps.get(i-1))/1e6);
                Collections.sort(deltas);
                double median=deltas.isEmpty()?-1:deltas.get(deltas.size()/2),p95=deltas.isEmpty()?-1:deltas.get((int)(deltas.size()*.95));
                double seconds=stamps.size()<2?1:(stamps.get(stamps.size()-1)-stamps.get(0))/1e9;
                report.append(String.format(Locale.ROOT,"%-24s %8d %8d %8d | %-26s %8.1f %8.1f %8.1f %8d %8d%n",shape.name(),points.size(),counts[0],counts[1],mode,(stamps.size()-1)/seconds,median,p95,heap,gc));
            }
            if(counts[0]<=OutlineShape.DEFAULT_MAX_EDGES){
                // The camera moving, which is when the contour has to be worked out again every tick. The contour of a big shape is found off the game
                // thread, so this shows whether moving stalls the game; afterwards, whether a contour was found at all.
                c.runOnClient(mc->{OutlineShape.maxEdges=OutlineShape.DEFAULT_MAX_EDGES;OutlineShape.maxFaces=OutlineShape.DEFAULT_MAX_FACES;ClientState.options.selectionStyle=Options.SelectionStyle.SILHOUETTE;});
                double[] origin=c.computeOnClient(mc->new double[]{mc.player.getX(),mc.player.getY(),mc.player.getZ()});
                c.waitTicks(SETTLE_TICKS);
                STAMPS.clear();
                for(int t=0;t<MEASURE_TICKS;t++){int k=t;c.runOnClient(mc->mc.player.setPos(origin[0]+4*Math.sin(k/6.0),origin[1],origin[2]));c.waitTicks(1);}
                var moving=new ArrayList<>(STAMPS);
                c.runOnClient(mc->mc.player.setPos(origin[0],origin[1],origin[2]));
                c.waitTicks(40);
                boolean contour=c.computeOnClient(mc->SelectionOverlay.outline().hasContour());
                var d=new ArrayList<Double>();for(int i=1;i<moving.size();i++)d.add((moving.get(i)-moving.get(i-1))/1e6);
                Collections.sort(d);
                double seconds=moving.size()<2?1:(moving.get(moving.size()-1)-moving.get(0))/1e9;
                report.append(String.format(Locale.ROOT,"%-24s %8d %8d %8d | %-26s %8.1f %8.1f %8.1f   worst %.1f ms, true contour when still: %s%n",shape.name(),points.size(),counts[0],counts[1],"camera moving",(moving.size()-1)/seconds,d.isEmpty()?-1:d.get(d.size()/2),d.isEmpty()?-1:d.get((int)(d.size()*.95)),d.isEmpty()?-1:d.get(d.size()-1),contour?"yes":"no (creases)"));
            }
            if(counts[0]<=OutlineShape.DEFAULT_MAX_EDGES){
                // The camera in the middle of the shape: the walls are seen from behind, the screen is washed, and the silhouette has nothing to outline.
                int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
                for(Point p:points){minX=Math.min(minX,p.x());minY=Math.min(minY,p.y());minZ=Math.min(minZ,p.z());maxX=Math.max(maxX,p.x());maxY=Math.max(maxY,p.y());maxZ=Math.max(maxZ,p.z());}
                double ix=(minX+maxX+1)/2.0,iy=(minY+maxY+1)/2.0-1.62,iz=(minZ+maxZ+1)/2.0; // the feet, so the eyes are at the middle
                double[] home=c.computeOnClient(mc->new double[]{mc.player.getX(),mc.player.getY(),mc.player.getZ()});
                c.waitTicks(SETTLE_TICKS);
                STAMPS.clear();
                for(int t=0;t<MEASURE_TICKS;t++){c.runOnClient(mc->mc.player.setPos(ix,iy,iz));c.waitTicks(1);}
                var inside=new ArrayList<>(STAMPS);
                String note=c.computeOnClient(mc->String.valueOf(SelectionOverlay.outline().fallback()));
                var d=new ArrayList<Double>();for(int i=1;i<inside.size();i++)d.add((inside.get(i)-inside.get(i-1))/1e6);
                Collections.sort(d);
                double seconds=inside.size()<2?1:(inside.get(inside.size()-1)-inside.get(0))/1e9;
                report.append(String.format(Locale.ROOT,"%-24s %8d %8d %8d | %-26s %8.1f %8.1f %8.1f   worst %.1f ms, shown as: %s%n",shape.name(),points.size(),counts[0],counts[1],"camera inside",(inside.size()-1)/seconds,d.isEmpty()?-1:d.get(d.size()/2),d.isEmpty()?-1:d.get((int)(d.size()*.95)),d.isEmpty()?-1:d.get(d.size()-1),note));
                c.runOnClient(mc->mc.player.setPos(home[0],home[1],home[2]));
                c.waitTicks(20);
                String outside=c.computeOnClient(mc->String.valueOf(SelectionOverlay.outline().fallback()));
                report.append(String.format(Locale.ROOT,"%-24s back outside, shown as: %s%n",shape.name(),outside));
            }
            report.append(String.format(Locale.ROOT,"%-24s update() on the client thread: %.1f ms, worst frame in the 30 ticks after selecting: %.1f ms%n",shape.name(),update[0]/1e6,worst));
        }
        c.runOnClient(mc->{OutlineShape.maxEdges=OutlineShape.DEFAULT_MAX_EDGES;OutlineShape.maxFaces=OutlineShape.DEFAULT_MAX_FACES;ClientState.options.selectionStyle=Options.SelectionStyle.SILHOUETTE;});
        System.out.println("CRAFTIUM_SELECTION_DRAW_COST\n"+report);
        try{java.nio.file.Files.writeString(java.nio.file.Path.of("selection-draw-cost.txt"),report.toString());}catch(java.io.IOException e){System.out.println("could not write report: "+e);}
    }
}
