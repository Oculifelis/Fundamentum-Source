package com.fundamentum.poise;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** {@code fundamentum:staggered}: slower, weaker, slower swings, and a mob stops walking while it lasts. */
public final class StaggeredEffect extends MobEffect {
    public StaggeredEffect() {
        super(MobEffectCategory.HARMFUL, 0xC9B36B);
        addAttributeModifier(Attributes.MOVEMENT_SPEED, "6b1f6f5e-2c1a-4f0e-9a51-3d8c7a1e0b01", -0.6,
            AttributeModifier.Operation.MULTIPLY_TOTAL);
        addAttributeModifier(Attributes.ATTACK_DAMAGE, "6b1f6f5e-2c1a-4f0e-9a51-3d8c7a1e0b02", -0.3,
            AttributeModifier.Operation.MULTIPLY_TOTAL);
        addAttributeModifier(Attributes.ATTACK_SPEED, "6b1f6f5e-2c1a-4f0e-9a51-3d8c7a1e0b03", -0.5,
            AttributeModifier.Operation.MULTIPLY_TOTAL);
    }

    @Override
    public boolean isDurationEffectTick(int tickCount, int amplification) {
        return true;
    }

    @Override
    public void applyEffectTick(LivingEntity entity, int amplification) {
        if (entity instanceof Mob mob) mob.getNavigation().stop();
    }
}
