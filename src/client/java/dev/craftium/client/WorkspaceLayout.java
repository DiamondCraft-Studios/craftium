package dev.craftium.client;

import dev.craftium.net.Packets;
import net.fabricmc.loader.api.FabricLoader;
import dev.craftium.client.compat.Gfx;
import java.nio.file.*;
import java.util.*;

final class WorkspaceLayout {
    static final class Rect {int x,y,w,h;boolean visible=true,folded;Rect(int x,int y,int w,int h){this.x=x;this.y=y;this.w=w;this.h=h;}}
    static final Map<String,Rect> base=new LinkedHashMap<>(),windows=new LinkedHashMap<>();
    /** Where each panel opens when it has not been moved: its designed place in {@link #base}, lowered by its {@link #shift}. */
    private static final Map<String,Rect> defaults=new HashMap<>();
    private static final Map<String,Integer> shifts=new HashMap<>();
    /** A panel's contents are laid out against the place it was designed at; this opens the window {@code dy} lower than that without moving the contents. */
    static void shift(String id,int dy){shifts.put(id,dy);}
    private static final Path FILE=FabricLoader.getInstance().getConfigDir().resolve("craftium/layout.json");
    private static boolean loaded;
    /** The size of the editor's canvas that the saved places were made for, 0 when unknown. */
    private static int canvasW,canvasH;
    private WorkspaceLayout(){}
    static String key(String title){return switch(title){case "Tools"->"tools";case "Tool options","Builder controls"->"options";case "Target info"->"target";case "Clipboard"->"clipboard";case "Block palette"->"palette";case "History"->"history";case "Tool masks"->"masks";case "Rulers"->"rulers";case "Magic select"->"magic";case "Hierarchy"->"hierarchy";default->"";};}
    private static void load(){if(!loaded){loaded=true;try{if(Files.exists(FILE))importJson(Files.readString(FILE));}catch(Exception e){ClientState.notify("Could not load workspace layout");}}}
    /**
     * Called when the editor is laid out on a canvas of {@code w} by {@code h} units, which changes with the window and the interface size.
     * A panel in the right half keeps its distance from the right edge, and one in the lower half its distance from the bottom, so panels
     * that sat at an edge are still there. A panel still at its default place is left alone: it is placed again from the new size.
     * {@code legacy} is the canvas of layouts saved before the size was recorded.
     */
    static void fit(int w,int h,int[] legacy){
        load();
        int oldW=canvasW>0?canvasW:legacy[0],oldH=canvasH>0?canvasH:legacy[1];
        if(oldW!=w||oldH!=h){
            for(var e:windows.entrySet()){
                var r=e.getValue();var b=defaults.get(e.getKey());
                if(b!=null&&b.x==r.x&&b.y==r.y&&b.w==r.w&&b.h==r.h)continue;
                if(r.x+r.w/2.0>oldW/2.0)r.x+=w-oldW;
                if(r.y+r.h/2.0>oldH/2.0)r.y+=h-oldH;
                r.x=Math.clamp(r.x,0,Math.max(0,w-50));r.y=Math.clamp(r.y,24,Math.max(24,h-40));
            }
        }
        if(canvasW!=w||canvasH!=h){canvasW=w;canvasH=h;if(!windows.isEmpty())save();}
    }
    static void prepare(String id,int x,int y,int w,int h){if(id.isBlank())return;load();int dy=shifts.getOrDefault(id,0);var previous=defaults.get(id);var window=windows.get(id);if(previous!=null&&window!=null&&previous.x==window.x&&previous.y==window.y&&previous.w==window.w&&previous.h==window.h){window.x=x;window.y=y+dy;window.w=w;window.h=h;}defaults.put(id,new Rect(x,y+dy,w,h));base.put(id,new Rect(x,y,w,h));windows.computeIfAbsent(id,key->fresh(id,x,y+dy,w,h));}
    /** A panel as first laid out: all shown but History, Clipboard and Magic select, which stay out of the way until they are opened from the Window menu. */
    private static Rect fresh(String id,int x,int y,int w,int h){var r=new Rect(x,y,w,h);r.visible=!id.equals("history")&&!id.equals("magic")&&!id.equals("clipboard")&&!id.equals("rulers");return r;}
    static void snap(String id,int width,int height){var window=windows.get(id);if(window==null)return;if(window.x<12)window.x=0;if(window.y<37)window.y=27;if(Math.abs(window.x+window.w-width)<12)window.x=width-window.w;if(Math.abs(window.y+window.h-height+25)<12)window.y=height-25-window.h;for(var e:windows.entrySet())if(!e.getKey().equals(id)&&e.getValue().visible){var other=e.getValue();if(Math.abs(window.x-other.x-other.w)<10)window.x=other.x+other.w+6;if(Math.abs(window.y-other.y-other.h)<10)window.y=other.y+other.h+6;}window.x=Math.clamp(window.x,0,Math.max(0,width-50));window.y=Math.clamp(window.y,27,Math.max(27,height-40));}
    /** The height of a panel folded up to its title bar. */
    static final int TITLE=19;
    static boolean folded(String id){var r=windows.get(id);return r!=null&&r.folded;}
    static void fold(String id){var r=windows.get(id);if(r!=null){r.folded=!r.folded;save();}}
    /** Whether a point of the panel's own (unmoved) space lies in the part a folded panel hides. */
    static boolean hiddenByFold(String id,double y){var r=windows.get(id);var b=base.get(id);return r!=null&&b!=null&&r.folded&&y>=b.y+TITLE;}
    static boolean visible(String id){var r=windows.get(id);return r==null||r.visible;}
    /** Moves drawing to where the panel is. Panels are never stretched: contents lay themselves out in the panel's real size, see {@link #width} and {@link #height}. */
    static void apply(Gfx g,String id){var a=base.get(id);var b=windows.get(id);if(a==null||b==null)return;g.pose().translate(b.x-a.x,b.y-a.y);}
    /** The panel's real width, or {@code fallback} before it has been drawn once. */
    static int width(String id,int fallback){var r=windows.get(id);return r==null?fallback:r.w;}
    static int height(String id,int fallback){var r=windows.get(id);return r==null?fallback:r.h;}
    static double[] inverse(String id,double x,double y){var a=base.get(id);var b=windows.get(id);if(a==null||b==null)return new double[]{x,y};return new double[]{x-b.x+a.x,y-b.y+a.y};}
    static String hit(double x,double y,boolean header){var keys=new ArrayList<>(windows.keySet());Collections.reverse(keys);for(String key:keys){var r=windows.get(key);if(r.visible&&x>=r.x&&x<r.x+r.w&&y>=r.y&&y<r.y+(header?18:r.folded?TITLE:r.h))return key;}return "";}
    static void toggle(String id){var r=windows.get(id);if(r!=null)r.visible=!r.visible;save();}
    static void reset(){windows.clear();defaults.forEach((k,r)->windows.put(k,fresh(k,r.x,r.y,r.w,r.h)));save();}
    static void save(){try{Files.createDirectories(FILE.getParent());Files.writeString(FILE,json());}catch(Exception e){ClientState.notify("Could not save workspace layout");}}
    /** Bumped when the default places of panels change, so a saved layout does not hold the old ones: masks, palette and history at 2; the right column (Hierarchy, palette and masks), Target info at the bottom left and Tool options above it, and Clipboard off by default at 3, Rulers off by default at 4. */
    private static final int VERSION=6;
    static String json(){var tree=Packets.JSON.toJsonTree(windows).getAsJsonObject();tree.addProperty("_v",VERSION);tree.addProperty("_cw",canvasW);tree.addProperty("_ch",canvasH);return Packets.JSON.toJson(tree);}
    static void importJson(String json){var data=Packets.JSON.fromJson(json,com.google.gson.JsonObject.class);var next=new LinkedHashMap<String,Rect>();int saved=data.has("_v")?data.get("_v").getAsInt():0;canvasW=data.has("_cw")?data.get("_cw").getAsInt():0;canvasH=data.has("_ch")?data.get("_ch").getAsInt():0;for(var e:data.entrySet()){
        if(saved<2&&Set.of("masks","palette","history").contains(e.getKey()))continue; // laid out again at their new default places
        if(saved<3&&Set.of("target","clipboard","options","palette","masks").contains(e.getKey()))continue;
        if(saved<6&&Set.of("hierarchy","palette","masks","history","clipboard","rulers").contains(e.getKey()))continue; // the right-hand column changed width once more
        if(saved<5&&Set.of("tools","options","hierarchy","palette","masks","history","clipboard","rulers").contains(e.getKey()))continue; // the Tools window is taller, Tool options starts below it, and the right-hand column is wider
        if(saved<4&&e.getKey().equals("rulers"))continue; // the Rulers window starts out of the way now
        if(!Set.of("tools","options","target","clipboard","palette","history","masks","rulers","hierarchy").contains(e.getKey()))continue;
        var r=Packets.JSON.fromJson(e.getValue(),Rect.class);r.x=Math.clamp(r.x,0,4000);r.y=Math.clamp(r.y,24,3000);r.w=Math.clamp(r.w,110,1600);r.h=Math.clamp(r.h,60,1600);next.put(e.getKey(),r);
    }windows.clear();windows.putAll(next);}
    static String fieldPanel(String field){return switch(field){case "block"->"palette";case "mask","minY","maxY"->"masks";case "rename"->"hierarchy";case "offsetX","offsetY","offsetZ","copies"->"";default->"options";};}
}
