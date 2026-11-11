package com.fundamentum.armorset;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

/**
 * A custom armour set reward, registered with {@link SetRewards#register} and named in a tier's {@code rewards} list by its
 * {@code type}. {@link #grant} runs on the server when the wearer reaches the tier, {@link #revoke} when they drop below it.
 */
public interface SetReward {
    void grant(LivingEntity entity, ArmorSetDefinition set, ArmorSetDefinition.Tier tier);

    void revoke(LivingEntity entity, ArmorSetDefinition set, ArmorSetDefinition.Tier tier);

    /** A tooltip line for the reward, or null for none. */
    default @Nullable Component describe() {
        return null;
    }
}
