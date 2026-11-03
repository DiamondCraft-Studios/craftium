package dev.craftium.server;

import dev.craftium.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.*;
import java.util.*;

final class ClipboardEntities {
    private ClipboardEntities(){}
    record Capture(List<CompoundTag> relative,List<Session.EntityChange> removals){}
    static Capture capture(ServerPlayer player,Session session){
        Point min=session.selection.min(),max=session.selection.max();var bounds=new AABB(Vec3.atLowerCornerOf(EditPlanner.pos(min)),Vec3.atLowerCornerOf(EditPlanner.pos(max.add(1,1,1))));
        var roots=player.level().getEntities((Entity)null,bounds,e->!(e instanceof Player)&&!e.isPassenger()&&e.isAlive()&&e.getSelfAndPassengers().noneMatch(passenger->passenger instanceof Player)&&session.selection.contains(EditPlanner.point(e.blockPosition())));
        if(roots.size()>128||roots.stream().mapToLong(e->e.getSelfAndPassengers().count()).sum()>512)throw new IllegalArgumentException("Clipboard entity limit: 128 roots / 512 including passengers");
        var result=new ArrayList<CompoundTag>();var removals=new ArrayList<Session.EntityChange>();for(var entity:roots){var before=EntityEdits.snapshot(entity,player.level());removals.add(new Session.EntityChange(entity.getUUID(),before,null,true));var tag=before.copy();offset(tag,-min.x(),-min.y(),-min.z());result.add(tag);}return new Capture(List.copyOf(result),List.copyOf(removals));
    }
    static void offset(CompoundTag tag,double x,double y,double z){walk(tag,item->{var at=item.getListOrEmpty("Pos");position(item,at.getDoubleOr(0,0)+x,at.getDoubleOr(1,0)+y,at.getDoubleOr(2,0)+z);});}
    static void position(CompoundTag tag,double x,double y,double z){var at=new ListTag();at.add(DoubleTag.valueOf(x));at.add(DoubleTag.valueOf(y));at.add(DoubleTag.valueOf(z));tag.put("Pos",at);}
    static void walk(CompoundTag tag,java.util.function.Consumer<CompoundTag> action){walk(tag,action,0);}
    private static void walk(CompoundTag tag,java.util.function.Consumer<CompoundTag> action,int depth){if(depth>16)throw new IllegalArgumentException("Entity passenger tree is too deep");action.accept(tag);var children=tag.getListOrEmpty("Passengers");for(int i=0;i<children.size();i++)walk(children.getCompoundOrEmpty(i),action,depth+1);}
    static List<Session.EntityChange> paste(ServerPlayer player,Session session,Point origin){
        var result=new ArrayList<Session.EntityChange>();String token="craftium:clipboard:"+UUID.randomUUID();int[] count={0};
        for(var saved:session.clipboardEntities){var tag=saved.copy();offset(tag,origin.x(),origin.y(),origin.z());
            walk(tag,item->{if(++count[0]>512)throw new IllegalArgumentException("Clipboard has too many entities");item.remove("UUID");var tags=new ListTag();var old=item.getListOrEmpty("Tags");for(int i=0;i<old.size();i++){String value=old.getStringOr(i,"");if(!value.startsWith("craftium:clipboard:"))tags.add(StringTag.valueOf(value));}tags.add(StringTag.valueOf(token));item.put("Tags",tags);var at=item.getListOrEmpty("Pos");double x=at.getDoubleOr(0,0),y=at.getDoubleOr(1,0),z=at.getDoubleOr(2,0);if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))throw new IllegalArgumentException("Invalid clipboard entity coordinates");var p=net.minecraft.core.BlockPos.containing(x,y,z);if(!player.level().isInWorldBounds(p)||!player.level().hasChunk(p.getX()>>4,p.getZ()>>4))throw new IllegalArgumentException("Entity placement reaches unloaded chunks or world bounds");});
            Entity entity=dev.craftium.compat.Compat.loadEntity(tag,player.level());if(entity==null||entity instanceof Player)throw new IllegalArgumentException("Unsupported clipboard entity");result.add(new Session.EntityChange(entity.getUUID(),null,EntityEdits.snapshot(entity,player.level()),true));
        }return List.copyOf(result);
    }
    static void transform(Session session,java.util.function.UnaryOperator<Geometry.Vector> position,double yaw,boolean mirror){
        for(var root:session.clipboardEntities)walk(root,tag->{var at=tag.getListOrEmpty("Pos");var p=position.apply(new Geometry.Vector(at.getDoubleOr(0,0),at.getDoubleOr(1,0),at.getDoubleOr(2,0)));position(tag,p.x(),p.y(),p.z());var rotation=tag.getListOrEmpty("Rotation");float angle=rotation.getFloatOr(0,0);var next=new ListTag();next.add(FloatTag.valueOf((float)((mirror?-angle:angle)+yaw)));next.add(FloatTag.valueOf(rotation.getFloatOr(1,0)));tag.put("Rotation",next);});
    }
}
