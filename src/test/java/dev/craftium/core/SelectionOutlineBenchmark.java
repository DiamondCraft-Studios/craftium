package dev.craftium.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;

/**
 * Measures what building a selection outline costs and how big it comes out, so the caps in the client's OutlineShape
 * (8192 edges, 4096 faces) and ScreenSilhouette's budget can be judged against real shapes. Not a pass/fail test: it only runs
 * with {@code CRAFTIUM_BENCH=1} and writes its table to {@code build/bench/selection-outline.txt}.
 */
@EnabledIfEnvironmentVariable(named="CRAFTIUM_BENCH",matches=".+")
class SelectionOutlineBenchmark {
    /** The caps in the client's OutlineShape; past them the whole selection is drawn as one box. */
    private static final int MAX_EDGES=8192,MAX_FACES=4096;
    private static final int WARMUP=3,RUNS=7;

    private record Shape(String name,Supplier<Set<Point>> make){}

    private static Set<Point> sphere(int r,boolean hollow){
        var s=new HashSet<Point>();
        for(int x=-r;x<=r;x++)for(int y=-r;y<=r;y++)for(int z=-r;z<=r;z++){
            int d=x*x+y*y+z*z;
            if(d<=r*r&&(!hollow||d>(r-2)*(r-2)))s.add(new Point(x,y,z));
        }
        return s;
    }
    private static Set<Point> cube(int n){
        var s=new HashSet<Point>();
        for(int x=0;x<n;x++)for(int y=0;y<n;y++)for(int z=0;z<n;z++)s.add(new Point(x,y,z));
        return s;
    }
    /** A heightfield with rolling hills, like selected terrain. */
    private static Set<Point> terrain(int side,int depth,long seed){
        var rnd=new Random(seed);var s=new HashSet<Point>();
        for(int x=0;x<side;x++)for(int z=0;z<side;z++){
            int h=depth+(int)(6*Math.sin(x/9.0)+6*Math.cos(z/7.0)+rnd.nextInt(3));
            for(int y=0;y<h;y++)s.add(new Point(x,y,z));
        }
        return s;
    }
    /** Random blocks in a box, filled to {@code density}: the worst case for an outline, with almost no flat faces to merge. */
    private static Set<Point> scatter(int side,double density,long seed){
        var rnd=new Random(seed);var s=new HashSet<Point>();
        for(int x=0;x<side;x++)for(int y=0;y<side;y++)for(int z=0;z<side;z++)if(rnd.nextDouble()<density)s.add(new Point(x,y,z));
        return s;
    }
    /** Solid blob with random holes punched through it, like a worn building. */
    private static Set<Point> swiss(int n,long seed){
        var s=cube(n);var rnd=new Random(seed);
        for(int i=0;i<n*3;i++){
            int cx=rnd.nextInt(n),cy=rnd.nextInt(n),cz=rnd.nextInt(n),r=2+rnd.nextInt(3);
            for(int x=-r;x<=r;x++)for(int y=-r;y<=r;y++)for(int z=-r;z<=r;z++)if(x*x+y*y+z*z<=r*r)s.remove(new Point(cx+x,cy+y,cz+z));
        }
        return s;
    }
    /** A voxelised organic shape: a few overlapping spheres with a noisy surface. */
    private static Set<Point> blob(int r,long seed){
        var rnd=new Random(seed);var s=new HashSet<Point>();
        int[][] c={{0,0,0},{r/2,r/3,0},{-r/2,0,r/3},{0,r/2,-r/3}};
        for(int[] k:c)for(int x=-r;x<=r;x++)for(int y=-r;y<=r;y++)for(int z=-r;z<=r;z++){
            double d=Math.sqrt(x*x+y*y+z*z)+rnd.nextDouble()*1.5;
            if(d<=r*.7)s.add(new Point(k[0]+x,k[1]+y,k[2]+z));
        }
        return s;
    }

    private static List<Shape> shapes(){
        return List.of(
            new Shape("cube 10^3",()->cube(10)),
            new Shape("cube 46^3",()->cube(46)),
            new Shape("sphere r=10",()->sphere(10,false)),
            new Shape("sphere r=31",()->sphere(31,false)),
            new Shape("hollow sphere r=40",()->sphere(40,true)),
            new Shape("terrain 32x32",()->terrain(32,8,1)),
            new Shape("terrain 100x100",()->terrain(100,8,2)),
            new Shape("blob r=40",()->blob(40,3)),
            new Shape("swiss cube 48^3",()->swiss(48,4)),
            new Shape("scatter 22^3 @50%",()->scatter(22,.5,5)),
            new Shape("scatter 50^3 @80%",()->scatter(50,.8,6)),
            new Shape("scatter 100^3 @10%",()->scatter(100,.1,7))
        );
    }

    private static double ms(long nanos){return nanos/1e6;}

    @Test void run() throws Exception{
        var out=new StringBuilder();
        out.append(String.format("%-22s %8s %9s %9s %9s %8s | %9s %9s %12s | %s%n",
            "shape","blocks","build ms","edges","faces","creases","contour ms","contours","orig ms","verdict"));
        for(var shape:shapes()){
            Set<Point> blocks=shape.make().get();
            if(blocks.size()>Selection.LIMIT){out.append(String.format("%-22s %8d  over the %d block limit, skipped%n",shape.name(),blocks.size(),Selection.LIMIT));continue;}
            SelectionOutline.Result result=null;
            for(int i=0;i<WARMUP;i++)result=SelectionOutline.of(blocks);
            long[] times=new long[RUNS];
            for(int i=0;i<RUNS;i++){long t=System.nanoTime();result=SelectionOutline.of(blocks);times[i]=System.nanoTime()-t;}
            Arrays.sort(times);double median=ms(times[RUNS/2]);

            // The contour as seen from a camera a little outside the shape's bounding box.
            int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
            for(Point p:blocks){minX=Math.min(minX,p.x());minY=Math.min(minY,p.y());minZ=Math.min(minZ,p.z());maxX=Math.max(maxX,p.x());maxY=Math.max(maxY,p.y());maxZ=Math.max(maxZ,p.z());}
            double span=Math.max(maxX-minX,Math.max(maxY-minY,maxZ-minZ))+1;
            double ex=(minX+maxX)/2.0+span*1.3,ey=(minY+maxY)/2.0+span*.8,ez=(minZ+maxZ)/2.0+span*1.1;
            List<ScreenSilhouette.Line> contour=null;
            for(int i=0;i<WARMUP;i++)contour=ScreenSilhouette.of(result,ex,ey,ez);
            long[] ct=new long[RUNS];
            for(int i=0;i<RUNS;i++){long t=System.nanoTime();contour=ScreenSilhouette.of(result,ex,ey,ez);ct[i]=System.nanoTime()-t;}
            Arrays.sort(ct);
            // The same contour by the algorithm as it was before the grid and the sweep, which gave up (null) on a big shape.
            long[] ot=new long[RUNS];List<ReferenceSilhouette.Line> original=null;
            for(int i=0;i<RUNS;i++){long t=System.nanoTime();original=ReferenceSilhouette.of(result,ex,ey,ez);ot[i]=System.nanoTime()-t;}
            Arrays.sort(ot);
            String originalMs=original==null?"gave up":String.format("%.1f",ms(ot[RUNS/2]));

            String verdict;
            if(result.edges().size()>MAX_EDGES)verdict="BOX (edges>"+MAX_EDGES+")";
            else if(result.faces().size()>MAX_FACES)verdict="no fill (faces>"+MAX_FACES+")";
            else verdict="ok";
            if(contour==null)verdict+=", contour null";
            out.append(String.format("%-22s %8d %9.1f %9d %9d %8d | %9.1f %9s %12s | %s%n",
                shape.name(),blocks.size(),median,result.edges().size(),result.faces().size(),result.creases().size(),
                ms(ct[RUNS/2]),contour==null?"null":String.valueOf(contour.size()),originalMs,verdict));
        }
        out.append("\ncontour ms: the contour as the game finds it now; orig ms: the same contour by the original algorithm (it gave up past 3,000,000 segment-and-face pairs).\n");
        out.append("Heap: ").append(Runtime.getRuntime().maxMemory()/1048576).append(" MB max. Build ms is the median of ").append(RUNS).append(" runs after ").append(WARMUP).append(" warm-ups.\n");
        Path dir=Path.of(System.getProperty("user.dir"),"build","bench");Files.createDirectories(dir);
        Files.writeString(dir.resolve("selection-outline.txt"),out.toString());
        System.out.println(out);
    }

    /** What the client does on its own thread when a selection arrives, apart from the outline: decode it and make the lists and sets that point lookups use. */
    @Test void decodeCost() throws Exception{
        var out=new StringBuilder(String.format("%-22s %8s %10s %12s %12s%n","shape","blocks","bytes","decode ms","copies ms"));
        for(var shape:List.of(new Shape("sphere r=31",()->sphere(31,false)),new Shape("terrain 100x100",()->terrain(100,8,2)),new Shape("scatter 50^3 @80%",()->scatter(50,.8,5)))){
            var points=new ArrayList<>(shape.make().get());
            String encoded=SelectionCodec.encode(points);
            Set<Point> decoded=null;
            for(int i=0;i<WARMUP;i++)decoded=SelectionCodec.decode(encoded);
            long[] d=new long[RUNS],c=new long[RUNS];
            for(int i=0;i<RUNS;i++){
                long t=System.nanoTime();decoded=SelectionCodec.decode(encoded);d[i]=System.nanoTime()-t;
                t=System.nanoTime();var list=List.copyOf(decoded);var set=new HashSet<>(list);c[i]=System.nanoTime()-t;
                if(set.size()!=list.size())throw new AssertionError();
            }
            Arrays.sort(d);Arrays.sort(c);
            out.append(String.format("%-22s %8d %10d %12.1f %12.1f%n",shape.name(),points.size(),encoded.length(),ms(d[RUNS/2]),ms(c[RUNS/2])));
        }
        Path dir=Path.of(System.getProperty("user.dir"),"build","bench");Files.createDirectories(dir);
        Files.writeString(dir.resolve("selection-decode.txt"),out.toString());
        System.out.println(out);
    }

}
