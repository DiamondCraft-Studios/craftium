package dev.craftium.block;

import dev.craftium.core.MissingBlockTable;
import dev.craftium.server.MissingBlockRewrite;
import dev.craftium.server.MissingBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** The number of the missing block this one stands for, plus whatever block entity data the real block had. */
public final class MissingBlockEntity extends BlockEntity {
    private int key=-1;
    private CompoundTag kept;
    /** What the block was, e.g. {@code othermod:marble[axis=y]}. Only a client holds this from the server, which resolves it from the table when it sends the block. */
    private String label="";

    public MissingBlockEntity(BlockPos pos,BlockState state){super(MissingBlocks.TYPE,pos,state);}

    public int key(){return key;}
    public CompoundTag kept(){return kept;}
    public String label(){return label;}
    public void set(int key,CompoundTag kept){this.key=key;this.kept=kept;setChanged();}

    @Override protected void saveAdditional(ValueOutput output){
        super.saveAdditional(output);
        output.putInt(MissingBlockRewrite.KEY,key);
        if(kept!=null)output.store(MissingBlockRewrite.KEPT,CompoundTag.CODEC,kept);
    }
    @Override protected void loadAdditional(ValueInput input){
        super.loadAdditional(input);
        key=input.getIntOr(MissingBlockRewrite.KEY,-1);
        kept=input.read(MissingBlockRewrite.KEPT,CompoundTag.CODEC).orElse(null);
        label=input.getStringOr("label","");
    }
    @Override public Packet<ClientGamePacketListener> getUpdatePacket(){return ClientboundBlockEntityDataPacket.create(this);}
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries){
        var tag=saveCustomOnly(registries);
        // The table is the server's; the client is told the name so it can show what is missing without a table of its own.
        MissingBlockTable table=MissingBlocks.table();
        var entry=table==null?null:table.get(key);
        if(entry!=null)tag.putString("label",entry.text());
        // What the real block held is not for the client to see or keep.
        tag.remove(MissingBlockRewrite.KEPT);
        return tag;
    }
}
