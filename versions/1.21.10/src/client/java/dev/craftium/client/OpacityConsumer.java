package dev.craftium.client;

import com.mojang.blaze3d.vertex.VertexConsumer;

/** Forwards vertices unchanged except that colour alpha is multiplied by a fixed factor. */
public final class OpacityConsumer implements VertexConsumer {
    private final VertexConsumer delegate;
    private final double opacity;

    public OpacityConsumer(VertexConsumer delegate,double opacity){this.delegate=delegate;this.opacity=opacity;}

    @Override public VertexConsumer addVertex(float x,float y,float z){delegate.addVertex(x,y,z);return this;}
    @Override public VertexConsumer setColor(int red,int green,int blue,int alpha){delegate.setColor(red,green,blue,(int)Math.round(alpha*opacity));return this;}
    @Override public VertexConsumer setUv(float u,float v){delegate.setUv(u,v);return this;}
    @Override public VertexConsumer setUv1(int u,int v){delegate.setUv1(u,v);return this;}
    @Override public VertexConsumer setUv2(int u,int v){delegate.setUv2(u,v);return this;}
    @Override public VertexConsumer setNormal(float x,float y,float z){delegate.setNormal(x,y,z);return this;}
}
