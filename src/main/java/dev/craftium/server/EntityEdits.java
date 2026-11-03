package dev.craftium.server;

import dev.craftium.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import java.util.*;

final class EntityEdits {
    private EntityEdits(){}
    static CompoundTag snapshot(Entity entity,ServerLevel level){var out=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,level.registryAccess());if(!entity.save(out))throw new IllegalArgumentException("Entity cannot be saved");return out.buildResult();}
    static boolean editable(Entity entity){return entity instanceof net.minecraft.world.entity.Display||entity instanceof net.minecraft.world.entity.Marker;}
    static Entity nearest(ServerLevel level,Point at){return level.getEntities((Entity)null,new AABB(EditPlanner.pos(at)).inflate(8),EntityEdits::editable).stream().min(Comparator.comparingDouble(e->e.distanceToSqr(at.x(),at.y(),at.z()))).orElseThrow(()->new IllegalArgumentException("No display or marker within 8 blocks of target"));}
    static Session.Edit plan(String action,ServerPlayer player,Session s,Options o,Point at){
        var level=player.level();
        try{
            if(action.equals("entity_group")||action.equals("entity_ungroup")||action.equals("entity_group_transform"))return group(action,player,s,o,at);
            if(action.equals("entity_spawn")){
                if(!Set.of("block_display","item_display","text_display","marker").contains(o.entityType))throw new IllegalArgumentException("Choose a block, item, text display or marker");
                var tag=TagParser.parseCompoundFully(o.entityNbt);tag.putString("id","minecraft:"+o.entityType);tag.remove("UUID");tag.remove("Passengers");
                if(tag.contains("transformation"))com.mojang.math.Transformation.EXTENDED_CODEC.parse(NbtOps.INSTANCE,tag.get("transformation")).getOrThrow(error->new IllegalArgumentException("Invalid display transformation: "+error));
                Entity entity=dev.craftium.compat.Compat.loadEntity(tag,level);
                if(entity==null)throw new IllegalArgumentException("Invalid display entity NBT");entity.setPos(at.x()+.5,at.y()+1,at.z()+.5);s.selectedEntity=entity.getUUID();
                return new Session.Edit("Create "+o.entityType,List.of(),List.of(),2,List.of(new Session.EntityChange(entity.getUUID(),null,snapshot(entity,level))));
            }
            Entity entity=o.entityUuid.isBlank()?nearest(level,at):level.getEntity(UUID.fromString(o.entityUuid));
            if(entity==null||!editable(entity)||entity.distanceToSqr(player)>256*256)throw new IllegalArgumentException("Select a nearby display or marker entity");
            s.selectedEntity=entity.getUUID();var before=snapshot(entity,level);CompoundTag after=before.copy();
            switch(action){
                case "entity_delete" -> after=null;
                case "entity_update" -> {after.merge(TagParser.parseCompoundFully(o.entityNbt));after.putString("id",before.getStringOr("id",""));after.put("UUID",before.get("UUID"));after.remove("Passengers");}
                case "entity_move" -> {var pos=new ListTag();pos.add(DoubleTag.valueOf(entity.getX()+o.offsetX));pos.add(DoubleTag.valueOf(entity.getY()+o.offsetY));pos.add(DoubleTag.valueOf(entity.getZ()+o.offsetZ));after.put("Pos",pos);}
                case "entity_gizmo" -> {ClipboardEntities.position(after,entity.getX()+o.entityX,entity.getY()+o.entityY,entity.getZ()+o.entityZ);if(entity instanceof Display)composeTransform(after,o);else after.put("Rotation",floats(entity.getYRot()+(float)o.yaw,entity.getXRot()+(float)o.pitch));}
                case "entity_transform" -> {if(entity instanceof Display)transform(after,o);else{ClipboardEntities.position(after,entity.getX()+o.entityX,entity.getY()+o.entityY,entity.getZ()+o.entityZ);after.put("Rotation",floats((float)o.yaw,(float)o.pitch));}}
                case "entity_reset" -> {after.remove("transformation");if(entity instanceof Display)after.putString("billboard","fixed");else after.put("Rotation",floats(0,0));}
                case "entity_duplicate" -> {after.remove("UUID");var copy=dev.craftium.compat.Compat.loadEntity(after,level);if(copy==null)throw new IllegalArgumentException("Cannot duplicate entity");s.selectedEntity=copy.getUUID();return new Session.Edit("Duplicate display",List.of(),List.of(),2,List.of(new Session.EntityChange(copy.getUUID(),null,snapshot(copy,level))));}
                default -> throw new IllegalArgumentException("Unknown entity action");
            }
            if(after!=null){if(after.contains("transformation"))com.mojang.math.Transformation.EXTENDED_CODEC.parse(NbtOps.INSTANCE,after.get("transformation")).getOrThrow(error->new IllegalArgumentException("Invalid display transformation: "+error));var coordinates=after.getListOrEmpty("Pos");double x=coordinates.getDoubleOr(0,Double.NaN),y=coordinates.getDoubleOr(1,Double.NaN),z=coordinates.getDoubleOr(2,Double.NaN);if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))throw new IllegalArgumentException("Entity coordinates must be finite");var position=net.minecraft.core.BlockPos.containing(x,y,z);if(!level.isInWorldBounds(position)||!level.hasChunk(position.getX()>>4,position.getZ()>>4))throw new IllegalArgumentException("Entity edit reaches unloaded chunks or world bounds");}
            if(after!=null)after=normalize(after,level);
            return new Session.Edit(action.replace('_',' '),List.of(),List.of(),2,List.of(new Session.EntityChange(entity.getUUID(),before,after)));
        }catch(com.mojang.brigadier.exceptions.CommandSyntaxException e){throw new IllegalArgumentException("Invalid entity NBT: "+e.getMessage());}
    }
    private static ListTag floats(float... values){var result=new ListTag();for(float value:values)result.add(FloatTag.valueOf(value));return result;}
    private static CompoundTag normalize(CompoundTag tag,ServerLevel level){var entity=dev.craftium.compat.Compat.loadEntity(tag.copy(),level);if(entity==null)throw new IllegalArgumentException("Invalid editable entity data");return snapshot(entity,level);}
    private static void transform(CompoundTag tag,Options o){
        var rotation=new org.joml.Quaternionf().rotationYXZ((float)Math.toRadians(o.yaw),(float)Math.toRadians(o.pitch),(float)Math.toRadians(o.roll));var transform=new CompoundTag();
        transform.put("translation",floats((float)o.entityX,(float)o.entityY,(float)o.entityZ));transform.put("scale",floats((float)o.entityScaleX,(float)o.entityScaleY,(float)o.entityScaleZ));transform.put("left_rotation",floats(rotation.x,rotation.y,rotation.z,rotation.w));transform.put("right_rotation",floats(0,0,0,1));tag.put("transformation",transform);
    }
    private static void composeTransform(CompoundTag tag,Options o){var existing=tag.get("transformation");var original=existing==null?dev.craftium.compat.Compat.identityTransform():com.mojang.math.Transformation.EXTENDED_CODEC.parse(NbtOps.INSTANCE,existing).result().orElse(dev.craftium.compat.Compat.identityTransform());var delta=new org.joml.Matrix4f().rotationZ((float)Math.toRadians(o.roll)).rotateX((float)Math.toRadians(o.pitch)).rotateY((float)Math.toRadians(o.yaw)).scale((float)o.entityScaleX,(float)o.entityScaleY,(float)o.entityScaleZ).mul(original.getMatrix());tag.put("transformation",com.mojang.math.Transformation.EXTENDED_CODEC.encodeStart(NbtOps.INSTANCE,new com.mojang.math.Transformation(delta)).getOrThrow());}
    private static Session.Edit group(String action,ServerPlayer player,Session s,Options o,Point at){
        if(o.entityGroup.isBlank())throw new IllegalArgumentException("Enter an entity group name");String group="craftium:"+o.entityGroup;
        var level=player.level();AABB bounds=s.selection.size()>0?new AABB(net.minecraft.world.phys.Vec3.atLowerCornerOf(EditPlanner.pos(s.selection.min())),net.minecraft.world.phys.Vec3.atLowerCornerOf(EditPlanner.pos(s.selection.max().add(1,1,1)))):player.getBoundingBox().inflate(256);
        var entities=level.getEntities((Entity)null,bounds,e->editable(e)&&e.distanceToSqr(player)<=256*256&&(action.equals("entity_group")||dev.craftium.compat.Compat.entityTags(e).contains(group)));
        if(entities.isEmpty())throw new IllegalArgumentException("No editable entities match this group or selection");if(entities.size()>128)throw new IllegalArgumentException("Entity group limit: 128");
        var changes=new ArrayList<Session.EntityChange>();double px=entities.stream().mapToDouble(Entity::getX).average().orElse(0),py=entities.stream().mapToDouble(Entity::getY).average().orElse(0),pz=entities.stream().mapToDouble(Entity::getZ).average().orElse(0);
        for(var entity:entities){var before=snapshot(entity,level);var after=before.copy();
            if(action.equals("entity_group_transform")){
                var rotated=Geometry.rotate(new Geometry.Vector((entity.getX()-px)*o.entityScaleX,(entity.getY()-py)*o.entityScaleY,(entity.getZ()-pz)*o.entityScaleZ),o.yaw,o.pitch,o.roll);
                var pos=new ListTag();pos.add(DoubleTag.valueOf(px+rotated.x()+o.entityX));pos.add(DoubleTag.valueOf(py+rotated.y()+o.entityY));pos.add(DoubleTag.valueOf(pz+rotated.z()+o.entityZ));after.put("Pos",pos);
                if(entity instanceof Display)composeTransform(after,o);else after.put("Rotation",floats(entity.getYRot()+(float)o.yaw,entity.getXRot()+(float)o.pitch));
            }else{
                var tags=new ListTag();for(String tag:dev.craftium.compat.Compat.entityTags(entity))if(!tag.equals(group))tags.add(StringTag.valueOf(tag));if(action.equals("entity_group"))tags.add(StringTag.valueOf(group));after.put("Tags",tags);
            }
            changes.add(new Session.EntityChange(entity.getUUID(),before,normalize(after,level)));
        }
        return new Session.Edit(action.replace('_',' '),List.of(),List.of(),2,List.copyOf(changes));
    }
    static List<Session.EntityChange> apply(ServerLevel level,List<Session.EntityChange> changes,boolean backwards){
        var applied=new ArrayList<Session.EntityChange>();
        for(var change:changes){var expected=backwards?change.after():change.before();var desired=backwards?change.before():change.after();Entity current=level.getEntity(change.id());
            if(expected==null?current!=null:current==null||(!snapshot(current,level).equals(expected)&&(!change.moving()||!net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(current.getType()).toString().equals(expected.getStringOr("id","")))))continue;
            Entity replacement=desired==null?null:dev.craftium.compat.Compat.loadEntity(desired.copy(),level);
            if(desired!=null&&replacement==null)throw new IllegalArgumentException("Entity restoration failed");
            if(replacement!=null&&replacement.getSelfAndPassengers().anyMatch(e->{var existing=level.getEntity(e.getUUID());return existing!=null&&(current==null||current.getSelfAndPassengers().noneMatch(old->old.getUUID().equals(e.getUUID())));}))continue;
            if(current!=null)for(var entity:current.getPassengersAndSelf().toList()){if(entity instanceof net.minecraft.world.entity.player.Player)entity.stopRiding();else entity.discard();}
            if(replacement!=null&&!level.tryAddFreshEntityWithPassengers(replacement))throw new IllegalArgumentException("Entity insertion failed");applied.add(change);
        }return List.copyOf(applied);
    }
}
