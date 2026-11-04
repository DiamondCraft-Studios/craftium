package dev.craftium.server;

import dev.craftium.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import java.util.*;

/**
 * A structure as the export pop-up describes it: the blocks and entities taken from the selection or the clipboard, cut to
 * the chosen size, then mirrored, rotated, thinned by integrity and with air turned to void if asked. The Java file, the
 * Bedrock file and the preview are all written from this one result, so the preview shows what the file holds.
 */
final class StructureBuilder {
    /** Bedrock's block state version, which the game upgrades older blocks from. It matches the block table. */
    static final int BEDROCK_BLOCK_VERSION=18168865;
    static final int MAX_VOLUME=StructurePreview.MAX_VOLUME;

    final int sizeX,sizeY,sizeZ;
    /** One entry per cell, x then z then y fastest-first as {@code (y*sizeZ+z)*sizeX+x}; null is void, where the file places nothing. */
    final BlockSnapshot[] cells;
    final List<CompoundTag> entities;
    final Point origin;
    final StructureSettings settings;
    /** The size before mirror and rotation, for the pop-up's size fields. */
    int[] source;
    final List<String> notes=new ArrayList<>();

    private StructureBuilder(StructureSettings settings,int sx,int sy,int sz,BlockSnapshot[] cells,List<CompoundTag> entities,Point origin){
        this.settings=settings;sizeX=sx;sizeY=sy;sizeZ=sz;this.cells=cells;this.entities=entities;this.origin=origin;
    }
    int index(int x,int y,int z){return (y*sizeZ+z)*sizeX+x;}

    /** Reads the source, then applies the settings. Throws {@link IllegalArgumentException} with a message for the player. */
    static StructureBuilder build(EditPlanner p,ServerPlayer player){
        var s=p.o.structure;s.validate();
        boolean clipboard=s.source.equals(StructureSettings.CLIPBOARD);
        Map<Point,BlockSnapshot> source=new LinkedHashMap<>();List<CompoundTag> entities=new ArrayList<>();Point min;int dx,dy,dz;
        if(clipboard){
            if(p.session.clipboard.isEmpty()&&p.session.clipboardEntities.isEmpty())throw new IllegalArgumentException("Copy a selection first");
            int x0=Integer.MAX_VALUE,y0=Integer.MAX_VALUE,z0=Integer.MAX_VALUE,x1=Integer.MIN_VALUE,y1=Integer.MIN_VALUE,z1=Integer.MIN_VALUE;
            for(var at:p.session.clipboard.keySet()){x0=Math.min(x0,at.x());y0=Math.min(y0,at.y());z0=Math.min(z0,at.z());x1=Math.max(x1,at.x());y1=Math.max(y1,at.y());z1=Math.max(z1,at.z());}
            if(p.session.clipboard.isEmpty()){x0=y0=z0=0;x1=y1=z1=0;}
            min=new Point(x0,y0,z0);dx=x1-x0+1;dy=y1-y0+1;dz=z1-z0+1;
            if(s.includeBlocks)for(var entry:p.session.clipboard.entrySet())source.put(new Point(entry.getKey().x()-x0,entry.getKey().y()-y0,entry.getKey().z()-z0),entry.getValue());
            if(s.includeEntities)for(var saved:p.session.clipboardEntities){var copy=saved.copy();ClipboardEntities.offset(copy,-x0,-y0,-z0);entities.add(copy);}
        }else{
            if(p.session.selection.size()==0)throw new IllegalArgumentException("Create a selection first");
            min=p.session.selection.min();var max=p.session.selection.max();dx=max.x()-min.x()+1;dy=max.y()-min.y()+1;dz=max.z()-min.z()+1;
            if((long)dx*dy*dz>MAX_VOLUME)throw new IllegalArgumentException("The selection's bounding box is too large to export");
            if(s.includeBlocks)for(var at:p.session.selection.points())source.put(new Point(at.x()-min.x(),at.y()-min.y(),at.z()-min.z()),null); // read below, once the size is known
            if(s.includeEntities)entities.addAll(ClipboardEntities.capture(player,p.session).relative());
        }
        int sx=s.sizeX>0?s.sizeX:dx,sy=s.sizeY>0?s.sizeY:dy,sz=s.sizeZ>0?s.sizeZ:dz;
        if((long)sx*sy*sz>MAX_VOLUME)throw new IllegalArgumentException("The structure is too large to export");
        var cells=new BlockSnapshot[sx*sy*sz];
        for(var entry:source.entrySet()){
            var at=entry.getKey();if(at.x()<0||at.y()<0||at.z()<0||at.x()>=sx||at.y()>=sy||at.z()>=sz)continue;
            var snapshot=entry.getValue();if(snapshot==null)snapshot=BlockSnapshot.capture(p.level,EditPlanner.pos(new Point(at.x()+min.x(),at.y()+min.y(),at.z()+min.z())));
            cells[(at.y()*sz+at.z())*sx+at.x()]=snapshot;
        }
        entities.removeIf(tag->{var at=tag.getListOrEmpty("Pos");return at.getDoubleOr(0,0)<0||at.getDoubleOr(1,0)<0||at.getDoubleOr(2,0)<0||at.getDoubleOr(0,0)>=sx||at.getDoubleOr(1,0)>=sy||at.getDoubleOr(2,0)>=sz;});
        var built=new StructureBuilder(s,sx,sy,sz,cells,entities,clipboard?new Point(0,0,0):min);
        built.source=new int[]{sx,sy,sz};
        var result=built.transformed().thinned().voided();result.source=built.source;return result;
    }

    private static Mirror mirrorOf(String name){return switch(name){case "left_right"->Mirror.LEFT_RIGHT;case "front_back"->Mirror.FRONT_BACK;default->Mirror.NONE;};}
    private static Rotation rotationOf(int degrees){return switch(degrees){case 90->Rotation.CLOCKWISE_90;case 180->Rotation.CLOCKWISE_180;case 270->Rotation.COUNTERCLOCKWISE_90;default->Rotation.NONE;};}

    /** Mirror first, then rotate, as a structure block does when it places a structure. */
    private StructureBuilder transformed(){
        var mirror=mirrorOf(settings.mirror);var rotation=rotationOf(settings.rotation);
        if(mirror==Mirror.NONE&&rotation==Rotation.NONE)return this;
        boolean swap=rotation==Rotation.CLOCKWISE_90||rotation==Rotation.COUNTERCLOCKWISE_90;int nx=swap?sizeZ:sizeX,nz=swap?sizeX:sizeZ;
        var out=new BlockSnapshot[cells.length];
        for(int y=0;y<sizeY;y++)for(int z=0;z<sizeZ;z++)for(int x=0;x<sizeX;x++){
            var snapshot=cells[(y*sizeZ+z)*sizeX+x];if(snapshot==null)continue;
            int mx=mirror==Mirror.FRONT_BACK?sizeX-1-x:x,mz=mirror==Mirror.LEFT_RIGHT?sizeZ-1-z:z,rx,rz;
            switch(rotation){case CLOCKWISE_90->{rx=sizeZ-1-mz;rz=mx;}case COUNTERCLOCKWISE_90->{rx=mz;rz=sizeX-1-mx;}case CLOCKWISE_180->{rx=sizeX-1-mx;rz=sizeZ-1-mz;}default->{rx=mx;rz=mz;}}
            out[(y*nz+rz)*nx+rx]=new BlockSnapshot(snapshot.state().mirror(mirror).rotate(rotation),snapshot.data());
        }
        var moved=new ArrayList<CompoundTag>();
        for(var tag:entities){
            var copy=tag.copy();ClipboardEntities.walk(copy,item->{
                var at=item.getListOrEmpty("Pos");double x=at.getDoubleOr(0,0),y=at.getDoubleOr(1,0),z=at.getDoubleOr(2,0);
                var angles=item.getListOrEmpty("Rotation");float yaw=angles.getFloatOr(0,0);
                if(mirror==Mirror.FRONT_BACK){x=sizeX-x;yaw=-yaw;}else if(mirror==Mirror.LEFT_RIGHT){z=sizeZ-z;yaw=180-yaw;}
                double rx,rz;switch(rotation){case CLOCKWISE_90->{rx=sizeZ-z;rz=x;yaw+=90;}case COUNTERCLOCKWISE_90->{rx=z;rz=sizeX-x;yaw+=270;}case CLOCKWISE_180->{rx=sizeX-x;rz=sizeZ-z;yaw+=180;}default->{rx=x;rz=z;}}
                ClipboardEntities.position(item,rx,y,rz);var next=new ListTag();next.add(FloatTag.valueOf(yaw));next.add(FloatTag.valueOf(angles.getFloatOr(1,0)));item.put("Rotation",next);
            });
            moved.add(copy);
        }
        return new StructureBuilder(settings,nx,sizeY,nz,out,moved,origin);
    }

    /** Integrity drops each block with the chance it leaves out, from the seed, as a structure block placing it would. */
    private StructureBuilder thinned(){
        if(settings.integrity>=100)return this;
        var random=new Random(settings.seed);float keep=settings.integrity/100f;
        for(int i=0;i<cells.length;i++)if(cells[i]!=null&&random.nextFloat()>keep)cells[i]=null;
        return this;
    }

    /** Air becomes void when asked; a structure void block always does, as it never is saved. */
    private StructureBuilder voided(){
        int air=0;
        for(int i=0;i<cells.length;i++){
            var snapshot=cells[i];if(snapshot==null)continue;
            if(snapshot.state().is(Blocks.STRUCTURE_VOID)||settings.airToVoid&&!settings.schematic()&&snapshot.state().isAir()){cells[i]=null;if(snapshot.state().isAir())air++;}
        }
        if(air>0)notes.add(air+" air blocks are void, so they leave what is already there");
        return this;
    }

    int blocks(){int n=0;for(var cell:cells)if(cell!=null&&!cell.state().isAir())n++;return n;}

    // --- Java structure NBT ---

    CompoundTag javaNbt(){
        var root=new CompoundTag();var palette=new ListTag();var indices=new HashMap<BlockState,Integer>();var blocks=new ListTag();
        for(int y=0;y<sizeY;y++)for(int z=0;z<sizeZ;z++)for(int x=0;x<sizeX;x++){
            var snapshot=cells[index(x,y,z)];if(snapshot==null)continue;
            int state=indices.computeIfAbsent(snapshot.state(),key->{palette.add(NbtUtils.writeBlockState(key));return palette.size()-1;});
            var block=new CompoundTag();block.put("pos",ints(x,y,z));block.putInt("state",state);
            if(snapshot.data()!=null){var data=snapshot.data().copy();data.remove("x");data.remove("y");data.remove("z");block.put("nbt",data);}
            blocks.add(block);
        }
        root.putInt("DataVersion",net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version());
        root.put("size",ints(sizeX,sizeY,sizeZ));root.put("palette",palette);root.put("blocks",blocks);root.put("entities",AssetNbt.structureEntities(entities));
        return root;
    }
    private static ListTag ints(int... values){var list=new ListTag();for(int v:values)list.add(IntTag.valueOf(v));return list;}

    // --- Sponge schematic (.schem, version 2) ---

    CompoundTag schematicNbt(){
        var root=new CompoundTag();root.putInt("Version",2);root.putInt("DataVersion",net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version());
        root.putShort("Width",(short)sizeX);root.putShort("Height",(short)sizeY);root.putShort("Length",(short)sizeZ);root.putIntArray("Offset",new int[]{settings.offsetX,settings.offsetY,settings.offsetZ});
        var palette=new CompoundTag();var indices=new LinkedHashMap<String,Integer>();var bytes=new java.io.ByteArrayOutputStream();var blockEntities=new ListTag();int gaps=0;
        for(int y=0;y<sizeY;y++)for(int z=0;z<sizeZ;z++)for(int x=0;x<sizeX;x++){
            var snapshot=cells[index(x,y,z)];if(snapshot==null)gaps++;
            // A schematic has no void: a cell nothing is placed in is air.
            String state=snapshot==null?"minecraft:air":net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(snapshot.state());
            int value=indices.computeIfAbsent(state,key->indices.size());while((value&~0x7f)!=0){bytes.write((value&0x7f)|0x80);value>>>=7;}bytes.write(value);
            if(snapshot!=null&&snapshot.data()!=null){var tag=snapshot.data().copy();tag.putString("Id",tag.getStringOr("id",""));tag.remove("id");tag.remove("x");tag.remove("y");tag.remove("z");tag.putIntArray("Pos",new int[]{x,y,z});blockEntities.add(tag);}
        }
        indices.forEach(palette::putInt);root.put("Palette",palette);root.putInt("PaletteMax",indices.size());root.putByteArray("BlockData",bytes.toByteArray());
        root.put("BlockEntities",blockEntities);root.put("Entities",AssetNbt.schematicEntities(entities));
        if(gaps>0&&!settings.airToVoid)notes.add(gaps+" cells outside the selection are air in a schematic");
        return root;
    }

    // --- Bedrock .mcstructure ---

    /** The cells Bedrock can hold, with the blocks it has no equivalent for turned to void and counted in {@link #notes}. */
    private static final class Bedrock {
        final List<Map<String,Object>> palette=new ArrayList<>();final int[] primary,secondary;final Map<String,Integer> unmapped=new TreeMap<>();int blockEntities;int water=-1;
        Bedrock(int cells){primary=new int[cells];secondary=new int[cells];Arrays.fill(primary,-1);Arrays.fill(secondary,-1);}
        int add(BedrockBlockTable.Bedrock block){
            var entry=new LinkedHashMap<String,Object>();entry.put("name",block.name());entry.put("states",new LinkedHashMap<String,Object>(block.states()));entry.put("version",BEDROCK_BLOCK_VERSION);
            palette.add(entry);return palette.size()-1;
        }
    }
    private Bedrock bedrock(){
        var result=new Bedrock(cells.length);var indices=new HashMap<BlockState,Integer>();var failed=new HashSet<BlockState>();
        for(int x=0;x<sizeX;x++)for(int y=0;y<sizeY;y++)for(int z=0;z<sizeZ;z++){
            var snapshot=cells[index(x,y,z)];if(snapshot==null)continue;var state=snapshot.state();int at=(x*sizeY+y)*sizeZ+z;
            Integer id=indices.get(state);
            if(id==null&&!failed.contains(state)){var mapped=BedrockBlocks.toBedrock(state);if(mapped==null)failed.add(state);else{id=result.add(mapped);indices.put(state,id);}}
            if(id==null){result.unmapped.merge(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),1,Integer::sum);continue;}
            result.primary[at]=id;
            if(snapshot.data()!=null)result.blockEntities++;
            if(state.hasProperty(BlockStateProperties.WATERLOGGED)&&state.getValue(BlockStateProperties.WATERLOGGED)){
                if(result.water<0){var water=BedrockBlockTable.get().toBedrock("minecraft:water",Map.of("level","0"));result.water=water==null?-2:result.add(water);}
                if(result.water>=0)result.secondary[at]=result.water;
            }
        }
        if(!result.unmapped.isEmpty()){
            int total=result.unmapped.values().stream().mapToInt(Integer::intValue).sum();
            notes.add(total+" blocks have no Bedrock equivalent and are left out ("+String.join(", ",result.unmapped.keySet().stream().limit(3).toList())+(result.unmapped.size()>3?", …":"")+")");
        }
        if(result.blockEntities>0)notes.add(result.blockEntities+" block entities (chests, signs…) keep their block but not their contents in Bedrock files");
        if(!entities.isEmpty())notes.add(entities.size()+" entities are not written to Bedrock files");
        return result;
    }

    byte[] bedrockBytes(){
        var b=bedrock();var structure=new LinkedHashMap<String,Object>();
        structure.put("block_indices",List.of(new BedrockNbt.IntList(b.primary),new BedrockNbt.IntList(b.secondary)));
        structure.put("entities",new ArrayList<Object>());
        var defaults=new LinkedHashMap<String,Object>();defaults.put("block_palette",new ArrayList<Object>(b.palette));defaults.put("block_position_data",new LinkedHashMap<String,Object>());
        var palette=new LinkedHashMap<String,Object>();palette.put("default",defaults);structure.put("palette",palette);
        var root=new LinkedHashMap<String,Object>();root.put("format_version",1);root.put("size",List.of(sizeX,sizeY,sizeZ));root.put("structure",structure);
        root.put("structure_world_origin",List.of(origin.x()+settings.offsetX,origin.y()+settings.offsetY,origin.z()+settings.offsetZ));
        return BedrockNbt.write(root);
    }

    // --- Preview ---

    StructurePreview preview(){
        boolean bedrock=settings.bedrock();var bedrockCells=bedrock?bedrock():null;
        var palette=new ArrayList<StructurePreview.Block>();var indices=new HashMap<BlockState,Integer>();var out=new int[cells.length];int blocks=0;
        for(int y=0;y<sizeY;y++)for(int z=0;z<sizeZ;z++)for(int x=0;x<sizeX;x++){
            int i=index(x,y,z);var snapshot=cells[i];
            if(snapshot==null&&settings.schematic()){out[i]=StructurePreview.AIR;continue;} // a schematic holds air where a structure holds nothing
            if(snapshot==null||bedrockCells!=null&&bedrockCells.primary[(x*sizeY+y)*sizeZ+z]<0){out[i]=StructurePreview.VOID;continue;}
            var state=snapshot.state();
            if(state.isAir()){out[i]=StructurePreview.AIR;continue;}
            Integer id=indices.get(state);
            if(id==null){if(palette.size()>=60_000){out[i]=StructurePreview.VOID;continue;}id=palette.size();indices.put(state,id);palette.add(block(state));}
            out[i]=id;blocks++;
        }
        return new StructurePreview(sizeX,sizeY,sizeZ,List.copyOf(palette),out,List.copyOf(notes),blocks,source);
    }
    private static StructurePreview.Block block(BlockState state){
        var bounds=new int[]{0,0,0,16,16,16};
        try{
            var shape=state.getShape(EmptyBlockGetter.INSTANCE,BlockPos.ZERO);
            if(shape.isEmpty())bounds=new int[]{6,0,6,10,10,10};
            else{var box=shape.bounds();bounds=new int[]{q(box.minX),q(box.minY),q(box.minZ),q(box.maxX),q(box.maxY),q(box.maxZ)};}
        }catch(RuntimeException e){ /* a shape that needs the world: show it whole */ }
        boolean full=bounds[0]==0&&bounds[1]==0&&bounds[2]==0&&bounds[3]==16&&bounds[4]==16&&bounds[5]==16;
        return new StructurePreview.Block(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),bounds,full&&state.canOcclude());
    }
    private static int q(double v){return Math.clamp((int)Math.round(v*16),0,16);}
}
