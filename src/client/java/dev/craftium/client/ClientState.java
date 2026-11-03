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
    /** The corners of the box being selected, which stay as handles the player can drag to resize it. At most two. */
    public static final List<Point> boxCorners=new ArrayList<>();
    /** When above zero, the editor shows a small popup asking whether this many selected blocks was meant. */
    public static int largeSelection;
    /** Whether the selection outline settings (colour and width) are showing. */
    public static boolean outlineSettings;
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
    public static Point rulerStart,rulerEnd;
    public static final List<Point> rulerPoints=new ArrayList<>();
    public static List<BlueprintInfo> blueprints=List.of();
    public static int libraryVersion;
    public static final List<String> palette=new ArrayList<>(List.of("minecraft:stone","minecraft:andesite","minecraft:deepslate","minecraft:grass_block","minecraft:dirt","minecraft:sand","minecraft:oak_planks","minecraft:glass","minecraft:air"));
    private ClientState() {}
    public static boolean symmetry(){return options.symmetryX||options.symmetryY||options.symmetryZ||options.symmetryRotations>1;}
    public static void load() {
        if(!Files.exists(CONFIG))return;
        try {Options saved=Packets.JSON.fromJson(Files.readString(CONFIG),Options.class);saved.validate();options=saved;}
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
        options.block=block;palette.remove(block);palette.addFirst(block);while(palette.size()>18)palette.removeLast();
    }
}
