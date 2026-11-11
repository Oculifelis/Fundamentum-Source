package com.fundamentum.event;

import com.fundamentum.config.ConfigEvent;
import com.fundamentum.port.FId;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * The first set of ecosystem events. Each record's id is {@code fundamentum:<snake_case name>} and never changes; its
 * fields are what triggers, data conditions and scripts can match on. Fields that may be empty are noted; a field
 * holding a player makes the event count for that player's {@code fundamentum:event} advancement trigger.
 * <p>
 * Fundamentum fires {@link FirstJoin}, {@link DimensionEntered} and {@code config_changed} itself. The rest are posted
 * by the systems and mods they belong to (Fundamentum's own older systems take them up as they move to 2.0).
 */
public final class Events {
    private Events() { }

    private static volatile boolean done;

    /** Registers the types below. Runs by itself the first time any event is used. */
    public static synchronized void bootstrap() {
        if (done) return;
        done = true;
        reg("spell_cast", SpellCast.class, true);
        reg("mana_spent", ManaSpent.class, true);
        reg("machine_processed", MachineProcessed.class, false);
        reg("boss_spawned", BossSpawned.class, false);
        reg("boss_phase_changed", BossPhaseChanged.class, false);
        reg("boss_killed", BossKilled.class, false);
        reg("entity_tamed", EntityTamed.class, true);
        reg("summon_created", SummonCreated.class, true);
        reg("structure_generated", StructureGenerated.class, false);
        reg("structure_entered", StructureEntered.class, false);
        reg("ritual_started", RitualStarted.class, true);
        reg("ritual_finished", RitualFinished.class, false);
        reg("relic_placed", RelicPlaced.class, true);
        reg("relic_broken", RelicBroken.class, false);
        reg("rep_changed", RepChanged.class, true);
        reg("rank_changed", RankChanged.class, false);
        reg("quest_accepted", QuestAccepted.class, true);
        reg("quest_completed", QuestCompleted.class, false);
        reg("devotion_changed", DevotionChanged.class, true);
        reg("unlock_granted", UnlockGranted.class, true);
        reg("stat_changed", StatChanged.class, false);
        reg("set_bonus_on", SetBonusOn.class, false);
        reg("set_bonus_off", SetBonusOff.class, false);
        reg("effect_tick", EffectTick.class, true);
        reg("ammo_drawn", AmmoDrawn.class, true);
        reg("thrown_weapon_hit", ThrownWeaponHit.class, true);
        reg("wings_take_off", WingsTakeOff.class, true);
        reg("material_unified", MaterialUnified.class, false);
        reg("config_changed", ConfigEvent.Changed.class, false);
        reg("first_join", FirstJoin.class, false);
        reg("dimension_entered", DimensionEntered.class, false);
    }

    private static <E extends Record> void reg(String name, Class<E> type, boolean cancellable) {
        FEvents.register(FId.of("fundamentum", name), type, cancellable);
    }

    /** A spell is about to be cast. Cancel to stop it (no mana is spent). */
    public record SpellCast(LivingEntity caster, FId spell, int manaCost) { }

    /** Mana is about to be spent. Cancel to keep it. */
    public record ManaSpent(LivingEntity entity, int amount, int left) { }

    /** A machine finished one operation ({@code recipe} may be null). */
    public record MachineProcessed(ServerLevel level, BlockPos pos, FId machine, FId recipe) { }

    public record BossSpawned(LivingEntity boss, FId bossId) { }

    public record BossPhaseChanged(LivingEntity boss, FId bossId, int from, int to) { }

    /** {@code killer} may be null. */
    public record BossKilled(LivingEntity boss, FId bossId, ServerPlayer killer) { }

    /** Cancel to refuse the taming. */
    public record EntityTamed(LivingEntity entity, ServerPlayer owner) { }

    /** A summoned creature is about to join the world. Cancel to stop it. */
    public record SummonCreated(LivingEntity summon, LivingEntity owner) { }

    public record StructureGenerated(ServerLevel level, FId structure, BlockPos origin) { }

    public record StructureEntered(ServerPlayer player, FId structure, BlockPos origin) { }

    /** Cancel to stop the ritual from starting. */
    public record RitualStarted(ServerPlayer player, FId ritual, BlockPos pos) { }

    public record RitualFinished(ServerPlayer player, FId ritual, BlockPos pos, boolean success) { }

    /** Cancel to stop the placing. */
    public record RelicPlaced(ServerPlayer player, FId relic, BlockPos pos) { }

    /** {@code player} may be null (broken by an explosion, say). */
    public record RelicBroken(ServerPlayer player, FId relic, BlockPos pos) { }

    /** Reputation with a faction is about to change. Cancel to keep the old value. */
    public record RepChanged(ServerPlayer player, FId faction, int from, int to) { }

    public record RankChanged(ServerPlayer player, FId faction, String from, String to) { }

    /** Cancel to refuse the quest. */
    public record QuestAccepted(ServerPlayer player, FId quest) { }

    public record QuestCompleted(ServerPlayer player, FId quest) { }

    /** Devotion to a deity is about to change. Cancel to keep the old value. */
    public record DevotionChanged(ServerPlayer player, FId deity, int from, int to) { }

    /** Cancel to withhold the unlock. */
    public record UnlockGranted(ServerPlayer player, FId unlock) { }

    public record StatChanged(LivingEntity entity, FId stat, double from, double to) { }

    public record SetBonusOn(ServerPlayer player, FId set, int pieces) { }

    public record SetBonusOff(ServerPlayer player, FId set) { }

    /** A custom status effect is about to tick. Cancel to skip this tick. */
    public record EffectTick(LivingEntity entity, FId effect, int amplifier, int duration) { }

    /** Ammo is about to be taken for a shot. Cancel to stop the shot. */
    public record AmmoDrawn(LivingEntity shooter, ItemStack weapon, ItemStack ammo) { }

    /** A thrown weapon hit something. {@code thrower} may be null. Cancel to stop the hit's damage. */
    public record ThrownWeaponHit(LivingEntity thrower, Entity target, ItemStack weapon) { }

    /** A player is about to start gliding with wings. Cancel to stay on the ground. */
    public record WingsTakeOff(ServerPlayer player, ItemStack wings) { }

    /** Items of a tag were turned into the one chosen item. */
    public record MaterialUnified(FId tag, FId from, FId to) { }

    /** A player joined this world for the first time. */
    public record FirstJoin(ServerPlayer player) { }

    /** A player arrived in another dimension. */
    public record DimensionEntered(ServerPlayer player, FId from, FId to) { }
}
