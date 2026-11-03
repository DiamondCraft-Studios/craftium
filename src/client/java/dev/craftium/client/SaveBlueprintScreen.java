package dev.craftium.client;

import dev.craftium.client.compat.CraftiumScreen;
import dev.craftium.client.compat.Gfx;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Ctrl+P opens naming controls rather than silently overwriting an unnamed file. */
final class SaveBlueprintScreen extends CraftiumScreen {
    private final Screen parent;private EditBox name,author,tags;
    SaveBlueprintScreen(Screen parent){super(Component.literal("Save blueprint"));this.parent=parent;}
    @Override protected void init(){int x=(width-320)/2,y=(height-205)/2;
        name=field(x,y+35,"Name",ClientState.options.name,80);author=field(x,y+82,"Author",ClientState.options.author,80);tags=field(x,y+129,"Tags",ClientState.options.tags,256);
        addRenderableWidget(new FlatButton(x,y+169,153,22,"Save",()->{if(name.getValue().isBlank())return;var o=ClientState.options;o.name=name.getValue();o.author=author.getValue();o.tags=tags.getValue();CraftiumClient.send("blueprint_save",null,null);onClose();}));addRenderableWidget(new FlatButton(x+167,y+169,153,22,"Cancel",this::onClose));setInitialFocus(name);
    }
    private EditBox field(int x,int y,String label,String value,int limit){var field=new EditBox(font,x,y,320,22,Component.literal(label));field.setMaxLength(limit);field.setValue(value);addRenderableWidget(field);return field;}
    @Override protected void draw(Gfx g,int x,int y,float delta){g.fill(0,0,width,height,0xf510151c);int left=(width-320)/2,top=(height-205)/2;g.text(font,"SAVE BLUEPRINT",left,top,0xffdce6ef,false);g.text(font,"Name",left,top+24,0xff8595a8,false);g.text(font,"Author",left,top+71,0xff8595a8,false);g.text(font,"Tags",left,top+118,0xff8595a8,false);drawWidgets(g,x,y,delta);}
    @Override public void onClose(){minecraft.gui.setScreen(parent);}
}
