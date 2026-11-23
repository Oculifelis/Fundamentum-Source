package com.fundamentum.spear;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A thrown spear (see {@link Spears#throwSpear}): flies like a trident, hits once for its base damage with the spear as
 * the weapon (so the spear's enchantments and damage class apply), then drops to be picked up again. It is drawn as the
 * spear item turned along its flight.
 */
public class ThrownSpear extends AbstractArrow implements ItemSupplier {
    private static final EntityDataAccessor<ItemStack> ITEM = SynchedEntityData.defineId(ThrownSpear.class, EntityDataSerializers.ITEM_STACK);
    private boolean dealtDamage;
    private double spearDamage = 8.0;

    public ThrownSpear(EntityType<? extends ThrownSpear> type, Level level) {
        super(type, level);
    }

    public ThrownSpear(Level level, LivingEntity owner, ItemStack spear) {
        super(Spears.THROWN_SPEAR.get(), owner, level, spear, spear);
        this.entityData.set(ITEM, spear.copyWithCount(1));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(ITEM, ItemStack.EMPTY);
    }

    @Override
    public ItemStack getItem() {
        ItemStack item = this.entityData.get(ITEM);
        return item.isEmpty() ? new ItemStack(Items.IRON_SPEAR) : item;
    }

    @Override
    public ItemStack getWeaponItem() {
        return this.getPickupItemStackOrigin();
    }

    @Override
    protected ItemStack getDefaultPickupItem() {
        return new ItemStack(Items.IRON_SPEAR);
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
        if (this.level() instanceof ServerLevel level) {
            damage = EnchantmentHelper.modifyDamage(level, this.getWeaponItem(), target, source, damage);
        }
        this.dealtDamage = true;
        if (target.hurtOrSimulate(source, damage)) {
            if (this.level() instanceof ServerLevel level) {
                EnchantmentHelper.doPostAttackEffectsWithItemSourceOnBreak(level, target, source, this.getWeaponItem(), weapon -> this.kill(level));
            }
            if (target instanceof LivingEntity living) {
                this.doKnockback(living, source);
                this.doPostHurtEffects(living);
            }
        }
        this.deflect(ProjectileDeflection.REVERSE, target, this.owner, false);
        this.setDeltaMovement(this.getDeltaMovement().multiply(0.02, 0.2, 0.02));
        this.playSound(SoundEvents.TRIDENT_HIT, 1.0F, 1.0F);
    }

    @Override
    protected SoundEvent getDefaultHitGroundSoundEvent() {
        return SoundEvents.TRIDENT_HIT_GROUND;
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        this.entityData.set(ITEM, this.getPickupItemStackOrigin().copyWithCount(1));
        this.spearDamage = input.getDoubleOr("spear_damage", 8.0);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putDouble("spear_damage", this.spearDamage);
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
