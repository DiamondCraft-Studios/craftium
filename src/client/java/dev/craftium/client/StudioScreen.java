package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.core.*;
import dev.craftium.client.compat.CraftiumScreen;
import dev.craftium.client.compat.Gfx;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.lang.reflect.Field;
import java.util.*;

/** Paged inspector for the detailed controls; keeps settings out of the world viewport. */
public final class StudioScreen extends CraftiumScreen {
    private final EditorScreen editor;
    private String tab="Selection",message="";
    private int page,left,top,columns=2,rows;
    private int canvasW,canvasH;
    private float uiScale=1;
    private boolean previousHud,initialized;
    private final List<Label> labels=new ArrayList<>();
    private record Label(String text,int x,int y){}
    private static final LinkedHashMap<String,List<String>> GROUPS=new LinkedHashMap<>();
    static {
        GROUPS.put("Selection",List.of("compare","selectLimit","selectRange","selectUp","selectDown","selectHorizontal","selectCorners","includeNonSolid","position1","position2","lassoDepth","mode"));
        GROUPS.put("Brush",List.of("radius","strength","shape","invert","hollow","surfaceOnly","keepExisting","pasteAir","smoothing","threshold","meldStrength","denoise","maskY","fractureMaterial","exposedFaces","property"));
        GROUPS.put("Geometry",List.of("shapeKind","sizeX","sizeY","sizeZ","yaw","pitch","roll","exponent","innerRadius","polygonSides","pathMode","slack","looped","extendToGround","useStairsAndSlabs","modelMode","modelColumns","nodes","modifyMode","copies","offsetX","offsetY","offsetZ"));
        GROUPS.put("Paint",List.of("block","secondary","materials","copyProperties","typeReplace","noise","noise3D","noiseScale","seed","octaves","lacunarity","gain","jitter","scaleX","scaleY","scaleZ","density","biome","fillVertically","visualizeBiome","baseChance","minSpacing","randomYaw","randomFlip"));
        GROUPS.put("Masks",List.of("mask","maskExpression","maskScript","inSelection","minY","maxY","symmetryX","symmetryY","symmetryZ","symmetryOrigin"));
        GROUPS.put("Script",List.of("script","maskScript"));
        GROUPS.put("Assets",List.of("name","text","useBuiltinFont","fontName","fontFile","fontSize","textThickness","fullBlocks","heightmap"));
        GROUPS.put("Operations",List.of());
        GROUPS.put("Library",List.of("name","author","tags"));
        GROUPS.put("Entities",List.of("entityType","entityUuid","entityNbt","offsetX","offsetY","offsetZ","entityX","entityY","entityZ","yaw","pitch","roll","entityScaleX","entityScaleY","entityScaleZ","entityGroup"));
        GROUPS.put("World",List.of("worldTime","randomTickSpeed","worldToggle","name"));
        GROUPS.put("Annotations",List.of("annotationMode","text","annotationColor","annotationWidth","position1","position2","annotationId","annotationImageScale","annotationNormal"));
        GROUPS.put("Style",List.of("uiBackground","uiPanel","uiAccent","uiText","uiMuted","uiBorder"));
        GROUPS.put("Fluids",List.of("floodLimit","floodUp","floodDown","floodHorizontal","floodCorners","fluidType","flowLength","fluidQuality","requireSupport","infiniteSources","fillEdges","removeFluidsWithoutSources"));
        GROUPS.put("Capabilities",List.of("enhancedFlight","cameraFlight","flightMomentum","liquidOpacity","minimumBrightness","showKeyHints","showMarkerGizmos","showDisplayGizmos","showAnnotations","bulldozer","replaceMode","forcePlace","noUpdates","tinker","fastPlace","angelPlacement","infiniteReach","noClip","showCollisionMesh","showLightBlocks","showStructureVoid","expandHitboxes","solidFluids","preventInteractions","autoSwapToCreative"));
        GROUPS.put("Ruler",List.of("rulerMode","rulerSplit"));
        GROUPS.put("Placement",List.of("keepExisting","pasteAir","copyEntities","pasteEntities","mergeBlocks","unlockRotation","gizmoMode","gizmoTarget","gizmoLocal","yaw","pitch","roll","sizeX","sizeY","sizeZ"));
        GROUPS.put("Hotbars",List.of("name"));
        GROUPS.put("Rebuild",List.of("rebuildStartDelay","rebuildMaxDelay","rebuildAirDelay","rebuildSameDelay","rebuildDifferentDelay","rebuildRandomDelay","rebuildHorizontalMultiplier","rebuildVerticalMultiplier","seed"));
        GROUPS.put("Lighting",List.of("shadeSun","shadeFrom","shadeAzimuth","shadeAltitude","shadeAmbientOcclusion","shadeBounces","shadeDither","shadeExposure","shadeSamples","shadeRayLength","shadeAutomatic","shadeFullBlocks","shadeOpaque","shadeNoOres","shadeNoGlazed","shadeNoBlockEntities","materials"));
    }
    public StudioScreen(EditorScreen editor){super(Component.literal("Craftium Studio Inspector"));this.editor=editor;}
    public EditorScreen editor(){return editor;}
    @Override protected void init(){
        if(!initialized){previousHud=ClientCompat.hudHidden(minecraft);if(!previousHud)ClientCompat.toggleHud(minecraft);initialized=true;}
        uiScale=Math.min(1f,Math.min(width/680f,height/440f));canvasW=Math.round(width/uiScale);canvasH=Math.round(height/uiScale);
        clearWidgets();labels.clear();left=Math.max(12,(canvasW-640)/2);top=30;rows=Math.max(2,(canvasH-(tab.equals("Entities")?250:170))/46);
        int tx=left,ty=top;for(String name:GROUPS.keySet()){int bw=font.width(name)+13;if(tx+bw>canvasW-18){tx=left;ty+=22;}button(name,tx,ty,bw,18,()->{tab=name;page=0;init();});tx+=bw+3;}
        List<String> names=tab.equals("Script")?List.of():GROUPS.get(tab);int count=rows*columns,total=Math.max(1,(names.size()+count-1)/count);page=Math.clamp(page,0,total-1);
        for(int i=page*count;i<Math.min(names.size(),(page+1)*count);i++){
            String name=names.get(i);int index=i-page*count,x=left+(index%columns)*316,y=top+70+(index/columns)*46;
            labels.add(new Label(human(name),x,y-11));createField(name,x,y,300);
        }
        int bottom=canvasH-57;
        button("‹",left,bottom,24,20,()->{page=Math.max(0,page-1);init();});button("›",left+29,bottom,24,20,()->{page=Math.min(total-1,page+1);init();});
        labels.add(new Label((page+1)+" / "+total,left+62,bottom+6));
        button("Apply tool",left+385,bottom,110,20,()->send("use"));
        button("Save & return",left+505,bottom,122,20,this::onClose);
        if(tab.equals("Script")){
            labels.add(new Label("Lua brush",left,top+60));labels.add(new Label("Lua boolean mask",left+316,top+60));
            var brush=MultiLineEditBox.builder().setX(left).setY(top+77).build(font,300,canvasH-230,Component.literal("Lua brush"));brush.setCharacterLimit(16384);brush.setValue(ClientState.options.script);brush.setValueListener(value->ClientState.options.script=value);addRenderableWidget(brush);
            var mask=MultiLineEditBox.builder().setX(left+316).setY(top+77).build(font,300,canvasH-230,Component.literal("Lua mask"));mask.setCharacterLimit(16384);mask.setValue(ClientState.options.maskScript);mask.setValueListener(value->ClientState.options.maskScript=value);addRenderableWidget(mask);
            actionRow(new String[]{"Brush variables…","Mask variables…"},new Runnable[]{()->ClientCompat.setScreen(minecraft,new ScriptVariablesScreen(this,false)),()->ClientCompat.setScreen(minecraft,new ScriptVariablesScreen(this,true))});
        }else if(tab.equals("Selection")){
            actionRow(new String[]{"Select coordinates","Move outline","Expand","Shrink","Invert"},new String[]{"select_box","selection_move","selection_expand","selection_shrink","selection_invert"});
        }else if(tab.equals("Operations")){
            String[] actions={"fill","fill_outline","fill_walls","fill_top","fill_bottom","fill_nearest","replace","set_biome","autoshade","drain","waterlog","smooth_snow","gravity","trigger_updates","hollow","fill_gaps","selection_convex","move","clone","stack","analyze","selection_filter","selection_bounds","selection_distort","selection_smooth","selection_scale"};
            for(int i=0;i<actions.length;i++){String action=actions[i];button(human(action),left+(i%4)*158,top+40+(i/4)*30,150,24,()->send(action));}
            actionRow(new String[]{"Generate CIELAB field"},new Runnable[]{ColourField::generate});
        }else if(tab.equals("Masks")){
            actionRow(new String[]{"Visual rules…","Block rule","Surface rule","Wrap NOT","Clear rules"},new Runnable[]{()->ClientCompat.setScreen(minecraft,new MaskScreen(this)),()->ClientState.options.maskExpression="block("+ClientState.options.block+")",()->ClientState.options.maskExpression="surface",()->ClientState.options.maskExpression="not("+ClientState.options.maskExpression+")",()->ClientState.options.maskExpression=""});
        }else if(tab.equals("Geometry")){
            actionRow(new String[]{"Add cursor node","Remove last","Clear nodes"},new Runnable[]{()->{var hit=editor.target();if(hit!=null&&ClientState.options.nodes.size()<64)ClientState.options.nodes.add(CraftiumClient.point(hit.getBlockPos()));},()->{if(!ClientState.options.nodes.isEmpty())ClientState.options.nodes.removeLast();},()->ClientState.options.nodes.clear()});
        }else if(tab.equals("Library")){
            button("Save tool preset",left,top+155,180,24,()->{ClientLibrary.savePreset(ClientState.options.name);message="Preset saved";});
            button("Load tool preset",left+190,top+155,180,24,()->{ClientLibrary.loadPreset(ClientState.options.name);init();message="Preset loaded";});
            button("Save palette",left,top+189,180,24,()->{ClientLibrary.savePalette(ClientState.options.name);message="Palette saved";});
            button("Load palette",left+190,top+189,180,24,()->{ClientLibrary.loadPalette(ClientState.options.name);message="Palette loaded";});
            button("Save clipboard blueprint",left,top+223,180,24,()->send("blueprint_save"));
            button("Load blueprint",left+190,top+223,180,24,()->send("blueprint_load"));
            labels.add(new Label("Presets: "+String.join(", ",ClientLibrary.list("presets")),left,top+270));
            labels.add(new Label("Palettes: "+String.join(", ",ClientLibrary.list("palettes")),left,top+286));
            actionRow(new String[]{"Browse blueprints"},new Runnable[]{()->ClientCompat.setScreen(minecraft,new BlueprintScreen(this))});
        }else if(tab.equals("Assets")){
            actionRow(new String[]{"Import asset…","Import schematic","Export schematic","Structure NBT","Selection CSV"},new Runnable[]{()->AssetPicker.open(this::init),()->send("schematic_import"),()->send("schematic_export"),()->send("structure_export"),()->send("selection_csv")});
        }else if(tab.equals("Paint")){
            actionRow(new String[]{"Colour studio","Weighted palette"},new Runnable[]{()->ClientCompat.setScreen(minecraft,new ColourScreen(this)),()->ClientCompat.setScreen(minecraft,new PaletteScreen(this))});
        }else if(tab.equals("Ruler")){
            actionRow(new String[]{"Split segments","Select center","Clear ruler"},new Runnable[]{()->{
                var next=new ArrayList<Point>();var points=ClientState.rulerPoints;int parts=ClientState.options.rulerSplit;
                for(int i=1;i<points.size();i++){Point a=points.get(i-1),b=points.get(i);for(int j=0;j<parts;j++)next.add(new Point((int)Math.round(a.x()+(b.x()-a.x())*j/(double)parts),(int)Math.round(a.y()+(b.y()-a.y())*j/(double)parts),(int)Math.round(a.z()+(b.z()-a.z())*j/(double)parts)));}
                if(!points.isEmpty())next.add(points.getLast());if(next.size()>256)throw new IllegalArgumentException("Ruler limit: 256 points");points.clear();points.addAll(next);
            },()->{
                var points=ClientState.rulerPoints;if(points.size()<2)throw new IllegalArgumentException("Set at least two ruler points");Point a=points.getFirst(),b=points.getLast();Point center=new Point((a.x()+b.x())/2,(a.y()+b.y())/2,(a.z()+b.z())/2);ClientState.options.position1=center;ClientState.options.position2=center;send("select_box");
            },()->{ClientState.rulerPoints.clear();ClientState.rulerStart=null;ClientState.rulerEnd=null;}});
        }else if(tab.equals("Placement")){
            actionRow(new String[]{"Transform clipboard","Snap to ground","Paste copy","Paste & select","Confirm"},new Runnable[]{()->send("clipboard_transform"),PlacementPreview::snapToGround,PlacementPreview::pasteCopy,PlacementPreview::pasteAndSelect,PlacementPreview::confirm});
        }else if(tab.equals("Hotbars")){
            actionRow(new String[]{"Save named hotbar","Load named hotbar","List hotbars","Inventory"},new Runnable[]{()->send("hotbar_save"),()->send("hotbar_load"),()->send("hotbar_list"),()->ClientCompat.setScreen(minecraft,new InventoryPaletteScreen(this))});
            button("Previous saved slot",left,top+125,200,24,()->send("hotbar_previous"));button("Next saved slot",left+212,top+125,200,24,()->send("hotbar_next"));
        }else if(tab.equals("Lighting")){
            actionRow(new String[]{"Autoshade","Add light here","Clear lights","List lights","Palette…"},new Runnable[]{()->send("autoshade"),()->{var player=minecraft.player;if(player!=null&&ClientState.options.shadeLights.size()<16)ClientState.options.shadeLights.add(new ShadeLight(player.getX(),player.getEyeY(),player.getZ(),ClientState.options.strength));},()->ClientState.options.shadeLights.clear(),()->ClientCompat.setScreen(minecraft,new ReportScreen(this,String.join("\n",ClientState.options.shadeLights.stream().map(Object::toString).toList()))),()->ClientCompat.setScreen(minecraft,new PaletteScreen(this))});
        }else if(tab.equals("Rebuild")){
            actionRow(new String[]{"Start animated rebuild","Cancel and restore"},new String[]{"animated_rebuild","rebuild_cancel"});
        }else if(tab.equals("Entities")){
            actionRow(new String[]{"Create display","Select nearest","Update NBT","Move","Delete"},new String[]{"entity_spawn","entity_select","entity_update","entity_move","entity_delete"});
            String[] extra={"entity_duplicate","entity_transform","entity_reset","entity_group","entity_ungroup","entity_group_transform"};
            for(int i=0;i<extra.length;i++){String action=extra[i];button(human(action.replace("entity_","")),left+(i%3)*209,canvasH-145+(i/3)*26,201,22,()->send(action));}

        }else if(tab.equals("World")){
            String[] actions={"world_time","world_clear","world_rain","world_thunder","world_random_ticks","world_advance_time","world_advance_weather","world_spawn_mobs","world_keep_inventory","world_block_gravity","world_trample_farmland","world_block_drops","world_player_invulnerability","world_fire_tick","gamemode_creative","gamemode_spectator","gamemode_survival","gamemode_adventure","view_save","view_load"};
            for(int i=0;i<actions.length;i++){String action=actions[i];button(human(action),left+(i%4)*158,top+170+(i/4)*28,150,22,()->send(action));}
        }else if(tab.equals("Annotations")){
            actionRow(new String[]{"Create","Update","Delete","Clear all","Import image…"},new Runnable[]{()->send("annotation_create"),()->send("annotation_update"),()->send("annotation_delete"),()->send("annotation_clear"),()->AnnotationImages.choose(this::init)});
            button("Browse annotations",left+316,top+254,300,22,()->{String report=ClientState.status.annotations()==null?"No annotations":String.join("\n",ClientState.status.annotations().stream().map(note->note.id()+"  "+note.mode()+"  "+note.text()).toList());ClientCompat.setScreen(minecraft,new ReportScreen(this,report));});
        }
    }
    private void createField(String name,int x,int y,int width){
        try{
            Field f=Options.class.getField(name);Object value=f.get(ClientState.options);
            if(f.getType()==boolean.class){button((Boolean)value?"Enabled":"Disabled",x,y,width,22,()->{try{f.setBoolean(ClientState.options,!f.getBoolean(ClientState.options));init();}catch(Exception e){message=e.getMessage();}});return;}
            if(f.getType().isEnum()){button(human(value.toString()),x,y,width,22,()->{try{Object[] values=f.getType().getEnumConstants();f.set(ClientState.options,values[(((Enum<?>)value).ordinal()+1)%values.length]);init();}catch(Exception e){message=e.getMessage();}});return;}
            boolean color=name.startsWith("ui")||name.equals("annotationColor");String text=color?String.format(Locale.ROOT,"#%08X",(Integer)value):value instanceof Point p?p.coordinates():value instanceof List<?> list?String.join("; ",list.stream().map(v->((Point)v).coordinates()).toList()):String.valueOf(value).replace("\n","\\n");
            EditBox field=new EditBox(font,x,y,width,22,Component.literal(human(name)));field.setMaxLength(16384);field.setValue(text);field.setResponder(input->{
                try{Object parsed;
                    if(color)parsed=(int)Long.parseUnsignedLong(input.replaceFirst("^(#|0x)",""),16);else if(f.getType()==int.class)parsed=Integer.parseInt(input);else if(f.getType()==long.class)parsed=Long.parseLong(input);else if(f.getType()==double.class)parsed=Double.parseDouble(input);else if(f.getType()==float.class)parsed=Float.parseFloat(input);
                    else if(f.getType()==Point.class)parsed=point(input);else if(List.class.isAssignableFrom(f.getType())){var points=new ArrayList<Point>();if(!input.isBlank())for(String token:input.split(";"))points.add(point(token));parsed=points;}
                    else parsed=input.replace("\\n","\n");f.set(ClientState.options,parsed);message="";
                }catch(Exception e){message="Enter a valid value for "+human(name);}
            });addRenderableWidget(field);
        }catch(Exception e){message=e.getMessage();}
    }
    private static Point point(String input){String[] parts=input.trim().split("[,\\s]+");if(parts.length!=3)throw new IllegalArgumentException();return new Point(Integer.parseInt(parts[0]),Integer.parseInt(parts[1]),Integer.parseInt(parts[2]));}
    private String human(String name){String s=name.replaceAll("([a-z])([A-Z])","$1 $2").replace('_',' ').toLowerCase(Locale.ROOT);return s.isEmpty()?s:Character.toUpperCase(s.charAt(0))+s.substring(1);}
    private void actionRow(String[] names,String[] actions){Runnable[] run=new Runnable[actions.length];for(int i=0;i<actions.length;i++){String action=actions[i];run[i]=()->send(action);}actionRow(names,run);}
    private void actionRow(String[] names,Runnable[] actions){for(int i=0;i<names.length;i++){Runnable action=actions[i];button(names[i],left+i*125,canvasH-89,119,21,()->{try{action.run();init();}catch(Exception e){message=e.getMessage();}});}}
    private void button(String text,int x,int y,int width,int height,Runnable action){addRenderableWidget(new FlatButton(x,y,width,height,text,()->{try{action.run();}catch(Exception e){message=e.getMessage();}}));}
    private void send(String action){try{if(action.startsWith("entity_"))ClientState.options.gizmoTarget=Options.GizmoTarget.ENTITY;if(action.equals("use")&&(ClientState.options.tool==Tool.LASSO_SELECT||ClientState.options.tool==Tool.RULER)){message="Return to the viewport to draw a lasso or place ruler points";return;}ClientState.options.validate();var hit=editor.target();CraftiumClient.send(action,hit==null?ClientState.options.position1:CraftiumClient.point(hit.getBlockPos()),hit==null?new Point(0,1,0):CraftiumClient.normal(hit));message="Sent "+human(action);}catch(Exception e){message=e.getMessage();}}
    @Override protected void drawBackground(Gfx g,int x,int y,float delta){}
    @Override protected void draw(Gfx g,int x,int y,float delta){
        g.pose().pushMatrix();g.pose().scale(uiScale,uiScale);
        g.fill(0,0,canvasW,canvasH,0xee101923);g.fill(left-12,top-13,Math.min(canvasW-5,left+640),canvasH-25,0xff172330);g.text(font,"CRAFTIUM  /  "+tab.toUpperCase(Locale.ROOT),left,top-25,0xffb5dcfc,false);
        for(Label label:labels)g.text(font,font.plainSubstrByWidth(label.text,625),label.x,label.y,0xff9db5cb,false);
        drawWidgets(g,(int)(x/uiScale),(int)(y/uiScale),delta);g.text(font,font.plainSubstrByWidth(message.isBlank()?ClientState.status.message():message,canvasW-30),15,canvasH-17,0xffffdca8,false);g.pose().popMatrix();
    }
    @Override public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event,boolean twice){return super.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(event.x()/uiScale,event.y()/uiScale,event.buttonInfo()),twice);}
    @Override public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event){return super.mouseReleased(new net.minecraft.client.input.MouseButtonEvent(event.x()/uiScale,event.y()/uiScale,event.buttonInfo()));}
    @Override public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event,double x,double y){return super.mouseDragged(new net.minecraft.client.input.MouseButtonEvent(event.x()/uiScale,event.y()/uiScale,event.buttonInfo()),x/uiScale,y/uiScale);}
    @Override public boolean isPauseScreen(){return false;}
    @Override public boolean isInGameUi(){return true;}
    @Override public void onClose(){ClientState.save();CraftiumClient.send("capabilities",null,null);ClientCompat.setScreen(minecraft,editor);}
    @Override public void removed(){if(initialized&&ClientCompat.hudHidden(minecraft)!=previousHud)ClientCompat.toggleHud(minecraft);initialized=false;}
}
