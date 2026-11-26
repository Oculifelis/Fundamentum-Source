package com.fundamentum.dodge;

import com.fundamentum.port.FId;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;

/** The loader side of dodging: hits that miss a dodging player, no attacks during recovery, and the low pose. */
final class DodgeHooks {
    private DodgeHooks() { }

    static void init() {
        NeoForge.EVENT_BUS.addListener(DodgeHooks::incoming);
        NeoForge.EVENT_BUS.addListener(DodgeHooks::attack);
    }

    private static void incoming(LivingIncomingDamageEvent event) {
        DamageSource src = event.getSource();
        if (src.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return;
        if (Dodges.dodgeHit(event.getEntity(), src.getEntity(), typeId(src), event.getAmount())) event.setCanceled(true);
    }

    private static void attack(AttackEntityEvent event) {
        if (!event.getEntity().level().isClientSide() && Dodges.locked(event.getEntity())) event.setCanceled(true);
    }

    static FId typeId(DamageSource src) {
        return src.typeHolder().unwrapKey().map(k -> FId.from(k.identifier())).orElse(null);
    }

    /** Puts the player in a crawl-height pose while a low dodge moves, or back to normal. */
    static void setLow(Player p, boolean low) {
        p.setForcedPose(low ? Pose.SWIMMING : null);
    }
}
