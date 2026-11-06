package dev.craftium.client;

import dev.craftium.core.EditorControls.Action;
import dev.craftium.core.Options;
import dev.craftium.core.Tool;
import java.util.*;

/** The shortcuts the bottom bar shows: the ones that do something with the tool that is selected, in the order they matter. */
final class ToolHints {
    private ToolHints(){}

    /**
     * The hints for {@code tool}, or for no tool chosen when it is null, most useful first so a narrow bar can drop the
     * last ones. {@code gizmo} is whether a transform gizmo is up (the wheel nudges), {@code shape} whether the Shape tool has one down.
     */
    static List<String> of(Tool tool,boolean gizmo,boolean shape){
        var hints=new ArrayList<String>();
        hints.add(EditorBindings.label(Action.ROTATE_CAMERA)+" Drag: Camera");
        if(tool!=null)hints.add(EditorBindings.label(Action.USE_TOOL)+": "+use(tool));
        hints.add(EditorBindings.label(Action.PICK_BLOCK)+": Pick block");
        if(tool!=null&&confirms(tool))hints.add(EditorBindings.label(Action.CONFIRM)+": Apply");
        hints.add(wheel(tool,gizmo,shape));
        if(tool!=null&&brush(tool))hints.add(EditorBindings.label(Action.ADJUST_RADIUS)+"+Wheel / [ ]: Radius");
        hints.add("WASD / Space Shift: Fly");
        return hints;
    }

    /** The hints while a gizmo handle is held: what letting go does, and the keys that change how the drag snaps. */
    static List<String> held(Options.GizmoMode mode){
        var hints=new ArrayList<String>();
        hints.add(EditorBindings.label(Action.USE_TOOL)+" Release: Apply");
        if(mode==Options.GizmoMode.ROTATE){hints.add("Shift: 1° steps");hints.add("Ctrl: Coarse steps");}
        else if(mode==Options.GizmoMode.SCALE){hints.add("Shift: Fine");hints.add("Ctrl: Scale evenly");hints.add("Alt: From centre");}
        else hints.add("Alt: From centre");
        hints.add("WASD / Space Shift: Fly");
        return hints;
    }

    /** The line to draw, as many hints as fit in {@code room} pixels. {@code held} is the mode of the gizmo handle being dragged, or null when none is. */
    static String line(Tool tool,boolean gizmo,boolean shape,Options.GizmoMode held,java.util.function.ToIntFunction<String> width,int room){
        var line=new StringBuilder();
        for(String hint:held!=null?held(held):of(tool,gizmo,shape)){
            String next=line.isEmpty()?hint:line+"     "+hint;
            if(!line.isEmpty()&&width.applyAsInt(next)>room)break;
            line=new StringBuilder(next);
        }
        return line.toString();
    }

    private static String wheel(Tool tool,boolean gizmo,boolean shape){
        if(tool==null)return "Wheel: Zoom";
        if(shape)return "Wheel: Size shape";
        if(gizmo)return "Wheel: Nudge";
        return "Wheel: Zoom";
    }
    /** Tools that act through a brush, so the radius shortcut applies. */
    static boolean brush(Tool tool){return !tool.selection()&&!tool.transform()&&tool!=Tool.RULER&&tool!=Tool.ANNOTATION&&tool!=Tool.MODIFY&&tool!=Tool.LINE&&tool!=Tool.PATH&&tool!=Tool.MODELLING&&tool!=Tool.SHAPE;}
    private static boolean confirms(Tool tool){return tool.transform()&&tool!=Tool.PIVOT||tool==Tool.SHAPE;}
    private static String use(Tool tool){
        return switch(tool){
            case MAGIC_SELECT->"Select connected";
            case BOX_SELECT->"Set corner";
            case FREEHAND_SELECT->"Brush selection";
            case LASSO_SELECT->"Draw lasso";
            case RULER->"Add point";
            case LINE,PATH,MODELLING->"Add point";
            case SHAPE->"Put down shape";
            case MOVE->"Drag arrows to move";
            case ROTATE->"Drag rings to rotate";
            case SCALE->"Drag squares to scale";
            case PIVOT->"Set pivot";
            case MODIFY->"Fix preview";
            case ANNOTATION->"Annotate";
            case TINKER->"Tinker with block";
            case TEXT->"Place text";
            case STAMP->"Stamp clipboard";
            case ERASE->"Erase";
            case PAINTER,NOISE_PAINTER,BIOME_PAINTER,CLENTAMINATOR,GRADIENT_PAINTER,SCRIPT_BRUSH->"Paint";
            default->"Use "+tool.label;
        };
    }
}
