package dev.craftium.client;

import dev.craftium.Craftium;
import dev.craftium.core.*;
import dev.craftium.net.Packets;
import net.fabricmc.loader.api.FabricLoader;
import com.google.gson.GsonBuilder;
import java.nio.file.*;
import java.util.*;

public final class ClientState {
    private static final Path CONFIG=FabricLoader.getInstance().getConfigDir().resolve("craftium.json");
    public static Options options=new Options();
    public static Packets.Status status=new Packets.Status("Ready · Right-click to use your tool",false,false,0,new Point(0,0,0),new Point(0,0,0),0,List.of(),0,0);
    public static String notice="";
    public static long noticeUntil;
    public static long strokeId;
    public static boolean duplicatePending;
    public static Point pendingPlacementOrigin;
    /** Set while a selection action is in flight, so its result can be checked for being suspiciously large. */
    /** An edit the server has worked out and is holding until it is confirmed with Enter. */
    public static GhostBlocks pending=GhostBlocks.EMPTY;
    public static boolean hasPending(){return pending.total()>0;}
    /** The build an AI app is holding as a preview, kept apart from the player's own: drawn in violet, and applied or discarded from the AI menu. */
    public static GhostBlocks aiPending=GhostBlocks.EMPTY;
    public static boolean hasAiPending(){return aiPending.total()>0;}
    /** The corners of the box being selected, which stay as handles the player can drag to resize it. At most two. */
    public static final List<Point> boxCorners=new ArrayList<>();
    /** When above zero, the editor shows a small popup asking whether this many selected blocks was meant. */
    public static int largeSelection;
    /** The selection the server just sent while the popup is waiting, kept aside so it is not built or drawn until confirmed. */
    public static String heldSelection="";
    public static boolean selectionPending;
    /** When the selection action in flight was sent, or 0. One that is still out after {@link #SELECTING_DELAY} milliseconds is a large one. */
    public static long selectingSince;
    public static final long SELECTING_DELAY=250;
    public static boolean selecting(){long since=selectingSince;return since!=0&&System.currentTimeMillis()-since>SELECTING_DELAY&&System.currentTimeMillis()-since<120_000;}
    public static int selectionSizeBefore;
    /** A selection this many blocks bigger than before is confirmed with the player first. */
    public static final int LARGE_SELECTION=1024;
    /** The measurements the Ruler tool has made, each with a knob at both ends. */
    public static final List<Ruler> rulers=new ArrayList<>();
    /** The ruler picked in the Rulers panel or in the world, or null. */
    public static Ruler selectedRuler;
    /** The picked ruler if it is still there, else null. */
    public static Ruler selectedRuler(){if(selectedRuler!=null&&!rulers.contains(selectedRuler))selectedRuler=null;return selectedRuler;}
    /** The ends of every ruler, one after the other, as one list: the tool's handles work on it, and adding a point places the next end. */
    public static final List<Point> rulerPoints=new java.util.AbstractList<>(){
        @Override public int size(){int count=0;for(var r:rulers)count+=r.complete()?2:1;return count;}
        @Override public Point get(int index){
            for(var r:rulers){if(index==0)return r.a;index--;if(r.complete()){if(index==0)return r.b;index--;}}
            throw new IndexOutOfBoundsException(index);
        }
        @Override public Point set(int index,Point point){
            for(var r:rulers){
                if(index==0){var old=r.a;r.a=point;return old;}index--;
                if(r.complete()){if(index==0){var old=r.b;r.b=point;return old;}index--;}
            }
            throw new IndexOutOfBoundsException(index);
        }
        /** Only the end of the list: the second end of the ruler waiting for one, or the first end of a new ruler. */
        @Override public void add(int index,Point point){
            if(index!=size())throw new UnsupportedOperationException("Ruler ends are added at the end");
            if(!rulers.isEmpty()&&!rulers.getLast().complete())rulers.getLast().b=point;
            else if(rulers.size()<256){var ruler=new Ruler(point);ruler.mode=options.rulerMode;rulers.add(ruler);} // a new ruler starts as the mode chosen for new ones
            else throw new IllegalArgumentException("Ruler limit: 256 rulers");
        }
        /** Taking an end away takes its whole ruler. */
        @Override public Point remove(int index){
            int at=index;
            for(var r:rulers){
                int ends=r.complete()?2:1;
                if(at<ends){var old=get(index);rulers.remove(r);return old;}
                at-=ends;
            }
            throw new IndexOutOfBoundsException(index);
        }
        @Override public void clear(){rulers.clear();}
    };
    public static List<BlueprintInfo> blueprints=List.of();
    public static int libraryVersion;
    public static final List<String> palette=new ArrayList<>(List.of("minecraft:stone","minecraft:andesite","minecraft:deepslate","minecraft:grass_block","minecraft:dirt","minecraft:sand","minecraft:oak_planks","minecraft:glass","minecraft:air"));
    private ClientState() {}
    public static boolean symmetry(){return options.symmetryX||options.symmetryY||options.symmetryZ||options.symmetryRotations>1;}
    public static void load() {
        if(!Files.exists(CONFIG))return;
        try {Options saved=Packets.JSON.fromJson(Files.readString(CONFIG),Options.class);saved.validate();saved.invert=false;options=saved;}
        catch(Exception e){Craftium.LOG.warn("Could not read craftium.json; using defaults",e);}
    }
    public static void save() {
        try {
            Files.createDirectories(CONFIG.getParent());Path temp=CONFIG.resolveSibling("craftium.json.tmp");
            Files.writeString(temp,new GsonBuilder().setPrettyPrinting().create().toJson(options));
            Files.move(temp,CONFIG,StandardCopyOption.REPLACE_EXISTING);
        } catch(Exception e){Craftium.LOG.warn("Could not save Craftium settings",e);}
    }
    public static void notify(String message) {notice=message;noticeUntil=System.currentTimeMillis()+4500;}
    public static void pick(String block) {
        // A block already in the palette keeps its cell; only a new one is added, at the front.
        options.block=block;if(!palette.contains(block)){palette.addFirst(block);while(palette.size()>18)palette.removeLast();}
    }
}
