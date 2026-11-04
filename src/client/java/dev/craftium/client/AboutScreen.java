package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.client.compat.CraftiumScreen;
import dev.craftium.client.compat.Gfx;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;

/** A small pop-up over the editor naming the mod, its version and what it is. */
final class AboutScreen extends CraftiumScreen {
    private static final int W=300,H=150;
    private final EditorScreen parent;
    private final String version,description;
    private int left,top;
    AboutScreen(EditorScreen parent){
        super(Component.literal("About Craftium"));this.parent=parent;
        var mod=FabricLoader.getInstance().getModContainer("craftium");
        version=mod.map(m->m.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
        description=mod.map(m->m.getMetadata().getDescription()).orElse("");
    }
    @Override protected void init(){
        left=(width-W)/2;top=(height-H)/2;
        addRenderableWidget(new FlatButton(left+W/2-45,top+H-34,90,22,"Close",this::onClose));
    }
    @Override protected void drawBackground(Gfx g,int x,int y,float delta){}
    @Override protected void draw(Gfx g,int mouseX,int mouseY,float delta){
        parent.suspended=true;
        drawUnder(parent,g,-1,-1,delta);
        var o=ClientState.options;
        g.fill(left-8,top-8,left+W+8,top+H+8,0xff172330);g.outline(left-8,top-8,W+16,H+16,o.uiBorder);
        CraftiumBrand.draw(g,left+14,top+12,32);
        g.text(font,"CRAFTIUM",left+54,top+16,o.uiText,false);
        g.text(font,"Version "+version,left+54,top+30,o.uiMuted,false);
        int line=0;for(var row:font.split(Component.literal(description),W-28)){if(line>=4)break;g.text(font,row,left+14,top+58+line*11,o.uiMuted,false);line++;}
        drawWidgets(g,mouseX,mouseY,delta);
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){ClientCompat.setScreen(minecraft,parent);}
}
