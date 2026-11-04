package dev.craftium.client;

import dev.craftium.Craftium;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.*;
import net.minecraft.world.item.ItemStack;
import java.nio.file.*;
import java.util.*;

/**
 * A store of hotbars kept on this computer for every world: ninety-nine pages, each of five hotbars of nine slots, and a name for each page.
 * Pages only change when their items are edited, and a hotbar is copied into the one in use (never swapped with it).
 * In the file, hotbars are numbered in order from 1, so page 1 holds 1 to 5, page 2 holds 6 to 10, and so on; a page's name is under "n" and its number.
 */
final class HotbarPages {
    static final int COUNT=99,ROWS=5,SLOTS=9,NAME_LIMIT=24;
    private static final Path FILE=FabricLoader.getInstance().getConfigDir().resolve("craftium").resolve("hotbar_pages.nbt");
    private static final Map<Integer,List<ItemStack>> HOTBARS=new HashMap<>();
    private static final Map<Integer,String> NAMES=new HashMap<>();
    private static boolean loaded,dirty;
    private HotbarPages(){}

    private static net.minecraft.resources.RegistryOps<Tag> ops(Minecraft mc){return mc.level.registryAccess().createSerializationContext(NbtOps.INSTANCE);}
    private static int index(int page,int row){return (page-1)*ROWS+row+1;}

    /** Reads the file once a world is open, as items need the world's registries to be read. */
    static void load(Minecraft mc){
        if(loaded||mc.level==null)return;loaded=true;
        if(!Files.exists(FILE))return;
        try{
            var ops=ops(mc);var root=NbtIo.readCompressed(FILE,NbtAccounter.create(64_000_000));
            for(String key:root.keySet()){
                if(key.startsWith("n")){try{int page=Integer.parseInt(key.substring(1));if(page>=1&&page<=COUNT)NAMES.put(page,root.getStringOr(key,""));}catch(NumberFormatException ignored){}continue;}
                int number;try{number=Integer.parseInt(key);}catch(NumberFormatException e){continue;}
                if(number<1||number>COUNT*ROWS)continue;
                var slots=root.getListOrEmpty(key);var stacks=new ArrayList<ItemStack>(SLOTS);
                for(int i=0;i<SLOTS;i++)stacks.add(i<slots.size()?ItemStack.OPTIONAL_CODEC.parse(ops,slots.get(i)).result().orElse(ItemStack.EMPTY):ItemStack.EMPTY);
                HOTBARS.put(number,stacks);
            }
        }catch(Exception e){Craftium.LOG.warn("Could not read saved hotbar pages",e);}
    }
    /** Writes the file if anything has changed since it was last written. */
    static void flush(Minecraft mc){if(dirty&&mc.level!=null)write(mc);}
    private static void write(Minecraft mc){
        try{
            var ops=ops(mc);var root=new CompoundTag();
            for(var entry:HOTBARS.entrySet()){
                if(entry.getValue().stream().allMatch(ItemStack::isEmpty))continue;
                var slots=new ListTag();for(ItemStack stack:entry.getValue())slots.add(ItemStack.OPTIONAL_CODEC.encodeStart(ops,stack).getOrThrow());
                root.put(Integer.toString(entry.getKey()),slots);
            }
            for(var entry:NAMES.entrySet())if(!entry.getValue().isBlank())root.putString("n"+entry.getKey(),entry.getValue());
            Files.createDirectories(FILE.getParent());Path temp=FILE.resolveSibling("hotbar_pages.nbt.tmp");
            NbtIo.writeCompressed(root,temp);Files.move(temp,FILE,StandardCopyOption.REPLACE_EXISTING);dirty=false;
        }catch(Exception e){Craftium.LOG.warn("Could not save hotbar pages",e);}
    }

    /** The nine items of one hotbar on a page (row 0 is the lowest); empty stacks where it has none. */
    static List<ItemStack> row(int page,int row){return HOTBARS.getOrDefault(index(page,row),List.of());}
    static boolean empty(int page,int row){return row(page,row).stream().allMatch(ItemStack::isEmpty);}
    static String name(int page){return NAMES.getOrDefault(page,"");}
    static void rename(int page,String name){NAMES.put(page,name);dirty=true;}

    /** One slot of a page's hotbar, changed and saved. */
    static void set(Minecraft mc,int page,int row,int slot,ItemStack stack){
        var stacks=new ArrayList<ItemStack>(SLOTS);var old=row(page,row);
        for(int i=0;i<SLOTS;i++)stacks.add(i==slot?stack.copy():i<old.size()?old.get(i):ItemStack.EMPTY);
        HOTBARS.put(index(page,row),stacks);dirty=true;write(mc);
    }
    /** One slot of the hotbar in use, set here and told to the server as creative players do. */
    static void setHotbar(Minecraft mc,int slot,ItemStack stack){
        mc.player.inventoryMenu.getSlot(36+slot).set(stack.copy()); // the inventory menu's hotbar slots follow the 36 before them
        mc.gameMode.handleCreativeModeItemAdd(stack.copy(),36+slot);
    }
    /** Copies one of a page's hotbars into the hotbar in use, as a creative player takes items from the menu. */
    static void apply(Minecraft mc,int page,int row){
        var stacks=row(page,row);
        for(int i=0;i<SLOTS;i++)setHotbar(mc,i,i<stacks.size()?stacks.get(i):ItemStack.EMPTY);
    }
}
