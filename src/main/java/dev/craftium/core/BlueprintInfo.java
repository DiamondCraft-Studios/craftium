package dev.craftium.core;
import java.util.List;
/**
 * What the blueprint browser lists for a file. {@code palette} (block ids) and {@code cells} (see {@link BlueprintVoxels}) are the blocks
 * themselves for the picture, and are null for an older listing or a blueprint too big for them; {@code thumbnail} is the flat fallback.
 */
public record BlueprintInfo(String file,String name,String author,String tags,int blocks,Point size,List<Integer> thumbnail,List<String> palette,String cells){
    /** The same listing for a file with the picture data left as it was (or absent). */
    public BlueprintInfo(String file,String name,String author,String tags,int blocks,Point size,List<Integer> thumbnail){this(file,name,author,tags,blocks,size,thumbnail,null,null);}
    /** The cells of this listing, or null when it has none that fit its size and palette. */
    public int[] voxels(){
        if(palette==null||cells==null||size==null||palette.size()>BlueprintVoxels.MAX_PALETTE)return null;
        int[] decoded=BlueprintVoxels.decode(cells,size.x(),size.y(),size.z());
        if(decoded==null)return null;
        for(int cell:decoded)if(cell>=palette.size())return null;
        return decoded;
    }
}
