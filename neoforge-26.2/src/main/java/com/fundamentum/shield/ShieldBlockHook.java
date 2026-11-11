package com.fundamentum.shield;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent;

/**
 * Fires {@link ShieldBlockEvent} around the game's shield block: Pre before Fundamentum's parry, Post after everyone. An
 * amount set in Pre is put back after Fundamentum's own handling (at LOW), so it holds in the parry window too.
 */
final class ShieldBlockHook {
    private static final Map<LivingShieldBlockEvent, ShieldBlockEvent.Pre> OPEN = Collections.synchronizedMap(new IdentityHashMap<>());

    private static void pre(LivingShieldBlockEvent event) {
        LivingEntity user = event.getEntity();
        if (!event.getBlocked() || !(user.level() instanceof ServerLevel)) {
            return;
        }
        ItemStack shield = user.getItemBlockingWith();
        ShieldStats stats = shield == null ? null : Shields.stats(shield);
        boolean inWindow = stats != null && Shields.parryTiming(user, stats);
        ShieldBlockEvent.Pre pre = new ShieldBlockEvent.Pre(user, event.getDamageSource(), shield == null ? ItemStack.EMPTY : shield,
            event.getBlockedDamage(), inWindow);
        NeoForge.EVENT_BUS.post(pre);
        if (pre.isCanceled()) {
            event.setBlocked(false);
        } else if (pre.getBlockedAmount() != pre.getOriginalBlockedAmount()) {
            event.setBlockedDamage(pre.getBlockedAmount());
        }
        OPEN.put(event, pre);
    }

    private static void reapply(LivingShieldBlockEvent event) {
        ShieldBlockEvent.Pre pre = OPEN.get(event);
        if (pre != null && !pre.isCanceled() && event.getBlocked() && pre.getBlockedAmount() != pre.getOriginalBlockedAmount()) {
            event.setBlockedDamage(pre.getBlockedAmount());
        }
    }

    private static void post(LivingShieldBlockEvent event) {
        ShieldBlockEvent.Pre pre = OPEN.remove(event);
        if (pre == null) {
            return;
        }
        NeoForge.EVENT_BUS.post(new ShieldBlockEvent.Post(pre.getDefender(), pre.getSource(), pre.getShield(), event.getBlocked(),
            event.getBlockedDamage(), pre.isParried(), pre.data()));
    }

    static void init() {
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, ShieldBlockHook::pre);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, ShieldBlockHook::reapply);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ShieldBlockHook::post);
    }

    private ShieldBlockHook() {}
}
