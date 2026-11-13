package dev.craftium.core;

import java.util.*;

/** The edits the Materials window makes to {@link Options#materials} and {@link Options#materialWeights}, which are kept the same length and in the same order. */
public final class MaterialPalette {
    public static final double MIN_WEIGHT=.1,MAX_WEIGHT=100;
    private MaterialPalette(){}

    public static List<String> blocks(Options o){try{return MaterialList.split(o.materials);}catch(IllegalArgumentException bad){return List.of();}}
    public static double weight(Options o,int index){return Math.clamp(index<o.materialWeights.size()?o.materialWeights.get(index):1,MIN_WEIGHT,MAX_WEIGHT);}
    public static List<Double> weights(Options o,int count){var list=new ArrayList<Double>(count);for(int i=0;i<count;i++)list.add(weight(o,i));return list;}
    /** How often the block at {@code index} is used, 0 to 1, as its part of all the weights. */
    public static double share(Options o,int index){int n=blocks(o).size();double total=0;for(int i=0;i<n;i++)total+=weight(o,i);return total==0?0:weight(o,index)/total;}

    public static void set(Options o,List<String> blocks,List<Double> weights){
        if(blocks.isEmpty())throw new IllegalArgumentException("The palette needs at least one block");
        o.materials=String.join(",",blocks);o.materialWeights=new ArrayList<>(weights);
    }
    /** Adds a block at the end with weight 1; false when the palette is full. */
    public static boolean add(Options o,String block){
        var blocks=new ArrayList<>(blocks(o));if(blocks.size()>=MaterialList.MAX||block==null||block.isBlank()||block.contains(","))return false;
        var weights=weights(o,blocks.size());blocks.add(block.trim());weights.add(1d);set(o,blocks,weights);return true;
    }
    /** Removes a block, never the last one. */
    public static boolean remove(Options o,int index){
        var blocks=new ArrayList<>(blocks(o));if(index<0||index>=blocks.size()||blocks.size()<=1)return false;
        var weights=weights(o,blocks.size());blocks.remove(index);weights.remove(index);set(o,blocks,weights);return true;
    }
    public static boolean replace(Options o,int index,String block){
        var blocks=new ArrayList<>(blocks(o));if(index<0||index>=blocks.size()||block==null||block.isBlank()||block.contains(","))return false;
        var weights=weights(o,blocks.size());blocks.set(index,block.trim());set(o,blocks,weights);return true;
    }
    /** Moves a block {@code delta} places up (negative) or down; false at either end. */
    public static boolean move(Options o,int index,int delta){
        var blocks=new ArrayList<>(blocks(o));int to=index+delta;if(index<0||index>=blocks.size()||to<0||to>=blocks.size())return false;
        var weights=weights(o,blocks.size());Collections.swap(blocks,index,to);Collections.swap(weights,index,to);set(o,blocks,weights);return true;
    }
    public static void setWeight(Options o,int index,double weight){
        var blocks=blocks(o);if(index<0||index>=blocks.size()||!Double.isFinite(weight))return;
        var weights=weights(o,blocks.size());weights.set(index,Math.clamp(weight,MIN_WEIGHT,MAX_WEIGHT));o.materialWeights=weights;
    }
    /** Puts the blocks in the order given by {@code order}, a list of the old indices; weights follow their blocks. */
    public static void reorder(Options o,List<Integer> order){
        var blocks=blocks(o);if(order.size()!=blocks.size())throw new IllegalArgumentException("Order does not match the palette");
        var weights=weights(o,blocks.size());var nb=new ArrayList<String>();var nw=new ArrayList<Double>();
        for(int i:order){nb.add(blocks.get(i));nw.add(weights.get(i));}set(o,nb,nw);
    }
}
