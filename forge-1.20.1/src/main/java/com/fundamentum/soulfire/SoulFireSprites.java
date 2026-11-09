package com.fundamentum.soulfire;

import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.resources.ResourceLocation;

/** Vanilla's soul fire block sprites, in the same block atlas as the fire sprites they stand in for. Client only. */
public final class SoulFireSprites {
    public static final Material SOUL_FIRE_0 = new Material(TextureAtlas.LOCATION_BLOCKS, new ResourceLocation("block/soul_fire_0"));
    public static final Material SOUL_FIRE_1 = new Material(TextureAtlas.LOCATION_BLOCKS, new ResourceLocation("block/soul_fire_1"));

    /** fire_0 becomes soul_fire_0 and fire_1 soul_fire_1; anything else stays. */
    public static Material swap(Material sprite) {
        if (sprite.equals(ModelBakery.FIRE_0)) {
            return SOUL_FIRE_0;
        }
        if (sprite.equals(ModelBakery.FIRE_1)) {
            return SOUL_FIRE_1;
        }
        return sprite;
    }

    private SoulFireSprites() {}
}
