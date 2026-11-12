package com.fundamentum.boss;

import com.fundamentum.event.Events;
import com.fundamentum.event.FEvents;
import com.fundamentum.port.FId;
import com.fundamentum.music.BossMusic;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import org.jspecify.annotations.Nullable;

/**
 * One live boss: its entity, spec, bar, phase, arena centre and fighters. {@link Bosses} creates and ticks these; mods
 * read them with {@link Bosses#get}. All of it runs on the server thread.
 */
public final class Boss {
    static final Identifier PARTY_MODIFIER = Identifier.fromNamespaceAndPath("fundamentum", "boss_party_health");
    private static final double LOCK_MARGIN = 24;

    private final LivingEntity entity;
    private final BossSpec spec;
    private final ServerBossEvent bar;
    private final Vec3 home;
    private int phase = -1;
    private int emptyTicks;
    private int party = 1;
    private boolean engaged;
    private boolean ended;
    private final Set<UUID> fighters = new LinkedHashSet<>();

    Boss(LivingEntity entity, BossSpec spec, Vec3 home) {
        this.entity = entity;
        this.spec = spec;
        this.home = spec.arena != null && spec.arena.center() != null ? spec.arena.center() : home;
        this.bar = new ServerBossEvent(entity.getUUID(), spec.name, spec.color, spec.overlay);
        this.bar.setDarkenScreen(spec.darken);
        this.bar.setCreateWorldFog(spec.fog);
        this.bar.setPlayBossMusic(spec.bossMusicFlag);
        this.phase = findPhase();
        applyBar();
    }

    public LivingEntity entity() {
        return this.entity;
    }

    public BossSpec spec() {
        return this.spec;
    }

    public ServerBossEvent bar() {
        return this.bar;
    }

    /** The arena centre (the spec's fixed centre, or where the boss first appeared). */
    public Vec3 home() {
        return this.home;
    }

    /** The current phase's id, "base" when no phase holds. */
    public String phase() {
        return this.phase < 0 ? "base" : this.spec.phases.get(this.phase).id;
    }

    /** True while players are fighting it (inside the arena, or in range without an arena). */
    public boolean engaged() {
        return this.engaged;
    }

    /** The players who fought it since its last reset. */
    public Set<UUID> fighters() {
        return Set.copyOf(this.fighters);
    }

    /** Whether this point is inside the boss's arena (false without an arena). */
    public boolean inArena(Vec3 pos) {
        return this.spec.arena != null && this.spec.arena.contains(this.home, pos);
    }

    /** The track the boss plays now (phase music, else the base music), or null. */
    public @Nullable Identifier music() {
        for (int i = this.phase; i >= 0; i--) {
            BossPhase p = this.spec.phases.get(i);
            if (p.stopMusic) return null;
            if (p.music != null) return p.music;
        }
        return this.spec.music;
    }

    void addFighter(ServerPlayer player) {
        this.fighters.add(player.getUUID());
    }

    /**
     * One server tick: bar viewers, music, arena rules, reset, party health, phase and bar progress. {@code candidates}
     * are the players to consider (normally every player in the boss's level).
     */
    public void tick(Collection<? extends ServerPlayer> candidates) {
        if (this.ended) return;
        ArenaSpec arena = this.spec.arena;
        List<ServerPlayer> inside = new ArrayList<>();
        List<ServerPlayer> viewers = new ArrayList<>();
        for (ServerPlayer p : candidates) {
            if (p.isSpectator() || p.isRemoved() || p.level() != this.entity.level()) continue;
            boolean in = arena != null && arena.contains(this.home, p.position());
            if (in) inside.add(p);
            if (this.spec.visibility == BossSpec.Visibility.ARENA ? in : p.distanceToSqr(this.entity) <= this.spec.range * this.spec.range) {
                viewers.add(p);
            }
        }
        updateViewers(viewers);
        List<ServerPlayer> fighting = arena != null ? inside : viewers;
        this.engaged = !fighting.isEmpty();
        fighting.forEach(this::addFighter);

        if (arena != null) {
            if (arena.locksIn() && !this.fighters.isEmpty()) {
                for (ServerPlayer p : candidates) {
                    if (!this.fighters.contains(p.getUUID()) || inside.contains(p) || p.level() != this.entity.level() || p.isSpectator()) continue;
                    if (p.position().distanceTo(this.home) <= Math.max(arena.rx(), arena.rz()) + LOCK_MARGIN) {
                        Vec3 back = arena.clamp(this.home, p.position());
                        p.teleportTo(back.x, back.y, back.z);
                    }
                }
            }
            if (arena.leashes() && !arena.contains(this.home, this.entity.position())) {
                Vec3 back = arena.clamp(this.home, this.entity.position());
                this.entity.teleportTo(back.x, back.y, back.z);
                if (this.entity instanceof Mob mob) mob.getNavigation().stop();
            }
            if (arena.resetWhenEmpty() > 0 && !this.fighters.isEmpty() && inside.isEmpty()) {
                if (++this.emptyTicks >= arena.resetWhenEmpty()) reset();
            } else {
                this.emptyTicks = 0;
            }
            if (arena.healthPerExtraPlayer() > 0 && this.engaged && inside.size() > this.party) {
                setParty(inside.size());
            }
        }
        updatePhase();
        applyProgress();
    }

    /** Heals the boss, drops party health, returns it to its base phase and forgets its fighters. */
    public void reset() {
        setParty(1);
        this.entity.setHealth(this.entity.getMaxHealth());
        if (this.entity instanceof Mob mob) mob.setTarget(null);
        if (this.spec.arena != null && this.spec.arena.leashes()) this.entity.teleportTo(this.home.x, this.home.y, this.home.z);
        this.fighters.clear();
        this.emptyTicks = 0;
        this.engaged = false;
        updatePhase();
        applyProgress();
        NeoForge.EVENT_BUS.post(new BossEvents.Reset(this));
    }

    private void setParty(int players) {
        AttributeInstance max = this.entity.getAttribute(Attributes.MAX_HEALTH);
        if (max == null || players == this.party) return;
        float frac = this.entity.getHealth() / this.entity.getMaxHealth();
        max.removeModifier(PARTY_MODIFIER);
        if (players > 1) {
            max.addTransientModifier(new AttributeModifier(PARTY_MODIFIER, this.spec.arena.healthPerExtraPlayer() * (players - 1),
                AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
        this.entity.setHealth(frac * this.entity.getMaxHealth());
        this.party = players;
    }

    private void updateViewers(List<ServerPlayer> viewers) {
        Set<ServerPlayer> now = new HashSet<>(viewers);
        for (ServerPlayer p : List.copyOf(this.bar.getPlayers())) {
            if (!now.contains(p)) {
                this.bar.removePlayer(p);
                BossMusic.stop(p, this.spec.fadeOut);
            }
        }
        Collection<ServerPlayer> had = this.bar.getPlayers();
        Identifier music = music();
        for (ServerPlayer p : viewers) {
            if (!had.contains(p)) {
                this.bar.addPlayer(p);
                if (music != null) BossMusic.play(p, music, this.spec.fadeIn, this.entity);
            }
        }
    }

    private int findPhase() {
        int found = -1;
        for (int i = 0; i < this.spec.phases.size(); i++) {
            if (this.spec.phases.get(i).holds(this.entity)) found = i;
        }
        return found;
    }

    void updatePhase() {
        int next = findPhase();
        if (next == this.phase) return;
        String from = phase();
        int fromIndex = this.phase;
        Identifier oldMusic = music();
        if (this.phase >= 0 && this.spec.phases.get(this.phase).onExit != null) this.spec.phases.get(this.phase).onExit.accept(this);
        this.phase = next;
        applyBar();
        Identifier newMusic = music();
        if (!Objects.equals(oldMusic, newMusic)) {
            int fade = Math.max(this.spec.fadeIn, 1);
            for (ServerPlayer p : this.bar.getPlayers()) {
                if (newMusic == null) BossMusic.stop(p, this.spec.fadeOut);
                else BossMusic.play(p, newMusic, fade, this.entity);
            }
        }
        if (this.phase >= 0 && this.spec.phases.get(this.phase).onEnter != null) this.spec.phases.get(this.phase).onEnter.accept(this);
        NeoForge.EVENT_BUS.post(new BossEvents.Phase(this, from, phase()));
        FEvents.post(new Events.BossPhaseChanged(this.entity, FId.from(this.spec.id()), fromIndex + 1, this.phase + 1));
    }

    private void applyBar() {
        var name = this.spec.name;
        var color = this.spec.color;
        var overlay = this.spec.overlay;
        for (int i = 0; i <= this.phase; i++) {
            BossPhase p = this.spec.phases.get(i);
            if (p.name != null) name = p.name;
            if (p.color != null) color = p.color;
            if (p.overlay != null) overlay = p.overlay;
        }
        this.bar.setName(name);
        this.bar.setColor(color);
        this.bar.setOverlay(overlay);
    }

    private void applyProgress() {
        double v = this.spec.progress != null ? this.spec.progress.applyAsDouble(this.entity)
            : this.entity.getHealth() / Math.max(1e-6f, this.entity.getMaxHealth());
        this.bar.setProgress((float) Math.max(0, Math.min(1, v)));
    }

    /** The largest hit allowed now so the boss stops on its next phase line (or {@code amount} when none is closer). */
    float capDamage(float amount) {
        if (!this.spec.capDamageAtPhase) return amount;
        float health = this.entity.getHealth();
        float max = this.entity.getMaxHealth();
        float cap = amount;
        for (int i = this.phase + 1; i < this.spec.phases.size(); i++) {
            float line = this.spec.phases.get(i).below;
            if (line < 0) continue;
            float lineHealth = max * line;
            if (health > lineHealth && health - amount < lineHealth) cap = Math.min(cap, health - lineHealth);
        }
        return Math.max(0, cap);
    }

    /** Ends the bar and music (death or unload). */
    void end() {
        if (this.ended) return;
        this.ended = true;
        for (ServerPlayer p : List.copyOf(this.bar.getPlayers())) BossMusic.stop(p, this.spec.fadeOut);
        this.bar.removeAllPlayers();
    }
}
