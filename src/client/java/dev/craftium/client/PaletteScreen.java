package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.core.*;
import dev.craftium.client.compat.CraftiumScreen;
import dev.craftium.client.compat.Gfx;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import java.util.*;

/** Ordered, weighted palette with block search, inventory input and drag-to-reorder. */
public final class PaletteScreen extends CraftiumScreen {
    private final Screen parent;
    private final List<String> blocks=new ArrayList<>();
    private final List<Double> weights=new ArrayList<>();
    private List<String> results=List.of();
    private int page,resultPage,left,top,cw,ch,drag=-1;
    private float scale=1;
    private String query="",dragBlock,message="";
    private boolean dragging;
    public PaletteScreen(Screen parent){super(Component.literal("Craftium Palette"));this.parent=parent;blocks.addAll(MaterialList.split(ClientState.options.materials));if(blocks.isEmpty())blocks.add(ClientState.options.block);for(int i=0;i<blocks.size();i++)weights.add(i<ClientState.options.materialWeights.size()?ClientState.options.materialWeights.get(i):1d);}
    @Override protected void init(){
        scale=Math.min(1f,Math.min(width/700f,height/460f));cw=Math.round(width/scale);ch=Math.round(height/scale);left=(cw-660)/2;top=(ch-420)/2;clearWidgets();page=Math.clamp(page,0,Math.max(0,(blocks.size()-1)/8));
        var search=new EditBox(font,left+378,top+31,270,21,Component.literal("Search blocks"));search.setMaxLength(128);search.setValue(query);search.setResponder(s->{query=s;resultPage=0;search();});addRenderableWidget(search);search();
        for(int i=page*8;i<Math.min(blocks.size(),page*8+8);i++){int index=i,y=top+45+(i-page*8)*36;var slider=new FlatSlider(left+40,y+10,245,20,Component.empty(),(Math.min(10,weights.get(i))-.1)/9.9){protected void updateMessage(){setMessage(Component.literal(String.format(Locale.ROOT,"Weight %.2f",weights.get(index))));}protected void applyValue(){weights.set(index,.1+value*9.9);updateMessage();}};slider.setRaw((Math.min(10,weights.get(i))-.1)/9.9);slider.range(.1,10,1);addRenderableWidget(slider);button("×",left+319,y+7,23,23,()->{if(blocks.size()>1){blocks.remove(index);weights.remove(index);init();}});}
        button("‹",left,top+342,27,21,()->{page--;init();});button("›",left+32,top+342,27,21,()->{page++;init();});
        button("Sort dark → light",left+95,top+342,150,21,()->{BlockColours.build();var order=new ArrayList<Integer>();for(int i=0;i<blocks.size();i++)order.add(i);order.sort(Comparator.comparingDouble(i->{var e=BlockColours.get(blocks.get(i));return e==null?0:e.lab()[0];}));var copy=List.copyOf(blocks);var values=List.copyOf(weights);for(int i=0;i<order.size();i++){blocks.set(i,copy.get(order.get(i)));weights.set(i,values.get(order.get(i)));}init();});
        button("‹",left+378,top+253,27,21,()->{resultPage--;search();});button("›",left+410,top+253,27,21,()->{resultPage++;search();});
        button("Save palette",left,top+382,145,24,()->{apply();ClientLibrary.savePalette(ClientState.options.name);message="Saved: "+ClientState.options.name;});
        button("Load palette",left+153,top+382,145,24,()->{ClientLibrary.loadPalette(ClientState.options.name);blocks.clear();blocks.addAll(MaterialList.split(ClientState.options.materials));weights.clear();for(int i=0;i<blocks.size();i++)weights.add(i<ClientState.options.materialWeights.size()?ClientState.options.materialWeights.get(i):1d);init();});
        button("Use palette",left+502,top+382,146,24,this::onClose);
    }
    private void search(){results=java.util.stream.StreamSupport.stream(BuiltInRegistries.BLOCK.spliterator(),false).filter(b->b.asItem()!=net.minecraft.world.item.Items.AIR).map(b->BuiltInRegistries.BLOCK.getKey(b).toString()).filter(id->id.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))).sorted().toList();resultPage=Math.clamp(resultPage,0,Math.max(0,(results.size()-1)/24));}
    private void button(String text,int x,int y,int w,int h,Runnable action){addRenderableWidget(new FlatButton(x,y,w,h,text,()->{try{action.run();}catch(Exception e){message=e.getMessage();}}));}
    private void apply(){ClientState.options.materials=String.join(",",blocks);ClientState.options.materialWeights=new ArrayList<>(weights);ClientState.save();}
    private void item(Gfx g,String block,int x,int y){var id=dev.craftium.compat.Compat.tryParse(block.split("\\[",2)[0]);if(id!=null)BuiltInRegistries.BLOCK.getOptional(id).ifPresent(b->g.item(b.asItem().getDefaultInstance(),x,y));}
    @Override protected void drawBackground(Gfx g,int x,int y,float delta){}
    @Override protected void draw(Gfx g,int mouseX,int mouseY,float delta){
        int mx=(int)(mouseX/scale),my=(int)(mouseY/scale);g.pose().pushMatrix();g.pose().scale(scale,scale);g.fill(0,0,cw,ch,0xef101923);g.fill(left-12,top-25,left+672,top+420,0xff172330);g.text(font,"Palette",left,top-17,0xffb5dcfc,false);g.text(font,"DARK → LIGHT  ·  drag icons to reorder",left,top+12,0xff9db5cb,false);g.text(font,"SEARCH BLOCKS",left+378,top+12,0xff9db5cb,false);
        for(int i=page*8;i<Math.min(blocks.size(),page*8+8);i++){int y=top+45+(i-page*8)*36;boolean over=mx>=left&&mx<left+347&&my>=y&&my<y+34;g.fill(left,y,left+347,y+34,i==drag?0xff365576:over?Hover.fill(0xff213146):0xff213146);item(g,blocks.get(i),left+8,y+9);g.text(font,font.plainSubstrByWidth(blocks.get(i),270),left+40,y+1,0xffdce6ef,false);}
        for(int i=resultPage*24;i<Math.min(results.size(),resultPage*24+24);i++){int j=i-resultPage*24,x=left+378+(j%6)*45,y=top+65+(j/6)*46;boolean over=mx>=x&&mx<x+40&&my>=y&&my<y+39;g.fill(x,y,x+40,y+39,over?Hover.fill(0xff223247):0xff223247);if(over){g.outline(x,y,40,39,ClientState.options.uiAccent);message=results.get(i);}item(g,results.get(i),x+12,y+7);}
        g.text(font,(page+1)+" / "+Math.max(1,(blocks.size()+7)/8),left+62,top+349,0xff9db5cb,false);g.text(font,results.size()+" blocks · page "+(resultPage+1),left+445,top+260,0xff9db5cb,false);
        g.text(font,"YOUR HOTBAR · click or drag blocks",left+378,top+292,0xff9db5cb,false);for(int i=0;i<9;i++){int x=left+378+i*30,y=top+309;boolean over=mx>=x&&mx<x+27&&my>=y&&my<y+27;g.fill(x,y,x+27,y+27,over?Hover.fill(0xff25364a):0xff25364a);if(over)g.outline(x,y,27,27,ClientState.options.uiAccent);g.item(minecraft.player.getInventory().getItem(i),x+5,y+5);}
        drawWidgets(g,mx,my,delta);if(dragBlock!=null){item(g,dragBlock,mx-8,my-8);}g.text(font,font.plainSubstrByWidth(message,650),left,top+410,0xffffdca8,false);g.pose().popMatrix();
    }
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean twice){double x=event.x()/scale,y=event.y()/scale;
        for(int i=page*8;i<Math.min(blocks.size(),page*8+8);i++){int by=top+45+(i-page*8)*36;if(x>=left&&x<left+35&&y>=by&&y<by+34){drag=i;dragBlock=blocks.get(i);dragging=false;return true;}}
        for(int i=resultPage*24;i<Math.min(results.size(),resultPage*24+24);i++){int j=i-resultPage*24,bx=left+378+(j%6)*45,by=top+65+(j/6)*46;if(x>=bx&&x<bx+40&&y>=by&&y<by+39){drag=-1;dragBlock=results.get(i);dragging=false;return true;}}
        for(int i=0;i<9;i++){int bx=left+378+i*30,by=top+309;if(x>=bx&&x<bx+27&&y>=by&&y<by+27){var stack=minecraft.player.getInventory().getItem(i);if(stack.getItem() instanceof BlockItem item){drag=-1;dragBlock=BuiltInRegistries.BLOCK.getKey(item.getBlock()).toString();dragging=false;}return true;}}
        return super.mouseClicked(new MouseButtonEvent(x,y,event.buttonInfo()),twice);
    }
    @Override public boolean mouseDragged(MouseButtonEvent event,double dx,double dy){if(dragBlock!=null){dragging=true;return true;}return super.mouseDragged(new MouseButtonEvent(event.x()/scale,event.y()/scale,event.buttonInfo()),dx/scale,dy/scale);}
    @Override public boolean mouseReleased(MouseButtonEvent event){if(dragBlock!=null){int index=Math.clamp(page*8+(int)((event.y()/scale-top-45)/36),0,blocks.size());boolean inList=event.x()/scale>=left&&event.x()/scale<left+347;if(drag>=0&&dragging&&inList){String block=blocks.remove(drag);double weight=weights.remove(drag);if(index>drag)index--;index=Math.clamp(index,0,blocks.size());blocks.add(index,block);weights.add(index,weight);}else if(drag<0&&(!dragging||inList)&&blocks.size()<256){if(!inList)index=blocks.size();blocks.add(index,dragBlock);weights.add(index,1d);}else if(drag>=0&&!dragging)ClientState.pick(dragBlock);drag=-1;dragBlock=null;dragging=false;init();return true;}return super.mouseReleased(new MouseButtonEvent(event.x()/scale,event.y()/scale,event.buttonInfo()));}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){if(x/scale<left+360){page+=(int)-Math.signum(vertical);init();}else{resultPage+=(int)-Math.signum(vertical);search();}return true;}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){apply();ClientCompat.setScreen(minecraft,parent);}
}
