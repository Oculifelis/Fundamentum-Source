package com.fundamentum.soulfire;

import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.sprite.SpriteId;

/** Vanilla's soul fire block sprites, in the same block atlas as the fire sprites they stand in for. Client only. */
public final class SoulFireSprites {
    public static final SpriteId SOUL_FIRE_0 = Sheets.BLOCKS_MAPPER.defaultNamespaceApply("soul_fire_0");
    public static final SpriteId SOUL_FIRE_1 = Sheets.BLOCKS_MAPPER.defaultNamespaceApply("soul_fire_1");

    /** fire_0 becomes soul_fire_0 and fire_1 soul_fire_1; anything else stays. */
    public static SpriteId swap(SpriteId sprite) {
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
