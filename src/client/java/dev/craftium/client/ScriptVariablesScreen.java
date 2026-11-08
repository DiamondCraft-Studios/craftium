package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.core.ScriptTemplates;
import dev.craftium.client.compat.CraftiumScreen;
import dev.craftium.client.compat.Gfx;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

final class ScriptVariablesScreen extends CraftiumScreen {
    private final Screen parent;private final boolean mask;
    private List<ScriptTemplates.Variable> variables=List.of();private int page,left,top;private float scale;private String error="";
    ScriptVariablesScreen(Screen parent,boolean mask){super(Component.literal("Craftium Script Variables"));this.parent=parent;this.mask=mask;}
    private Map<String,String> values(){return mask?ClientState.options.maskVariables:ClientState.options.scriptVariables;}
    @Override protected void init(){
        scale=Math.min(1f,Math.min(width/680f,height/440f));int w=Math.round(width/scale),h=Math.round(height/scale);left=(w-640)/2;top=(h-350)/2;clearWidgets();
        try{variables=ScriptTemplates.expand(mask?ClientState.options.maskScript:ClientState.options.script,values()).variables();error="";}catch(IllegalArgumentException e){error=e.getMessage();}
        page=Math.clamp(page,0,Math.max(0,(variables.size()-1)/10));
        for(int i=page*10;i<Math.min(variables.size(),page*10+10);i++){
            var variable=variables.get(i);int index=i-page*10,x=left+(index%2)*320,y=top+42+(index/2)*47;
            if(variable.kind().equals("int")||variable.kind().equals("float")){
                double range=variable.max()-variable.min(),initial=Double.parseDouble(variable.value());var slider=new FlatSlider(x,y,300,22,Component.empty(),range==0?0:(initial-variable.min())/range){
                    protected void updateMessage(){double number=variable.min()+value*(variable.max()-variable.min());String text=variable.kind().equals("int")?Long.toString(Math.round(number)):String.format(Locale.ROOT,"%.3f",number);setMessage(Component.literal(text));}
                    protected void applyValue(){double number=variable.min()+value*(variable.max()-variable.min());values().put(variable.title(),variable.kind().equals("int")?Long.toString(Math.round(number)):Double.toString(number));updateMessage();}
                };slider.setRaw(range==0?0:(initial-variable.min())/range);slider.range(variable.min(),variable.max(),defaultOf(variable));addRenderableWidget(slider);
            }else if(variable.kind().equals("boolean"))addRenderableWidget(new FlatButton(x,y,300,22,variable.value(),()->{values().put(variable.title(),Boolean.toString(!Boolean.parseBoolean(variable.value())));init();}));
            else {var field=new EditBox(font,x,y,300,22,Component.literal(variable.title()));field.setMaxLength(256);field.setValue(variable.value());field.setResponder(value->values().put(variable.title(),value));addRenderableWidget(field);}
        }
        addRenderableWidget(new FlatButton(left,top+300,90,22,"Previous",()->{page--;init();}));addRenderableWidget(new FlatButton(left+100,top+300,90,22,"Next",()->{page++;init();}));addRenderableWidget(new FlatButton(left+210,top+300,140,22,"Reset variables",()->{values().clear();init();}));addRenderableWidget(new FlatButton(left+490,top+300,130,22,"Return",this::onClose));
    }
    /** The value the template itself gives a variable, before any change made here. */
    private double defaultOf(ScriptTemplates.Variable variable){
        try{for(var v:ScriptTemplates.expand(mask?ClientState.options.maskScript:ClientState.options.script,java.util.Map.of()).variables())if(v.title().equals(variable.title()))return Double.parseDouble(v.value());}catch(RuntimeException ignored){}
        return variable.min();
    }
    @Override protected void drawBackground(Gfx g,int x,int y,float delta){}
    @Override protected void draw(Gfx g,int x,int y,float delta){g.pose().pushMatrix();g.pose().scale(scale,scale);int w=Math.round(width/scale),h=Math.round(height/scale);g.fill(0,0,w,h,0xef101923);g.fill(left-12,top-20,left+632,top+340,0xff172330);g.text(font,(mask?"MASK":"BRUSH")+" VARIABLES",left,top-10,0xffb5dcfc,false);for(int i=page*10;i<Math.min(variables.size(),page*10+10);i++){int index=i-page*10;g.text(font,font.plainSubstrByWidth(variables.get(i).title(),300),left+(index%2)*320,top+30+(index/2)*47,0xff9db5cb,false);}if(variables.isEmpty())g.text(font,error.isBlank()?"Add $int(Title,5,1,10)$ or another variable to the script first.":error,left,top+40,0xffffdca8,false);drawWidgets(g,(int)(x/scale),(int)(y/scale),delta);g.pose().popMatrix();}
    @Override public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent e,boolean twice){return super.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(e.x()/scale,e.y()/scale,e.buttonInfo()),twice);}
    @Override public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent e){return super.mouseReleased(new net.minecraft.client.input.MouseButtonEvent(e.x()/scale,e.y()/scale,e.buttonInfo()));}
    @Override public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent e,double x,double y){return super.mouseDragged(new net.minecraft.client.input.MouseButtonEvent(e.x()/scale,e.y()/scale,e.buttonInfo()),x/scale,y/scale);}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){ClientCompat.setScreen(minecraft,parent);}
}
