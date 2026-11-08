package dev.craftium.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.craftium.client.compat.ClientCompat;
import dev.craftium.client.compat.CraftiumScreen;
import dev.craftium.client.compat.Gfx;
import dev.craftium.core.EditorControls.*;
import dev.craftium.core.Tool;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/**
 * Every shortcut in one place, split into tabs: the editor's keyboard actions, its mouse actions and the tool keys can be
 * changed, and the controls that are built in are listed so nothing is hidden. Press a button, then the new key; Esc
 * cancels and Backspace clears.
 */
final class KeyboardShortcutsScreen extends CraftiumScreen {
    private sealed interface Row permits Header,ActionRow,KeyRow,FixedRow {}
    private record Header(String title) implements Row {}
    private record ActionRow(Action action) implements Row {}
    private record KeyRow(String label,KeyMapping mapping) implements Row {}
    private record FixedRow(String label,String keys) implements Row {}

    private enum Tab {
        EDITOR("Editor","Editing, selecting and viewing"),
        MOUSE("Mouse","Camera and tool buttons"),
        TOOLS("Tools","A key for each tool, and for opening the editor"),
        BUILT_IN("Built in","Controls that cannot be changed here");
        final String label,about;
        Tab(String label,String about){this.label=label;this.about=about;}
    }

    private final Screen parent;
    private Tab tab=Tab.EDITOR;
    private int page;
    private Action waitingAction;
    private KeyMapping waitingKey;

    KeyboardShortcutsScreen(Screen parent){super(Component.literal("Keyboard shortcuts"));this.parent=parent;}

    /** The built-in controls that cannot be changed here, so the list is the whole picture. */
    private static final String[][] FIXED={
        {"Fly forward, back, left, right","Your Minecraft movement keys"},
        {"Fly up / down","Space / Shift (not with Ctrl held)"},
        {"Pan the camera","Middle mouse drag"},
        {"Zoom toward the cursor","Mouse wheel, with no tool chosen"},
        {"Nudge the gizmo's target","Mouse wheel, with a tool chosen"},
        {"Confirm an edit or popup","Enter"},
        {"Cancel an edit, popup or menu","Esc"},
        {"Scale from the centre","Alt, while dragging a Scale handle"},
        {"Scale smoothly, not by whole blocks","Ctrl, while dragging a Scale handle"},
        {"Rotate in smaller steps","Shift (1°) or Ctrl (5°), while dragging a ring"},
        {"Builder: turn it on","0"},
        {"Builder: turn it off","1 to 9, or Esc"},
        {"Builder: change tool","Alt + mouse wheel"},
        {"Builder: cancel the selection / place it","Left click / right click"},
        {"Builder: second corner, or expand a face","Middle click"},
        {"Builder: place or erase","Enter"},
        {"Builder: choose the axis for the wheel","Hold X, Y or Z"},
        {"Builder: undo, redo","Ctrl+Z, Ctrl+Y"},
        {"Builder: rotate, flip the preview","Ctrl+R, Ctrl+F"},
        {"Shape: rotate, tilt, flip (relative to the camera)","Ctrl+R, Ctrl+T, Ctrl+F"},
    };

    private List<Row> rows(){
        var list=new ArrayList<Row>();
        switch(tab){
            case EDITOR->{for(Action action:Action.values())if(!action.mouseOnly())list.add(new ActionRow(action));}
            case MOUSE->{for(Action action:Action.values())if(action.mouseOnly())list.add(new ActionRow(action));}
            case TOOLS->{
                list.add(new Header("OPEN AND CLOSE"));
                list.add(new KeyRow("Open or close the editor",CraftiumClient.editorKey));
                list.add(new KeyRow("Builder: context menu",CraftiumClient.contextKey));
                list.add(new KeyRow("Swap",CraftiumClient.swapKey));
                list.add(new KeyRow("Replace mode",CraftiumClient.replaceKey));
                list.add(new Header("TOOLS"));
                for(Tool tool:Tool.values()){var mapping=CraftiumClient.toolKeys.get(tool);if(mapping!=null)list.add(new KeyRow(tool.label,mapping));}
            }
            case BUILT_IN->{for(String[] fixed:FIXED)list.add(new FixedRow(fixed[0],fixed[1]));}
        }
        return list;
    }
    private static final int LIST_TOP=76;
    private int rowsPerPage(){return Math.max(1,(height-LIST_TOP-80)/24);}
    private int left(){return Math.max(12,(width-480)/2);}

    @Override protected void init(){
        clearWidgets();
        int x=left(),per=rowsPerPage();
        // The tabs, in a row under the heading.
        int tx=x;
        for(Tab t:Tab.values()){
            int w=font.width(t.label)+24;
            addRenderableWidget(new FlatButton(tx,40,w,20,t.label,()->{tab=t;page=0;cancel();init();}));
            tx+=w+4;
        }
        var rows=rows();int pages=Math.max(1,(rows.size()+per-1)/per);page=Math.clamp(page,0,pages-1);
        for(int i=page*per;i<Math.min(rows.size(),(page+1)*per);i++){
            int y=LIST_TOP-6+(i-page*per)*24;
            if(rows.get(i) instanceof ActionRow row){
                Action a=row.action();
                addRenderableWidget(new FlatButton(x+245,y,180,20,waitingAction==a?(a.mouseOnly()?"Press a mouse button…":a.keyboardOnly()?"Press a key…":"Press a key or mouse button…"):EditorBindings.label(a),()->{waitingAction=a;waitingKey=null;init();}));
                addRenderableWidget(new FlatButton(x+430,y,50,20,"Reset",()->{EditorBindings.set(a,a.defaults);init();}));
            }else if(rows.get(i) instanceof KeyRow row){
                KeyMapping m=row.mapping();
                addRenderableWidget(new FlatButton(x+245,y,180,20,waitingKey==m?"Press a key…":m.getTranslatedKeyMessage().getString(),()->{waitingKey=m;waitingAction=null;init();}));
                addRenderableWidget(new FlatButton(x+430,y,50,20,"Reset",()->{m.setKey(m.getDefaultKey());saveKeys();init();}));
            }
        }
        addRenderableWidget(new FlatButton(x,height-62,32,20,"‹",()->{page--;cancel();init();}));
        addRenderableWidget(new FlatButton(x+37,height-62,32,20,"›",()->{page++;cancel();init();}));
        addRenderableWidget(new FlatButton(x+280,height-62,110,20,"Reset editor",()->{EditorBindings.reset();EditorBindings.save();init();}));
        addRenderableWidget(new FlatButton(x+395,height-62,85,20,"Done",this::onClose));
    }
    private void cancel(){waitingAction=null;waitingKey=null;}
    private void saveKeys(){KeyMapping.resetMapping();minecraft.options.save();}

    @Override protected void draw(Gfx g,int mouseX,int mouseY,float delta){
        g.fill(0,0,width,height,0xf510151c);
        int x=left(),per=rowsPerPage();
        g.text(font,"KEYBOARD SHORTCUTS",x,14,0xffdce6ef,false);
        g.text(font,tab.about+". Choose a shortcut, then press the new key; Esc cancels, Backspace clears.",x,27,0xff8595a8,false);
        var rows=rows();
        for(int i=page*per;i<Math.min(rows.size(),(page+1)*per);i++){
            int y=LIST_TOP-2+(i-page*per)*24;Row row=rows.get(i);
            if(row instanceof Header h)g.text(font,h.title(),x,y,0xff7fb2e0,false);
            else if(row instanceof ActionRow a)g.text(font,a.action().label,x,y,0xffdce6ef,false);
            else if(row instanceof KeyRow k)g.text(font,k.label(),x,y,0xffdce6ef,false);
            else if(row instanceof FixedRow f){g.text(font,f.label(),x,y,0xffdce6ef,false);g.text(font,f.keys(),x+245,y,0xff8595a8,false);}
        }
        g.text(font,"Page "+(page+1)+" / "+Math.max(1,(rows.size()+per-1)/per),x+80,height-79,0xff8595a8,false);
        drawWidgets(g,mouseX,mouseY,delta);
        // The open tab is marked with a bar under it, drawn over the buttons.
        int tx=x;
        for(Tab t:Tab.values()){
            int w=font.width(t.label)+24;
            if(t==tab){g.outline(tx,40,w,20,0xff8bc7ff);g.fill(tx,58,tx+w,60,0xff599cda);}
            tx+=w+4;
        }
    }

    @Override public boolean keyPressed(KeyEvent event){
        if(waitingAction==null&&waitingKey==null)return super.keyPressed(event);
        int key=event.key();
        if(key==GLFW.GLFW_KEY_ESCAPE){cancel();init();return true;}
        if(key==340||key==341||key==342||key==344||key==345||key==346)return true; // a modifier on its own waits for the key it modifies
        if(waitingKey!=null){
            waitingKey.setKey(key==GLFW.GLFW_KEY_BACKSPACE?InputConstants.UNKNOWN:InputConstants.Type.KEYSYM.getOrCreate(key));
            saveKeys();waitingKey=null;init();return true;
        }
        if(waitingAction.mouseOnly()&&key!=GLFW.GLFW_KEY_BACKSPACE)return true;
        EditorBindings.set(waitingAction,Binding.key(key==GLFW.GLFW_KEY_BACKSPACE?-1:key,key==GLFW.GLFW_KEY_BACKSPACE?0:event.modifiers()&7));
        waitingAction=null;init();return true;
    }
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean twice){
        if(waitingKey!=null){cancel();init();return true;}
        if(waitingAction!=null){
            if(waitingAction.keyboardOnly())return true;
            EditorBindings.set(waitingAction,Binding.mouse(event.button(),event.buttonInfo().modifiers()&7));
            waitingAction=null;init();return true;
        }
        return super.mouseClicked(event,twice);
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){page+=vertical<0?1:-1;cancel();init();return true;}
    @Override public void onClose(){ClientCompat.setScreen(minecraft,parent);}
}
