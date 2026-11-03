package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.client.compat.CraftiumScreen;
import dev.craftium.client.compat.Gfx;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import java.util.*;
import java.util.function.Consumer;

/** Edits the state of one block as a list of properties, each a button that steps through its values. */
public final class BlockStateScreen extends CraftiumScreen {
    private static final int ROW=24,WIDTH=300,MAX_ROWS=10;
    private final Screen parent;
    private final Consumer<String> done;
    private final String id;
    private final Block block;
    private BlockState state;
    private List<Property<?>> properties=List.of();
    private int scroll;
    public BlockStateScreen(Screen parent,String block,Consumer<String> done){
        super(Component.literal("Craftium Block State"));this.parent=parent;this.done=done;
        id=block.split("\\[",2)[0];var key=dev.craftium.compat.Compat.tryParse(id);
        this.block=key==null?null:BuiltInRegistries.BLOCK.getOptional(key).orElse(null);
        state=this.block==null?null:parse(this.block,block);
        if(this.block!=null)properties=List.copyOf(this.block.defaultBlockState().getProperties());
    }
    /** The block's default state with the properties named in {@code text} ("id[a=b,c=d]") applied; unknown ones are ignored. */
    private static BlockState parse(Block block,String text){
        var state=block.defaultBlockState();int open=text.indexOf('['),close=text.lastIndexOf(']');
        if(open<0||close<open)return state;
        for(String pair:text.substring(open+1,close).split(",")){
            String[] kv=pair.split("=",2);if(kv.length!=2)continue;
            for(var property:state.getProperties())if(property.getName().equals(kv[0].trim()))state=with(state,property,kv[1].trim());
        }
        return state;
    }
    private static <T extends Comparable<T>> BlockState with(BlockState state,Property<T> property,String value){return property.getValue(value).map(v->state.setValue(property,v)).orElse(state);}
    private static <T extends Comparable<T>> BlockState step(BlockState state,Property<T> property){var values=List.copyOf(property.getPossibleValues());return state.setValue(property,values.get((values.indexOf(state.getValue(property))+1)%values.size()));}
    private static <T extends Comparable<T>> String value(BlockState state,Property<T> property){return property.getName(state.getValue(property));}
    /** "id[a=b,c=d]" naming every property, so the result matches exactly this state. */
    private String result(){
        if(properties.isEmpty())return id;
        var parts=new ArrayList<String>();for(var property:properties)parts.add(property.getName()+"="+value(state,property));
        return id+"["+String.join(",",parts)+"]";
    }
    private int rows(){return Math.min(MAX_ROWS,properties.size());}
    private int left(){return (width-WIDTH)/2;}
    private int top(){return (height-(rows()*ROW+84))/2;}
    @Override protected void init(){
        clearWidgets();if(block==null){addRenderableWidget(new FlatButton(left(),top()+40,WIDTH,22,"Close",this::onClose));return;}
        scroll=Math.clamp(scroll,0,Math.max(0,properties.size()-MAX_ROWS));
        for(int r=0;r<rows();r++){
            var property=properties.get(scroll+r);
            addRenderableWidget(new FlatButton(left()+130,top()+34+r*ROW,WIDTH-130,21,value(state,property),()->{state=step(state,property);init();}));
        }
        int y=top()+40+rows()*ROW+(properties.isEmpty()?24:0);
        addRenderableWidget(new FlatButton(left(),y,95,22,"Reset",()->{state=block.defaultBlockState();init();}));
        addRenderableWidget(new FlatButton(left()+105,y,95,22,"Cancel",this::onClose));
        addRenderableWidget(new FlatButton(left()+205,y,95,22,"Done",()->{done.accept(result());ClientCompat.setScreen(minecraft,parent);}));
    }
    @Override protected void drawBackground(Gfx g,int x,int y,float delta){}
    @Override protected void draw(Gfx g,int mouseX,int mouseY,float delta){
        if(parent instanceof EditorScreen editor)editor.suspended=true;
        drawUnder(parent,g,-1,-1,delta);
        int left=left(),top=top();
        g.fill(left-12,top-26+24,left+WIDTH+12,top+rows()*ROW+(properties.isEmpty()?24:0)+76,0xff172330);
        g.text(font,"Edit block state",left,top+4,0xffb5dcfc,false);g.text(font,font.plainSubstrByWidth(id,WIDTH-120),left+WIDTH-Math.min(font.width(id),WIDTH-120),top+4,0xff9db5cb,false);
        if(block==null)g.text(font,"Unknown block",left,top+22,0xffffdca8,false);
        else if(properties.isEmpty())g.text(font,"This block has no states.",left,top+36,0xff9db5cb,false);
        for(int r=0;r<rows();r++)g.text(font,properties.get(scroll+r).getName(),left,top+40+r*ROW,0xffdce6ef,false);
        drawWidgets(g,mouseX,mouseY,delta);
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){
        if(properties.size()>MAX_ROWS){scroll=Math.clamp(scroll+(int)-Math.signum(vertical),0,properties.size()-MAX_ROWS);init();}
        return true;
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){ClientCompat.setScreen(minecraft,parent);}
}
