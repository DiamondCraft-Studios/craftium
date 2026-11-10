package dev.craftium.core;
import java.util.List;
/**
 * What the blueprint browser lists for a file. {@code palette} (block ids) and {@code cells} (see {@link BlueprintVoxels}) are the blocks
 * themselves for the picture, each cell standing for a cube of {@code scale} blocks (0 or 1: one block); they are null for a listing
 * made before they existed. {@code thumbnail} is the flat fallback.
 */
public record BlueprintInfo(String file,String name,String author,String tags,int blocks,Point size,List<Integer> thumbnail,List<String> palette,String cells,int scale){
    /** A listing with no picture data. */
    public BlueprintInfo(String file,String name,String author,String tags,int blocks,Point size,List<Integer> thumbnail){this(file,name,author,tags,blocks,size,thumbnail,null,null,1);}
    /** The same listing with these blocks for the picture (or none, when {@code packed} is null). */
    public BlueprintInfo withVoxels(BlueprintVoxels.Packed packed){return new BlueprintInfo(file,name,author,tags,blocks,size,thumbnail,packed==null?null:packed.palette(),packed==null?null:packed.cells(),packed==null?1:packed.scale());}
    /** The same listing with another name, author and tags. */
    public BlueprintInfo withDetails(String name,String author,String tags){return new BlueprintInfo(file,name,author,tags,blocks,size,thumbnail,palette,cells,scale);}
    /** The same listing under another file name. */
    public BlueprintInfo at(String path){return new BlueprintInfo(path,name,author,tags,blocks,size,thumbnail,palette,cells,scale);}
    public boolean hasVoxels(){return palette!=null&&cells!=null;}
    /** The cells of this listing, or null when it has none that fit its size and palette. */
    public int[] voxels(){
        if(!hasVoxels()||size==null||palette.size()>BlueprintVoxels.MAX_PALETTE)return null;
        int sc=Math.max(1,scale);
        int[] decoded=BlueprintVoxels.decode(cells,BlueprintVoxels.cells(size.x(),sc),BlueprintVoxels.cells(size.y(),sc),BlueprintVoxels.cells(size.z(),sc));
        if(decoded==null)return null;
        for(int cell:decoded)if(cell>=palette.size())return null;
        return decoded;
    }
}
