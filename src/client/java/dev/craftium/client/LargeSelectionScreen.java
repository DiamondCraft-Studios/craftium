package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.client.compat.CraftiumScreen;
import dev.craftium.client.compat.Gfx;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** A selection this big is most likely a misclick, so it has to be confirmed; cancelling gives the old selection back. */
final class LargeSelectionScreen extends CraftiumScreen {
    private final EditorScreen parent;
    private final int count;
    LargeSelectionScreen(EditorScreen parent,int count){super(Component.literal("Confirm selection"));this.parent=parent;this.count=count;}
    @Override protected void init(){
        int x=width/2,y=height/2;
        addRenderableWidget(new FlatButton(x-107,y+8,100,22,"Confirm",this::confirm));
        addRenderableWidget(new FlatButton(x+7,y+8,100,22,"Cancel",this::cancel));
    }
    private void confirm(){ClientCompat.setScreen(minecraft,parent);}
    private void cancel(){CraftiumClient.send("selection_restore",null,null);ClientCompat.setScreen(minecraft,parent);}
    @Override protected void draw(Gfx g,int mouseX,int mouseY,float delta){
        g.fill(0,0,width,height,0x99000000);
        int left=width/2-125,top=height/2-48;
        g.fill(left,top,left+250,top+86,0xf510151c);g.outline(left,top,250,86,0xff599cda);
        String title="Selected "+count+" blocks, confirm?";
        g.text(font,title,width/2-font.width(title)/2,top+16,0xffdce6ef,false);
        String hint="Large selections are often a misclick.";
        g.text(font,hint,width/2-font.width(hint)/2,top+32,0xff8b9bb0,false);
    }
    @Override public boolean keyPressed(KeyEvent event){
        if(event.key()==GLFW.GLFW_KEY_ENTER){confirm();return true;}
        if(event.key()==GLFW.GLFW_KEY_ESCAPE){cancel();return true;}
        return super.keyPressed(event);
    }
    @Override public void onClose(){cancel();}
}
