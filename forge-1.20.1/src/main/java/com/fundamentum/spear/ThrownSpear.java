package com.fundamentum.spear;

import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * A thrown spear (see {@link Spears#throwSpear}): flies like a trident, hits once for its base damage with the spear as
 * the weapon (so the spear's enchantments and damage class apply), then drops to be picked up again. It is drawn as the
 * spear item turned along its flight. 1.20.1 arrows carry no weapon stack, so the spear keeps its own (saved as
 * "spear") and runs the enchantment effects from it.
 */
public class ThrownSpear extends AbstractArrow implements ItemSupplier {
    private static final EntityDataAccessor<ItemStack> ITEM = SynchedEntityData.defineId(ThrownSpear.class, EntityDataSerializers.ITEM_STACK);
    private ItemStack spear = new ItemStack(Items.TRIDENT);
    private boolean dealtDamage;
    private double spearDamage = 8.0;

    public ThrownSpear(EntityType<? extends ThrownSpear> type, Level level) {
        super(type, level);
    }

    public ThrownSpear(Level level, LivingEntity owner, ItemStack spear) {
        super(Spears.THROWN_SPEAR.get(), owner, level);
        this.spear = spear.copy();
        this.entityData.set(ITEM, spear.copyWithCount(1));
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(ITEM, ItemStack.EMPTY);
    }

    @Override
    public ItemStack getItem() {
        ItemStack item = this.entityData.get(ITEM);
        return item.isEmpty() ? new ItemStack(Items.TRIDENT) : item;
    }

    @Override
    protected ItemStack getPickupItem() {
        return this.spear.copy();
    }

    @Override
    protected @Nullable EntityHitResult findHitEntity(Vec3 from, Vec3 to) {
        return this.dealtDamage ? null : super.findHitEntity(from, to);
    }

    @Override
    protected void onHitEntity(EntityHitResult hit) {
        Entity target = hit.getEntity();
        Entity owner = this.getOwner();
        DamageSource source = this.damageSources().trident(this, owner == null ? this : owner);
        float damage = (float) this.spearDamage;
        if (target instanceof LivingEntity living) {
            damage += EnchantmentHelper.getDamageBonus(this.spear, living.getMobType());
        }
        this.dealtDamage = true;
        if (target.hurt(source, damage)) {
            if (target.getType() == EntityType.ENDERMAN) {
                return;
            }
            LivingEntity attacker = owner instanceof LivingEntity l ? l : null;
            if (target instanceof LivingEntity living) {
                int knockback = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.KNOCKBACK, this.spear);
                if (knockback > 0) {
                    Vec3 push = this.getDeltaMovement().multiply(1.0, 0.0, 1.0).normalize().scale(knockback * 0.6);
                    if (push.lengthSqr() > 0.0) {
                        living.push(push.x, 0.1, push.z);
                    }
                }
                if (attacker != null) {
                    EnchantmentHelper.doPostHurtEffects(living, attacker);
                }
                this.doPostHurtEffects(living);
            }
            if (attacker != null) {
                for (Map.Entry<Enchantment, Integer> e : EnchantmentHelper.getEnchantments(this.spear).entrySet()) {
                    e.getKey().doPostAttack(attacker, target, e.getValue());
                }
            }
            int fire = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.FIRE_ASPECT, this.spear);
            if (fire > 0) {
                target.setSecondsOnFire(4 * fire);
            }
        }
        // The 26 builds turn it back (deflect REVERSE, x -0.5) and then slow it (0.02, 0.2, 0.02); this is the same.
        this.setDeltaMovement(this.getDeltaMovement().multiply(-0.01, -0.1, -0.01));
        this.playSound(SoundEvents.TRIDENT_HIT, 1.0F, 1.0F);
    }

    @Override
    protected SoundEvent getDefaultHitGroundSoundEvent() {
        return SoundEvents.TRIDENT_HIT_GROUND;
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("spear", 10)) {
            this.spear = ItemStack.of(tag.getCompound("spear"));
        }
        this.entityData.set(ITEM, this.spear.copyWithCount(1));
        this.dealtDamage = tag.getBoolean("dealt_damage");
        this.spearDamage = tag.contains("spear_damage") ? tag.getDouble("spear_damage") : 8.0;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.put("spear", this.spear.save(new CompoundTag()));
        tag.putBoolean("dealt_damage", this.dealtDamage);
        tag.putDouble("spear_damage", this.spearDamage);
    }

    /** The damage this spear deals on a hit, before enchantments. */
    public double spearDamage() {
        return this.spearDamage;
    }

    public void setSpearDamage(double damage) {
        this.spearDamage = damage;
    }

    @Override
    public void tickDespawn() {
        if (this.pickup != AbstractArrow.Pickup.ALLOWED) {
            super.tickDespawn();
        }
    }
}
