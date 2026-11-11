package com.fundamentum.shield;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;

/**
 * Fires {@link ShieldBlockEvent} around Forge's shield block event: Pre before Fundamentum's own block maths, Post after
 * everyone. On Forge a canceled block event means the hit is not blocked, and Fundamentum works the amount out from the
 * original damage, so an amount set in Pre is put back after that (at LOW).
 */
final class ShieldBlockHook {
    private static final Map<net.minecraftforge.event.entity.living.ShieldBlockEvent, ShieldBlockEvent.Pre> OPEN =
        Collections.synchronizedMap(new IdentityHashMap<>());

    private static void pre(net.minecraftforge.event.entity.living.ShieldBlockEvent event) {
        LivingEntity user = event.getEntity();
        if (event.isCanceled() || !(user.level() instanceof ServerLevel)) {
            return;
        }
        ItemStack shield = Shields.blockingStack(user);
        ShieldStats stats = shield == null ? null : Shields.stats(shield);
        boolean inWindow = stats != null && Shields.parryTiming(user, stats);
        ShieldBlockEvent.Pre pre = new ShieldBlockEvent.Pre(user, event.getDamageSource(), shield == null ? ItemStack.EMPTY : shield,
            event.getBlockedDamage(), inWindow);
        MinecraftForge.EVENT_BUS.post(pre);
        if (pre.isCanceled()) {
            event.setCanceled(true);
        } else if (pre.getBlockedAmount() != pre.getOriginalBlockedAmount()) {
            event.setBlockedDamage(pre.getBlockedAmount());
        }
        OPEN.put(event, pre);
    }

    private static void reapply(net.minecraftforge.event.entity.living.ShieldBlockEvent event) {
        ShieldBlockEvent.Pre pre = OPEN.get(event);
        if (pre != null && !pre.isCanceled() && !event.isCanceled() && pre.getBlockedAmount() != pre.getOriginalBlockedAmount()) {
            event.setBlockedDamage(pre.getBlockedAmount());
        }
    }

    private static void post(net.minecraftforge.event.entity.living.ShieldBlockEvent event) {
        ShieldBlockEvent.Pre pre = OPEN.remove(event);
        if (pre == null) {
            return;
        }
        boolean blocked = !event.isCanceled();
        MinecraftForge.EVENT_BUS.post(new ShieldBlockEvent.Post(pre.getDefender(), pre.getSource(), pre.getShield(), blocked,
            blocked ? event.getBlockedDamage() : 0, pre.isParried(), pre.data()));
    }

    static void init() {
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGH, ShieldBlockHook::pre);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOW, true, ShieldBlockHook::reapply);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, ShieldBlockHook::post);
    }

    private ShieldBlockHook() {}
}
