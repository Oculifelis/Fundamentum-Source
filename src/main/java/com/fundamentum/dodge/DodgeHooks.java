package com.fundamentum.dodge;

import com.fundamentum.port.FId;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;

/** The loader side of dodging: hits that miss a dodging player, no attacks during recovery, and the low pose. */
final class DodgeHooks {
    private DodgeHooks() { }

    /** Players held in the low pose; this version has no forced pose, so it is set again after each player tick. */
    private static final Set<UUID> LOW = ConcurrentHashMap.newKeySet();

    static void init() {
        MinecraftForge.EVENT_BUS.addListener(DodgeHooks::incoming);
        MinecraftForge.EVENT_BUS.addListener(DodgeHooks::attack);
        MinecraftForge.EVENT_BUS.addListener(DodgeHooks::playerTick);
    }

    private static void incoming(LivingAttackEvent event) {
        DamageSource src = event.getSource();
        if (src.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return;
        if (Dodges.dodgeHit(event.getEntity(), src.getEntity(), typeId(src), event.getAmount())) event.setCanceled(true);
    }

    private static void attack(AttackEntityEvent event) {
        if (!event.getEntity().level().isClientSide() && Dodges.locked(event.getEntity())) event.setCanceled(true);
    }

    private static void playerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && LOW.contains(event.player.getUUID())) event.player.setPose(Pose.SWIMMING);
    }

    static FId typeId(DamageSource src) {
        return src.typeHolder().unwrapKey().map(k -> FId.from(k.location())).orElse(null);
    }

    /** Puts the player in a crawl-height pose while a low dodge moves, or back to normal. */
    static void setLow(Player p, boolean low) {
        if (low) {
            LOW.add(p.getUUID());
            p.setPose(Pose.SWIMMING);
        } else if (LOW.remove(p.getUUID())) {
            p.setPose(Pose.STANDING);
        }
    }
}
