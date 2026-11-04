package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.core.*;
import dev.craftium.client.compat.CraftiumScreen;
import dev.craftium.client.compat.Gfx;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Nested drag-and-drop rule blocks, with the editable expression always available. */
public final class MaskScreen extends CraftiumScreen {
    private final Screen parent;
    private MaskTree.Node root,selected,dragged;
    private final List<Row> rows=new ArrayList<>();
    private int left,top,cw,ch,scroll;
    private float scale=1;
    private String message="";
    private record Row(MaskTree.Node node,int depth){}
    public MaskScreen(Screen parent){super(Component.literal("Craftium Tool Masks"));this.parent=parent;root=MaskTree.parse(ClientState.options.maskExpression);selected=root;}
    @Override protected void init(){
        scale=Math.min(1f,Math.min(width/720f,height/460f));cw=Math.round(width/scale);ch=Math.round(height/scale);left=(cw-680)/2;top=(ch-410)/2;clearWidgets();rows.clear();flatten(root,0);scroll=Math.clamp(scroll,0,Math.max(0,rows.size()-9));
        var value=new EditBox(font,left,top+330,430,21,Component.literal("Selected rule"));value.setMaxLength(512);value.setValue(selected.rule);value.setResponder(s->selected.rule=s);addRenderableWidget(value);
        String[] rules={"all","any","not","offset(0,1,0)","block("+ClientState.options.block+")","above(minecraft:air)","below(minecraft:dirt)","near(minecraft:stone,2)","neighbor(minecraft:stone)","adjacent(minecraft:stone)","y(0,64)","angle(90,10)","selection","sky","surface","true"};
        String[] names={"AND","OR","NOT","Offset","Block = active","Above","Below","Near","Neighbour","Adjacent","Y level","Angle","In selection","Can see sky","Surface","Always"};for(int i=0;i<rules.length;i++){String rule=rules[i];button(names[i],left+451+(i%2)*112,top+40+(i/2)*30,106,23,()->add(rule));}
        button("Wrap AND",left+451,top+285,106,22,()->wrap("all"));button("Wrap NOT",left+563,top+285,106,22,()->wrap("not"));
        button("Remove",left+451,top+330,106,21,()->{var p=MaskTree.parent(root,selected);if(p==null)root=selected=new MaskTree.Node("all");else{p.children.remove(selected);selected=p;}init();});
        button("Copy string",left,top+376,125,24,()->minecraft.keyboardHandler.setClipboard(root.expression()));
        button("Paste string",left+135,top+376,125,24,()->{var next=MaskTree.parse(minecraft.keyboardHandler.getClipboard());validate(next.expression());root=selected=next;init();});
        button("Clear",left+270,top+376,100,24,()->{root=selected=new MaskTree.Node("all");init();});
        button("Cancel",left+382,top+376,100,24,()->ClientCompat.setScreen(minecraft,parent));
        button("Apply mask",left+535,top+376,134,24,this::onClose);
    }
    private void flatten(MaskTree.Node node,int depth){if(rows.size()>=128)throw new IllegalArgumentException("Visual mask limit: 128 rules");rows.add(new Row(node,depth));for(var child:node.children)flatten(child,depth+1);}
    private void add(String rule){if(rows.size()>=128){message="Visual mask limit: 128 rules";return;}var child=new MaskTree.Node(rule);if(selected.acceptsChild())selected.children.add(child);else{var parent=MaskTree.parent(root,selected);if(parent==null){parent=new MaskTree.Node("all");parent.children.add(root);root=parent;}if(!parent.acceptsChild())throw new IllegalArgumentException("Select an AND or OR block to add more rules");parent.children.add(child);}selected=child;init();}
    private void wrap(String rule){var parent=MaskTree.parent(root,selected);var group=new MaskTree.Node(rule);group.children.add(selected);if(parent==null)root=group;else parent.children.set(parent.children.indexOf(selected),group);selected=group;init();}
    private void button(String label,int x,int y,int w,int h,Runnable action){addRenderableWidget(new FlatButton(x,y,w,h,label,()->{try{action.run();}catch(Exception e){message=e.getMessage();}}));}
    private static void validate(String expression){MaskExpression.compile(expression,new MaskExpression.Context(){public boolean block(Point p,String state){return false;}public boolean selection(Point p){return false;}public boolean sky(Point p){return false;}public boolean surface(Point p){return false;}public double angle(Point p){return 0;}});}
    @Override protected void drawBackground(Gfx g,int x,int y,float delta){}
    @Override protected void draw(Gfx g,int mouseX,int mouseY,float delta){
        int mx=(int)(mouseX/scale),my=(int)(mouseY/scale);g.pose().pushMatrix();g.pose().scale(scale,scale);g.fill(0,0,cw,ch,0xef101923);g.fill(left-12,top-25,left+692,top+414,0xff172330);g.text(font,"CRAFTIUM  /  TOOL MASKS",left,top-17,0xffb5dcfc,false);g.text(font,font.plainSubstrByWidth(root.expression(),665),left,top+5,0xffe6cf8b,false);g.text(font,"Drag a rule into an AND / OR / NOT / OFFSET block",left,top+23,0xff9db5cb,false);
        for(int i=scroll;i<Math.min(rows.size(),scroll+9);i++){var row=rows.get(i);int x=left+Math.min(9,row.depth())*17,y=top+45+(i-scroll)*29;boolean over=mx>=x&&mx<left+430&&my>=y&&my<y+26;g.fill(x,y,left+430,y+26,row.node()==selected?0xff365576:over?Hover.fill(row.node().group()?0xff263d56:0xff223247):row.node().group()?0xff263d56:0xff223247);g.outline(x,y,left+430-x,26,row.node()==dragged?0xffffd66d:0xff3a4d60);g.text(font,(row.node().group()?"▾ ":"• ")+font.plainSubstrByWidth(row.node().rule,390-(x-left)),x+8,y+8,0xffdce6ef,false);}
        g.text(font,"Selected rule / offset coordinates",left,top+314,0xff9db5cb,false);drawWidgets(g,mx,my,delta);g.text(font,font.plainSubstrByWidth(message,670),left,top+358,0xffffdca8,false);g.pose().popMatrix();
    }
    private MaskTree.Node rowAt(double x,double y){if(x<left||x>left+430)return null;int index=scroll+(int)((y-top-45)/29);return y>=top+45&&index>=scroll&&index<Math.min(rows.size(),scroll+9)?rows.get(index).node():null;}
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean twice){double x=event.x()/scale,y=event.y()/scale;var node=rowAt(x,y);if(node!=null){selected=node;init();dragged=node;return true;}return super.mouseClicked(new MouseButtonEvent(x,y,event.buttonInfo()),twice);}
    @Override public boolean mouseDragged(MouseButtonEvent event,double dx,double dy){if(dragged!=null)return true;return super.mouseDragged(new MouseButtonEvent(event.x()/scale,event.y()/scale,event.buttonInfo()),dx/scale,dy/scale);}
    @Override public boolean mouseReleased(MouseButtonEvent event){if(dragged!=null){var destination=rowAt(event.x()/scale,event.y()/scale);if(destination!=null&&destination!=dragged){message=MaskTree.move(root,dragged,destination)?"Rule moved":"Drop onto an empty NOT / OFFSET or an AND / OR block";init();}dragged=null;return true;}return super.mouseReleased(new MouseButtonEvent(event.x()/scale,event.y()/scale,event.buttonInfo()));}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){scroll=Math.clamp(scroll-(int)Math.signum(vertical),0,Math.max(0,rows.size()-9));return true;}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){try{String expression=root.expression();validate(expression);ClientState.options.maskExpression=expression.equals("true")?"":expression;ClientState.save();ClientCompat.setScreen(minecraft,parent);}catch(Exception e){message=e.getMessage();}}
}
