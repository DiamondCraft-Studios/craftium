package dev.craftium.test;

import dev.craftium.client.*;
import dev.craftium.core.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;

public final class CraftiumGameTest implements FabricClientGameTest {
    private ClientGameTestContext ctx;
    private TestSingleplayerContext world;
    private final Point up=new Point(0,1,0);

    @Override public void runTest(ClientGameTestContext context) {
        if("paper".equals(System.getenv("CRAFTIUM_TEST_SCOPE"))){PaperCompanionTest.run(context);return;}
        ctx=context;
        try(var singleplayer=context.worldBuilder().adjustSettings(s->{s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);s.setAllowCommands(true);}).create()) {
            world=singleplayer;
            ctx.runOnClient(mc->ClientState.options=new Options());
            world.getServer().runCommand("tp @a 0 10 18 180 25");
            ctx.waitTicks(20);world.getConnection().waitForChunksDownload();
            require(ctx.computeOnClient(mc->CraftiumClient.supported()),"Craftium network channel was not negotiated");
            if("gallery".equals(System.getenv("CRAFTIUM_TEST_SCOPE"))){dev.craftium.client.GalleryCapture.run(ctx,world);return;}
            if("keybinds".equals(System.getenv("CRAFTIUM_TEST_SCOPE"))){dev.craftium.client.KeybindGameTest.run(ctx,world);return;}
            if("branding".equals(System.getenv("CRAFTIUM_TEST_SCOPE"))){screenshot();return;}
            if("gizmos".equals(System.getenv("CRAFTIUM_TEST_SCOPE"))){gizmoControls();return;}
            if("builder".equals(System.getenv("CRAFTIUM_TEST_SCOPE"))){builderToolsAndLighting();return;}
            selectionAndHistory();
            clipboardAndBlockEntities();
            selectionTransforms();
            brushTools();
            biomeAndStrokeHistory();
            builderControls();
            advancedFeatures();
            documentedWorkflows();
            remainingTools();
            persistentHistory();
            animatedRebuild();
            entityClipboardAndSchematicData();
            documentedLua();
            colourField();
            builderToolsAndLighting();
            librariesAndCommands();
            viewingCapabilities();
            gizmoControls();
            blockOrientation();
            conflictAndLimits();
            screenshot();
        }
    }
    private void request(String action,Point p) {
        // The production server throttles requests by wall-clock time, including in accelerated gametests.
        try{Thread.sleep(80);}catch(InterruptedException e){throw new AssertionError(e);}
        ctx.runOnClient(mc->CraftiumClient.send(action,p,up));
        world.getConnection().waitForServerboundPackets();ctx.waitTicks(4);
        world.getConnection().waitForClientboundPackets();
        ctx.waitFor(mc->!ClientState.status.busy(),400);
        world.getConnection().waitForClientboundPackets();
        require(ctx.computeOnClient(mc->!ClientState.status.error()),"Request "+action+" failed: "+ctx.computeOnClient(mc->ClientState.status.message()));
    }
    private void options(java.util.function.Consumer<Options> set){ctx.runOnClient(mc->set.accept(ClientState.options));}
    private void builderToolsAndLighting(){
        ctx.runOnClient(mc->ClientState.options=new Options());options(o->{o.noUpdates=true;o.position1=new Point(55,25,45);o.position2=new Point(56,25,45);o.builderTool=Options.BuilderTool.MOVE;o.offsetX=3;o.offsetY=0;o.offsetZ=0;});
        world.getServer().runOnServer(server->{var level=server.overworld();level.setBlock(new BlockPos(55,25,45),Blocks.GOLD_BLOCK.defaultBlockState(),18);level.setBlock(new BlockPos(56,25,45),Blocks.DIAMOND_BLOCK.defaultBlockState(),18);});
        request("builder_commit",new Point(55,25,45));checkBlock(new Point(55,25,45),Blocks.AIR);checkBlock(new Point(58,25,45),Blocks.GOLD_BLOCK);checkBlock(new Point(59,25,45),Blocks.DIAMOND_BLOCK);request("undo",null);checkBlock(new Point(55,25,45),Blocks.GOLD_BLOCK);
        options(o->{o.keepExisting=true;o.offsetX=1;});request("builder_commit",new Point(55,25,45));checkBlock(new Point(55,25,45),Blocks.AIR);checkBlock(new Point(56,25,45),Blocks.GOLD_BLOCK);checkBlock(new Point(57,25,45),Blocks.DIAMOND_BLOCK);request("undo",null);checkBlock(new Point(55,25,45),Blocks.GOLD_BLOCK);checkBlock(new Point(56,25,45),Blocks.DIAMOND_BLOCK);options(o->{o.keepExisting=false;o.offsetX=3;});
        options(o->{o.builderTool=Options.BuilderTool.CLONE;o.builderRotation=1;o.offsetZ=4;});request("builder_commit",new Point(55,25,45));checkBlock(new Point(58,25,49),Blocks.GOLD_BLOCK);checkBlock(new Point(58,25,50),Blocks.DIAMOND_BLOCK);request("undo",null);
        options(o->{o.builderTool=Options.BuilderTool.STACK;o.builderRotation=0;o.offsetX=0;o.offsetZ=3;o.copies=3;});request("builder_commit",new Point(55,25,45));checkBlock(new Point(55,25,54),Blocks.GOLD_BLOCK);request("undo",null);
        ctx.runOnClient(mc->{BuilderMode.select(Options.BuilderTool.CLONE);BuilderMode.corners(new Point(55,25,45),new Point(56,25,45));BuilderMode.nudge(new Point(0,1,0),2);BuilderMode.confirm();BuilderMode.reset();});world.getConnection().waitForServerboundPackets();ctx.waitTicks(8);world.getConnection().waitForClientboundPackets();checkBlock(new Point(55,27,45),Blocks.GOLD_BLOCK);request("undo",null);
        options(o->{o.builderTool=Options.BuilderTool.ERASE;});request("builder_commit",new Point(55,25,45));checkBlock(new Point(55,25,45),Blocks.AIR);request("undo",null);
        options(o->{o.symmetryOrigin=new Point(65,25,45);o.symmetryHalfX=true;o.symmetryX=true;o.block="minecraft:emerald_block";o.position1=new Point(64,25,45);o.position2=o.position1;});request("select_box",null);request("fill",null);checkBlock(new Point(67,25,45),Blocks.EMERALD_BLOCK);request("undo",null);
        options(o->{o.symmetryX=false;o.symmetryHalfX=false;o.symmetryRotations=4;o.position1=new Point(66,25,45);o.position2=o.position1;});request("select_box",null);request("fill",null);checkBlock(new Point(65,25,46),Blocks.EMERALD_BLOCK);checkBlock(new Point(64,25,45),Blocks.EMERALD_BLOCK);request("undo",null);
        options(o->{o.symmetryRotations=1;o.position1=new Point(55,25,45);o.position2=new Point(56,25,45);o.materials="minecraft:coal_block,minecraft:stone,minecraft:quartz_block";o.shadeSun=false;o.shadeAmbientOcclusion=0;o.shadeBounces=0;o.shadeDither=0;});request("select_box",null);request("autoshade",null);checkBlock(new Point(55,25,45),Blocks.QUARTZ_BLOCK);request("undo",null);
        options(o->{o.shadeSun=true;o.shadeFrom=Options.ShadeFrom.CUSTOM_POSITIONS;o.shadeLights.add(new ShadeLight(55.5,30,45.5,1));o.shadeAmbientOcclusion=.5;o.shadeBounces=2;});request("autoshade",null);require(world.getServer().computeOnServer(server->!server.overworld().getBlockState(new BlockPos(55,25,45)).is(Blocks.GOLD_BLOCK)),"Custom-light Autoshade did not paint the surface");request("undo",null);
        options(o->{o.shadeAutomatic=true;o.shadeFrom=Options.ShadeFrom.PLAYER_POSITION;});request("autoshade",null);request("undo",null);checkBlock(new Point(55,25,45),Blocks.GOLD_BLOCK);
        options(o->{o.shadeAutomatic=false;o.shadeLights.clear();});request("clear",null);
        System.out.println("CRAFTIUM_BUILDER_LIGHTING_PASS virtual_slot move clone rotate stack erase half_symmetry rotational_symmetry custom_lights automatic_palette");
    }
    private void librariesAndCommands(){
        options(o->{o.position1=new Point(55,25,45);o.position2=new Point(56,25,45);o.name="Client-transfer-test";o.downloadExports=true;});request("select_box",null);request("copy",null);int version=ctx.computeOnClient(mc->ClientState.libraryVersion);request("structure_export",null);ctx.waitTicks(4);require(ctx.computeOnClient(mc->ClientState.libraryVersion>version&&ClientState.notice.startsWith("Downloaded ")),"Client export download was not received");
        ctx.runOnClient(mc->ClientAssets.load("Client-transfer-test.nbt"));world.getConnection().waitForServerboundPackets();ctx.waitTicks(8);world.getConnection().waitForClientboundPackets();require(ctx.computeOnClient(mc->!ClientState.status.error()&&ClientState.status.clipboardSize()==2),"Local blueprint did not transfer into the server clipboard");request("paste_at",new Point(70,25,50));checkBlock(new Point(70,25,50),Blocks.GOLD_BLOCK);checkBlock(new Point(71,25,50),Blocks.DIAMOND_BLOCK);request("undo",null);
        world.getServer().runCommand("craftiumDoBlockGravity false");require(world.getServer().computeOnServer(server->!dev.craftium.server.WorldRules.get(server.overworld()).blockGravity),"Craftium world rule command did not apply");world.getServer().runCommand("craftiumDoBlockGravity true");
        ctx.runOnClient(mc->mc.getConnection().sendCommand("craftium toggle fastPlace"));ctx.waitTicks(3);require(ctx.computeOnClient(mc->ClientState.options.fastPlace),"Client capability command did not run");ctx.runOnClient(mc->mc.getConnection().sendCommand("craftium toggle fastPlace"));ctx.waitTicks(3);
        ctx.runOnClient(mc->mc.getConnection().sendCommand("craftium pos1 55 25 45"));ctx.waitTicks(3);ctx.runOnClient(mc->mc.getConnection().sendCommand("craftium pos2 56 25 45"));world.getConnection().waitForServerboundPackets();ctx.waitTicks(5);world.getConnection().waitForClientboundPackets();require(ctx.computeOnClient(mc->ClientState.status.selectionSize()==2),"Craftium position commands did not visualize the selection");request("clear",null);
        System.out.println("CRAFTIUM_LIBRARY_COMMANDS_PASS client_download local_upload clipboard_restore client_commands world_rule_commands position_visualization");
    }
    private void viewingCapabilities(){
        ctx.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_0);ctx.waitTick();require(ctx.computeOnClient(mc->BuilderMode.active()),"Native 0 key did not activate the tenth slot");ctx.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_1);ctx.waitTick();require(ctx.computeOnClient(mc->!BuilderMode.active()),"Native 1 key did not leave the tenth slot");
        require(ctx.computeOnClient(mc->{var o=ClientState.options;o.cameraFlight=true;o.enhancedFlight=true;o.flightMomentum=0;mc.player.getAbilities().flying=true;mc.player.setXRot(-45);double y=mc.player.getY();mc.player.travel(new net.minecraft.world.phys.Vec3(0,0,1));return mc.player.getY()>y+.01;}),"Camera flight did not follow the camera pitch");
        require(ctx.computeOnClient(mc->{ClientState.options.flightMomentum=.8;mc.player.travel(new net.minecraft.world.phys.Vec3(0,0,1));double before=mc.player.getDeltaMovement().length();mc.player.travel(net.minecraft.world.phys.Vec3.ZERO);double after=mc.player.getDeltaMovement().length();return after>0&&after<before;}),"Flight momentum did not retain and decay velocity");
        options(o->{o.cameraFlight=false;o.flightMomentum=0;o.enhancedFlight=false;o.minimumBrightness=.8;o.liquidOpacity=.25;});ctx.waitTicks(4);options(o->{o.minimumBrightness=0;o.liquidOpacity=1;});ctx.waitTicks(4);
        System.out.println("CRAFTIUM_VIEWING_PASS camera_flight momentum_decay brightness fluid_recompile");
    }
    private void gizmoControls(){
        options(o->{o.tool=Tool.BOX_SELECT;o.entityUuid="";o.position1=new Point(55,30,50);o.position2=new Point(56,31,51);o.gizmoMode=Options.GizmoMode.MOVE;o.gizmoTarget=Options.GizmoTarget.AUTO;o.gizmoLocal=false;});request("select_box",null);
        world.getServer().runCommand("tp @a 55 30 70 180 0");ctx.waitTicks(8);ctx.runOnClient(mc->{mc.player.getAbilities().flying=true;mc.player.onUpdateAbilities();mc.getWindow().setWindowed(1200,800);mc.options.guiScale().set(2);mc.resizeGui();CraftiumClient.open(false);});ctx.waitTicks(5);
        try{Thread.sleep(80);}catch(InterruptedException e){throw new AssertionError(e);}
        ctx.takeScreenshot("gizmo-before");ctx.runOnClient(mc->dragWorldHandle(mc,new net.minecraft.world.phys.Vec3(59,30,50),new net.minecraft.world.phys.Vec3(63,30,50)));world.getConnection().waitForServerboundPackets();ctx.waitTicks(8);world.getConnection().waitForClientboundPackets();require(ctx.computeOnClient(mc->ClientState.status.min().x()==59),"Translation axis did not move the selection outline: "+ctx.computeOnClient(mc->ClientState.status.min()+" / "+ClientState.status.message()));
        options(o->o.gizmoMode=Options.GizmoMode.SCALE);ctx.waitTicks(3);try{Thread.sleep(80);}catch(InterruptedException e){throw new AssertionError(e);}
        ctx.runOnClient(mc->dragWorldHandle(mc,new net.minecraft.world.phys.Vec3(64,30,50),new net.minecraft.world.phys.Vec3(69,30,50)));world.getConnection().waitForServerboundPackets();ctx.waitTicks(8);world.getConnection().waitForClientboundPackets();require(ctx.computeOnClient(mc->ClientState.status.selectionSize()==16),"Scale node did not resample the selection");
        ctx.runOnClient(mc->mc.gui.setScreen(null));options(o->{o.entityType="block_display";o.entityNbt="{block_state:{Name:\"minecraft:stone\"},transformation:{translation:[0f,0f,0f],scale:[2.0f,1.0f,1.0f],left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f]}}";o.entityUuid="";o.entityX=.25;o.entityY=.5;o.entityZ=.75;o.entityScaleX=2;o.entityScaleY=1;o.entityScaleZ=1;o.yaw=0;o.pitch=0;o.roll=0;});request("entity_spawn",new Point(55,30,50));String id=ctx.computeOnClient(mc->ClientState.options.entityUuid);request("entity_gizmo",new Point(55,30,50));
        require(world.getServer().computeOnServer(server->{var entity=server.overworld().getEntity(java.util.UUID.fromString(id));var out=net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING,server.registryAccess());entity.save(out);var transform=com.mojang.math.Transformation.EXTENDED_CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE,out.buildResult().get("transformation")).getOrThrow();return Math.abs(entity.getX()-55.75)<.001&&Math.abs(transform.scale().x()-4)<.001;}),"Entity gizmo did not compose the existing scale and fractional movement");request("undo",null);request("undo",null);options(o->{o.entityUuid="";o.entityX=o.entityY=o.entityZ=0;o.entityScaleX=o.entityScaleY=o.entityScaleZ=1;o.gizmoMode=Options.GizmoMode.MOVE;});request("clear",null);world.getServer().runCommand("tp @a 0 20 18 180 25");ctx.waitTicks(5);
        System.out.println("CRAFTIUM_GIZMO_PASS axis_drag selection_scale fractional_entity_transform matrix_composition undo");
    }
    private void blockOrientation(){
        world.getServer().runOnServer(server->{var level=server.overworld();level.setBlock(new BlockPos(55,28,50),Blocks.PISTON.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING,net.minecraft.core.Direction.NORTH),18);level.setBlock(new BlockPos(56,28,50),Blocks.OAK_LOG.defaultBlockState(),18);});
        options(o->{o.noUpdates=true;o.position1=new Point(55,28,50);o.position2=o.position1;o.yaw=0;o.pitch=90;o.roll=0;o.sizeX=o.sizeY=o.sizeZ=1;});request("select_box",null);request("copy",null);request("clipboard_transform",null);request("paste_at",new Point(75,28,50));require(world.getServer().computeOnServer(server->server.overworld().getBlockState(new BlockPos(75,28,50)).getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)==net.minecraft.core.Direction.UP),"Pitch rotation did not turn the piston facing");request("undo",null);
        options(o->{o.position1=new Point(56,28,50);o.position2=o.position1;});request("select_box",null);request("copy",null);request("clipboard_transform",null);request("paste_at",new Point(76,28,50));require(world.getServer().computeOnServer(server->server.overworld().getBlockState(new BlockPos(76,28,50)).getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS)==net.minecraft.core.Direction.Axis.Z),"Pitch rotation did not turn the log axis");request("undo",null);options(o->{o.pitch=0;});request("clear",null);
        System.out.println("CRAFTIUM_STATE_ROTATION_PASS vertical_piston log_axis undo");
    }
    private void dragWorldHandle(net.minecraft.client.Minecraft mc,net.minecraft.world.phys.Vec3 from,net.minecraft.world.phys.Vec3 to){double[] start=projectWorld(mc,from),end=projectWorld(mc,to);System.out.println("CRAFTIUM_GIZMO_INPUT "+java.util.Arrays.toString(start)+" -> "+java.util.Arrays.toString(end)+" placement="+PlacementPreview.active());var info=new net.minecraft.client.input.MouseButtonInfo(0,0);var screen=mc.gui.screen();var press=new net.minecraft.client.input.MouseButtonEvent(start[0],start[1],info);var release=new net.minecraft.client.input.MouseButtonEvent(end[0],end[1],info);screen.mouseClicked(press,false);screen.mouseDragged(release,end[0]-start[0],end[1]-start[1]);screen.mouseReleased(release);}
    private double[] projectWorld(net.minecraft.client.Minecraft mc,net.minecraft.world.phys.Vec3 point){var camera=mc.gameRenderer.mainCamera();var near=camera.getNearPlane(camera.getFov());var center=near.getPointOnPlane(0,0);var offset=point.subtract(camera.position());double depth=offset.dot(center.normalize()),focal=mc.getWindow().getGuiScaledHeight()/(2*Math.tan(Math.toRadians(camera.getFov())/2));return new double[]{mc.getWindow().getGuiScaledWidth()/2d+offset.dot(near.getPointOnPlane(1,0).subtract(center).normalize())*focal/depth,mc.getWindow().getGuiScaledHeight()/2d-offset.dot(near.getPointOnPlane(0,1).subtract(center).normalize())*focal/depth};}
    private void selectionAndHistory() {
        options(o->{o.tool=Tool.BOX_SELECT;o.block="minecraft:gold_block";o.noUpdates=true;});
        request("use",new Point(0,0,0));request("use",new Point(2,2,2));
        require(ctx.computeOnClient(mc->ClientState.status.selectionSize()==27),"Box selection should contain 27 blocks");
        request("fill",null);checkBlock(new Point(1,1,1),Blocks.GOLD_BLOCK);
        request("undo",null);checkBlock(new Point(1,1,1),Blocks.AIR);
        request("redo",null);checkBlock(new Point(1,1,1),Blocks.GOLD_BLOCK);
        options(o->{o.block="minecraft:diamond_block";o.mask="minecraft:dirt";});
        request("fill",null);checkBlock(new Point(1,1,1),Blocks.GOLD_BLOCK);
        options(o->o.mask="");
    }
    private void clipboardAndBlockEntities() {
        world.getServer().runOnServer(server->{
            var level=server.overworld();var p=new BlockPos(6,1,0);level.setBlock(p,Blocks.CHEST.defaultBlockState(),2|16);
            ((ChestBlockEntity)level.getBlockEntity(p)).setItem(0,new ItemStack(Items.DIAMOND,17));
        });ctx.waitTick();
        request("clear",null);options(o->o.tool=Tool.BOX_SELECT);
        request("use",new Point(6,1,0));request("use",new Point(6,1,0));request("copy",null);
        request("rotate",null);request("flip",null);request("paste",new Point(10,0,0));
        checkChest(new Point(10,1,0));
        request("undo",null);checkBlock(new Point(10,1,0),Blocks.AIR);
        request("redo",null);checkChest(new Point(10,1,0));
        request("delete",null);checkBlock(new Point(6,1,0),Blocks.AIR);
        request("undo",null);checkChest(new Point(6,1,0));
    }
    private void brushTools() {
        Tool[] tools={Tool.PAINTER,Tool.NOISE_PAINTER,Tool.FREEHAND_DRAW,Tool.SCULPT_DRAW,Tool.ROCK,Tool.WELD,Tool.MELT,Tool.TEXT,Tool.ELEVATION,Tool.FLATTEN,Tool.SLOPE,Tool.SMOOTH,Tool.DISTORT,Tool.ROUGHEN,Tool.SHATTER,Tool.EXTRUDE,Tool.SHAPE,Tool.ERASE,Tool.TINKER,Tool.BIOME_PAINTER,Tool.CLENTAMINATOR};
        for(Tool tool:tools) {
            world.getServer().runOnServer(server->{
                var level=server.overworld();
                for(int x=-5;x<=5;x++)for(int y=-5;y<=6;y++)for(int z=-5;z<=5;z++)
                    level.setBlock(new BlockPos(x,y,z),(y<=0?Blocks.STONE:Blocks.AIR).defaultBlockState(),2|16);
            });
            options(o->{o.tool=tool;o.radius=3;o.strength=2;o.block="minecraft:andesite";o.secondary="minecraft:stone";o.text="BUILD";o.biome="minecraft:desert";o.inSelection=false;o.keepExisting=false;});
            request("use",new Point(0,0,0));
            System.out.println("CRAFTIUM_TOOL_PASS "+tool);
        }
        options(o->{o.tool=Tool.FREEHAND_SELECT;o.mode=Selection.Mode.REPLACE;});request("use",new Point(0,0,0));
        require(ctx.computeOnClient(mc->ClientState.status.selectionSize()>20),"Freehand brush did not select blocks");
        options(o->{o.tool=Tool.MAGIC_SELECT;o.surfaceOnly=false;});request("use",new Point(0,-1,0));
        require(ctx.computeOnClient(mc->ClientState.status.selectionSize()>0),"Magic selection was empty");
        options(o->o.tool=Tool.RULER);request("use",new Point(0,0,0));request("use",new Point(3,4,0));
        require(ctx.computeOnClient(mc->ClientState.status.message().contains("5.00")),"Ruler distance was incorrect");
        options(o->{o.tool=Tool.LINE;o.radius=1;});request("use",new Point(0,5,0));request("use",new Point(6,5,0));checkBlock(new Point(3,5,0),Blocks.ANDESITE);
    }
    private void selectionTransforms() {
        world.getServer().runOnServer(server->{
            var level=server.overworld();
            level.setBlock(new BlockPos(4,15,0),Blocks.GOLD_BLOCK.defaultBlockState(),2|16);
            level.setBlock(new BlockPos(5,15,0),Blocks.DIAMOND_BLOCK.defaultBlockState(),2|16);
        });
        request("clear",null);options(o->{o.tool=Tool.BOX_SELECT;o.offsetX=1;o.offsetY=0;o.offsetZ=0;});
        request("use",new Point(4,15,0));request("use",new Point(5,15,0));
        request("move",null);
        checkBlock(new Point(4,15,0),Blocks.AIR);checkBlock(new Point(5,15,0),Blocks.GOLD_BLOCK);checkBlock(new Point(6,15,0),Blocks.DIAMOND_BLOCK);
        request("undo",null);checkBlock(new Point(4,15,0),Blocks.GOLD_BLOCK);checkBlock(new Point(5,15,0),Blocks.DIAMOND_BLOCK);checkBlock(new Point(6,15,0),Blocks.AIR);
        options(o->o.offsetZ=4);request("clone",null);checkBlock(new Point(5,15,4),Blocks.GOLD_BLOCK);checkBlock(new Point(6,15,4),Blocks.DIAMOND_BLOCK);
        options(o->{o.offsetX=0;o.offsetZ=8;o.copies=3;});request("stack",null);
        checkBlock(new Point(4,15,8),Blocks.GOLD_BLOCK);checkBlock(new Point(5,15,16),Blocks.DIAMOND_BLOCK);checkBlock(new Point(4,15,24),Blocks.GOLD_BLOCK);
        request("undo",null);checkBlock(new Point(4,15,8),Blocks.AIR);checkBlock(new Point(5,15,16),Blocks.AIR);checkBlock(new Point(4,15,24),Blocks.AIR);
        System.out.println("CRAFTIUM_TRANSFORMS_PASS overlap_move clone stack undo");
    }
    private void builderControls() {
        options(o->o.flySpeed=2.5f);request("flight",null);
        require(world.getServer().computeOnServer(server->Math.abs(world.getConnection().getServerPlayer().getAbilities().getFlyingSpeed()-.125f)<.0001),"Flight speed was not applied");
        world.getServer().runOnServer(server->{var p=world.getConnection().getServerPlayer();p.getInventory().setItem(0,new ItemStack(Items.STONE,12));p.getInventory().setItem(9,new ItemStack(Items.DIAMOND,7));});
        request("hotbar",null);
        require(world.getServer().computeOnServer(server->{var inv=world.getConnection().getServerPlayer().getInventory();return inv.getItem(0).is(Items.DIAMOND)&&inv.getItem(9).is(Items.STONE);}),"Hotbar swap failed");
        request("gamemode",null);require(world.getServer().computeOnServer(server->world.getConnection().getServerPlayer().isSpectator()),"Game mode did not switch to Spectator");
        request("gamemode",null);require(world.getServer().computeOnServer(server->world.getConnection().getServerPlayer().isCreative()),"Game mode did not return to Creative");
        options(o->o.flySpeed=1);
        world.getServer().runCommand("tp @a 0 20 18");world.getServer().runOnServer(server->{var player=world.getConnection().getServerPlayer();player.getAbilities().flying=true;player.onUpdateAbilities();});ctx.waitTicks(3);
        System.out.println("CRAFTIUM_BUILDER_PASS flight hotbar gamemode");
    }
    private void conflictAndLimits() {
        options(o->{o.tool=Tool.BOX_SELECT;o.mode=Selection.Mode.REPLACE;o.block="minecraft:gold_block";});
        request("clear",null);request("use",new Point(15,2,0));request("use",new Point(15,2,0));request("fill",null);
        world.getServer().runOnServer(server->server.overworld().setBlock(new BlockPos(15,2,0),Blocks.EMERALD_BLOCK.defaultBlockState(),2|16));
        request("undo",null);checkBlock(new Point(15,2,0),Blocks.EMERALD_BLOCK);
        request("clear",null);request("use",new Point(-50,0,-50));
        try{Thread.sleep(80);}catch(InterruptedException e){throw new AssertionError(e);}
        ctx.runOnClient(mc->CraftiumClient.send("use",new Point(50,30,50),up));world.getConnection().waitForServerboundPackets();ctx.waitTicks(4);world.getConnection().waitForClientboundPackets();
        require(ctx.computeOnClient(mc->ClientState.status.error()),"Oversized selection should be rejected");
        require(ctx.computeOnClient(mc->ClientState.status.selectionSize()==0),"Rejected selection changed the current selection");
        request("clear",null);
    }
    private void advancedFeatures() {
        options(o->{o.position1=new Point(20,5,20);o.position2=new Point(22,7,22);o.block="minecraft:gold_block";});
        request("select_box",null);request("fill_outline",null);checkBlock(new Point(20,5,20),Blocks.GOLD_BLOCK);checkBlock(new Point(21,6,21),Blocks.AIR);
        request("fill_gaps",null);checkBlock(new Point(21,6,21),Blocks.GOLD_BLOCK);request("hollow",null);checkBlock(new Point(21,6,21),Blocks.AIR);
        options(o->{o.tool=Tool.SCRIPT_BRUSH;o.radius=1;o.script="if y == 20 then return blocks.emerald_block end";});
        request("use",new Point(0,20,0));checkBlock(new Point(0,20,0),Blocks.EMERALD_BLOCK);checkBlock(new Point(0,21,0),Blocks.AIR);
        options(o->o.script="while true do end");expectError("use",new Point(0,20,0));checkBlock(new Point(0,20,0),Blocks.EMERALD_BLOCK);
        options(o->{o.tool=Tool.PATH;o.radius=1;o.block="minecraft:andesite";o.pathMode=Options.PathMode.CATMULL_ROM;o.nodes=new java.util.ArrayList<>(java.util.List.of(new Point(0,25,0),new Point(4,28,0),new Point(8,25,0)));});
        request("use",new Point(0,25,0));checkBlock(new Point(4,28,0),Blocks.ANDESITE);
        options(o->{o.tool=Tool.MODELLING;o.nodes=new java.util.ArrayList<>(java.util.List.of(new Point(12,20,0),new Point(16,20,0),new Point(12,24,0),new Point(12,20,4)));});
        request("use",new Point(12,20,0));checkBlock(new Point(13,21,1),Blocks.ANDESITE);
        options(o->{o.name="Craftium-integration-blueprint";o.position1=new Point(20,5,20);o.position2=new Point(22,7,22);});
        request("select_box",null);request("copy",null);request("blueprint_save",null);request("blueprint_load",null);
        require(ctx.computeOnClient(mc->ClientState.status.clipboardSize()==27),"Blueprint round-trip lost blocks");
        request("schematic_export",null);options(o->o.name="Craftium-integration-blueprint.schem");request("schematic_import",null);
        require(ctx.computeOnClient(mc->ClientState.status.clipboardSize()==27),"Schematic round-trip lost blocks");
        options(o->{o.entityType="block_display";o.entityNbt="{block_state:{Name:\"minecraft:gold_block\"}}";o.entityUuid="";});
        request("entity_spawn",new Point(4,20,8));String id=ctx.computeOnClient(mc->ClientState.options.entityUuid);
        require(!id.isBlank(),"Display creation did not return its identifier");
        request("entity_delete",new Point(4,20,8));
        require(world.getServer().computeOnServer(server->server.overworld().getEntity(java.util.UUID.fromString(id))==null),"Display was not deleted");
        request("undo",null);require(world.getServer().computeOnServer(server->server.overworld().getEntity(java.util.UUID.fromString(id))!=null),"Display undo did not restore the entity");
        options(o->{o.annotationMode="TEXT";o.text="Build here";});request("annotation_create",new Point(0,20,8));
        require(ctx.computeOnClient(mc->!ClientState.status.annotations().isEmpty()),"Annotation was not synchronized");request("undo",null);
        require(ctx.computeOnClient(mc->ClientState.status.annotations().isEmpty()),"Annotation undo did not remove the note");
        options(o->{o.worldTime=7000;o.name="Craftium-integration-view";});request("world_time",null);request("view_save",null);request("view_load",null);
        options(o->{o.nodes.clear();o.entityUuid="";o.maskExpression="";});request("clear",null);
        System.out.println("CRAFTIUM_ADVANCED_PASS operations masks scripts curves meshes blueprints schematics entities annotations views");
    }
    private void expectError(String action,Point at){
        try{Thread.sleep(80);}catch(InterruptedException e){throw new AssertionError(e);}
        ctx.runOnClient(mc->CraftiumClient.send(action,at,up));world.getConnection().waitForServerboundPackets();ctx.waitTicks(4);world.getConnection().waitForClientboundPackets();
        require(ctx.computeOnClient(mc->ClientState.status.error()),"Expected failure for "+action);
    }
    private void persistentHistory(){
        options(o->{o.tool=Tool.FREEHAND_DRAW;o.block="minecraft:gold_block";o.radius=1;o.invert=false;});
        for(int x:new int[]{60,64,68})request("use",new Point(x,20,40));
        options(o->o.historySteps=3);request("history_undo_many",null);for(int x:new int[]{60,64,68})checkBlock(new Point(x,20,40),Blocks.AIR);
        request("history_redo_many",null);for(int x:new int[]{60,64,68})checkBlock(new Point(x,20,40),Blocks.GOLD_BLOCK);
        request("history_save",null);int count=ctx.computeOnClient(mc->ClientState.status.undo());
        world.getServer().runOnServer(server->dev.craftium.server.EditorServer.disconnect(world.getConnection().getServerPlayer().getUUID()));request("status",null);
        require(ctx.computeOnClient(mc->ClientState.status.undo()==count),"Saved history did not reload after session eviction");request("undo",null);checkBlock(new Point(68,20,40),Blocks.AIR);request("redo",null);checkBlock(new Point(68,20,40),Blocks.GOLD_BLOCK);
        options(o->o.radius=5);System.out.println("CRAFTIUM_HISTORY_PASS multi_rewind multi_redo disk_reload block_restore");
    }
    private void animatedRebuild(){
        world.getServer().runOnServer(server->{var level=server.overworld();for(int x=60;x<=64;x++)level.setBlock(new BlockPos(x,25,44),Blocks.GOLD_BLOCK.defaultBlockState(),2|16);level.setBlock(new BlockPos(62,25,44),Blocks.CHEST.defaultBlockState(),2|16);((ChestBlockEntity)level.getBlockEntity(new BlockPos(62,25,44))).setItem(0,new ItemStack(Items.DIAMOND,17));});
        options(o->{o.position1=new Point(60,25,44);o.position2=new Point(64,25,44);o.rebuildStartDelay=.2;o.rebuildMaxDelay=.01;o.rebuildAirDelay=.01;o.rebuildSameDelay=.01;o.rebuildDifferentDelay=.01;o.rebuildRandomDelay=0;});request("select_box",null);request("animated_rebuild",null);
        checkChest(new Point(62,25,44));checkBlock(new Point(60,25,44),Blocks.GOLD_BLOCK);request("undo",null);checkChest(new Point(62,25,44));
        options(o->o.rebuildStartDelay=10);try{Thread.sleep(80);}catch(InterruptedException e){throw new AssertionError(e);}
        ctx.runOnClient(mc->CraftiumClient.send("animated_rebuild",null,up));world.getConnection().waitForServerboundPackets();ctx.waitTicks(4);world.getConnection().waitForClientboundPackets();require(ctx.computeOnClient(mc->ClientState.status.busy()),"Rebuild did not enter its timed phase");checkBlock(new Point(62,25,44),Blocks.AIR);
        request("undo",null);checkChest(new Point(62,25,44));for(int x=60;x<=64;x++)if(x!=62)checkBlock(new Point(x,25,44),Blocks.GOLD_BLOCK);
        options(o->o.rebuildStartDelay=1);request("clear",null);System.out.println("CRAFTIUM_REBUILD_PASS completion cancel recovery chest_inventory");
    }
    private void entityClipboardAndSchematicData(){
        world.getServer().runOnServer(server->server.overworld().setBlock(new BlockPos(70,19,41),Blocks.GOLD_BLOCK.defaultBlockState(),2|16));
        world.getServer().runCommand("summon minecraft:pig 71.5 20 42.5 {NoGravity:1b,NoAI:1b,Passengers:[{id:\"minecraft:chicken\",NoGravity:1b,NoAI:1b}]}");
        options(o->{o.entityType="block_display";o.entityNbt="{block_state:{Name:\"minecraft:gold_block\"}}";o.entityUuid="";});request("entity_spawn",new Point(70,19,42));
        options(o->{o.position1=new Point(70,19,41);o.position2=new Point(73,22,44);o.copyEntities=true;o.pasteEntities=true;});request("select_box",null);request("copy",null);request("paste_at",new Point(80,19,41));
        require(entityCount(79,85)==3,"Entity clipboard did not paste the display, mob and passenger");
        world.getServer().runOnServer(server->{var box=new net.minecraft.world.phys.AABB(79,18,40,85,24,46);for(var pig:server.overworld().getEntitiesOfClass(net.minecraft.world.entity.animal.pig.Pig.class,box))pig.setPos(83,20,43);});
        request("undo",null);require(entityCount(79,85)==0,"Undo left a moving pasted entity or passenger behind");request("redo",null);require(entityCount(79,85)==3,"Redo did not restore the pasted passenger tree");request("undo",null);
        options(o->o.name="Integration/Entities");request("blueprint_save",null);request("blueprint_load",null);request("paste_at",new Point(90,19,41));require(entityCount(89,95)==3,"Structure NBT lost entities");request("undo",null);
        request("schematic_export",null);options(o->o.name="Integration/Entities.schem");request("schematic_import",null);request("paste_at",new Point(100,19,41));require(entityCount(99,105)==3,"Sponge schematic lost entities");request("undo",null);
        options(o->{o.copyEntities=false;o.position1=new Point(62,25,44);o.position2=o.position1;o.name="Integration/Chest";});request("select_box",null);request("copy",null);request("schematic_export",null);options(o->o.name="Integration/Chest.schem");request("schematic_import",null);request("paste_at",new Point(100,25,44));checkChest(new Point(100,25,44));request("undo",null);
        world.getServer().runOnServer(server->{try{
            var root=new net.minecraft.nbt.CompoundTag();root.putShort("Width",(short)1);root.putShort("Height",(short)1);root.putShort("Length",(short)1);root.putByteArray("Blocks",new byte[]{54});root.putByteArray("Data",new byte[]{2});var chest=new net.minecraft.nbt.CompoundTag();chest.putString("id","Chest");chest.putInt("x",0);chest.putInt("y",0);chest.putInt("z",0);var diamond=new net.minecraft.nbt.CompoundTag();diamond.putShort("id",(short)264);diamond.putByte("Count",(byte)17);diamond.putShort("Damage",(short)0);diamond.putByte("Slot",(byte)0);var items=new net.minecraft.nbt.ListTag();items.add(diamond);chest.put("Items",items);var tiles=new net.minecraft.nbt.ListTag();tiles.add(chest);root.put("TileEntities",tiles);
            net.minecraft.nbt.NbtIo.writeCompressed(root,net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("craftium/blueprints/Craftium-legacy-chest.schematic"));
        }catch(Exception e){throw new AssertionError(e);}});
        options(o->o.name="Craftium-legacy-chest.schematic");request("schematic_import",null);request("paste_at",new Point(104,25,44));checkChest(new Point(104,25,44));request("undo",null);request("clear",null);
        System.out.println("CRAFTIUM_ENTITY_CLIPBOARD_PASS moving_undo passengers nbt schem legacy_inventory");
    }
    private int entityCount(int minX,int maxX){return world.getServer().computeOnServer(server->server.overworld().getEntities((net.minecraft.world.entity.Entity)null,new net.minecraft.world.phys.AABB(minX,18,40,maxX,24,46),e->!(e instanceof net.minecraft.world.entity.player.Player)).size());}
    private void documentedLua(){
        options(o->{o.tool=Tool.SCRIPT_BRUSH;o.radius=2;o.scriptVariables.put("Height","3");o.script="$once$ local h=$int(Height,2,1,5)$ assert(type(blocks.oak_log)=='number') for i=0,h-1 do setBlock(x,y+i,z,withBlockProperty(blocks.oak_log,'axis=x')) end";});request("use",new Point(110,20,44));
        for(int y=20;y<23;y++)checkBlock(new Point(110,y,44),Blocks.OAK_LOG);checkBlock(new Point(109,20,44),Blocks.AIR);
        require(world.getServer().computeOnServer(server->server.overworld().getBlockState(new BlockPos(110,20,44)).getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS)==net.minecraft.core.Direction.Axis.X),"Lua property helper did not set axis=x");
        options(o->o.script="$once$ if isBlockTagged(getBlock(x,y,z),'minecraft:logs') and getBlockProperty(getBlockState(x,y,z),'axis')=='x' and isSolid(blocks.oak_slab) and not isSolid(blocks.water) then return blocks.emerald_block end");request("use",new Point(110,20,44));checkBlock(new Point(110,20,44),Blocks.EMERALD_BLOCK);
        options(o->o.script="$once$ local n=getSimplexNoise(x/8,y/8,z/8,37) local v=getVoroniEdgeNoise(x/8,y/8,z/8,37) assert(n>=0 and n<=1 and v>=0 and v<=1) local color=getBlockRGB(blocks.stone) assert(type(color)=='number') local closest=findClosestBlockToRGB(color,4,1) assert(type(closest)=='number') return closest");request("use",new Point(112,20,44));
        require(world.getServer().computeOnServer(server->!server.overworld().getBlockState(new BlockPos(112,20,44)).isAir()),"Lua colour search returned no block");
        options(o->{o.tool=Tool.FREEHAND_DRAW;o.radius=1;o.block="minecraft:gold_block";o.maskScript="return $boolean(Allow,false)$";o.maskVariables.put("Allow","false");});request("use",new Point(114,20,44));checkBlock(new Point(114,20,44),Blocks.AIR);
        options(o->o.maskVariables.put("Allow","true"));request("use",new Point(114,20,44));checkBlock(new Point(114,20,44),Blocks.GOLD_BLOCK);request("undo",null);
        options(o->{o.maskScript="";o.maskVariables.clear();o.scriptVariables.clear();o.script="$once$ return $blockState(Material,stone)$";o.radius=5;});
        System.out.println("CRAFTIUM_LUA_API_PASS numeric_states properties tags collision noise colours templates once mask_variables");
    }
    private void colourField(){
        options(o->{o.position1=new Point(80,30,50);o.position2=new Point(91,41,61);o.worldToggle=false;});request("select_box",null);request("world_block_gravity",null);
        try{Thread.sleep(80);}catch(InterruptedException e){throw new AssertionError(e);}ctx.runOnClient(mc->ColourField.generate());world.getConnection().waitForServerboundPackets();ctx.waitTicks(4);world.getConnection().waitForClientboundPackets();ctx.waitFor(mc->!ClientState.status.busy(),400);
        require(ctx.computeOnClient(mc->!ClientState.status.error()),"Colour field generation failed");
        require(world.getServer().computeOnServer(server->{var types=new java.util.HashSet<net.minecraft.world.level.block.Block>();for(int x=80;x<=91;x++)for(int y=30;y<=41;y++)for(int z=50;z<=61;z++){var state=server.overworld().getBlockState(new BlockPos(x,y,z));if(!state.isAir())types.add(state.getBlock());}return types.size()>100;}),"Colour field did not place a diverse collection without collisions");request("undo",null);
        options(o->o.worldToggle=true);request("world_block_gravity",null);request("clear",null);System.out.println("CRAFTIUM_COLOUR_FIELD_PASS cielab unique_placement undo");
    }
    private void documentedWorkflows(){
        world.getServer().runOnServer(server->{
            try{var tag=new net.minecraft.nbt.CompoundTag();tag.putShort("Width",(short)2);tag.putShort("Height",(short)1);tag.putShort("Length",(short)1);tag.putByteArray("Blocks",new byte[]{1,53});tag.putByteArray("Data",new byte[]{0,0});var path=net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("craftium/blueprints/Craftium-legacy.schematic");java.nio.file.Files.createDirectories(path.getParent());net.minecraft.nbt.NbtIo.writeCompressed(tag,path);}catch(Exception e){throw new AssertionError(e);}
        });
        options(o->{o.name="Craftium-legacy.schematic";o.offsetX=0;o.offsetY=1;o.offsetZ=0;});request("schematic_import",null);
        require(ctx.computeOnClient(mc->ClientState.status.clipboardSize()==2),"Legacy schematic lost blocks");
        ctx.runOnClient(mc->PlacementPreview.begin(new Point(28,15,20)));ctx.waitTick();checkBlock(new Point(28,15,20),Blocks.AIR);
        ctx.runOnClient(mc->PlacementPreview.confirm());world.getConnection().waitForServerboundPackets();ctx.waitTicks(5);world.getConnection().waitForClientboundPackets();ctx.waitFor(mc->!ClientState.status.busy(),400);
        checkBlock(new Point(28,15,20),Blocks.STONE);checkBlock(new Point(29,15,20),Blocks.OAK_STAIRS);request("undo",null);checkBlock(new Point(28,15,20),Blocks.AIR);
        options(o->{o.infiniteReach=true;o.noClip=true;});request("capabilities",null);ctx.waitTicks(2);
        require(world.getServer().computeOnServer(server->world.getConnection().getServerPlayer().getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_INTERACTION_RANGE).hasModifier(net.minecraft.resources.Identifier.fromNamespaceAndPath("craftium","builder_reach"))),"Reach modifier was not enabled");
        require(world.getServer().computeOnServer(server->dev.craftium.server.EditorServer.noClip(world.getConnection().getServerPlayer())),"No-clip capability was not synchronized");
        options(o->{o.infiniteReach=false;o.noClip=false;o.worldToggle=true;});request("capabilities",null);request("world_player_invulnerability",null);
        world.getServer().runOnServer(server->{var player=world.getConnection().getServerPlayer();player.kill(server.overworld());require(player.isAlive(),"Invulnerability did not prevent /kill");player.hurtServer(server.overworld(),player.damageSources().fellOutOfWorld(),1000);require(player.isAlive(),"Invulnerability did not prevent void damage");});
        options(o->o.worldToggle=false);request("world_player_invulnerability",null);request("world_block_gravity",null);request("world_trample_farmland",null);request("world_block_drops",null);
        world.getServer().runOnServer(server->{var level=server.overworld();level.setBlock(new BlockPos(25,12,25),Blocks.SAND.defaultBlockState(),3);});ctx.waitTicks(12);checkBlock(new Point(25,12,25),Blocks.SAND);
        world.getServer().runOnServer(server->{var level=server.overworld();var pos=new BlockPos(26,12,25);var state=Blocks.FARMLAND.defaultBlockState();level.setBlock(pos,state,2);for(int i=0;i<50;i++)Blocks.FARMLAND.fallOn(level,state,pos,world.getConnection().getServerPlayer(),20);require(level.getBlockState(pos).is(Blocks.FARMLAND),"Farmland rule did not prevent trampling");var bounds=new net.minecraft.world.phys.AABB(20,10,20,30,20,30);int before=level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,bounds).size();net.minecraft.world.level.block.Block.popResource(level,pos,new ItemStack(Items.DIAMOND));net.minecraft.world.Containers.dropItemStack(level,26,12,25,new ItemStack(Items.DIAMOND));require(level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,bounds).size()==before,"Block drop rule did not prevent drops");});
        options(o->o.worldToggle=true);request("world_block_gravity",null);request("world_trample_farmland",null);request("world_block_drops",null);
        options(o->{o.materials="minecraft:stone,minecraft:andesite,minecraft:deepslate";});request("palette_hotbar",null);
        require(world.getServer().computeOnServer(server->world.getConnection().getServerPlayer().getInventory().getItem(2).is(Items.DEEPSLATE)),"Gradient hotbar export failed");
        System.out.println("CRAFTIUM_DOCUMENTED_PASS legacy_schematic pending_placement reach world_rules gradient_hotbar");
    }
    private void remainingTools(){
        options(o->{o.selectionPoints=new java.util.ArrayList<>(java.util.List.of(new Point(30,15,30),new Point(31,15,30),new Point(30,16,30)));o.mode=Selection.Mode.REPLACE;});request("lasso_select",null);
        require(ctx.computeOnClient(mc->SelectionCodec.decode(ClientState.status.selectionData()).size()==3),"Irregular selection data was not synchronized");
        options(o->{o.tool=Tool.FLOODFILL;o.block="minecraft:gold_block";o.floodLimit=9;o.floodUp=false;o.floodDown=false;o.floodHorizontal=true;});request("use",new Point(40,15,30));checkBlock(new Point(40,15,30),Blocks.GOLD_BLOCK);request("undo",null);checkBlock(new Point(40,15,30),Blocks.AIR);
        world.getServer().runOnServer(server->{for(int x=37;x<=43;x++)for(int z=27;z<=33;z++)server.overworld().setBlock(new BlockPos(x,9,z),Blocks.STONE.defaultBlockState(),2);});
        options(o->{o.tool=Tool.FLUID_BALL;o.fluidType=Options.FluidType.SNOW;o.radius=1;o.requireSupport=true;o.flowLength=3;});request("use",new Point(40,9,30));checkBlock(new Point(40,10,30),Blocks.SNOW);request("undo",null);checkBlock(new Point(40,10,30),Blocks.AIR);
        options(o->{o.position1=new Point(30,15,30);o.position2=o.position1;o.block="minecraft:gold_block";});request("select_box",null);request("fill",null);request("copy",null);
        world.getServer().runOnServer(server->server.overworld().setBlock(new BlockPos(33,15,30),Blocks.STONE.defaultBlockState(),2));
        options(o->{o.tool=Tool.SMEAR;o.offsetX=5;o.offsetY=0;o.offsetZ=0;});request("use",new Point(30,15,30));checkBlock(new Point(32,15,30),Blocks.GOLD_BLOCK);checkBlock(new Point(34,15,30),Blocks.AIR);request("undo",null);checkBlock(new Point(32,15,30),Blocks.AIR);
        options(o->{o.tool=Tool.STAMP;o.baseChance=1;o.randomYaw=false;o.randomFlip=false;o.minSpacing=1;o.radius=1;});request("use",new Point(40,15,35));checkBlock(new Point(40,16,35),Blocks.GOLD_BLOCK);request("undo",null);
        options(o->{o.tool=Tool.MODIFY;o.modifyMode=Options.ModifyMode.TRANSLATE_COPIES;o.copies=2;o.offsetX=3;o.offsetY=0;o.offsetZ=4;});request("use",new Point(30,15,30));checkBlock(new Point(33,15,34),Blocks.GOLD_BLOCK);checkBlock(new Point(36,15,38),Blocks.GOLD_BLOCK);request("undo",null);
        options(o->{o.name="Integration/Golden";o.author="Craftium Tests";o.tags="test statue";});request("blueprint_save",null);request("blueprint_list",null);
        require(ctx.computeOnClient(mc->ClientState.blueprints.stream().anyMatch(e->e.file().equals("Integration/Golden.nbt")&&e.author().equals("Craftium Tests")&&e.thumbnail().size()==256)),"Nested blueprint metadata or thumbnails were lost");
        options(o->{o.entityType="block_display";o.entityUuid="";o.entityNbt="{block_state:{Name:\"minecraft:stone\"}}";});request("entity_spawn",new Point(30,20,30));String id=ctx.computeOnClient(mc->ClientState.options.entityUuid);
        options(o->{o.entityX=.25;o.entityY=.5;o.entityZ=.75;o.entityScaleX=2;o.entityScaleY=2;o.entityScaleZ=2;o.yaw=45;});request("entity_transform",new Point(30,20,30));
        options(o->{o.position1=new Point(29,19,29);o.position2=new Point(32,23,32);o.entityGroup="Integration group";});request("select_box",null);request("entity_group",new Point(30,20,30));
        require(world.getServer().computeOnServer(server->server.overworld().getEntity(java.util.UUID.fromString(id)).entityTags().contains("craftium:Integration group")),"Display grouping did not retain its tag");
        options(o->{o.entityX=2;o.entityY=0;o.entityZ=0;o.yaw=0;o.entityScaleX=1;o.entityScaleY=1;o.entityScaleZ=1;});request("entity_group_transform",new Point(30,20,30));
        require(world.getServer().computeOnServer(server->Math.abs(server.overworld().getEntity(java.util.UUID.fromString(id)).getX()-32.5)<.001),"Group translation did not move the display");request("undo",null);
        options(o->{o.nodes.clear();o.yaw=0;o.offsetX=0;o.offsetY=1;o.offsetZ=0;o.radius=5;o.requireSupport=false;o.selectionPoints.clear();});request("clear",null);
        options(o->{o.annotationMode="DRAW";o.annotationPoints=new java.util.ArrayList<>(java.util.List.of(new Point(0,20,8),new Point(1,21,8),new Point(2,20,8)));});request("annotation_create",new Point(0,20,8));
        require(ctx.computeOnClient(mc->ClientState.status.annotations().getFirst().points().size()==3),"Freehand annotation lost points");request("undo",null);
        options(o->{o.annotationMode="IMAGE";o.annotationImageWidth=2;o.annotationImageHeight=2;o.annotationPixels=new java.util.ArrayList<>(java.util.List.of(0xffff0000,0xff00ff00,0xff0000ff,0xffffffff));});request("annotation_create",new Point(0,20,8));ctx.waitTicks(4);
        require(ctx.computeOnClient(mc->ClientState.status.annotations().getFirst().pixels().size()==4),"Image annotation pixels were not synchronized");request("undo",null);
        options(o->{o.annotationMode="TEXT";o.annotationPixels.clear();o.annotationPoints.clear();o.block="minecraft:gold_block";o.position1=new Point(30,15,30);o.position2=new Point(32,17,32);});request("select_box",null);request("selection_filter",null);
        require(ctx.computeOnClient(mc->ClientState.status.selectionSize()==1),"Selection filter retained blocks of the wrong type");request("selection_bounds",null);
        options(o->{o.sizeX=2;o.sizeY=2;o.sizeZ=2;});request("selection_scale",null);require(ctx.computeOnClient(mc->ClientState.status.selectionSize()==8),"Selection scaling did not fill its resampled volume");options(o->{o.sizeX=1;o.sizeY=1;o.sizeZ=1;});request("clear",null);
        options(o->{o.sizeX=2;o.sizeY=2;o.sizeZ=2;});request("clipboard_transform",null);require(ctx.computeOnClient(mc->ClientState.status.clipboardSize()==8),"Clipboard scaling did not replicate a single block");options(o->{o.sizeX=1;o.sizeY=1;o.sizeZ=1;});
        request("paste_select",new Point(44,20,32));require(ctx.computeOnClient(mc->ClientState.status.selectionSize()==8),"Paste and Select did not select the placement");checkBlock(new Point(45,21,33),Blocks.GOLD_BLOCK);request("undo",null);
        world.getServer().runOnServer(server->{server.overworld().setBlock(new BlockPos(42,18,32),Blocks.STONE_SLAB.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SLAB_TYPE,net.minecraft.world.level.block.state.properties.SlabType.TOP),2);server.overworld().setBlock(new BlockPos(44,18,32),Blocks.STONE_SLAB.defaultBlockState(),2);});
        options(o->{o.position1=new Point(42,18,32);o.position2=o.position1;o.mergeBlocks=true;});request("select_box",null);request("copy",null);request("paste_at",new Point(44,18,32));
        require(world.getServer().computeOnServer(server->server.overworld().getBlockState(new BlockPos(44,18,32)).getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SLAB_TYPE)==net.minecraft.world.level.block.state.properties.SlabType.DOUBLE),"Merge Blocks did not combine opposite slabs");request("undo",null);options(o->o.mergeBlocks=false);request("clear",null);
        options(o->o.name="Integration hotbar");request("hotbar_save",null);world.getServer().runOnServer(server->world.getConnection().getServerPlayer().getInventory().setItem(0,new ItemStack(Items.DIAMOND,17)));request("hotbar_load",null);
        require(world.getServer().computeOnServer(server->world.getConnection().getServerPlayer().getInventory().getItem(0).is(Items.STONE)),"Named hotbar load lost the saved stack");
        request("hotbar_next",null);require(world.getServer().computeOnServer(server->world.getConnection().getServerPlayer().getInventory().getItem(0).isEmpty()),"New hotbar slot should start empty");world.getServer().runOnServer(server->world.getConnection().getServerPlayer().getInventory().setItem(0,new ItemStack(Items.EMERALD,9)));request("hotbar_previous",null);
        require(world.getServer().computeOnServer(server->world.getConnection().getServerPlayer().getInventory().getItem(0).is(Items.STONE)),"Cycling back did not restore the original hotbar");request("hotbar_next",null);
        require(world.getServer().computeOnServer(server->world.getConnection().getServerPlayer().getInventory().getItem(0).is(Items.EMERALD)&&world.getConnection().getServerPlayer().getInventory().getItem(0).getCount()==9),"Hotbar auto-save did not preserve the new slot");request("hotbar_previous",null);
        options(o->{o.tool=Tool.TEXT;o.text="A";o.block="minecraft:stone";o.fontSize=8;o.useBuiltinFont=false;o.fullBlocks=false;});request("use",new Point(45,20,36));
        require(world.getServer().computeOnServer(server->{for(int x=45;x<60;x++)for(int y=20;y<30;y++)if(server.overworld().getBlockState(new BlockPos(x,y,36)).is(Blocks.STONE_SLAB))return true;return false;}),"Half-block text did not rasterize into slabs");request("undo",null);options(o->{o.text="CRAFTIUM";o.useBuiltinFont=true;o.fullBlocks=true;});
        System.out.println("CRAFTIUM_NEW_TOOLS_PASS lasso floodfill fluid_ball smear stamp modify blueprint_metadata display_transform_groups");
    }
    private void biomeAndStrokeHistory() {
        var original=world.getServer().computeOnServer(server->server.overworld().getNoiseBiome(0,0,0));
        options(o->{o.tool=Tool.BIOME_PAINTER;o.biome="minecraft:badlands";o.radius=3;});request("use",new Point(0,0,0));
        require(world.getServer().computeOnServer(server->!server.overworld().getNoiseBiome(0,0,0).equals(original)),"Biome painter did not change the biome");
        request("undo",null);
        require(world.getServer().computeOnServer(server->server.overworld().getNoiseBiome(0,0,0).equals(original)),"Undo did not restore the biome");
        options(o->{o.tool=Tool.FREEHAND_DRAW;o.radius=1;o.block="minecraft:gold_block";});
        int before=ctx.computeOnClient(mc->ClientState.status.undo());
        ctx.runOnClient(mc->ClientState.strokeId=1234567);
        request("use",new Point(0,12,0));request("use",new Point(4,12,0));
        ctx.runOnClient(mc->ClientState.strokeId=0);
        require(ctx.computeOnClient(mc->ClientState.status.undo())==before+1,"A continuous stroke should produce one history entry");
        request("undo",null);checkBlock(new Point(0,12,0),Blocks.AIR);checkBlock(new Point(4,12,0),Blocks.AIR);
    }
    private void screenshot() {
        world.getServer().runOnServer(server->{
            var level=server.overworld();
            for(int x=-18;x<=18;x++)for(int z=-15;z<=15;z++) {
                int top=(int)(5+Math.sin(x*.18)*3+Math.cos(z*.23)*3);
                for(int y=-1;y<=top;y++)level.setBlock(new BlockPos(x,y,z),(y==top?Blocks.GRASS_BLOCK:y>top-3?Blocks.DIRT:Blocks.STONE).defaultBlockState(),2|16);
            }
        });
        options(o->{o.tool=Tool.BOX_SELECT;o.radius=5;o.block="minecraft:stone";o.noUpdates=false;});
        request("use",new Point(-9,0,-5));request("use",new Point(0,10,5));
        world.getServer().runCommand("tp @a 30 24 34 137 26");ctx.waitTicks(30);world.getConnection().waitForChunksRender();
        world.getServer().runCommand("tp @a 30 24 34 137 26");
        world.getServer().runOnServer(server->{var p=server.getPlayerList().getPlayers().getFirst();p.getAbilities().flying=true;p.onUpdateAbilities();});
        ctx.waitTicks(8);
        ctx.runOnClient(mc->{mc.getWindow().setWindowed(1440,900);mc.options.guiScale().set(2);mc.resizeGui();ClientState.notice="";CraftiumClient.open(false);});
        ctx.waitTicks(12);System.out.println("CRAFTIUM_EDITOR_SCREENSHOT "+ctx.takeScreenshot("craftium-editor"));
        ctx.runOnClient(mc->CraftiumClient.open(true));ctx.waitTicks(6);ctx.takeScreenshot("craftium-builder");
        ctx.runOnClient(mc->{ClientState.options.tool=Tool.NOISE_PAINTER;mc.gui.setScreen(new EditorScreen(false));});ctx.waitTicks(6);ctx.takeScreenshot("craftium-noise-painter");
        uiControls();
    }
    private void uiControls() {
        ctx.runOnClient(mc->mc.gui.setScreen(new StudioScreen((EditorScreen)mc.gui.screen())));ctx.waitTicks(4);
        require(ctx.computeOnClient(mc->mc.gui.screen() instanceof StudioScreen),"Inspector did not open");ctx.takeScreenshot("craftium-inspector");
        ctx.runOnClient(mc->{var studio=(StudioScreen)mc.gui.screen();mc.gui.setScreen(new ColourScreen(studio));});ctx.waitTicks(5);ctx.takeScreenshot("craftium-colour-studio");
        ctx.runOnClient(mc->{mc.gui.screen().onClose();mc.gui.setScreen(new PaletteScreen(mc.gui.screen()));});ctx.waitTicks(4);ctx.takeScreenshot("craftium-weighted-palette");
        ctx.runOnClient(mc->{mc.gui.screen().onClose();ClientState.options.maskExpression="all(block(minecraft:stone),not(above(minecraft:air)),any(surface,sky))";mc.gui.setScreen(new MaskScreen(mc.gui.screen()));});ctx.waitTicks(4);ctx.takeScreenshot("craftium-visual-masks");ctx.runOnClient(mc->{mc.gui.screen().onClose();ClientState.options.maskExpression="";mc.gui.setScreen(new ColourScreen(mc.gui.screen()));});
        ctx.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE);ctx.waitTicks(2);
        ctx.runOnClient(mc->mc.gui.setScreen(new BlueprintScreen((StudioScreen)mc.gui.screen())));ctx.waitTicks(5);ctx.takeScreenshot("craftium-blueprint-browser");
        ctx.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE);ctx.waitTicks(2);ctx.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE);ctx.waitTicks(2);
        require(ctx.computeOnClient(mc->mc.gui.screen() instanceof EditorScreen),"Inspector return did not restore the editor");
        ctx.runOnClient(mc->{try(var stream=mc.getResourceManager().getResourceOrThrow(net.minecraft.resources.Identifier.fromNamespaceAndPath("craftium","icon.png")).open()){var image=java.nio.file.Files.createTempFile("craftium-reference-",".png");java.nio.file.Files.copy(stream,image,java.nio.file.StandardCopyOption.REPLACE_EXISTING);ReferenceImages.load(image);}catch(Exception e){throw new AssertionError(e);}});ctx.waitTicks(3);ctx.takeScreenshot("craftium-reference-image");ctx.runOnClient(mc->ReferenceImages.closeAll());
        ctx.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_M);
        require(ctx.computeOnClient(mc->ClientState.options.tool==Tool.MAGIC_SELECT),"Editor shortcut did not select Magic Select");
        require(ctx.computeOnClient(mc->mc.gui.hud.isHidden()),"Vanilla HUD should be hidden while editing");
        // Drive the actual menu and modal event handlers in GUI coordinates.
        ctx.runOnClient(mc->{
            int x=108;for(String label:new String[]{"File","Edit","Select","View","Create"})x+=mc.font.width(label)+16;
            click(mc,(x+12),12);
        });ctx.waitTicks(2);
        ctx.runOnClient(mc->{int x=108;for(String label:new String[]{"File","Edit","Select","View","Create"})x+=mc.font.width(label)+16;click(mc,x+18,89);});
        ctx.waitTicks(2);ctx.takeScreenshot("craftium-selection-transforms");
        ctx.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE);ctx.waitTick();
        ctx.getInput().pressKey(CraftiumClient.editorKey);ctx.waitTick();
        require(ctx.computeOnClient(mc->mc.gui.screen()==null&&!mc.gui.hud.isHidden()),"Closing the editor should restore the HUD");
        System.out.println("CRAFTIUM_UI_PASS shortcuts menus modal hud_restore");
    }
    private void click(net.minecraft.client.Minecraft mc,double x,double y) {
        var screen=mc.gui.screen();float s=Math.min(1f,Math.min(screen.width/1000f,screen.height/562f));
        var event=new net.minecraft.client.input.MouseButtonEvent(x*s,y*s,new net.minecraft.client.input.MouseButtonInfo(0,0));
        screen.mouseClicked(event,false);screen.mouseReleased(event);
    }
    private void checkBlock(Point p,net.minecraft.world.level.block.Block block) {
        require(world.getServer().computeOnServer(server->server.overworld().getBlockState(new BlockPos(p.x(),p.y(),p.z())).is(block)),"Unexpected block at "+p.coordinates()+", expected "+block);
    }
    private void checkChest(Point p) {
        require(world.getServer().computeOnServer(server->{var e=server.overworld().getBlockEntity(new BlockPos(p.x(),p.y(),p.z()));return e instanceof ChestBlockEntity chest&&chest.getItem(0).is(Items.DIAMOND)&&chest.getItem(0).getCount()==17;}),"Chest inventory was not preserved at "+p.coordinates());
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
