package dev.craftium.client;

import dev.craftium.core.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.*;
import java.util.*;

/** Original showcase world, rendered by Minecraft without post-processing. */
public final class GalleryCapture {
    private static final int FLAGS=2|16;
    private static Block block(String id){return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(net.minecraft.resources.Identifier.parse("minecraft:"+id)).orElseThrow();}
    private static int top(int x,int z){double river=Math.abs(x-Math.sin(z*.17)*2);if(river<2)return 2;return (int)Math.round(4+7*Math.exp(-(Math.pow(x+12,2)+Math.pow(z+7,2))/95)+9*Math.exp(-(Math.pow(x-13,2)+Math.pow(z+8,2))/70)+1.2*Math.sin(x*.24)*Math.cos(z*.2));}
    private static void put(ServerLevel level,int x,int y,int z,Block block){level.setBlock(new BlockPos(x,y,z),block.defaultBlockState(),FLAGS);}
    private static void tree(ServerLevel level,int x,int z){int y=top(x,z)+1;for(int h=0;h<7;h++)put(level,x,y+h,z,Blocks.SPRUCE_LOG);for(int h=2;h<=7;h++){int r=h>=6?1:h%2==0?3:2;for(int dx=-r;dx<=r;dx++)for(int dz=-r;dz<=r;dz++)if(Math.abs(dx)+Math.abs(dz)<=r+1&&(dx!=0||dz!=0))put(level,x+dx,y+h,z+dz,Blocks.SPRUCE_LEAVES);}}
    private static void scene(ServerLevel level){
        for(int x=-26;x<=26;x++)for(int z=-21;z<=21;z++){
            double edge=x*x/(26d*26)+z*z/(21d*21);if(edge>1)continue;int high=top(x,z),bottom=(int)Math.round(-7+edge*9+Math.sin(x*.3+z*.4));
            for(int y=bottom;y<=high;y++){
                boolean cliff=high>8&&Math.abs(x-13)<9&&z<0;Block block=y==high?(cliff?Blocks.STONE:Blocks.GRASS_BLOCK):y>high-3&&!cliff?Blocks.DIRT:(Math.floorMod(x*31+y*17+z*7,13)<3?Blocks.ANDESITE:Blocks.STONE);
                put(level,x,y,z,block);
            }
            if(high==2){put(level,x,2,z,Blocks.GRAVEL);put(level,x,3,z,Blocks.WATER);}
            if(edge<.85&&high<8&&Math.abs(x)>4&&Math.floorMod(x*71+z*13,37)==0)put(level,x,high+1,z,Math.floorMod(x+z,2)==0?Blocks.OXEYE_DAISY:Blocks.SHORT_GRASS);
        }
        // A small stone watchtower with a copper roof and windows.
        int cx=-12,cz=-7,base=top(cx,cz)+1;
        for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++)for(int y=base-3;y<=base;y++)put(level,cx+dx,y,cz+dz,Blocks.STONE_BRICKS);
        for(int h=1;h<=10;h++)for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++){
            if(Math.abs(dx)!=3&&Math.abs(dz)!=3)continue;
            boolean window=(h==4||h==5||h==8)&&((dx==0&&Math.abs(dz)==3)||(dz==0&&Math.abs(dx)==3));
            put(level,cx+dx,base+h,cz+dz,window?block("cyan_stained_glass"):Math.abs(dx)==3&&Math.abs(dz)==3?Blocks.POLISHED_ANDESITE:Blocks.STONE_BRICKS);
        }
        for(int h=0;h<5;h++){int r=4-h;for(int dx=-r;dx<=r;dx++)for(int dz=-r;dz<=r;dz++)if(Math.abs(dx)==r||Math.abs(dz)==r||h==0)put(level,cx+dx,base+11+h,cz+dz,block("weathered_copper"));}
        put(level,cx,base+16,cz,block("lightning_rod"));
        // Timber workshop on the near bank.
        int hx=13,hz=8,hy=top(hx,hz)+1;
        for(int dx=-4;dx<=4;dx++)for(int dz=-3;dz<=3;dz++)put(level,hx+dx,hy,hz+dz,Blocks.COBBLESTONE);
        for(int h=1;h<=4;h++)for(int dx=-3;dx<=3;dx++)for(int dz=-2;dz<=2;dz++)if(Math.abs(dx)==3||Math.abs(dz)==2)put(level,hx+dx,hy+h,hz+dz,Math.abs(dx)==3&&Math.abs(dz)==2?Blocks.STRIPPED_SPRUCE_LOG:h==2&&dx%2==0?Blocks.GLASS_PANE:Blocks.SPRUCE_PLANKS);
        for(int step=0;step<4;step++)for(int dx=-4;dx<=4;dx++)for(int sign:new int[]{-1,1})put(level,hx+dx,hy+5+step,hz+sign*(3-step),Blocks.DARK_OAK_PLANKS);
        for(int h=0;h<5;h++)put(level,hx+2,hy+6+h,hz,Blocks.BRICKS);
        // A bridge and path connect both banks.
        for(int x=-7;x<=7;x++)for(int z=-1;z<=1;z++)put(level,x,5,z,Blocks.SPRUCE_PLANKS);
        for(int x=-7;x<=7;x++)if(x%2==0)for(int z:new int[]{-2,2})put(level,x,6,z,Blocks.SPRUCE_FENCE);
        for(int x:new int[]{-6,6})for(int z:new int[]{-2,2}){for(int y=2;y<=7;y++)put(level,x,y,z,Blocks.STRIPPED_SPRUCE_LOG);put(level,x,8,z,Blocks.LANTERN);}
        for(int x=-22;x<=22;x++)if(Math.abs(x)>7)for(int dz=-1;dz<=1;dz++){int z=(int)Math.round(Math.sin(x*.13)*3)+dz,y=top(x,z);put(level,x,y,z,Blocks.DIRT_PATH);}
        for(int[] t:new int[][]{{-20,4},{-17,11},{-8,14},{7,-15},{20,-7},{20,2},{-19,-11}})tree(level,t[0],t[1]);
    }
    private static void request(ClientGameTestContext c,TestSingleplayerContext w,String action,Point point){
        try{Thread.sleep(100);}catch(InterruptedException e){throw new AssertionError(e);}
        c.runOnClient(mc->CraftiumClient.send(action,point,new Point(0,1,0)));w.getConnection().waitForServerboundPackets();c.waitTicks(5);w.getConnection().waitForClientboundPackets();c.waitFor(mc->!ClientState.status.busy(),300);w.getConnection().waitForClientboundPackets();
        if(c.computeOnClient(mc->ClientState.status.error()))throw new AssertionError(c.computeOnClient(mc->ClientState.status.message()));
    }
    private static void capture(ClientGameTestContext c,String name){c.runOnClient(mc->{ClientState.notice="";ClientState.noticeUntil=0;mc.gui.hud.getChat().clearMessages(true);});c.waitTicks(8);System.out.println("CRAFTIUM_GALLERY "+c.takeScreenshot(name));}
    public static void run(ClientGameTestContext c,TestSingleplayerContext w){
        w.getServer().runOnServer(server->scene(server.overworld()));
        w.getServer().runCommand("time set 5500");w.getServer().runCommand("weather clear");w.getServer().runCommand("tp @a 30 30 34 138 26");
        w.getServer().runOnServer(server->{var p=server.getPlayerList().getPlayers().getFirst();p.getAbilities().flying=true;p.onUpdateAbilities();Block[] hotbar={Blocks.STONE_BRICKS,Blocks.ANDESITE,block("weathered_copper"),Blocks.SPRUCE_PLANKS,Blocks.DARK_OAK_PLANKS,Blocks.GRASS_BLOCK,Blocks.MOSS_BLOCK,Blocks.SPRUCE_LEAVES,Blocks.LANTERN};for(int i=0;i<hotbar.length;i++)p.getInventory().setItem(i,hotbar[i].asItem().getDefaultInstance());p.inventoryMenu.broadcastChanges();});
        c.waitTicks(25);w.getConnection().waitForChunksDownload();w.getConnection().waitForChunksRender();
        c.runOnClient(mc->{mc.getWindow().setWindowed(1920,1080);mc.options.guiScale().set(2);mc.options.fov().set(70);mc.resizeGui();ClientState.options=new Options();ClientState.palette.clear();ClientState.palette.addAll(List.of("minecraft:stone_bricks","minecraft:andesite","minecraft:weathered_copper","minecraft:spruce_planks","minecraft:dark_oak_planks","minecraft:grass_block","minecraft:stone","minecraft:moss_block"));WorkspaceLayout.windows.clear();CraftiumClient.open(false);});c.waitTicks(12);
        int base=top(-12,-7)+1;
        c.runOnClient(mc->{var o=ClientState.options;o.tool=Tool.BOX_SELECT;o.position1=new Point(-16,base-3,-11);o.position2=new Point(-8,base+16,-3);o.block="minecraft:stone_bricks";mc.gui.setScreen(new EditorScreen(false));});request(c,w,"select_box",null);capture(c,"craftium-gallery-01-editor");
        request(c,w,"copy",null);c.runOnClient(mc->{PlacementPreview.begin(new Point(4,7,-12));mc.gui.setScreen(new EditorScreen(false));});capture(c,"craftium-gallery-02-placement");
        c.runOnClient(mc->PlacementPreview.cancel());request(c,w,"clear",null);
        c.runOnClient(mc->{var o=ClientState.options;o.tool=Tool.ROCK;o.block="minecraft:stone";o.radius=4;o.density=.45;mc.gui.setScreen(new EditorScreen(false));});
        request(c,w,"use",new Point(13,top(13,-8)+1,-8));
        c.getInput().setCursorPos(1180,450);c.waitTicks(8);capture(c,"craftium-gallery-03-terrain");
        c.runOnClient(mc->{ClientState.options.materials="minecraft:deepslate,minecraft:tuff,minecraft:andesite,minecraft:stone,minecraft:calcite";ClientState.options.materialWeights=new ArrayList<>(List.of(1d,2d,4d,2d,1d));ClientState.options.maskExpression="all(surface,not(block(minecraft:stone_bricks)),y(4,32))";mc.gui.setScreen(new MaskScreen(mc.gui.screen()));});capture(c,"craftium-gallery-04-masks");
        c.runOnClient(mc->{mc.gui.setScreen(new EditorScreen(false));var palette=new PaletteScreen(mc.gui.screen());mc.gui.setScreen(palette);for(var child:palette.children())if(child instanceof net.minecraft.client.gui.components.EditBox field)field.setValue("stone");});capture(c,"craftium-gallery-05-palette");
        c.runOnClient(mc->{mc.gui.setScreen(new EditorScreen(false));var colour=new ColourScreen(mc.gui.screen());mc.gui.setScreen(colour);var fields=colour.children().stream().filter(child->child instanceof net.minecraft.client.gui.components.EditBox).map(child->(net.minecraft.client.gui.components.EditBox)child).toList();fields.get(1).setValue("minecraft:calcite");});capture(c,"craftium-gallery-06-colour-studio");
        c.runOnClient(mc->{mc.gui.setScreen(new EditorScreen(false));mc.gui.setScreen(new EditorKeybindScreen(mc.gui.screen()));});capture(c,"craftium-gallery-07-keybinds");
        c.runOnClient(mc->{mc.gui.setScreen(null);BuilderMode.select(Options.BuilderTool.CLONE);});c.getInput().holdAlt();c.waitTicks(4);capture(c,"craftium-gallery-08-builder");c.getInput().releaseAlt();
        c.runOnClient(mc->{BuilderMode.reset();mc.gui.setScreen(null);});
        System.out.println("CRAFTIUM_GALLERY_PASS original_scene actual_editor placement rock masks palette colour keybinds builder");
    }
}
