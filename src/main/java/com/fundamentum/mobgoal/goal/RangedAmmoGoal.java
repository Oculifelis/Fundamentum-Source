package com.fundamentum.mobgoal.goal;

import com.fundamentum.mobgoal.GoalParams;
import com.fundamentum.mobgoal.MobGoals;
import com.fundamentum.ammo.Ammo;
import com.fundamentum.port.FId;
import com.fundamentum.port.FTags;
import com.fundamentum.port.FVanilla;
import java.util.EnumSet;
import java.util.function.Predicate;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * {@code fundamentum:ranged_ammo}: shoots the target while the mob carries ammo (offhand, mainhand or a quiver slot),
 * using it up through {@link Ammo}. The shot itself comes from a {@link com.fundamentum.mobgoal.MobShooter}, or the mob's
 * own ranged attack. With no ammo left the goal stops and lower-priority goals (melee, fleeing) take over.
 * Params: ammo ("#minecraft:arrows", an item tag or item id), interval (40 ticks), range (15), power (1.0),
 * speed (1.0, used to close in when out of range).
 */
public final class RangedAmmoGoal extends Goal {
    private final Mob mob;
    private final Predicate<ItemStack> ammo;
    private final int interval;
    private final double range;
    private final float power;
    private final double speed;
    private int cooldown;
    private int shots;

    public RangedAmmoGoal(Mob mob, GoalParams p) {
        this.mob = mob;
        this.ammo = predicate(p.s("ammo", "#minecraft:arrows"));
        this.interval = Math.max(1, p.i("interval", 40));
        this.range = p.d("range", 15);
        this.power = p.f("power", 1.0);
        this.speed = p.d("speed", 1.0);
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    static Predicate<ItemStack> predicate(String s) {
        if (s.startsWith("#")) {
            var tag = FTags.item(FId.of(s.substring(1)));
            return st -> st.is(tag);
        }
        Item item = FVanilla.item(FId.of(s));
        return st -> st.is(item);
    }

    /** True when the mob carries ammo this goal accepts. */
    public boolean hasAmmo() {
        return Ammo.find(mob, mob.getMainHandItem(), ammo) != null;
    }

    @Override
    public boolean canUse() {
        LivingEntity t = GoalUtil.target(mob);
        return t != null && mob.distanceToSqr(t) <= range * range * 2.25 && hasAmmo();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        cooldown = interval / 2;
    }

    @Override
    public void tick() {
        LivingEntity t = GoalUtil.target(mob);
        if (t == null) return;
        mob.getLookControl().setLookAt(t, 30, 30);
        double d = mob.distanceToSqr(t);
        boolean sees = mob.getSensing().hasLineOfSight(t);
        if (d > range * range || !sees) mob.getNavigation().moveTo(t, speed);
        else mob.getNavigation().stop();
        if (--cooldown > 0 || !sees || d > range * range) return;
        if (shoot(t)) cooldown = interval;
    }

    /** Pays for and fires one shot at {@code target} now; false when there is no ammo or nothing could fire it. */
    public boolean shoot(LivingEntity target) {
        ItemStack weapon = mob.getMainHandItem();
        Ammo.Found f = Ammo.find(mob, weapon, ammo);
        if (f == null) return false;
        ItemStack shot = f.stack().copyWithCount(1);
        if (!MobGoals.fire(mob, target, shot, power)) return false;
        Ammo.consume(mob, weapon, f, 1);
        shots++;
        return true;
    }

    /** Shots fired by this goal. */
    public int shots() {
        return shots;
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
    }
}
