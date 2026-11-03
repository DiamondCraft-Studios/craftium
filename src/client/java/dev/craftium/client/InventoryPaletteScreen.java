package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.client.compat.CraftiumScreen;
import dev.craftium.client.compat.Gfx;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;

final class InventoryPaletteScreen extends CraftiumScreen {
    private final Screen parent;
    private int left,top;
    InventoryPaletteScreen(Screen parent){super(Component.literal("Craftium Inventory"));this.parent=parent;}
    @Override protected void init(){left=(width-276)/2;top=(height-190)/2;addRenderableWidget(new FlatButton(left+156,top+163,120,22,"Return",this::onClose));}
    @Override protected void drawBackground(Gfx g,int x,int y,float delta){}
    @Override protected void draw(Gfx g,int x,int y,float delta){g.fill(0,0,width,height,0xee101923);g.fill(left-12,top-25,left+288,top+198,0xff172330);g.text(font,"CRAFTIUM  /  INVENTORY",left,top-16,0xffb5dcfc,false);
        for(int i=0;i<36;i++){int row=i<9?3:(i-9)/9,col=i%9,bx=left+col*30,by=top+row*30+(row==3?9:0);g.fill(bx,by,bx+26,by+26,0xff25364a);var item=minecraft.player.getInventory().getItem(i);if(!item.isEmpty()){g.item(item,bx+5,by+5);if(x>=bx&&x<bx+26&&y>=by&&y<by+26)g.text(font,item.getHoverName(),left,top+145,0xffffdca8,false);}}
        g.text(font,"Click a block to use it in the editor",left,top+132,0xff9db5cb,false);drawWidgets(g,x,y,delta);
    }
    @Override public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event,boolean twice){for(int i=0;i<36;i++){int row=i<9?3:(i-9)/9,col=i%9,bx=left+col*30,by=top+row*30+(row==3?9:0);if(event.x()>=bx&&event.x()<bx+26&&event.y()>=by&&event.y()<by+26){var item=minecraft.player.getInventory().getItem(i);if(item.getItem() instanceof BlockItem block){ClientState.pick(BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString());onClose();}return true;}}return super.mouseClicked(event,twice);}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){ClientCompat.setScreen(minecraft,parent);}
}
