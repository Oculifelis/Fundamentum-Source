package com.fundamentum.boss;

import com.google.gson.JsonObject;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

/**
 * One boss phase: when it holds (health at or below a fraction, or a predicate) and what it changes (bar name, colour,
 * overlay, music). A boss is in the last of its phases that holds; with none it is in its base phase ("base").
 * {@code onEnter} and {@code onExit} run on the server beside {@link BossPhaseEvent}.
 */
public final class BossPhase {
    final String id;
    final float below;
    final @Nullable Predicate<LivingEntity> when;
    final @Nullable Component name;
    final @Nullable BossEvent.BossBarColor color;
    final @Nullable BossEvent.BossBarOverlay overlay;
    final @Nullable ResourceLocation music;
    final boolean stopMusic;
    @Nullable Consumer<Boss> onEnter;
    @Nullable Consumer<Boss> onExit;

    private BossPhase(String id, float below, @Nullable Predicate<LivingEntity> when, @Nullable Component name,
            @Nullable BossEvent.BossBarColor color, @Nullable BossEvent.BossBarOverlay overlay, @Nullable ResourceLocation music, boolean stopMusic) {
        this.id = id;
        this.below = below;
        this.when = when;
        this.name = name;
        this.color = color;
        this.overlay = overlay;
        this.music = music;
        this.stopMusic = stopMusic;
    }

    /** A phase that holds while health / max health is at or below {@code fraction}. */
    public static BossPhase below(String id, float fraction) {
        return new BossPhase(id, fraction, null, null, null, null, null, false);
    }

    /** A phase that holds while {@code when} is true (checked every tick). */
    public static BossPhase when(String id, Predicate<LivingEntity> when) {
        return new BossPhase(id, -1f, when, null, null, null, null, false);
    }

    public BossPhase name(Component name) {
        return copy(name, this.color, this.overlay, this.music, this.stopMusic);
    }

    public BossPhase color(BossEvent.BossBarColor color) {
        return copy(this.name, color, this.overlay, this.music, this.stopMusic);
    }

    public BossPhase overlay(BossEvent.BossBarOverlay overlay) {
        return copy(this.name, this.color, overlay, this.music, this.stopMusic);
    }

    /** The track this phase plays (a sound event id); the client crossfades into it. */
    public BossPhase music(ResourceLocation sound) {
        return copy(this.name, this.color, this.overlay, sound, false);
    }

    /** This phase is silent. */
    public BossPhase silent() {
        return copy(this.name, this.color, this.overlay, null, true);
    }

    public BossPhase onEnter(Consumer<Boss> action) {
        this.onEnter = action;
        return this;
    }

    public BossPhase onExit(Consumer<Boss> action) {
        this.onExit = action;
        return this;
    }

    public String id() {
        return this.id;
    }

    /** The health fraction this phase starts at, or -1 for a predicate phase. */
    public float threshold() {
        return this.below;
    }

    boolean holds(LivingEntity entity) {
        if (this.when != null) return this.when.test(entity);
        return entity.getHealth() <= entity.getMaxHealth() * this.below;
    }

    private BossPhase copy(@Nullable Component name, @Nullable BossEvent.BossBarColor color, @Nullable BossEvent.BossBarOverlay overlay,
            @Nullable ResourceLocation music, boolean stopMusic) {
        BossPhase p = new BossPhase(this.id, this.below, this.when, name, color, overlay, music, stopMusic);
        p.onEnter = this.onEnter;
        p.onExit = this.onExit;
        return p;
    }

    static BossPhase fromJson(JsonObject json, int index) {
        String id = json.has("id") ? json.get("id").getAsString() : "phase_" + (index + 1);
        if (!json.has("below")) throw new IllegalArgumentException("phase " + id + " needs \"below\" (a health fraction)");
        BossPhase p = below(id, json.get("below").getAsFloat());
        if (json.has("name")) p = p.name(BossSpec.text(json.get("name")));
        if (json.has("color")) p = p.color(BossSpec.color(json.get("color").getAsString()));
        if (json.has("overlay")) p = p.overlay(BossSpec.overlay(json.get("overlay").getAsString()));
        if (json.has("music")) {
            p = json.get("music").isJsonNull() || json.get("music").getAsString().isEmpty() ? p.silent()
                : p.music(new ResourceLocation(json.get("music").getAsString()));
        }
        return p;
    }
}
