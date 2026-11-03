package dev.craftium.client;

import dev.craftium.core.EditorControls.*;
import dev.craftium.client.compat.CraftiumScreen;
import dev.craftium.client.compat.Gfx;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

final class EditorKeybindScreen extends CraftiumScreen {
    private final Screen parent;private int page;private Action waiting;
    EditorKeybindScreen(Screen parent){super(Component.literal("Craftium keybinds"));this.parent=parent;}
    @Override protected void init(){clearWidgets();int x=Math.max(12,(width-480)/2);var all=Action.values();int rows=Math.max(1,(height-125)/24),pages=(all.length+rows-1)/rows;page=Math.clamp(page,0,pages-1);
        for(int i=page*rows;i<Math.min(all.length,(page+1)*rows);i++){Action a=all[i];int y=48+(i-page*rows)*24;addRenderableWidget(new FlatButton(x+245,y,180,20,waiting==a?(a.mouseOnly()?"Press a mouse button…":a.keyboardOnly()?"Press a key…":"Press a key or mouse button…"):EditorBindings.label(a),()->{waiting=a;init();}));addRenderableWidget(new FlatButton(x+430,y,50,20,"Reset",()->{EditorBindings.set(a,a.defaults);init();}));}
        addRenderableWidget(new FlatButton(x,height-62,32,20,"‹",()->{page--;waiting=null;init();}));addRenderableWidget(new FlatButton(x+37,height-62,32,20,"›",()->{page++;waiting=null;init();}));
        addRenderableWidget(new FlatButton(x+80,height-62,190,20,"Tools & Minecraft controls",()->minecraft.gui.setScreen(new net.minecraft.client.gui.screens.options.controls.KeyBindsScreen(this,minecraft.options))));
        addRenderableWidget(new FlatButton(x+280,height-62,110,20,"Reset editor",()->{EditorBindings.reset();EditorBindings.save();init();}));addRenderableWidget(new FlatButton(x+395,height-62,85,20,"Done",this::onClose));
    }
    @Override protected void draw(Gfx g,int mouseX,int mouseY,float delta){g.fill(0,0,width,height,0xf510151c);int x=Math.max(12,(width-480)/2);g.text(font,"CRAFTIUM / KEYBINDS",x,14,0xffdce6ef,false);g.text(font,"Choose a binding. Esc cancels; Backspace clears. Modifiers are supported.",x,29,0xff8595a8,false);var all=Action.values();int rows=Math.max(1,(height-125)/24);for(int i=page*rows;i<Math.min(all.length,(page+1)*rows);i++)g.text(font,all[i].label,x,54+(i-page*rows)*24,0xffdce6ef,false);g.text(font,"Page "+(page+1)+" / "+((all.length+rows-1)/rows),x+80,height-79,0xff8595a8,false);drawWidgets(g,mouseX,mouseY,delta);}
    @Override public boolean keyPressed(KeyEvent event){if(waiting==null)return super.keyPressed(event);int key=event.key();if(key==GLFW.GLFW_KEY_ESCAPE){waiting=null;init();return true;}if(key==340||key==341||key==342||key==344||key==345||key==346)return true;if(waiting.mouseOnly()&&key!=GLFW.GLFW_KEY_BACKSPACE)return true;EditorBindings.set(waiting,Binding.key(key==GLFW.GLFW_KEY_BACKSPACE?-1:key,key==GLFW.GLFW_KEY_BACKSPACE?0:event.modifiers()&7));waiting=null;init();return true;}
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean twice){if(waiting!=null){if(waiting.keyboardOnly())return true;EditorBindings.set(waiting,Binding.mouse(event.button(),event.buttonInfo().modifiers()&7));waiting=null;init();return true;}return super.mouseClicked(event,twice);}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){page+=vertical<0?1:-1;waiting=null;init();return true;}
    @Override public void onClose(){minecraft.gui.setScreen(parent);}
}
