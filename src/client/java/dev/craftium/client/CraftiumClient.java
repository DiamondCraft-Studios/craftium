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
    private long breakingSince,tinkerAt;
    private boolean rightWas;
    private BlockHitResult tinkerHit;
    private net.minecraft.world.level.block.Block tinkerBlock;
    private boolean hadWorld,toolCursor;
    private double lastLiquidOpacity=1;
    @Override public void onInitializeClient() {
        Overlay.install();
        ClientState.load();EditorBindings.load();AiBridge.install();
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
        ClientPlayNetworking.registerGlobalReceiver(Packets.AiReply.TYPE,(payload,context)->AiBridge.dataReceived(payload.json()));
        ClientPlayNetworking.registerGlobalReceiver(Packets.Update.TYPE,(payload,context)->context.client().execute(()->{
            receiveStatus(payload.json());
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)-> {
            ClientState.status=new Packets.Status("Disconnected",false,false,0,new Point(0,0,0),new Point(0,0,0),0,List.of(),0,0);
            ClientState.save();resetEditor();placementSynced="";
            ClientCommands.reset();
            ClientAssets.clear();bridge.clear();
            PlacementPreview.update("");
            ClientState.blueprints=List.of();ClientState.libraryVersion++;
        });
        // What the shared placement mixins ask about the local player; only when the server runs the mod too, as it must agree with the client.
        dev.craftium.runtime.PlayerCapabilities.client=player->{var mc=Minecraft.getInstance();return player==mc.player&&player.isCreative()&&vanillaPlacement()?ClientState.options:null;};
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        if(Boolean.getBoolean("craftium.maximize")){ // set by the development run configuration, so the test client opens maximized
            var once=new boolean[1];
            ClientTickEvents.END_CLIENT_TICK.register(mc->{if(!once[0]){once[0]=true;GLFW.glfwMaximizeWindow(mc.getWindow().handle());}});
        }
        UseBlockCallback.EVENT.register((player,level,hand,hit)->{
            var o=ClientState.options;
            boolean block=player.getMainHandItem().getItem() instanceof BlockItem;
            boolean tinker=player.getMainHandItem().isEmpty()&&o.tinker;
            if(!level.isClientSide() || !player.isCreative() || !supported() || ClientCompat.screen(Minecraft.getInstance())!=null)return InteractionResult.PASS;
            if(block&&vanillaPlacement()){syncPlacement();return InteractionResult.PASS;} // the game places the block; the mod bends it from the server and the client alike
            // Without the mod on the server, nothing there can bend vanilla placement, so the click is sent as an editor request instead.
            if(block&&o.placementCapabilities()||tinker)return InteractionResult.SUCCESS; // SUCCESS skips the server use, and the client swings the arm for it
            return InteractionResult.PASS;
        });
        AttackBlockCallback.EVENT.register((player,level,hand,pos,face)->
            level.isClientSide() && player.isCreative() && supported() && (ClientState.symmetry()||ClientState.options.reach==Options.ReachMode.INFINITE) ? InteractionResult.FAIL:InteractionResult.PASS);
        HudElementRegistry.addLast(dev.craftium.compat.Compat.id("craftium","builder"),(raw,delta)->{
            var mc=Minecraft.getInstance();if(mc.player==null || !mc.player.isCreative() || ClientCompat.screen(mc)!=null)return;
            var g=Gfx.of(raw);
            ReferenceImages.draw(g,false,0,0);
            int h=g.guiHeight();
            if(System.currentTimeMillis()<ClientState.noticeUntil)g.text(mc.font,ClientState.notice,8,8,0xffffd891,false);
            BuilderMode.hud(g);BuilderWidget.draw(g);AiBridge.hud(g);
            if(ClientState.options.showKeyHints){StringBuilder keys=new StringBuilder();for(int key:new int[]{GLFW.GLFW_KEY_W,GLFW.GLFW_KEY_A,GLFW.GLFW_KEY_S,GLFW.GLFW_KEY_D,GLFW.GLFW_KEY_X,GLFW.GLFW_KEY_Y,GLFW.GLFW_KEY_Z})if(GLFW.glfwGetKey(mc.getWindow().handle(),key)==GLFW.GLFW_PRESS)keys.append((char)key).append(' ');if(mc.hasControlDown())keys.append("Ctrl ");if(mc.hasShiftDown())keys.append("Shift ");g.text(mc.font,keys.toString(),g.guiWidth()-mc.font.width(keys.toString())-10,h-48,0xffe6cf8b,false);}
        });
    }
    private static final BridgeCodec.Assembler bridge=new BridgeCodec.Assembler();
    /**
     * Forgets everything the editor is in the middle of: selection jobs and popups, held gizmo drags, previews, the builder and its
     * search thread. Nothing built is touched. Used when leaving a world, and by {@code /craftium reset} when something has got stuck.
     */
    public static void resetEditor(){
        ClientState.selectionPending=false;ClientState.selectingSince=0;ClientState.selectionSizeBefore=0;ClientState.largeSelection=0;ClientState.heldSelection="";
        ClientState.boxCorners.clear();ClientState.pending=GhostBlocks.EMPTY;ClientState.aiPending=GhostBlocks.EMPTY;ClientState.duplicatePending=false;ClientState.pendingPlacementOrigin=null;
        ShapeObject.cancel();TransformGizmo.cancel();PlacementPreview.cancel();BuilderMode.reset();SelectionOverlay.update("");
    }
    private static void receiveStatus(String json){
            var next=Packets.JSON.fromJson(json,Packets.Status.class);
            if(next!=null){ClientState.status=next;HistoryPulse.receive(next);checkLargeSelection(next);applyPivot(next.pivotUpdate());if(next.message().startsWith("Undid ")||next.message().startsWith("Redid "))ClientState.boxCorners.clear();ClientRestore.apply(next.restore());if(next.message().startsWith("Blueprint library")){ClientState.blueprints=next.blueprints()==null?List.of():next.blueprints();ClientState.libraryVersion++;}if(ClientState.duplicatePending&&(next.message().startsWith("Copied ")||next.message().startsWith("Applied Cut "))){ClientState.duplicatePending=false;PlacementPreview.begin(ClientState.pendingPlacementOrigin==null?next.min():ClientState.pendingPlacementOrigin);ClientState.pendingPlacementOrigin=null;}if(next.error()){ClientState.duplicatePending=false;ClientState.pendingPlacementOrigin=null;}if(ClientState.largeSelection>0)ClientState.heldSelection=next.selectionData();else SelectionOverlay.update(next.selectionData());PlacementPreview.update(next.clipboardData());PlacementPreview.updateGhost(next.clipboardGhost());ClientState.pending=GhostBlocks.parse(next.pendingData());ClientState.aiPending=GhostBlocks.parse(next.aiPendingData());if(ClientState.aiPending.total()>0)ClientState.aiPending.ai=true;if(next.entityUuid()!=null&&!next.entityUuid().isBlank()){ClientState.options.entityUuid=next.entityUuid();if(next.entityNbt()!=null&&!next.entityNbt().isBlank())ClientState.options.entityNbt=next.entityNbt();}if(next.error())ClientState.notify(next.message());var mc=Minecraft.getInstance();if(ClientCompat.screen(mc) instanceof EditorScreen editor)editor.statusReceived();AiBridge.statusReceived(next);}
    }
    /** Opens the confirmation when a selection action has just selected a suspiciously large area. */
    private static void checkLargeSelection(Packets.Status next){
        if(!ClientState.selectionPending)return;
        if(next.error()){ClientState.selectionPending=false;ClientState.selectingSince=0;return;}
        String message=next.message();
        if(!(message.startsWith("Selected ")||message.startsWith("Lasso selected ")||message.startsWith("Magic Wand: ")))return;
        ClientState.selectionPending=false;ClientState.selectingSince=0;
        int added=next.selectionSize()-ClientState.selectionSizeBefore;
        if(added<ClientState.LARGE_SELECTION)return;
        // A Magic Wand click that had its outline under the cursor was seen before it was made, so it needs no warning; only a selection too big for that preview asks.
        var options=ClientState.options;
        if(!options.largeSelectionWarnings)return;
        if(options.tool==Tool.MAGIC_SELECT&&options.magicPreview&&added<options.hoverPreviewLimit)return;
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
                case "ai_reply" -> AiBridge.dataReceived(message.data());
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
        BuilderMode.tick();BuilderWidget.tick();RulerStore.tick(mc);
        SelectingIndicator.tick(mc);
        if(BuilderMode.active()&&held(contextKey)||BuilderWidget.active()){if(!toolCursor){mc.mouseHandler.releaseMouse();toolCursor=true;}}
        else if(toolCursor){if(ClientCompat.screen(mc)==null)mc.mouseHandler.grabMouse();toolCursor=false;}
        if(lastLiquidOpacity!=ClientState.options.liquidOpacity){lastLiquidOpacity=ClientState.options.liquidOpacity;ClientCompat.rebuildChunks(mc);}
        if(!hadWorld){hadWorld=true;if(supported())send("status",null,null);}
        while(editorKey.consumeClick()) {
            if(ClientCompat.screen(mc) instanceof EditorScreen)ClientCompat.setScreen(mc,null);
            else if(ClientCompat.screen(mc)==null)open(false);
        }
        while(contextKey.consumeClick()){}
        if(supported())syncPlacement();
        preview(mc);
        if(ClientCompat.screen(mc)!=null)return;
        for(var entry:toolKeys.entrySet())while(entry.getValue().consumeClick()){}
        while(swapKey.consumeClick())if(!mc.hasControlDown())send("hotbar",null,null);
        while(replaceKey.consumeClick()) {ClientState.options.replaceMode=!ClientState.options.replaceMode;ClientCompat.overlayMessage(mc,"Replace mode "+(ClientState.options.replaceMode?"enabled":"disabled"));mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK,1.0F));ClientState.save();}
        if(!mc.player.isCreative() || !supported())return;
        if(BuilderMode.active())return;
        var o=ClientState.options;long now=System.currentTimeMillis();
        boolean left=GLFW.glfwGetMouseButton(mc.getWindow().handle(),GLFW.GLFW_MOUSE_BUTTON_LEFT)==GLFW.GLFW_PRESS;
        boolean right=GLFW.glfwGetMouseButton(mc.getWindow().handle(),GLFW.GLFW_MOUSE_BUTTON_RIGHT)==GLFW.GLFW_PRESS;
        if(!left)breakingSince=0;else if(breakingSince==0){breakingSince=now;ClientState.strokeId=System.nanoTime();} // a symmetric stroke is one undo step
        if(right&&!rightWas&&o.tinker&&mc.player.getMainHandItem().isEmpty()&&mc.hitResult instanceof BlockHitResult th&&th.getType()==HitResult.Type.BLOCK){tinkerHit=th;tinkerAt=now;}
        rightWas=right;
        if(tinkerHit!=null){ // one click is one change: held down it does not repeat; a click that lands during another edit waits briefly for it
            if(now-tinkerAt>500||!supported())tinkerHit=null;
            else if(!ClientState.status.busy()){tinker(mc,o,tinkerHit);tinkerHit=null;}
        }
        if(now-lastBuilder>(o.fastPlace?70:125) && !ClientState.status.busy()) {
            BlockHitResult hit=mc.hitResult instanceof BlockHitResult b && b.getType()==HitResult.Type.BLOCK?b:null;
            if(hit==null&&o.angelPlacement&&right)hit=angelHit(mc);
            boolean canPlace=mc.player.getMainHandItem().getItem() instanceof BlockItem && o.placementCapabilities();
            if(hit!=null && right && canPlace && vanillaPlacement()) {
                // The game places the block itself. It only needs a nudge where it would not click by itself: faster than its own repeat, or at a point in the air.
                if(mc.hitResult!=hit){
                    var result=mc.gameMode.useItemOn(mc.player,net.minecraft.world.InteractionHand.MAIN_HAND,hit);
                    if(result.consumesAction())mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                    lastBuilder=now;
                }
            } else if(hit!=null && ((left&&(ClientState.symmetry()||o.reach==Options.ReachMode.INFINITE))||(right&&canPlace))) {
                if(mc.player.getMainHandItem().getItem() instanceof BlockItem item)ClientState.pick(BuiltInRegistries.BLOCK.getKey(item.getBlock()).toString());
                send(left?"builder_break":"builder_place",point(hit.getBlockPos()),normal(hit));lastBuilder=now;
                if(left){mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);} // the attack callback still returns FAIL, which does not swing
            }
        }
    }
    /**
     * Tinker on the block aimed at: a click makes the change that block most likely needs, chosen from where on it you aim.
     * A sneaking click instead picks a specific property, which the following clicks on that block cycle; aiming at another block goes back to the smart choice.
     */
    private void tinker(Minecraft mc,Options o,BlockHitResult hit){
        var state=mc.level.getBlockState(hit.getBlockPos());
        if(state.getBlock()!=tinkerBlock){tinkerBlock=state.getBlock();o.tinkerIndex=0;o.tinkerManual=false;}
        var properties=dev.craftium.runtime.TinkerProperties.of(state);
        var location=hit.getLocation();var pos=hit.getBlockPos();
        o.tinkerX=location.x-pos.getX();o.tinkerY=location.y-pos.getY();o.tinkerZ=location.z-pos.getZ();
        var d=hit.getDirection();
        var aim=dev.craftium.runtime.TinkerRules.Aim.of(d.getStepX(),d.getStepY(),d.getStepZ(),o.tinkerX,o.tinkerY,o.tinkerZ);
        if(mc.player.isShiftKeyDown()){
            if(properties.isEmpty()){ClientState.notify("Tinker: this block has no properties");return;}
            mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            o.tinkerIndex=o.tinkerManual?Math.floorMod(o.tinkerIndex+1,properties.size()):0;o.tinkerManual=true;
            ClientState.notify("Tinker: picked "+dev.craftium.runtime.TinkerProperties.describe(state,dev.craftium.runtime.TinkerProperties.pick(state,o.tinkerIndex))+" · click to change it");
            return;
        }
        var result=dev.craftium.runtime.TinkerRules.apply(state,aim,o.tinkerManual?o.tinkerIndex:-1);
        if(result==null){ClientState.notify("Tinker: this block has no properties");return;}
        mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        ClientState.notify("Tinker: "+result.message());
        send("builder_place",point(hit.getBlockPos()),normal(hit));
    }
    public static boolean held(KeyMapping mapping){
        if(mapping.isUnbound())return false;var key=InputConstants.getKey(mapping.saveString());long window=Minecraft.getInstance().getWindow().handle();return key.getType()==InputConstants.Type.MOUSE?GLFW.glfwGetMouseButton(window,key.getValue())==GLFW.GLFW_PRESS:InputConstants.isKeyDown(Minecraft.getInstance().getWindow(),key.getValue());
    }
    public static boolean ingameKey(net.minecraft.client.input.KeyEvent event,int action){
        var mc=Minecraft.getInstance();if(action!=GLFW.GLFW_PRESS||ClientCompat.screen(mc)!=null||mc.player==null||!mc.player.isCreative()||!supported())return false;
        if(EntityShortcuts.ingame(event))return true;
        // Outside the builder the undo and redo chords still reach what the builder made, and nothing else.
        if(!BuilderMode.active()){
            boolean undo=EditorBindings.matches(EditorControls.Action.UNDO,event);
            if(undo||EditorBindings.matches(EditorControls.Action.REDO,event)){send(undo?"builder_undo":"builder_redo",null,null);return true;}
        }
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
        String key=o.symmetryX+","+o.symmetryY+","+o.symmetryZ+","+o.symmetryRotations+","+o.symmetryOrigin+","+o.symmetryHalfX+o.symmetryHalfY+o.symmetryHalfZ+","+o.replaceMode+o.forcePlace+o.noUpdates+o.fastPlace+o.reach+o.farReach+o.angelPlacement+o.preventInteractions+o.noClip+o.blockStep;
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
        try{String request=Packets.JSON.toJson(new Packets.Request(action,target,normal,ClientState.options,action.equals("use")||action.equals("builder_break")?ClientState.strokeId:0));
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
        BlockHitResult hit;
        SlabSplit.suppress(true); // the whole of a double slab is aimed at, not one half
        try{hit=mc.level.clip(new ClipContext(start,start.add(direction.scale(256)),ClipContext.Block.OUTLINE,ClientState.options.solidFluids?ClipContext.Fluid.ANY:ClipContext.Fluid.NONE,mc.player));}finally{SlabSplit.suppress(false);}
        return hit.getType()==HitResult.Type.BLOCK?hit:null;
    }
    /** The transform handles: the builder's, or the editor's with the box corners and the point being dragged. Drawn each frame. */
    /** Every ruler: its line (or circle), its length, and a knob at each end. The end being dragged shows where it will land, and the length with it. */
    private static void drawRulers(EditorScreen screen){
        var o=ClientState.options;
        boolean editing=screen!=null&&!screen.toolActive(); // the rulers can be picked up when no tool is chosen
        int picked=editing?screen.handleIndex():-1;Vec3 live=editing?TransformGizmo.liveNode():null;
        int index=0;
        for(var ruler:ClientState.rulers){
            Vec3 start=centre(ruler.a),end=ruler.b==null?null:centre(ruler.b);
            int first=index,second=index+1;
            if(live!=null&&(picked==first||end!=null&&picked==second)){ // the knob under the gizmo follows the drag, settled onto the block it will land on
                Vec3 landing=new Vec3(Math.round(live.x-.5)+.5,Math.round(live.y-.5)+.5,Math.round(live.z-.5)+.5);
                if(picked==first)start=landing;else end=landing;
                // The block it will land on is outlined, as a single block of a selection is.
                Overlay.box(new AABB(landing.x-.5,landing.y-.5,landing.z-.5,landing.x+.5,landing.y+.5,landing.z+.5),o.rulerKnobSelectedColor,2,o.rulerKnobSelectedColor&0xffffff|0x33000000);
            }
            if(end!=null){
                double length=start.distanceTo(end);
                double thickness=ruler==ClientState.selectedRuler()?RULER_LINE_HALF*1.6:RULER_LINE_HALF;
                bar(start,end,thickness,lineColor(o,start,end));
                if(ruler.mode==Options.RulerMode.CIRCLE){for(int j=0;j<96;j++){double x=j*Math.PI/48,y=(j+1)*Math.PI/48;bar(start.add(Math.cos(x)*length,0,Math.sin(x)*length),start.add(Math.cos(y)*length,0,Math.sin(y)*length),RULER_LINE_HALF*.75,o.rulerLineColor);}}
                // The length sits on the middle of the line, as big as the setting says. The game hangs text below the point it is given, so the point
                // is raised by half the height of a line of text (nine pixels) to leave the text itself centred on the line.
                float scale=.05f*(float)Math.clamp(o.rulerLabelSize,Options.RULER_LABEL_MIN,Options.RULER_LABEL_MAX);
                Overlay.text(Ruler.showSpan((int)Math.round(Math.abs(end.x-start.x))+1,(int)Math.round(Math.abs(end.y-start.y))+1,(int)Math.round(Math.abs(end.z-start.z))+1),start.add(end).scale(.5).add(cameraUp().scale(4.5*scale)),o.rulerTextColor,o.rulerTextBackground,scale);
            }
            knob(start,picked==first?o.rulerKnobSelectedColor:o.rulerKnobColor,picked==first);
            if(end!=null)knob(end,picked==second?o.rulerKnobSelectedColor:o.rulerKnobColor,picked==second);
            index+=end==null?1:2;
        }
    }
    /** The ruler is made of real size: its line is this thick and its knobs this big in blocks, so they grow and shrink with distance like the blocks around them. */
    private static final double RULER_LINE_HALF=.04,RULER_KNOB_HALF=.07,RULER_KNOB_SELECTED_HALF=.09;
    /** A knob: a small solid cube. */
    private static void knob(Vec3 at,int color,boolean selected){
        double h=selected?RULER_KNOB_SELECTED_HALF:RULER_KNOB_HALF;
        Overlay.box(new AABB(at.x-h,at.y-h,at.z-h,at.x+h,at.y+h,at.z+h),color&0xffffff,1,color); // no outline of its own: an outline is a fixed number of pixels wide
    }
    /** A line of real thickness from {@code a} to {@code b}: a square bar, drawn as four faces, each facing both ways. */
    private static void bar(Vec3 a,Vec3 b,double half,int color){
        Vec3 along=b.subtract(a);double length=along.length();if(length<1e-6)return;
        along=along.scale(1/length);
        Vec3 up=Math.abs(along.y)<.9?new Vec3(0,1,0):new Vec3(1,0,0);
        Vec3 u=along.cross(up).normalize().scale(half),v=along.cross(u).normalize().scale(half);
        Vec3[] from={a.add(u).add(v),a.subtract(u).add(v),a.subtract(u).subtract(v),a.add(u).subtract(v)};
        Vec3[] to={b.add(u).add(v),b.subtract(u).add(v),b.subtract(u).subtract(v),b.add(u).subtract(v)};
        for(int i=0;i<4;i++){
            int j=(i+1)%4;
            Overlay.face(from[i],from[j],to[j],to[i],color);
            Overlay.face(to[i],to[j],from[j],from[i],color);
        }
    }
    /** A line that runs along one world axis takes that axis's colour; any other line takes the ruler's own. */
    private static int lineColor(Options o,Vec3 a,Vec3 b){
        boolean x=Math.abs(b.x-a.x)>1e-6,y=Math.abs(b.y-a.y)>1e-6,z=Math.abs(b.z-a.z)>1e-6;
        if(x&&!y&&!z)return o.rulerAxisXColor;
        if(y&&!x&&!z)return o.rulerAxisYColor;
        if(z&&!x&&!y)return o.rulerAxisZColor;
        return o.rulerLineColor;
    }
    /** Which way is up on the screen, as a direction in the world: the way the camera's own up is, so it holds when the view is tilted. */
    private static Vec3 cameraUp(){
        var camera=ClientCompat.camera(Minecraft.getInstance());
        Vec3 forward=ClientCompat.ray(camera,0,0).normalize(),above=ClientCompat.ray(camera,0,.3f).normalize();
        Vec3 up=above.subtract(forward.scale(above.dot(forward)));
        return up.lengthSqr()<1e-9?new Vec3(0,1,0):up.normalize();
    }
    private static Vec3 centre(Point p){return new Vec3(p.x()+.5,p.y()+.5,p.z()+.5);}
    private static void handles(EditorScreen screen) {
        if(screen==null){var anchor=BuilderMode.gizmoAnchor();if(anchor!=null)TransformGizmo.display(anchor,BuilderMode.gizmoAxes());return;}
        if(ClientState.options.tool==Tool.BOX_SELECT)BoxHandles.draw(screen);
        TransformGizmo.draw(screen);
        Point handle=screen.handlePoint();if(handle!=null){double shift=TransformGizmo.nodeOffset();Vec3 anchor=new Vec3(handle.x()+shift,handle.y()+shift,handle.z()+shift);int[] colors={0xffff7777,0xff8bef9a,0xff78b9ff};for(int axis=0;axis<3;axis++)Overlay.line(anchor,anchor.add(axis==0?3:0,axis==1?3:0,axis==2?3:0),colors[axis],screen.handleAxis()==axis?4:2);}
    }
    private static void preview(Minecraft mc) {
        var screen=ClientCompat.screen(mc) instanceof EditorScreen editor?editor:ClientCompat.screen(mc) instanceof StudioScreen studio?studio.editor():null;
        var hit=screen==null?null:screen.target();
        try(var collector=Overlay.begin(mc)) {
            WorldDiagnostics.draw(mc);
            BuilderMode.draw();
            AngelGhost.draw(mc);
            ReplaceGhost.draw(mc);
            HistoryPulse.draw();
            if(screen!=null&&screen.bucketDragging())MagicPreview.drawBucket(mc,screen.bucketTarget());else{MagicPreview.draw(mc,hit);ClickPreview.draw(mc,screen!=null&&screen.toolActive()?hit:null);}
            // The Ruler tool shows the block a click would put an end on, outlined and filled as a block of a selection is.
            if(hit!=null&&screen!=null&&screen.toolActive()&&ClientState.options.tool==Tool.RULER){var o=ClientState.options;Overlay.box(new AABB(hit.getBlockPos()),o.selectionColor,(float)o.selectionWidth,o.selectionFill);}
            ClientState.pending.draw(new Point(0,0,0),true);
            ClientState.aiPending.draw(new Point(0,0,0),true);
            if(ClientState.options.showMarkerGizmos||ClientState.options.showDisplayGizmos){int count=0;for(var entity:mc.level.entitiesForRendering()){boolean show=entity instanceof net.minecraft.world.entity.Marker&&ClientState.options.showMarkerGizmos||entity instanceof net.minecraft.world.entity.Display&&ClientState.options.showDisplayGizmos;if(!show||entity.distanceToSqr(mc.player)>128*128||++count>256)continue;Vec3 at=entity.position();int color=entity.getUUID().toString().equals(ClientState.options.entityUuid)?0xffffd66d:0xffe6edf7;Overlay.point(at,color,6);Overlay.box(new AABB(at.add(-.08,-.08,-.08),at.add(.08,.08,.08)),color,1,0);}}
            if(ClientState.options.showAnnotations&&ClientState.status.annotations()!=null)for(var note:ClientState.status.annotations()){
                Point a=note.from(),b=note.to();Vec3 start=new Vec3(a.x()+.5,a.y()+.5,a.z()+.5),end=new Vec3(b.x()+.5,b.y()+.5,b.z()+.5);
                if(note.mode().equals("IMAGE")){AnnotationImages.draw(note);continue;}
                if(note.mode().equals("DRAW")&&note.points()!=null&&note.points().size()>1){for(int i=1;i<note.points().size();i++){Point a0=note.points().get(i-1),b0=note.points().get(i);Overlay.line(new Vec3(a0.x()+.5,a0.y()+.5,a0.z()+.5),new Vec3(b0.x()+.5,b0.y()+.5,b0.z()+.5),note.color(),note.width());}continue;}
                if(note.mode().equals("TEXT"))Overlay.text(note.text(),new net.minecraft.core.BlockPos(a.x(),a.y(),a.z()),note.color(),0x88151c26,.025f);
                else if(note.mode().equals("BOX"))Overlay.box(new AABB(start,end).inflate(.02),note.color(),note.width(),0);
                else Overlay.line(start,end,note.color(),note.width());
            }
            drawRulers(screen);
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
            ShapeObject.draw(mc);
            // The outline of the brush shows only for tools that use one, not the selection, transform, ruler or annotation tools.
            var tool=ClientState.options.tool;
            boolean brush=!tool.selection()&&!tool.transform()&&tool.brushUse()!=Tool.BrushUse.NONE;
            if(tool==Tool.MODIFY&&screen.toolActive())ModifyPreview.draw(mc,hit==null?null:point(hit.getBlockPos()));
            if(screen.toolActive()&&hit!=null && !PlacementPreview.active() && !ShapeObject.active() && brush) {
                var p=hit.getBlockPos();
                if(tool==Tool.SHAPE&&BrushGhost.draw(mc,p,ClientState.options)){/* drawn as ghost blocks */}
                else BrushPreview.draw(mc,p,ClientState.options);
            }
        }
    }
}
