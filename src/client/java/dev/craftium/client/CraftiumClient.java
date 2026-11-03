package dev.craftium.client;

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
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.GizmoStyle;
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
            ClientState.status=new Packets.Status("Disconnected",false,false,0,new Point(0,0,0),new Point(0,0,0),0,List.of(),0,0);
            ClientState.save();ClientState.duplicatePending=false;ClientState.pendingPlacementOrigin=null;
            BuilderMode.reset();
            ClientCommands.reset();
            ClientAssets.clear();bridge.clear();
            SelectionOverlay.update("");PlacementPreview.cancel();PlacementPreview.update("");
            ClientState.rulerStart=null;ClientState.rulerEnd=null;
            ClientState.rulerPoints.clear();ClientState.blueprints=List.of();ClientState.libraryVersion++;
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        UseBlockCallback.EVENT.register((player,level,hand,hit)->{
            var o=ClientState.options;
            boolean place=player.getMainHandItem().getItem() instanceof BlockItem && (ClientState.symmetry()||o.replaceMode||o.forcePlace||o.noUpdates||o.fastPlace||o.infiniteReach||o.angelPlacement||o.preventInteractions);
            boolean tinker=player.getMainHandItem().isEmpty()&&o.tinker&&!player.isShiftKeyDown();
            if(level.isClientSide() && player.isCreative() && supported() && ClientCompat.screen(Minecraft.getInstance())==null && (place||tinker))return InteractionResult.FAIL;
            return InteractionResult.PASS;
        });
        AttackBlockCallback.EVENT.register((player,level,hand,pos,face)->
            level.isClientSide() && player.isCreative() && supported() && (ClientState.symmetry()||ClientState.options.bulldozer||ClientState.options.infiniteReach) ? InteractionResult.FAIL:InteractionResult.PASS);
        HudElementRegistry.addLast(dev.craftium.compat.Compat.id("craftium","builder"),(raw,delta)->{
            var mc=Minecraft.getInstance();if(mc.player==null || !mc.player.isCreative() || ClientCompat.screen(mc)!=null)return;
            var g=Gfx.of(raw);
            ReferenceImages.draw(g,false,0,0);
            int h=g.guiHeight();String mode=ClientState.options.replaceMode?"REPLACE":"BUILD";
            g.fill(5,h-47,155,h-21,0xd9161d28);g.fill(5,h-47,7,h-21,0xff599cda);
            CraftiumBrand.draw(g,7,h-47,16);
            g.text(mc.font,"CRAFTIUM  /  "+mode,26,h-42,0xffc6d9ed,false);
            g.text(mc.font,"RShift Editor   Alt Builder",12,h-31,0xff8b9bb0,false);
            if(System.currentTimeMillis()<ClientState.noticeUntil)g.text(mc.font,ClientState.notice,8,8,0xffffd891,false);
            BuilderMode.hud(g);
            if(ClientState.options.showKeyHints){StringBuilder keys=new StringBuilder();for(int key:new int[]{GLFW.GLFW_KEY_W,GLFW.GLFW_KEY_A,GLFW.GLFW_KEY_S,GLFW.GLFW_KEY_D,GLFW.GLFW_KEY_X,GLFW.GLFW_KEY_Y,GLFW.GLFW_KEY_Z})if(GLFW.glfwGetKey(mc.getWindow().handle(),key)==GLFW.GLFW_PRESS)keys.append((char)key).append(' ');if(mc.hasControlDown())keys.append("Ctrl ");if(mc.hasShiftDown())keys.append("Shift ");g.text(mc.font,keys.toString(),g.guiWidth()-mc.font.width(keys.toString())-10,h-48,0xffe6cf8b,false);}
        });
    }
    private static final BridgeCodec.Assembler bridge=new BridgeCodec.Assembler();
    private static void receiveStatus(String json){
            var next=Packets.JSON.fromJson(json,Packets.Status.class);
            if(next!=null){ClientState.status=next;if(next.message().startsWith("Blueprint library")){ClientState.blueprints=next.blueprints()==null?List.of():next.blueprints();ClientState.libraryVersion++;}if(ClientState.duplicatePending&&(next.message().startsWith("Copied ")||next.message().startsWith("Applied Cut "))){ClientState.duplicatePending=false;PlacementPreview.begin(ClientState.pendingPlacementOrigin==null?next.min():ClientState.pendingPlacementOrigin);ClientState.pendingPlacementOrigin=null;}if(next.error()){ClientState.duplicatePending=false;ClientState.pendingPlacementOrigin=null;}SelectionOverlay.update(next.selectionData());PlacementPreview.update(next.clipboardData());if(next.entityUuid()!=null&&!next.entityUuid().isBlank()){ClientState.options.entityUuid=next.entityUuid();if(next.entityNbt()!=null&&!next.entityNbt().isBlank())ClientState.options.entityNbt=next.entityNbt();}if(next.error())ClientState.notify(next.message());}
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
            if(hit==null&&o.angelPlacement&&right){Vec3 at=mc.player.getEyePosition().add(mc.player.getLookAngle().scale(4));var pos=net.minecraft.core.BlockPos.containing(at).below();hit=new BlockHitResult(at,net.minecraft.core.Direction.UP,pos,false);}
            boolean canPlace=mc.player.getMainHandItem().getItem() instanceof BlockItem && (ClientState.symmetry()||o.replaceMode||o.forcePlace||o.noUpdates||o.fastPlace||o.infiniteReach||o.angelPlacement||o.preventInteractions);
            boolean canTinker=o.tinker&&mc.player.getMainHandItem().isEmpty()&&!mc.player.isShiftKeyDown();
            if(hit!=null && ((left&&(ClientState.symmetry()||o.infiniteReach||o.bulldozer&&now-breakingSince>350))||(right&&(canPlace||canTinker)))) {
                if(mc.player.getMainHandItem().getItem() instanceof BlockItem item)ClientState.pick(BuiltInRegistries.BLOCK.getKey(item.getBlock()).toString());
                send(left?"builder_break":"builder_place",point(hit.getBlockPos()),normal(hit));lastBuilder=now;
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
        if(supported())send("status",null,null);
    }
    public static boolean supported() {return Minecraft.getInstance().getConnection()!=null && (ClientPlayNetworking.canSend(Packets.Edit.TYPE)||ClientPlayNetworking.canSend(Packets.Bridge.TYPE));}
    public static void send(String action,Point target,Point normal) {
        if(!supported()){ClientState.notify("Install Craftium on this server to edit worlds");return;}
        var options=ClientState.options;boolean colours=action.equals("autoshade")&&options.shadeAutomatic||action.equals("use")&&(options.script.contains("getBlockRGB")||options.script.contains("findClosestBlockToRGB"))||options.maskScript.contains("getBlockRGB")||options.maskScript.contains("findClosestBlockToRGB");options.scriptColours=colours?new java.util.ArrayList<>(BlockColours.snapshot()):new java.util.ArrayList<>();
        ClientAssets.expect(action);
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
        Vec3 direction=ClientCompat.nearPlane(camera).getPointOnPlane(nx,ny).normalize();
        Vec3 start=camera.position();
        var hit=mc.level.clip(new ClipContext(start,start.add(direction.scale(256)),ClipContext.Block.OUTLINE,ClientState.options.solidFluids?ClipContext.Fluid.ANY:ClipContext.Fluid.NONE,mc.player));
        return hit.getType()==HitResult.Type.BLOCK?hit:null;
    }
    private static void preview(Minecraft mc) {
        var screen=ClientCompat.screen(mc) instanceof EditorScreen editor?editor:ClientCompat.screen(mc) instanceof StudioScreen studio?studio.editor():null;
        var hit=screen==null?null:screen.target();
        try(var collector=mc.collectPerTickGizmos()) {
            WorldDiagnostics.draw(mc);
            BuilderMode.draw();
            if(ClientState.options.showMarkerGizmos||ClientState.options.showDisplayGizmos){int count=0;for(var entity:mc.level.entitiesForRendering()){boolean show=entity instanceof net.minecraft.world.entity.Marker&&ClientState.options.showMarkerGizmos||entity instanceof net.minecraft.world.entity.Display&&ClientState.options.showDisplayGizmos;if(!show||entity.distanceToSqr(mc.player)>128*128||++count>256)continue;Vec3 at=entity.position();int color=entity.getUUID().toString().equals(ClientState.options.entityUuid)?0xffffd66d:0xffe6edf7;Gizmos.point(at,color,6).setAlwaysOnTop();Gizmos.cuboid(new AABB(at.add(-.08,-.08,-.08),at.add(.08,.08,.08)),GizmoStyle.stroke(color,1)).setAlwaysOnTop();}}
            if(ClientState.options.showAnnotations&&ClientState.status.annotations()!=null)for(var note:ClientState.status.annotations()){
                Point a=note.from(),b=note.to();Vec3 start=new Vec3(a.x()+.5,a.y()+.5,a.z()+.5),end=new Vec3(b.x()+.5,b.y()+.5,b.z()+.5);
                if(note.mode().equals("IMAGE")){AnnotationImages.draw(note);continue;}
                if(note.mode().equals("DRAW")&&note.points()!=null&&note.points().size()>1){for(int i=1;i<note.points().size();i++){Point a0=note.points().get(i-1),b0=note.points().get(i);Gizmos.line(new Vec3(a0.x()+.5,a0.y()+.5,a0.z()+.5),new Vec3(b0.x()+.5,b0.y()+.5,b0.z()+.5),note.color(),note.width()).setAlwaysOnTop();}continue;}
                if(note.mode().equals("TEXT"))Gizmos.billboardTextOverBlock(note.text(),new net.minecraft.core.BlockPos(a.x(),a.y(),a.z()),note.color(),0x88151c26,.025f).setAlwaysOnTop();
                else if(note.mode().equals("BOX"))Gizmos.cuboid(new AABB(start,end).inflate(.02),GizmoStyle.stroke(note.color(),note.width())).setAlwaysOnTop();
                else Gizmos.line(start,end,note.color(),note.width()).setAlwaysOnTop();
            }
            for(int i=1;i<ClientState.rulerPoints.size();i++) {
                Point a=ClientState.rulerPoints.get(i-1),b=ClientState.rulerPoints.get(i);Vec3 start=new Vec3(a.x()+.5,a.y()+.5,a.z()+.5),end=new Vec3(b.x()+.5,b.y()+.5,b.z()+.5);
                Gizmos.line(start,end,0xffffd66d,2).setAlwaysOnTop();
                if(ClientState.options.rulerMode==Options.RulerMode.CIRCLE){double radius=Math.sqrt(a.distanceSquared(b));for(int j=0;j<96;j++){double x=j*Math.PI/48,y=(j+1)*Math.PI/48;Gizmos.line(start.add(Math.cos(x)*radius,0,Math.sin(x)*radius),start.add(Math.cos(y)*radius,0,Math.sin(y)*radius),0xffffd66d,1.5f).setAlwaysOnTop();}}
                Gizmos.billboardTextOverBlock(String.format(java.util.Locale.ROOT,"%.2f blocks",Math.sqrt(a.distanceSquared(b))),new net.minecraft.core.BlockPos((a.x()+b.x())/2,(a.y()+b.y())/2+1,(a.z()+b.z())/2),0xffffe4a2,0xbb151c26,.02f).setAlwaysOnTop();
            }
            if(screen==null)return;
            TransformGizmo.draw(screen);
            Point handle=screen.handlePoint();if(handle!=null){Vec3 anchor=new Vec3(handle.x(),handle.y(),handle.z());int[] colors={0xffff7777,0xff8bef9a,0xff78b9ff};for(int axis=0;axis<3;axis++)Gizmos.line(anchor,anchor.add(axis==0?3:0,axis==1?3:0,axis==2?3:0),colors[axis],screen.handleAxis()==axis?4:2).setAlwaysOnTop();}
            var nodes=ClientState.options.nodes;
            for(int i=0;i<nodes.size();i++){
                Point n=nodes.get(i);Vec3 at=new Vec3(n.x()+.5,n.y()+.5,n.z()+.5);Gizmos.point(at,0xffe6cf8b,5).setAlwaysOnTop();
                if(i>0){Point before=nodes.get(i-1);Gizmos.line(new Vec3(before.x()+.5,before.y()+.5,before.z()+.5),at,0xff8ac9fc,2).setAlwaysOnTop();}
            }
            if(hit!=null&&ClientState.options.visualizeBiome&&(ClientState.options.tool==Tool.BIOME_PAINTER||ClientState.options.tool==Tool.CLENTAMINATOR)){
                String biome=mc.level.getBiome(hit.getBlockPos()).unwrapKey().map(k->dev.craftium.compat.Compat.keyName(k)).orElse("Unknown biome");
                Gizmos.billboardTextOverBlock(biome,hit.getBlockPos().above(2),0xffabefb7,0xbb151c26,.025f).setAlwaysOnTop();
            }
            var s=ClientState.status;
            for(AABB box:screen.showSelection()?SelectionOverlay.boxes:List.<AABB>of())if(TransformGizmo.selectionPreview())TransformGizmo.box(box);else Gizmos.cuboid(box,GizmoStyle.strokeAndFill(0xffffd66d,1,0x225d91ff)).setAlwaysOnTop();
            if(PlacementPreview.active()) {
                Point p=PlacementPreview.origin;Vec3 anchor=new Vec3(p.x(),p.y(),p.z());
                for(AABB box:PlacementPreview.boxes)TransformGizmo.box(box.move(anchor));
            }
            if(screen.toolActive()&&hit!=null && !PlacementPreview.active() && !ClientState.options.tool.selection()) {
                int r=ClientState.options.radius;var p=hit.getBlockPos();
                if(ClientState.options.shape==BrushMath.Shape.CUBE)Gizmos.cuboid(new AABB(p).inflate(r),GizmoStyle.stroke(0xffd6eaff,1.5f)).setAlwaysOnTop();
                else {
                    Vec3 c=Vec3.atCenterOf(p);double radius=r+.5;
                    for(int axis=0;axis<3;axis++)for(int i=0;i<48;i++) {
                        double a=i*Math.PI/24,b=(i+1)*Math.PI/24;
                        Vec3 from=axis==0?c.add(Math.cos(a)*radius,Math.sin(a)*radius,0):axis==1?c.add(Math.cos(a)*radius,0,Math.sin(a)*radius):c.add(0,Math.cos(a)*radius,Math.sin(a)*radius);
                        Vec3 to=axis==0?c.add(Math.cos(b)*radius,Math.sin(b)*radius,0):axis==1?c.add(Math.cos(b)*radius,0,Math.sin(b)*radius):c.add(0,Math.cos(b)*radius,Math.sin(b)*radius);
                        Gizmos.line(from,to,0xffd6eaff,1.2f).setAlwaysOnTop();
                    }
                }
            }
        }
    }
}
