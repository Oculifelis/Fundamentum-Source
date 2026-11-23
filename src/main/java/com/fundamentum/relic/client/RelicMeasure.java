package com.fundamentum.relic.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

/**
 * Measures a likeness for its relic ({@link RelicRenderer}): every buffer it hands out only notes where the vertices land
 * and draws nothing, so the relic fits the model as it is drawn rather than its hitbox.
 */
public final class RelicMeasure implements MultiBufferSource, VertexConsumer {
    /** min x, y, z, max x, y, z; min > max while nothing was seen. */
    final float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};

    boolean empty() {
        return b[0] > b[3];
    }

    /** Takes in a box (blocks) as if it had been drawn. */
    void box(float x0, float y0, float z0, float x1, float y1, float z1) {
        this.grow(x0, y0, z0);
        this.grow(x1, y1, z1);
    }

    private void grow(float x, float y, float z) {
        b[0] = Math.min(b[0], x);
        b[1] = Math.min(b[1], y);
        b[2] = Math.min(b[2], z);
        b[3] = Math.max(b[3], x);
        b[4] = Math.max(b[4], y);
        b[5] = Math.max(b[5], z);
    }

    @Override
    public VertexConsumer getBuffer(RenderType type) {
        return this;
    }

    @Override
    public VertexConsumer vertex(double x, double y, double z) {
        this.grow((float) x, (float) y, (float) z);
        return this;
    }

    @Override
    public VertexConsumer color(int r, int g, int b, int a) {
        return this;
    }

    @Override
    public VertexConsumer uv(float u, float v) {
        return this;
    }

    @Override
    public VertexConsumer overlayCoords(int u, int v) {
        return this;
    }

    @Override
    public VertexConsumer uv2(int u, int v) {
        return this;
    }

    @Override
    public VertexConsumer normal(float x, float y, float z) {
        return this;
    }

    @Override
    public void endVertex() {
    }

    @Override
    public void defaultColor(int r, int g, int b, int a) {
    }

    @Override
    public void unsetDefaultColor() {
    }
}
