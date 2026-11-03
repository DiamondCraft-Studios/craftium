package dev.craftium.client;

import dev.craftium.core.*;
import dev.craftium.core.EditorControls.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.client.input.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;
import static org.lwjgl.glfw.GLFW.*;

/** Tests real screen dispatch and native input in an isolated Creative world. */
public final class KeybindGameTest {
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static void key(ClientGameTestContext c,int key,int modifiers){c.runOnClient(mc->mc.gui.screen().keyPressed(new KeyEvent(key,0,modifiers)));}
    private static void settle(ClientGameTestContext c,TestSingleplayerContext w){w.getConnection().waitForServerboundPackets();c.waitTicks(5);w.getConnection().waitForClientboundPackets();c.waitFor(mc->!ClientState.status.busy(),200);w.getConnection().waitForClientboundPackets();}
    private static void pause(){try{Thread.sleep(100);}catch(InterruptedException e){throw new AssertionError(e);}}
    private static void request(ClientGameTestContext c,TestSingleplayerContext w,String action,Point p){pause();c.runOnClient(mc->CraftiumClient.send(action,p,new Point(0,1,0)));settle(c,w);require(c.computeOnClient(mc->!ClientState.status.error()),"Request failed: "+c.computeOnClient(mc->ClientState.status.message()));}
    public static void run(ClientGameTestContext c,TestSingleplayerContext w){
        c.runOnClient(mc->{EditorBindings.reset();for(var mapping:CraftiumClient.toolKeys.values())mapping.setKey(mapping.getDefaultKey());net.minecraft.client.KeyMapping.resetMapping();mc.getWindow().setWindowed(1200,800);mc.options.guiScale().set(2);mc.resizeGui();mc.player.getAbilities().flying=true;CraftiumClient.open(false);});c.waitTicks(5);
        Tool[] tools={Tool.MAGIC_SELECT,Tool.BOX_SELECT,Tool.FREEHAND_SELECT,Tool.LASSO_SELECT,Tool.PAINTER,Tool.NOISE_PAINTER,Tool.FREEHAND_DRAW,Tool.ROCK,Tool.WELD,Tool.MELT,Tool.ELEVATION,Tool.SMOOTH,Tool.EXTRUDE};
        String letters="MBNLPOGHJKEUZ";
        for(int i=0;i<tools.length;i++){c.getInput().pressKey(letters.charAt(i));Tool expected=tools[i];require(c.computeOnClient(mc->ClientState.options.tool==expected),"Native tool shortcut failed: "+expected);}
        require(c.computeOnClient(mc->!ClientState.options.gizmoLocal),"L changed local axes");
        c.runOnClient(mc->{ClientState.options.tool=Tool.PATH;ClientState.options.nodes.clear();ClientState.options.nodes.add(new Point(2,10,2));});
        key(c,'E',0);require(c.computeOnClient(mc->ClientState.options.tool==Tool.PATH&&ClientState.options.nodes.size()==2&&ClientState.options.nodes.getFirst().equals(ClientState.options.nodes.getLast())),"E did not extrude the path point before selecting Elevation");
        c.runOnClient(mc->ClientState.options.nodes.clear());key(c,'E',0);require(c.computeOnClient(mc->ClientState.options.tool==Tool.ELEVATION),"E did not select Elevation without a path point");
        c.getInput().holdKey(GLFW_KEY_E);c.waitTicks(4);c.getInput().releaseKey(GLFW_KEY_E);
        key(c,GLFW_KEY_F4,0);require(c.computeOnClient(mc->mc.gui.screen() instanceof EditorScreen),"F4 should be unbound");
        key(c,'B',2);require(c.computeOnClient(mc->ClientState.options.showBiomes&&ClientState.options.tool==Tool.ELEVATION),"Ctrl+B selected Box instead of toggling biomes");key(c,'B',2);
        c.runOnClient(mc->{ClientState.options.tool=Tool.PAINTER;ClientState.options.radius=5;ClientState.options.entityUuid="";});
        // A Ctrl+RMB gesture must pan, without entering a painting stroke.
        require(c.computeOnClient(mc->{var s=(EditorScreen)mc.gui.screen();var before=mc.player.position();double x=s.width/2d,y=s.height/2d;var start=new MouseButtonEvent(x,y,new MouseButtonInfo(1,2));s.mouseClicked(start,false);s.mouseDragged(new MouseButtonEvent(x+30,y+10,start.buttonInfo()),30,10);s.mouseReleased(start);return before.distanceTo(mc.player.position())>.01&&ClientState.strokeId==0;}),"Ctrl+RMB did not pan cleanly");
        require(c.computeOnClient(mc->{var s=(EditorScreen)mc.gui.screen();double x=s.width/2d,y=s.height/2d;var start=new MouseButtonEvent(x,y,new MouseButtonInfo(2,2));s.mouseClicked(start,false);s.mouseDragged(new MouseButtonEvent(x+24,y,start.buttonInfo()),24,0);s.mouseReleased(start);return ClientState.options.radius==8&&ClientState.strokeId==0;}),"Ctrl+MMB did not adjust radius cleanly");
        require(c.computeOnClient(mc->{var s=(EditorScreen)mc.gui.screen();var before=mc.player.position();double x=s.width/2d,y=s.height/2d;var start=new MouseButtonEvent(x,y,new MouseButtonInfo(0,2));s.mouseClicked(start,false);s.mouseDragged(new MouseButtonEvent(x+30,y,start.buttonInfo()),30,0);s.mouseReleased(start);return before.distanceTo(mc.player.position())>.01;}),"Ctrl+LMB did not orbit");
        c.runOnClient(mc->{PlacementPreview.origin=new Point(5,10,5);mc.player.setYRot(90);});key(c,GLFW_KEY_UP,0);key(c,GLFW_KEY_RIGHT,0);key(c,GLFW_KEY_PAGE_UP,0);
        require(c.computeOnClient(mc->PlacementPreview.origin.equals(new Point(4,11,4))),"Arrow/PageUp nudges did not use camera-relative axes");key(c,GLFW_KEY_DELETE,0);require(c.computeOnClient(mc->!PlacementPreview.active()),"Delete did not cancel placement");
        // Persist a custom shortcut and actually dispatch it after reloading.
        c.runOnClient(mc->{EditorBindings.set(Action.SHOW_BIOMES,Binding.key(GLFW_KEY_F8,2));EditorBindings.reset();EditorBindings.load();});key(c,GLFW_KEY_F8,2);require(c.computeOnClient(mc->ClientState.options.showBiomes),"Rebound shortcut did not persist");
        c.runOnClient(mc->{EditorBindings.reset();EditorBindings.save();ClientState.options.showBiomes=false;ClientState.options.tool=Tool.BOX_SELECT;ClientState.options.position1=new Point(2,10,2);ClientState.options.position2=new Point(3,10,2);ClientState.options.block="minecraft:gold_block";});
        request(c,w,"select_box",null);pause();key(c,'F',2);settle(c,w);
        require(w.getServer().computeOnServer(server->server.overworld().getBlockState(new BlockPos(2,10,2)).is(Blocks.GOLD_BLOCK)),"Ctrl+F did not fill selection");
        pause();key(c,'C',2);settle(c,w);require(c.computeOnClient(mc->ClientState.status.clipboardSize()==2),"Ctrl+C did not copy");
        key(c,'P',2);require(c.computeOnClient(mc->mc.gui.screen() instanceof SaveBlueprintScreen),"Ctrl+P did not open Save Blueprint");c.runOnClient(mc->mc.gui.screen().onClose());
        c.runOnClient(mc->PlacementPreview.begin(new Point(6,10,6)));pause();key(c,'R',2);settle(c,w);require(c.computeOnClient(mc->ClientState.status.message().contains("rotated")),"Ctrl+R ran Quick Replace during placement");
        pause();key(c,'F',2);settle(c,w);require(c.computeOnClient(mc->ClientState.status.message().contains("mirrored")),"Ctrl+F ran Quick Fill during placement");key(c,GLFW_KEY_DELETE,0);
        pause();key(c,'J',2);settle(c,w);require(c.computeOnClient(mc->PlacementPreview.active()),"Ctrl+J did not generate a duplicate placement");key(c,GLFW_KEY_DELETE,0);
        pause();key(c,'X',2);settle(c,w);require(c.computeOnClient(mc->PlacementPreview.active()),"Ctrl+X did not generate a cut placement");require(w.getServer().computeOnServer(server->server.overworld().getBlockState(new BlockPos(2,10,2)).isAir()),"Ctrl+X did not cut blocks");key(c,GLFW_KEY_DELETE,0);
        for(int axis=0;axis<3;axis++){
            Point a=new Point(4,12,4),b=a.add(axis==0?1:0,axis==1?1:0,axis==2?1:0);int selectedAxis=axis;
            w.getServer().runOnServer(server->{var level=server.overworld();level.setBlock(new BlockPos(a.x(),a.y(),a.z()),Blocks.GOLD_BLOCK.defaultBlockState(),3);level.setBlock(new BlockPos(b.x(),b.y(),b.z()),Blocks.STONE.defaultBlockState(),3);var player=server.getPlayerList().getPlayers().getFirst();player.setYRot(selectedAxis==0?-90:0);player.setXRot(selectedAxis==1?-90:0);});
            c.runOnClient(mc->{ClientState.options.position1=a;ClientState.options.position2=b;mc.player.setYRot(selectedAxis==0?-90:0);mc.player.setXRot(selectedAxis==1?-90:0);});
            request(c,w,"select_box",null);request(c,w,"copy",null);c.runOnClient(mc->PlacementPreview.begin(new Point(8,12,8)));pause();key(c,'F',2);settle(c,w);pause();key(c,GLFW_KEY_ENTER,0);settle(c,w);
            require(w.getServer().computeOnServer(server->{var level=server.overworld();return level.getBlockState(new BlockPos(8,12,8)).is(Blocks.STONE)&&level.getBlockState(new BlockPos(8+(selectedAxis==0?1:0),12+(selectedAxis==1?1:0),8+(selectedAxis==2?1:0))).is(Blocks.GOLD_BLOCK);}),"Ctrl+F did not flip along facing axis "+axis);
        }
        c.runOnClient(mc->{mc.gui.setScreen(null);BuilderMode.reset();});c.getInput().holdAlt();c.waitTicks(4);require(c.computeOnClient(mc->mc.gui.screen() instanceof EditorScreen),"Holding Alt did not open context menu");c.getInput().releaseAlt();c.waitTicks(4);require(c.computeOnClient(mc->mc.gui.screen()==null),"Releasing Alt did not close context menu");
        c.getInput().pressKey(GLFW_KEY_0);c.waitTick();c.getInput().pressKey(GLFW_KEY_0);c.waitTick();require(c.computeOnClient(mc->BuilderMode.active()),"0 toggled off the tenth slot");
        c.getInput().holdAlt();c.waitTicks(3);require(c.computeOnClient(mc->mc.gui.screen()==null&&BuilderMode.active()),"Alt opened editor instead of builder tool picker");c.getInput().scroll(1);c.getInput().releaseAlt();c.waitTicks(3);c.getInput().pressKey(GLFW_KEY_1);c.waitTick();require(c.computeOnClient(mc->!BuilderMode.active()),"1 did not return to hotbar");
        // Copy exact stair properties into the held item via the real ingame handler.
        w.getServer().runCommand("setblock 2 10 2 minecraft:oak_stairs[facing=east,half=top]");pause();c.runOnClient(mc->{mc.hitResult=new BlockHitResult(new Vec3(2.5,11,2.5),Direction.UP,new BlockPos(2,10,2),false);CraftiumClient.ingameKey(new KeyEvent('C',0,2),GLFW_PRESS);});settle(c,w);
        require(w.getServer().computeOnServer(server->{var stack=server.getPlayerList().getPlayers().getFirst().getMainHandItem();var props=stack.get(DataComponents.BLOCK_STATE);return props!=null&&"east".equals(props.properties().get("facing"))&&"top".equals(props.properties().get("half"));}),"Ingame Ctrl+C lost block properties");
        c.runOnClient(mc->{ClientState.options.entityType="block_display";ClientState.options.entityNbt="{block_state:{Name:\"minecraft:diamond_block\"}}";});request(c,w,"entity_spawn",new Point(6,12,6));
        String entity=c.computeOnClient(mc->ClientState.options.entityUuid);
        c.runOnClient(mc->{ClientState.options.gizmoTarget=Options.GizmoTarget.ENTITY;CraftiumClient.open(false);});pause();key(c,'C',2);settle(c,w);
        require(c.computeOnClient(mc->ClientState.status.clipboardSize()==0&&ClientState.status.clipboardEntityCount()==1),"Ctrl+C copied blocks instead of the selected display");
        c.runOnClient(mc->ClientState.options.name="Integration/EntityOnlyKeybinds");request(c,w,"blueprint_save",null);request(c,w,"blueprint_load",null);
        require(c.computeOnClient(mc->ClientState.status.clipboardEntityCount()==1&&ClientState.status.clipboardSize()==0),"Entity-only blueprint lost its clipboard");
        pause();key(c,'J',2);settle(c,w);require(c.computeOnClient(mc->PlacementPreview.origin!=null&&PlacementPreview.origin.equals(new Point(6,13,6))),"Ctrl+J did not create an entity placement at its source");key(c,GLFW_KEY_DELETE,0);
        pause();key(c,'X',2);settle(c,w);require(c.computeOnClient(mc->PlacementPreview.active())&&w.getServer().computeOnServer(server->server.overworld().getEntity(java.util.UUID.fromString(entity))==null),"Ctrl+X did not cut the selected entity into a placement");key(c,GLFW_KEY_DELETE,0);pause();key(c,'Z',2);settle(c,w);
        require(w.getServer().computeOnServer(server->server.overworld().getEntity(java.util.UUID.fromString(entity))!=null),"Undo did not restore the cut entity");
        c.runOnClient(mc->PlacementPreview.begin(new Point(10,12,10)));pause();key(c,GLFW_KEY_ENTER,0);settle(c,w);
        require(w.getServer().computeOnServer(server->server.overworld().getEntities((net.minecraft.world.entity.Entity)null,new AABB(9,11,9,12,15,12),e->e instanceof net.minecraft.world.entity.Display).size()==1),"Entity-only clipboard did not paste");
        pause();key(c,GLFW_KEY_DELETE,0);settle(c,w);require(w.getServer().computeOnServer(server->server.overworld().getEntity(java.util.UUID.fromString(entity))==null),"Delete did not remove the selected entity");
        pause();key(c,'Z',2);settle(c,w);require(w.getServer().computeOnServer(server->server.overworld().getEntity(java.util.UUID.fromString(entity))!=null),"Entity Delete could not be undone");
        c.runOnClient(mc->{ClientState.options.entityUuid=entity;ClientState.options.gizmoTarget=Options.GizmoTarget.ENTITY;mc.gui.setScreen(null);});pause();c.runOnClient(mc->CraftiumClient.ingameKey(new KeyEvent('C',0,2),GLFW_PRESS));settle(c,w);
        require(c.computeOnClient(mc->ClientState.status.clipboardEntityCount()==1&&ClientState.status.clipboardSize()==0),"Ingame Ctrl+C did not copy selected entity");
        pause();c.runOnClient(mc->{mc.hitResult=new BlockHitResult(new Vec3(14.5,12,14.5),Direction.UP,new BlockPos(14,12,14),false);CraftiumClient.ingameKey(new KeyEvent('V',0,2),GLFW_PRESS);});settle(c,w);
        require(w.getServer().computeOnServer(server->server.overworld().getEntities((net.minecraft.world.entity.Entity)null,new AABB(13,12,13,16,16,16),e->e instanceof net.minecraft.world.entity.Display).size()==1),"Ingame Ctrl+V did not paste entity clipboard");
        pause();c.runOnClient(mc->CraftiumClient.ingameKey(new KeyEvent(GLFW_KEY_DELETE,0,0),GLFW_PRESS));settle(c,w);require(w.getServer().computeOnServer(server->server.overworld().getEntity(java.util.UUID.fromString(entity))==null),"Ingame Delete did not remove selected entity");
        c.runOnClient(mc->{CraftiumClient.open(false);mc.gui.setScreen(new EditorKeybindScreen(mc.gui.screen()));});c.waitTicks(3);c.takeScreenshot("craftium-keybinds");
        c.runOnClient(mc->mc.gui.setScreen(null));
        System.out.println("CRAFTIUM_KEYBINDS_PASS native_13_tools contextual_E camera_pan_orbit_radius biome_toggle custom_bind_persistence camera_relative_nudges fill copy save_dialog rotate_flip_XYZ duplicate_cut alt_hold tenth_slot block_state_copy entity_copy_paste_delete_undo entity_duplicate_cut entity_only_blueprint");
    }
}
