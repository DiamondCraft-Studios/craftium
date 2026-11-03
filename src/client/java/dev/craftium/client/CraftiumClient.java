package dev.craftium.client;

import dev.craftium.client.compat.Overlay;
import dev.craftium.client.compat.ClientCompat;
import dev.craftium.client.compat.Gfx;
import com.mojang.blaze3d.platform.InputConstants;
import dev.craftium.core.*;
import dev.craftium.net.Packets;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import org.lwjgl.glfw.GLFW;
import java.util.List;

public final class CraftiumClient implements ClientModInitializer {
    public static KeyMapping editorKey,contextKey,swapKey,replaceKey;
    public static final java.util.Map<Tool,KeyMapping> toolKeys=new java.util.EnumMap<>(Tool.class);
    private long lastBuilder;
    private long breakingSince;
    private boolean hadWorld,toolCursor;
    private double lastLiquidOpacity=1;
    @Override public void onInitializeClient() {
        Overlay.install();
        ClientState.load();EditorBindings.load();
        ClientPlayNetworking.registerGlobalReceiver(Packets.Bridge.TYPE,(payload,context)->context.client().execute(()->receiveBridge(payload.bytes())));
        ClientCommands.register();
        ClientPlayNetworking.registerGlobalReceiver(Packets.Download.TYPE,(payload,context)->context.client().execute(()->ClientAssets.receive(payload)));
        var category=KeyMapping.Category.register(dev.craftium.compat.Compat.id("craftium","studio"));
        editorKey=ClientCompat.registerKey(new KeyMapping("key.craftium.editor",InputConstants.Type.KEYSYM,GLFW.GLFW_KEY_RIGHT_SHIFT,category));
        contextKey=ClientCompat.registerKey(new KeyMapping("key.craftium.context",InputConstants.Type.KEYSYM,GLFW.GLFW_KEY_LEFT_ALT,category));
        swapKey=ClientCompat.registerKey(new KeyMapping("key.craftium.hotbar",InputConstants.Type.KEYSYM,GLFW.GLFW_KEY_UNKNOWN,category));
        replaceKey=ClientCompat.registerKey(new KeyMapping("key.craftium.replace",InputConstants.Type.KEYSYM,GLFW.GLFW_KEY_R,category));
        for(Tool tool:Tool.values()){
            int key=EditorControls.toolDefault(tool);
            toolKeys.put(tool,ClientCompat.registerKey(new KeyMapping("key.craftium.tool."+tool.name().toLowerCase(java.util.Locale.ROOT),InputConstants.Type.KEYSYM,key,category)));
        }
        ClientPlayNetworking.registerGlobalReceiver(Packets.Report.TYPE,(payload,context)->context.client().execute(()->ClientCompat.setScreen(context.client(),new ReportScreen(ClientCompat.screen(context.client()),payload.text()))));
        ClientPlayNetworking.registerGlobalReceiver(Packets.Update.TYPE,(payload,context)->context.client().execute(()->{
            receiveStatus(payload.json());
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)-> {
            ClientState.status=new Packets.Status("Disconnected",false,false,0,new Point(0,0,0),new Point(0,0,0),0,List.of(),0,0);ClientState.pending=GhostBlocks.EMPTY;ClientState.largeSelection=0;
            ClientState.save();ClientState.duplicatePending=false;ClientState.pendingPlacementOrigin=null;
            BuilderMode.reset();placementSynced="";
            ClientCommands.reset();
            ClientAssets.clear();bridge.clear();
            SelectionOverlay.update("");PlacementPreview.cancel();PlacementPreview.update("");
            ClientState.rulerStart=null;ClientState.rulerEnd=null;
            ClientState.rulerPoints.clear();ClientState.blueprints=List.of();ClientState.libraryVersion++;
        });
        // What the shared placement mixins ask about the local player; only when the server runs the mod too, as it must agree with the client.
        dev.craftium.runtime.PlayerCapabilities.client=player->{var mc=Minecraft.getInstance();return player==mc.player&&player.isCreative()&&vanillaPlacement()?ClientState.options:null;};
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        UseBlockCallback.EVENT.register((player,level,hand,hit)->{
            var o=ClientState.options;
            boolean block=player.getMainHandItem().getItem() instanceof BlockItem;
            boolean tinker=player.getMainHandItem().isEmpty()&&o.tinker&&!player.isShiftKeyDown();
            if(!level.isClientSide() || !player.isCreative() || !supported() || ClientCompat.screen(Minecraft.getInstance())!=null)return InteractionResult.PASS;
            if(block&&vanillaPlacement()){syncPlacement();return InteractionResult.PASS;} // the game places the block; the mod bends it from the server and the client alike
            // Without the mod on the server, nothing there can bend vanilla placement, so the click is sent as an editor request instead.
            if(block&&o.placementCapabilities()||tinker)return InteractionResult.SUCCESS; // SUCCESS skips the server use, and the client swings the arm for it
            return InteractionResult.PASS;
        });
        AttackBlockCallback.EVENT.register((player,level,hand,pos,face)->
            level.isClientSide() && player.isCreative() && supported() && (ClientState.symmetry()||ClientState.options.bulldozer||ClientState.options.infiniteReach) ? InteractionResult.FAIL:InteractionResult.PASS);
        HudElementRegistry.addLast(dev.craftium.compat.Compat.id("craftium","builder"),(raw,delta)->{
            var mc=Minecraft.getInstance();if(mc.player==null || !mc.player.isCreative() || ClientCompat.screen(mc)!=null)return;
            var g=Gfx.of(raw);
            ReferenceImages.draw(g,false,0,0);
            int h=g.guiHeight();
            if(System.currentTimeMillis()<ClientState.noticeUntil)g.text(mc.font,ClientState.notice,8,8,0xffffd891,false);
            BuilderMode.hud(g);
            if(ClientState.options.showKeyHints){StringBuilder keys=new StringBuilder();for(int key:new int[]{GLFW.GLFW_KEY_W,GLFW.GLFW_KEY_A,GLFW.GLFW_KEY_S,GLFW.GLFW_KEY_D,GLFW.GLFW_KEY_X,GLFW.GLFW_KEY_Y,GLFW.GLFW_KEY_Z})if(GLFW.glfwGetKey(mc.getWindow().handle(),key)==GLFW.GLFW_PRESS)keys.append((char)key).append(' ');if(mc.hasControlDown())keys.append("Ctrl ");if(mc.hasShiftDown())keys.append("Shift ");g.text(mc.font,keys.toString(),g.guiWidth()-mc.font.width(keys.toString())-10,h-48,0xffe6cf8b,false);}
        });
    }
    private static final BridgeCodec.Assembler bridge=new BridgeCodec.Assembler();
    private static void receiveStatus(String json){
            var next=Packets.JSON.fromJson(json,Packets.Status.class);
            if(next!=null){ClientState.status=next;HistoryPulse.receive(next);checkLargeSelection(next);applyPivot(next.pivotUpdate());if(next.message().startsWith("Undid ")||next.message().startsWith("Redid "))ClientState.boxCorners.clear();if(next.message().startsWith("Blueprint library")){ClientState.blueprints=next.blueprints()==null?List.of():next.blueprints();ClientState.libraryVersion++;}if(ClientState.duplicatePending&&(next.message().startsWith("Copied ")||next.message().startsWith("Applied Cut "))){ClientState.duplicatePending=false;PlacementPreview.begin(ClientState.pendingPlacementOrigin==null?next.min():ClientState.pendingPlacementOrigin);ClientState.pendingPlacementOrigin=null;}if(next.error()){ClientState.duplicatePending=false;ClientState.pendingPlacementOrigin=null;}if(ClientState.largeSelection>0)ClientState.heldSelection=next.selectionData();else SelectionOverlay.update(next.selectionData());PlacementPreview.update(next.clipboardData());PlacementPreview.updateGhost(next.clipboardGhost());ClientState.pending=GhostBlocks.parse(next.pendingData());if(next.entityUuid()!=null&&!next.entityUuid().isBlank()){ClientState.options.entityUuid=next.entityUuid();if(next.entityNbt()!=null&&!next.entityNbt().isBlank())ClientState.options.entityNbt=next.entityNbt();}if(next.error())ClientState.notify(next.message());}
    }
    /** Opens the confirmation when a selection action has just selected a suspiciously large area. */
    private static void checkLargeSelection(Packets.Status next){
        if(!ClientState.selectionPending)return;
        if(next.error()){ClientState.selectionPending=false;ClientState.selectingSince=0;return;}
        String message=next.message();
        if(!(message.startsWith("Selected ")||message.startsWith("Lasso selected ")||message.startsWith("Magic Wand: ")))return;
        ClientState.selectionPending=false;ClientState.selectingSince=0;
        if(next.selectionSize()-ClientState.selectionSizeBefore<ClientState.LARGE_SELECTION)return;
        var mc=Minecraft.getInstance();
        // Set at once, before this status is drawn, so the popup comes first and the selection is not built until it is confirmed.
        if(ClientCompat.screen(mc) instanceof EditorScreen)ClientState.largeSelection=next.selectionSize();
    }
    /** Undo and redo carry the pivot back along with the selection: "none" for the selection's centre, else "x,y,z". */
    private static void applyPivot(String update){
        if(update==null||update.isEmpty())return;
        var o=ClientState.options;
        if(update.equals("none")){o.pivotSet=false;return;}
        try{
            String[] part=update.split(",");
            o.pivotX=Double.parseDouble(part[0]);o.pivotY=Double.parseDouble(part[1]);o.pivotZ=Double.parseDouble(part[2]);o.pivotSet=true;
        }catch(RuntimeException e){/* an unreadable pivot is left as it was */}
    }
    private static void receiveBridge(byte[] frame){
        try{bridge.accept(frame).ifPresent(json->{
            var message=Packets.JSON.fromJson(json,BridgeCodec.Message.class);
            if(message==null||message.version()!=BridgeCodec.VERSION)throw new IllegalArgumentException("Update both Craftium client and server");
            var mc=Minecraft.getInstance();
            switch(message.kind()){
                case "status" -> receiveStatus(message.data());
                case "report" -> ClientCompat.setScreen(mc,new ReportScreen(ClientCompat.screen(mc),message.data()));
                case "download" -> ClientAssets.receive(Packets.JSON.fromJson(message.data(),Packets.Download.class));
                case "hello" -> send("status",null,null);
                default -> throw new IllegalArgumentException("Unknown Craftium server message");
            }
        });}catch(RuntimeException e){ClientState.notify("Craftium connection: "+e.getMessage());}
    }
    private void tick(Minecraft mc) {
        EditorBindings.migrate(mc);
        if(mc.player==null || mc.level==null){hadWorld=false;toolCursor=false;return;}
        ClientCommands.tick();
        BuilderMode.tick();
        SelectingIndicator.tick(mc);
        if(BuilderMode.active()&&held(contextKey)){if(!toolCursor){mc.mouseHandler.releaseMouse();toolCursor=true;}}
        else if(toolCursor){if(ClientCompat.screen(mc)==null)mc.mouseHandler.grabMouse();toolCursor=false;}
        if(lastLiquidOpacity!=ClientState.options.liquidOpacity){lastLiquidOpacity=ClientState.options.liquidOpacity;ClientCompat.rebuildChunks(mc);}
        if(!hadWorld){hadWorld=true;if(supported())send("status",null,null);}
        while(editorKey.consumeClick()) {
            if(ClientCompat.screen(mc) instanceof EditorScreen)ClientCompat.setScreen(mc,null);
            else if(ClientCompat.screen(mc)==null)open(false);
        }
        while(contextKey.consumeClick()){}
        if(ClientCompat.screen(mc)==null&&held(contextKey)&&!BuilderMode.active())open(true,true);
        if(supported())syncPlacement();
        preview(mc);
        if(ClientCompat.screen(mc)!=null)return;
        for(var entry:toolKeys.entrySet())while(entry.getValue().consumeClick()){}
        while(swapKey.consumeClick())if(!mc.hasControlDown())send("hotbar",null,null);
        while(replaceKey.consumeClick()) {ClientState.options.replaceMode=!ClientState.options.replaceMode;ClientState.notify("Replace mode "+(ClientState.options.replaceMode?"enabled":"disabled"));ClientState.save();}
        if(!mc.player.isCreative() || !supported())return;
        if(BuilderMode.active())return;
        var o=ClientState.options;long now=System.currentTimeMillis();
        if(o.enhancedFlight&&o.flightMomentum==0&&mc.player.getAbilities().flying && !mc.options.keyUp.isDown()&&!mc.options.keyDown.isDown()&&!mc.options.keyLeft.isDown()&&!mc.options.keyRight.isDown()&&!mc.options.keyJump.isDown()&&!mc.options.keyShift.isDown())
            mc.player.setDeltaMovement(Vec3.ZERO);
        boolean left=GLFW.glfwGetMouseButton(mc.getWindow().handle(),GLFW.GLFW_MOUSE_BUTTON_LEFT)==GLFW.GLFW_PRESS;
        boolean right=GLFW.glfwGetMouseButton(mc.getWindow().handle(),GLFW.GLFW_MOUSE_BUTTON_RIGHT)==GLFW.GLFW_PRESS;
        if(!left)breakingSince=0;else if(breakingSince==0)breakingSince=now;
        if(now-lastBuilder>(o.fastPlace?70:125) && !ClientState.status.busy()) {
            BlockHitResult hit=mc.hitResult instanceof BlockHitResult b && b.getType()==HitResult.Type.BLOCK?b:null;
            if(hit==null&&o.angelPlacement&&right)hit=angelHit(mc);
            boolean canPlace=mc.player.getMainHandItem().getItem() instanceof BlockItem && o.placementCapabilities();
            boolean canTinker=o.tinker&&mc.player.getMainHandItem().isEmpty()&&!mc.player.isShiftKeyDown();
            if(hit!=null && right && canPlace && vanillaPlacement()) {
                // The game places the block itself. It only needs a nudge where it would not click by itself: faster than its own repeat, or at a point in the air.
                if(o.fastPlace||mc.hitResult!=hit){
                    var result=mc.gameMode.useItemOn(mc.player,net.minecraft.world.InteractionHand.MAIN_HAND,hit);
                    if(result.consumesAction())mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                    lastBuilder=now;
                }
            } else if(hit!=null && ((left&&(ClientState.symmetry()||o.infiniteReach||o.bulldozer&&now-breakingSince>350))||(right&&(canPlace||canTinker)))) {
                if(mc.player.getMainHandItem().getItem() instanceof BlockItem item)ClientState.pick(BuiltInRegistries.BLOCK.getKey(item.getBlock()).toString());
                send(left?"builder_break":"builder_place",point(hit.getBlockPos()),normal(hit));lastBuilder=now;
                if(left)mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND); // the attack callback still returns FAIL, which does not swing
            }
        }
    }
    public static boolean held(KeyMapping mapping){
        if(mapping.isUnbound())return false;var key=InputConstants.getKey(mapping.saveString());long window=Minecraft.getInstance().getWindow().handle();return key.getType()==InputConstants.Type.MOUSE?GLFW.glfwGetMouseButton(window,key.getValue())==GLFW.GLFW_PRESS:InputConstants.isKeyDown(Minecraft.getInstance().getWindow(),key.getValue());
    }
    public static boolean ingameKey(net.minecraft.client.input.KeyEvent event,int action){
        var mc=Minecraft.getInstance();if(action!=GLFW.GLFW_PRESS||ClientCompat.screen(mc)!=null||mc.player==null||!mc.player.isCreative()||!supported())return false;
        if(EntityShortcuts.ingame(event))return true;
        if(EditorBindings.matches(EditorControls.Action.INGAME_COPY,event)){
            var hit=mc.hitResult instanceof BlockHitResult b&&b.getType()==HitResult.Type.BLOCK?b:null;
            if(hit!=null){send("pick_block",point(hit.getBlockPos()),normal(hit));return true;}
        }
        if(EditorBindings.matches(EditorControls.Action.INGAME_DELETE,event)||event.key()==GLFW.GLFW_KEY_BACKSPACE&&(event.modifiers()&7)==0){
            if(BuilderMode.active())return false;var hit=mc.hitResult instanceof BlockHitResult b&&b.getType()==HitResult.Type.BLOCK?b:null;
            if(hit!=null){send("builder_break",point(hit.getBlockPos()),normal(hit));return true;}
        }
        return false;
    }
    public static void open(boolean builder){open(builder,false);}
    private static void open(boolean builder,boolean heldContext) {
        Minecraft mc=Minecraft.getInstance();
        if(mc.player==null)return;
        ClientCompat.setScreen(mc,new EditorScreen(builder,heldContext));
        if(builder&&ClientState.options.autoSwapToCreative&&supported()&&!mc.player.isCreative())send("gamemode_creative",null,null);
        else if(!builder&&supported()&&!mc.player.isSpectator())send("gamemode_editor",null,null);
        if(supported())send("status",null,null);
    }
    /** Whether the server runs the mod itself, so it can bend the game's own block placement. A Paper server reached over the bridge cannot. */
    public static boolean vanillaPlacement(){return supported()&&ClientPlayNetworking.canSend(Packets.Edit.TYPE);}
    private static String placementSynced="";
    /**
     * Tells the server the capabilities it reads on its own, as soon as one changes, so a toggle takes effect at once
     * without the editor being closed. Placement then finds them there, and so does breaking.
     */
    private static void syncPlacement(){
        var o=ClientState.options;
        String key=o.symmetryX+","+o.symmetryY+","+o.symmetryZ+","+o.symmetryRotations+","+o.symmetryOrigin+","+o.symmetryHalfX+o.symmetryHalfY+o.symmetryHalfZ+","+o.replaceMode+o.forcePlace+o.noUpdates+o.fastPlace+o.infiniteReach+o.angelPlacement+o.preventInteractions+o.noClip;
        if(key.equals(placementSynced))return;
        placementSynced=key;send("capabilities",null,null);
    }
    /**
     * A click on the empty block four blocks ahead. The game treats air as replaceable, so it places into the clicked block itself,
     * which makes that block the place for the ghost and the placement alike.
     */
    static BlockHitResult angelHit(Minecraft mc){
        Vec3 at=mc.player.getEyePosition().add(mc.player.getLookAngle().scale(4));
        return new BlockHitResult(at,net.minecraft.core.Direction.UP,net.minecraft.core.BlockPos.containing(at),false);
    }
    public static boolean supported() {return Minecraft.getInstance().getConnection()!=null && (ClientPlayNetworking.canSend(Packets.Edit.TYPE)||ClientPlayNetworking.canSend(Packets.Bridge.TYPE));}
    public static void send(String action,Point target,Point normal) {
        if(!supported()){ClientState.notify("Install Craftium on this server to edit worlds");return;}
        var options=ClientState.options;boolean colours=action.equals("autoshade")&&options.shadeAutomatic||action.equals("use")&&(options.script.contains("getBlockRGB")||options.script.contains("findClosestBlockToRGB"))||options.maskScript.contains("getBlockRGB")||options.maskScript.contains("findClosestBlockToRGB");options.scriptColours=colours?new java.util.ArrayList<>(BlockColours.snapshot()):new java.util.ArrayList<>();
        ClientAssets.expect(action);
        if(action.equals("select_box")||action.equals("lasso_select")||action.equals("use")&&ClientState.options.tool.selection()){ClientState.selectionPending=true;ClientState.selectingSince=System.currentTimeMillis();ClientState.selectionSizeBefore=ClientState.status.selectionSize();}
        try{String request=Packets.JSON.toJson(new Packets.Request(action,target,normal,ClientState.options,action.equals("use")?ClientState.strokeId:0));
            if(ClientPlayNetworking.canSend(Packets.Edit.TYPE))ClientPlayNetworking.send(new Packets.Edit(request));
            else for(byte[] frame:BridgeCodec.encode(Packets.JSON.toJson(new BridgeCodec.Message("edit",request))))ClientPlayNetworking.send(new Packets.Bridge(frame));}finally{options.scriptColours=new java.util.ArrayList<>();}
    }
    public static Point point(net.minecraft.core.BlockPos p){return new Point(p.getX(),p.getY(),p.getZ());}
    public static Point normal(BlockHitResult hit){var d=hit.getDirection();return new Point(d.getStepX(),d.getStepY(),d.getStepZ());}
    public static BlockHitResult raycast(double guiX,double guiY) {
        Minecraft mc=Minecraft.getInstance();if(mc.player==null||mc.level==null)return null;
        var camera=ClientCompat.camera(mc);
        float nx=(float)(guiX/mc.getWindow().getGuiScaledWidth()*2-1),ny=(float)(1-guiY/mc.getWindow().getGuiScaledHeight()*2);
        Vec3 direction=ClientCompat.ray(camera,nx,ny);
        Vec3 start=camera.position();
        var hit=mc.level.clip(new ClipContext(start,start.add(direction.scale(256)),ClipContext.Block.OUTLINE,ClientState.options.solidFluids?ClipContext.Fluid.ANY:ClipContext.Fluid.NONE,mc.player));
        return hit.getType()==HitResult.Type.BLOCK?hit:null;
    }
    /** The transform handles: the builder's, or the editor's with the box corners and the point being dragged. Drawn each frame. */
    private static void handles(EditorScreen screen) {
        if(screen==null){var anchor=BuilderMode.gizmoAnchor();if(anchor!=null)TransformGizmo.display(anchor,BuilderMode.gizmoAxes());return;}
        if(ClientState.options.tool==Tool.BOX_SELECT)BoxHandles.draw(screen);
        TransformGizmo.draw(screen);
        Point handle=screen.handlePoint();if(handle!=null){Vec3 anchor=new Vec3(handle.x(),handle.y(),handle.z());int[] colors={0xffff7777,0xff8bef9a,0xff78b9ff};for(int axis=0;axis<3;axis++)Overlay.line(anchor,anchor.add(axis==0?3:0,axis==1?3:0,axis==2?3:0),colors[axis],screen.handleAxis()==axis?4:2);}
    }
    private static void preview(Minecraft mc) {
        var screen=ClientCompat.screen(mc) instanceof EditorScreen editor?editor:ClientCompat.screen(mc) instanceof StudioScreen studio?studio.editor():null;
        var hit=screen==null?null:screen.target();
        try(var collector=Overlay.begin(mc)) {
            WorldDiagnostics.draw(mc);
            BuilderMode.draw();
            AngelGhost.draw(mc);
            HistoryPulse.draw();
            if(screen!=null&&screen.bucketDragging())MagicPreview.drawBucket(mc,screen.bucketTarget());else MagicPreview.draw(mc,hit);
            ClientState.pending.draw(new Point(0,0,0),true);
            if(ClientState.options.showMarkerGizmos||ClientState.options.showDisplayGizmos){int count=0;for(var entity:mc.level.entitiesForRendering()){boolean show=entity instanceof net.minecraft.world.entity.Marker&&ClientState.options.showMarkerGizmos||entity instanceof net.minecraft.world.entity.Display&&ClientState.options.showDisplayGizmos;if(!show||entity.distanceToSqr(mc.player)>128*128||++count>256)continue;Vec3 at=entity.position();int color=entity.getUUID().toString().equals(ClientState.options.entityUuid)?0xffffd66d:0xffe6edf7;Overlay.point(at,color,6);Overlay.box(new AABB(at.add(-.08,-.08,-.08),at.add(.08,.08,.08)),color,1,0);}}
            if(ClientState.options.showAnnotations&&ClientState.status.annotations()!=null)for(var note:ClientState.status.annotations()){
                Point a=note.from(),b=note.to();Vec3 start=new Vec3(a.x()+.5,a.y()+.5,a.z()+.5),end=new Vec3(b.x()+.5,b.y()+.5,b.z()+.5);
                if(note.mode().equals("IMAGE")){AnnotationImages.draw(note);continue;}
                if(note.mode().equals("DRAW")&&note.points()!=null&&note.points().size()>1){for(int i=1;i<note.points().size();i++){Point a0=note.points().get(i-1),b0=note.points().get(i);Overlay.line(new Vec3(a0.x()+.5,a0.y()+.5,a0.z()+.5),new Vec3(b0.x()+.5,b0.y()+.5,b0.z()+.5),note.color(),note.width());}continue;}
                if(note.mode().equals("TEXT"))Overlay.text(note.text(),new net.minecraft.core.BlockPos(a.x(),a.y(),a.z()),note.color(),0x88151c26,.025f);
                else if(note.mode().equals("BOX"))Overlay.box(new AABB(start,end).inflate(.02),note.color(),note.width(),0);
                else Overlay.line(start,end,note.color(),note.width());
            }
            for(int i=1;i<ClientState.rulerPoints.size();i++) {
                Point a=ClientState.rulerPoints.get(i-1),b=ClientState.rulerPoints.get(i);Vec3 start=new Vec3(a.x()+.5,a.y()+.5,a.z()+.5),end=new Vec3(b.x()+.5,b.y()+.5,b.z()+.5);
                Overlay.line(start,end,0xffffd66d,2);
                if(ClientState.options.rulerMode==Options.RulerMode.CIRCLE){double radius=Math.sqrt(a.distanceSquared(b));for(int j=0;j<96;j++){double x=j*Math.PI/48,y=(j+1)*Math.PI/48;Overlay.line(start.add(Math.cos(x)*radius,0,Math.sin(x)*radius),start.add(Math.cos(y)*radius,0,Math.sin(y)*radius),0xffffd66d,1.5f);}}
                Overlay.text(String.format(java.util.Locale.ROOT,"%.2f blocks",Math.sqrt(a.distanceSquared(b))),new net.minecraft.core.BlockPos((a.x()+b.x())/2,(a.y()+b.y())/2+1,(a.z()+b.z())/2),0xffffe4a2,0xbb151c26,.02f);
            }
            // The handles follow the camera and the pointer, so they are drawn every frame and not with the tick's batch.
            Overlay.frame(()->handles(screen));
            if(screen==null)return;
            var nodes=ClientState.options.nodes;
            for(int i=0;i<nodes.size();i++){
                Point n=nodes.get(i);Vec3 at=new Vec3(n.x()+.5,n.y()+.5,n.z()+.5);Overlay.point(at,0xffe6cf8b,5);
                if(i>0){Point before=nodes.get(i-1);Overlay.line(new Vec3(before.x()+.5,before.y()+.5,before.z()+.5),at,0xff8ac9fc,2);}
            }
            if(hit!=null&&ClientState.options.visualizeBiome&&(ClientState.options.tool==Tool.BIOME_PAINTER||ClientState.options.tool==Tool.CLENTAMINATOR)){
                String biome=mc.level.getBiome(hit.getBlockPos()).unwrapKey().map(k->dev.craftium.compat.Compat.keyName(k)).orElse("Unknown biome");
                Overlay.text(biome,hit.getBlockPos().above(2),0xffabefb7,0xbb151c26,.025f);
            }
            var s=ClientState.status;
            if(screen.showSelection())SelectionOverlay.draw(TransformGizmo.selectionPreview());
            PlacementPreview.draw();
            // The wireframe of the brush shows only for tools that use one, not the selection, transform, ruler or annotation tools.
            var tool=ClientState.options.tool;
            boolean brush=!tool.selection()&&!tool.transform()&&tool!=Tool.RULER&&tool!=Tool.ANNOTATION;
            if(screen.toolActive()&&hit!=null && !PlacementPreview.active() && brush) {
                int r=ClientState.options.radius;var p=hit.getBlockPos();
                if(tool==Tool.SHAPE&&BrushGhost.draw(mc,p,ClientState.options)){/* drawn as ghost blocks */}
                else if(ClientState.options.shape==BrushMath.Shape.CUBE)Overlay.box(new AABB(p).inflate(r),0xffd6eaff,1.5f,0);
                else {
                    Vec3 c=Vec3.atCenterOf(p);double radius=r+.5;
                    for(int axis=0;axis<3;axis++)for(int i=0;i<48;i++) {
                        double a=i*Math.PI/24,b=(i+1)*Math.PI/24;
                        Vec3 from=axis==0?c.add(Math.cos(a)*radius,Math.sin(a)*radius,0):axis==1?c.add(Math.cos(a)*radius,0,Math.sin(a)*radius):c.add(0,Math.cos(a)*radius,Math.sin(a)*radius);
                        Vec3 to=axis==0?c.add(Math.cos(b)*radius,Math.sin(b)*radius,0):axis==1?c.add(Math.cos(b)*radius,0,Math.sin(b)*radius):c.add(0,Math.cos(b)*radius,Math.sin(b)*radius);
                        Overlay.line(from,to,0xffd6eaff,1.2f);
                    }
                }
            }
        }
    }
}
