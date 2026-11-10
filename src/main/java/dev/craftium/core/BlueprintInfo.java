package dev.craftium.core;
import java.util.List;
public record BlueprintInfo(String file,String name,String author,String tags,int blocks,Point size,List<Integer> thumbnail){}
