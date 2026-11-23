package com.fundamentum.event;

import com.fundamentum.config.ConfigEvent;
import com.fundamentum.port.FId;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
 * Fundamentum's own systems post the events that belong to them (bosses, armour sets, effects, ammo, shields, loot,
 * moons, survival meters, music, elements and the rest); see docs/EVENTS.md for who posts what. The ones no
 * Fundamentum system owns (spells, machines, quests, factions, rituals, taming...) are for other mods to post.
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
        reg("slot_equipped", SlotEquipped.class, false);
        reg("slot_unequipped", SlotUnequipped.class, false);
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
        reg("multiblock_formed", MultiblockFormed.class, true);
        reg("multiblock_unformed", MultiblockUnformed.class, false);
        reg("config_changed", ConfigEvent.Changed.class, false);
        reg("first_join", FirstJoin.class, false);
        reg("dimension_entered", DimensionEntered.class, false);
        reg("shield_blocked", ShieldBlocked.class, false);
        reg("effect_applied", EffectApplied.class, true);
        reg("effect_ended", EffectEnded.class, false);
        reg("loot_added", LootAdded.class, true);
        reg("boss_loot_dropped", BossLootDropped.class, false);
        reg("music_changed", MusicChanged.class, false);
        reg("moon_rose", MoonRose.class, false);
        reg("moon_set", MoonSet.class, false);
        reg("night_event_started", NightEventStarted.class, false);
        reg("night_event_ended", NightEventEnded.class, false);
        reg("meter_threshold", MeterThreshold.class, false);
        reg("element_reacted", ElementReacted.class, false);
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

    /** A boss started for the first time (not when it loads again with its chunk). */
    public record BossSpawned(LivingEntity boss, FId bossId) { }

    /** A boss changed phase. Phases count from 1; 0 is the base phase before the first one. */
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

    /**
     * An item went into an accessory slot ({@code com.fundamentum.slots.Slots}), from either backend, after the change.
     * {@code slot} is the slot type ({@code wings}, {@code mount}...), {@code cosmetic} true for a cosmetic twin.
     */
    public record SlotEquipped(LivingEntity entity, String slot, int index, boolean cosmetic, ItemStack stack) { }

    /** An item left an accessory slot (taken out or replaced), after the change. */
    public record SlotUnequipped(LivingEntity entity, String slot, int index, boolean cosmetic, ItemStack stack) { }

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

    /** A player gained an armour set tier (posted once per tier gained; {@code pieces} is how many they wear). */
    public record SetBonusOn(ServerPlayer player, FId set, int pieces) { }

    /** A player lost the last tier of an armour set. */
    public record SetBonusOff(ServerPlayer player, FId set) { }

    /** A custom status effect is about to tick ({@code duration}: ticks left, -1 for never). Cancel to skip this tick. */
    public record EffectTick(LivingEntity entity, FId effect, int amplifier, int duration) { }

    /** Ammo is about to be taken for a shot. Cancel to keep the ammo (the shot still goes off). */
    public record AmmoDrawn(LivingEntity shooter, ItemStack weapon, ItemStack ammo) { }

    /** A thrown weapon hit something. {@code thrower} may be null. Cancel to stop the hit's damage. */
    public record ThrownWeaponHit(LivingEntity thrower, Entity target, ItemStack weapon) { }

    /**
     * A player is about to start flying with wings. Cancel to keep the server side folded, so other players see the
     * wings folded. The client drives its own flight, so a cancelled take-off still lets
     * that client glide until it lands.
     */
    public record WingsTakeOff(ServerPlayer player, ItemStack wings) { }

    /** Items of a tag were turned into the one chosen item. */
    public record MaterialUnified(FId tag, FId from, FId to) { }

    /** A multiblock structure was found built around its controller. Cancel to keep it unformed. */
    public record MultiblockFormed(ServerLevel level, BlockPos controller, FId structure, Direction facing, boolean mirrored) { }

    /** A formed multiblock structure was broken (or its pattern went away). */
    public record MultiblockUnformed(ServerLevel level, BlockPos controller, FId structure) { }

    /** A player joined this world for the first time. */
    public record FirstJoin(ServerPlayer player) { }

    /** A player arrived in another dimension. */
    public record DimensionEntered(ServerPlayer player, FId from, FId to) { }

    /** A shield took a hit for its holder. {@code blocked} is the damage it stopped. */
    public record ShieldBlocked(LivingEntity defender, ItemStack shield, float blocked, boolean parried) { }

    /**
     * A custom status effect is about to be applied (after {@code EffectEvent.Apply}). {@code duration} is in ticks, -1
     * for one that never runs out. Cancel to refuse it.
     */
    public record EffectApplied(LivingEntity entity, FId effect, int amplifier, int duration) { }

    /** A custom status effect ended. {@code reason} is {@code expired}, {@code cured} or {@code removed}. */
    public record EffectEnded(LivingEntity entity, FId effect, String reason) { }

    /** A loot addition rolled {@code stacks} extra stacks into a loot table's drops. Cancel to leave them out. */
    public record LootAdded(FId table, FId addition, int stacks) { }

    /** A boss's drops were handed out. {@code player} is null when the drop is shared rather than per player. */
    public record BossLootDropped(ServerPlayer player, FId bossId, int stacks) { }

    /**
     * The track the server expects a player to hear changed, worked out when their music state changes (biome or
     * time changes alone are only seen by the client). {@code from} and {@code to} are sound ids, null for silence.
     */
    public record MusicChanged(ServerPlayer player, FId from, FId to) { }

    /** A special moon rose in a dimension. */
    public record MoonRose(ServerLevel level, FId moon) { }

    /** A special moon set (dawn, or it was cleared). */
    public record MoonSet(ServerLevel level, FId moon) { }

    /** A night event started in a dimension. */
    public record NightEventStarted(ServerLevel level, FId event) { }

    /** A night event ended. {@code outcome} is {@code dawn}, {@code time}, {@code cleared} or another listener's word. */
    public record NightEventEnded(ServerLevel level, FId event, String outcome) { }

    /**
     * A survival meter threshold started or stopped holding for a player ({@code threshold} is its index in the meter's
     * list). This is where a meter starts or stops hurting or applying its effect.
     */
    public record MeterThreshold(ServerPlayer player, FId meter, int threshold, boolean active) { }

    /** An element reaction went off on {@code target}. {@code attacker} may be null. */
    public record ElementReacted(LivingEntity target, FId reaction, Entity attacker) { }
}
