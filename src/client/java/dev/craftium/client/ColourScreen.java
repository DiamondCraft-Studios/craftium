package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.core.*;
import dev.craftium.client.compat.CraftiumScreen;
import dev.craftium.client.compat.Gfx;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import java.awt.Color;
import java.util.*;

/** Colour matching, texture filters and an editable nine-slot OKLab gradient. */
public final class ColourScreen extends CraftiumScreen {
    private final Screen parent;
    private int color=0x8b9194,left,top,canvasW,canvasH;
    private float scale=1;
    private boolean full=true,solid=true,opaque,uniform,updatingHex;
    private EditBox hexField;
    private String first="minecraft:stone",last="minecraft:deepslate",message="";
    private boolean multipleStops;
    private final String[] gradient=new String[9];
    private final int[] cycles=new int[9];
    private java.util.List<BlockColours.Entry> matches=java.util.List.of();
    private final java.util.List<FlatSlider> colorWidgets=new ArrayList<>();
    public ColourScreen(Screen parent){super(Component.literal("Craftium Colour Studio"));this.parent=parent;}
    @Override protected void init(){
        BlockColours.build();scale=Math.min(1f,Math.min(width/700f,height/460f));canvasW=Math.round(width/scale);canvasH=Math.round(height/scale);left=(canvasW-660)/2;top=(canvasH-420)/2;
        clearWidgets();colorWidgets.clear();matches();
        addColorControls();
        filter("Full cube (F)",left,top+210,()->full=!full);filter("Solid (S)",left+165,top+210,()->solid=!solid);filter("Opaque (O)",left+330,top+210,()->opaque=!opaque);filter("Same texture (T)",left+495,top+210,()->uniform=!uniform);
        button("Eyedrop active block",left+445,top+171,200,22,()->{var e=BlockColours.get(ClientState.options.block);if(e!=null){color=e.rgb();init();}});
        button(multipleStops?"Stops: palette":"Stops: endpoints",left+276,top+266,99,20,()->{multipleStops=!multipleStops;gradient();});
        field(first,left+8,top+266,264,value->{first=value;Arrays.fill(cycles,0);gradient();});field(last,left+382,top+266,264,value->{last=value;Arrays.fill(cycles,0);gradient();});
        gradient();
        for(int i=0;i<9;i++){int slot=i;button("‹",left+10+i*72,top+338,26,18,()->{cycles[slot]=Math.max(0,cycles[slot]-1);gradient();});button("›",left+40+i*72,top+338,26,18,()->{cycles[slot]=(cycles[slot]+1)%32;gradient();});}
        button("Use gradient in Painter",left+8,top+369,209,24,()->{ClientState.options.materials=String.join(",",gradient);ClientState.options.materialWeights.clear();ClientState.options.tool=Tool.GRADIENT_PAINTER;ClientState.notify("Gradient exported to Gradient Painter");onClose();});
        button("Copy gradient to hotbar",left+225,top+369,209,24,()->{ClientState.options.materials=String.join(",",gradient);CraftiumClient.send("palette_hotbar",null,null);});
        button("Return to editor",left+442,top+369,209,24,this::onClose);
    }
    private void addColorControls(){
        String[] labels={"Red","Green","Blue","Hue","Saturation","Brightness"};float[] hsv=Color.RGBtoHSB((color>>16)&255,(color>>8)&255,color&255,null);
        for(int i=0;i<6;i++){
            int index=i;double value=i<3?((color>>(16-i*8))&255)/255d:hsv[i-3];
            var slider=new FlatSlider(left+445,top+30+i*22,200,19,Component.empty(),value){
                protected void updateMessage(){setMessage(Component.literal(labels[index]+": "+Math.round(value*(index==3?360:index<3?255:100))));}
                protected void applyValue(){
                    if(index<3){int shift=16-index*8;color=(color&~(255<<shift))|((int)Math.round(value*255)<<shift);}
                    else {float[] current=Color.RGBtoHSB((color>>16)&255,(color>>8)&255,color&255,null);current[index-3]=(float)value;color=Color.HSBtoRGB(current[0],current[1],current[2])&0xffffff;}
                    syncSliders();matches();gradient();
                }
            };slider.setMessage(Component.literal(labels[i]+": "+Math.round(value*(i==3?360:i<3?255:100))));slider.range(0,i<3?255:i==3?360:100,value*(i<3?255:i==3?360:100));addRenderableWidget(slider);colorWidgets.add(slider);
        }
        field(String.format(Locale.ROOT,"#%06X",color),left+445,top+5,128,value->{try{color=Integer.parseUnsignedInt(value.replace("#",""),16)&0xffffff;syncSliders();matches();gradient();}catch(Exception ignored){}});
    }
    private void syncSliders(){if(hexField!=null&&!hexField.isFocused()&&!updatingHex){updatingHex=true;hexField.setValue(String.format(Locale.ROOT,"#%06X",color));updatingHex=false;}float[] hsv=Color.RGBtoHSB((color>>16)&255,(color>>8)&255,color&255,null);for(int i=0;i<colorWidgets.size();i++)colorWidgets.get(i).setRaw(i<3?((color>>(16-i*8))&255)/255d:hsv[i-3]);}
    private void matches(){matches=BlockColours.nearest(color,full,solid,opaque,uniform,32);}
    private void gradient(){
        var a=BlockColours.get(first);var b=BlockColours.get(last);if(a==null||b==null){message="Choose two valid block identifiers for the gradient";Arrays.fill(gradient,"minecraft:stone");return;}
        var stops=multipleStops?MaterialList.split(ClientState.options.materials):java.util.List.of(first,last);if(stops.size()<2)stops=java.util.List.of(first,last);
        message="";for(int i=0;i<9;i++){double position=i/8d*(stops.size()-1);int lower=Math.min(stops.size()-2,(int)position);var from=BlockColours.get(stops.get(lower));var to=BlockColours.get(stops.get(lower+1));int rgb=ColourMath.interpolate(from==null?a.rgb():from.rgb(),to==null?b.rgb():to.rgb(),position-lower);var choices=BlockColours.nearest(rgb,full,solid,opaque,uniform,32);gradient[i]=choices.isEmpty()?first:choices.get(Math.min(cycles[i],choices.size()-1)).id();}gradient[0]=stops.getFirst();gradient[8]=stops.getLast();
    }
    private void field(String value,int x,int y,int w,java.util.function.Consumer<String> listener){var f=new EditBox(font,x,y,w,20,Component.literal("Block or hex colour"));f.setValue(value);if(value.startsWith("#"))hexField=f;f.setResponder(text->{if(!updatingHex)listener.accept(text);});addRenderableWidget(f);}
    private void filter(String text,int x,int y,Runnable action){button(text,x,y,157,22,()->{action.run();matches();gradient();});}
    private void button(String label,int x,int y,int w,int h,Runnable action){addRenderableWidget(new FlatButton(x,y,w,h,label,action));}
    private void item(Gfx g,String id,int x,int y){var key=dev.craftium.compat.Compat.tryParse(id);if(key!=null)BuiltInRegistries.BLOCK.getOptional(key).ifPresent(block->g.item(block.asItem().getDefaultInstance(),x,y));}
    @Override protected void drawBackground(Gfx g,int x,int y,float delta){}
    @Override protected void draw(Gfx g,int mouseX,int mouseY,float delta){
        int mx=(int)(mouseX/scale),my=(int)(mouseY/scale);g.pose().pushMatrix();g.pose().scale(scale,scale);g.fill(0,0,canvasW,canvasH,0xef101923);g.fill(left-12,top-25,left+672,top+414,0xff172330);
        g.text(font,"Colour Studio",left,top-17,0xffb5dcfc,false);g.text(font,"SIMILAR BLOCKS · "+BlockColours.size()+" texture averages",left+8,top+4,0xff9db5cb,false);
        for(int i=0;i<matches.size();i++){var e=matches.get(i);int x=left+8+(i%8)*53,y=top+25+(i/8)*43;boolean over=mx>=x&&mx<x+48&&my>=y&&my<y+38;g.fill(x,y,x+48,y+38,over?Hover.fill(0xff223247):0xff223247);if(over)g.outline(x,y,48,38,ClientState.options.uiAccent);g.fill(x+3,y+32,x+45,y+35,0xff000000|e.rgb());item(g,e.id(),x+16,y+6);if(mx>=x&&mx<x+48&&my>=y&&my<y+38)g.text(font,e.id(),left+8,top+199,0xffffdca8,false);}
        g.fill(left+581,top+5,left+645,top+24,0xff000000|color);g.text(font,"GRADIENT · OKLab",left+8,top+245,0xffb5dcfc,false);
        for(int i=0;i<9;i++){int x=left+10+i*72,y=top+299;boolean over=mx>=x&&mx<x+56&&my>=y&&my<y+35;g.fill(x,y,x+56,y+35,over?Hover.fill(0xff25364a):0xff25364a);if(over)g.outline(x,y,56,35,ClientState.options.uiAccent);item(g,gradient[i],x+20,y+7);if(mx>=x&&mx<x+56&&my>=y&&my<y+35)g.text(font,gradient[i],left+8,top+402,0xffffdca8,false);}
        String filters="Filters: "+(full?"F ":"")+(solid?"S ":"")+(opaque?"O ":"")+(uniform?"T":"");g.text(font,filters,left+8,top+234,0xff9db5cb,false);
        drawWidgets(g,mx,my,delta);if(!message.isBlank())g.text(font,message,left+8,top+402,0xffffdca8,false);g.pose().popMatrix();
    }
    @Override public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event,boolean twice){double x=event.x()/scale,y=event.y()/scale;for(int i=0;i<matches.size();i++){int bx=left+8+(i%8)*53,by=top+25+(i/8)*43;if(x>=bx&&x<bx+48&&y>=by&&y<by+38){var e=matches.get(i);if(event.button()==1){last=e.id();gradient();}else{ClientState.pick(e.id());color=e.rgb();first=e.id();init();}return true;}}return super.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(x,y,event.buttonInfo()),twice);}
    @Override public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event){return super.mouseReleased(new net.minecraft.client.input.MouseButtonEvent(event.x()/scale,event.y()/scale,event.buttonInfo()));}
    @Override public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event,double dx,double dy){return super.mouseDragged(new net.minecraft.client.input.MouseButtonEvent(event.x()/scale,event.y()/scale,event.buttonInfo()),dx/scale,dy/scale);}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){ClientCompat.setScreen(minecraft,parent);}
}
