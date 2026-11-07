package dev.craftium.core;

import java.util.*;

/** Default facts from Axiom's published keybind table; no platform dependencies. */
public final class EditorControls {
    public static final int SHIFT=1,CONTROL=2,ALT=4;
    public record Binding(boolean mouse,int key,int modifiers){
        public boolean matches(boolean mouse,int key,int modifiers){return this.key>=0&&this.mouse==mouse&&this.key==key&&this.modifiers==(modifiers&7);}
        public static Binding key(int key,int modifiers){return new Binding(false,key,modifiers);}
        public static Binding mouse(int button,int modifiers){return new Binding(true,button,modifiers);}
    }
    public enum Action {
        ROTATE_CAMERA("Rotate camera",Binding.mouse(0,0)),PICK_BLOCK("Pick block",Binding.mouse(2,0)),USE_TOOL("Use tool",Binding.mouse(1,0)),
        ARCBALL("Arcball camera",Binding.mouse(0,CONTROL)),PAN_CAMERA("Pan camera",Binding.mouse(1,CONTROL)),ADJUST_RADIUS("Adjust tool radius",Binding.mouse(2,CONTROL)),
        CROSSHAIR("Crosshair",Binding.mouse(1,0)),ADJUST_SPEED("Adjust flight speed"),
        CONFIRM("Confirm / clear selection",257,0),UNDO("Undo",'Z',CONTROL),REDO("Redo",'Y',CONTROL),COPY("Copy",'C',CONTROL),COPY_AIR("Copy with air"),
        PASTE("Paste",'V',CONTROL),PASTE_IN_PLACE("Paste in place",'V',CONTROL|SHIFT),DELETE("Delete",261,0),CUT("Cut",'X',CONTROL),DUPLICATE("Duplicate",'J',CONTROL),
        SWAP_BLOCKS("Swap foreground / background block",'X',0),EXTRUDE_POINT("Extrude point",'E',0),SAVE_BLUEPRINT("Save blueprint",'P',CONTROL),
        SHOW_SELECTION("Show selections"),CYCLE_SELECTION_STYLE("Cycle selection display",';',0),SHOW_BIOMES("Show biomes",'B',CONTROL),SHOW_ANNOTATIONS("Show annotations"),
        SHOW_DISPLAYS("Show display gizmos"),SHOW_MARKERS("Show marker gizmos"),SHOW_COLLISION("Show collision mesh"),SHOW_LIGHTS("Show light blocks"),SHOW_VOID("Show structure void"),
        QUICK_FILL("Quick fill",'F',CONTROL),QUICK_REPLACE("Quick replace",'R',CONTROL),
        ROTATE_PLACEMENT("Rotate placement",'R',CONTROL),FLIP_PLACEMENT("Flip placement",'F',CONTROL),TILT_PLACEMENT("Tilt placement",'T',CONTROL),PASTE_SELECT("Paste and select"),LAST_TOOL("Swap to last tool"),
        NUDGE_FORWARD("Nudge forwards",265,0),NUDGE_BACK("Nudge backwards",264,0),NUDGE_RIGHT("Nudge right",262,0),NUDGE_LEFT("Nudge left",263,0),NUDGE_UP("Nudge +Y",266,0),NUDGE_DOWN("Nudge -Y",267,0),
        INGAME_COPY("Ingame: copy",'C',CONTROL),INGAME_DELETE("Ingame: delete",261,0),
        INSPECTOR("Craftium: inspector"),HELP("Craftium: controls help"),PANELS("Craftium: side panels"),
        UNDO_ALT("Undo (second shortcut)",'Z',CONTROL|SHIFT),DESELECT("Deselect",'D',ALT),
        TOGGLE_MOVE("Toggle Move tool (second key)",'1',0),TOGGLE_ROTATE("Toggle Rotate tool (second key)",'2',0),TOGGLE_SCALE("Toggle Scale tool (second key)",'3',0),TOGGLE_PIVOT("Toggle Pivot tool (second key)",'4',0),
        GIZMO_MOVE("Craftium: move gizmo"),GIZMO_ROTATE("Craftium: rotate gizmo"),GIZMO_SCALE("Craftium: scale gizmo"),LOCAL_AXES("Craftium: local gizmo axes");
        public boolean mouseOnly(){return switch(this){case ROTATE_CAMERA,PICK_BLOCK,USE_TOOL,ARCBALL,PAN_CAMERA,CROSSHAIR,ADJUST_RADIUS->true;default->false;};}
        public boolean keyboardOnly(){return this==INGAME_COPY||this==INGAME_DELETE;}
        public final String label;public final Binding defaults;
        Action(String label){this(label,-1,0);}Action(String label,int key,int modifiers){this(label,Binding.key(key,modifiers));}
        Action(String label,Binding defaults){this.label=label;this.defaults=defaults;}
    }
    private EditorControls(){}
    public static int toolDefault(Tool tool){return ToolCatalog.of(tool).defaultKey();}
    /** Camera-relative horizontal nudges, independent of the gizmo's selected axis. */
    public static Point nudge(Action action,double yaw){
        int quadrant=Math.floorMod((int)Math.floor(yaw/90+.5),4);
        Point forward=switch(quadrant){case 0->new Point(0,0,1);case 1->new Point(-1,0,0);case 2->new Point(0,0,-1);default->new Point(1,0,0);};
        return switch(action){case NUDGE_FORWARD->forward;case NUDGE_BACK->new Point(-forward.x(),0,-forward.z());case NUDGE_RIGHT->new Point(-forward.z(),0,forward.x());case NUDGE_LEFT->new Point(forward.z(),0,-forward.x());case NUDGE_UP->new Point(0,1,0);case NUDGE_DOWN->new Point(0,-1,0);default->throw new IllegalArgumentException("Not a nudge action");};
    }
}
