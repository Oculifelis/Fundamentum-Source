package com.fundamentum.poise;

import com.fundamentum.port.FId;
import net.minecraft.server.level.ServerLevel;
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
        addAttributeModifier(Attributes.MOVEMENT_SPEED, FId.of("fundamentum", "staggered_speed").vanilla(), -0.6,
            AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        addAttributeModifier(Attributes.ATTACK_DAMAGE, FId.of("fundamentum", "staggered_damage").vanilla(), -0.3,
            AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        addAttributeModifier(Attributes.ATTACK_SPEED, FId.of("fundamentum", "staggered_swing").vanilla(), -0.5,
            AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int tickCount, int amplification) {
        return true;
    }

    @Override
    public boolean applyEffectTick(ServerLevel level, LivingEntity entity, int amplification) {
        if (entity instanceof Mob mob) mob.getNavigation().stop();
        return true;
    }
}
