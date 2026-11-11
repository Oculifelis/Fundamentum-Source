package com.fundamentum.boss;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.ToDoubleFunction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

/**
 * What makes an entity a boss: its bar (name, colour, overlay, darken / fog / boss-music flags), who sees the bar (players
 * in range, or players inside the arena), its phases, its music, its arena, how its defeats are recorded, and an optional
 * progress source (instead of health). Build one with {@link #builder(ResourceLocation)} or from
 * {@code data/<ns>/fundamentum/bosses/<name>.json} (see {@link Bosses}).
 */
public final class BossSpec {
    public enum Visibility { RANGE, ARENA }

    public enum Record { NONE, WORLD, PLAYER, BOTH }

    final ResourceLocation id;
    final Component name;
    final BossEvent.BossBarColor color;
    final BossEvent.BossBarOverlay overlay;
    final boolean darken;
    final boolean fog;
    final boolean bossMusicFlag;
    final Visibility visibility;
    final double range;
    final List<BossPhase> phases;
    final @Nullable ResourceLocation music;
    final int fadeIn;
    final int fadeOut;
    final @Nullable ArenaSpec arena;
    final Record record;
    final boolean capDamageAtPhase;
    final @Nullable ToDoubleFunction<LivingEntity> progress;
    final @Nullable JsonObject drops;

    private BossSpec(Builder b) {
        this.id = b.id;
        this.name = b.name;
        this.color = b.color;
        this.overlay = b.overlay;
        this.darken = b.darken;
        this.fog = b.fog;
        this.bossMusicFlag = b.bossMusicFlag;
        this.visibility = b.visibility;
        this.range = b.range;
        this.phases = List.copyOf(b.phases);
        this.music = b.music;
        this.fadeIn = b.fadeIn;
        this.fadeOut = b.fadeOut;
        this.arena = b.arena;
        this.record = b.record;
        this.capDamageAtPhase = b.capDamageAtPhase;
        this.progress = b.progress;
        this.drops = b.drops;
    }

    public ResourceLocation id() {
        return this.id;
    }

    public List<BossPhase> phases() {
        return this.phases;
    }

    public @Nullable ArenaSpec arena() {
        return this.arena;
    }

    public Record record() {
        return this.record;
    }

    /** The {@code drops} object of a data file, read by the loot package's boss drops (null when none). */
    public @Nullable JsonObject drops() {
        return this.drops;
    }

    public static Builder builder(ResourceLocation id) {
        return new Builder(id);
    }

    public static final class Builder {
        private final ResourceLocation id;
        private Component name;
        private BossEvent.BossBarColor color = BossEvent.BossBarColor.PURPLE;
        private BossEvent.BossBarOverlay overlay = BossEvent.BossBarOverlay.PROGRESS;
        private boolean darken;
        private boolean fog;
        private boolean bossMusicFlag;
        private Visibility visibility = Visibility.RANGE;
        private double range = 64;
        private final List<BossPhase> phases = new ArrayList<>();
        private @Nullable ResourceLocation music;
        private int fadeIn = 40;
        private int fadeOut = 60;
        private @Nullable ArenaSpec arena;
        private Record record = Record.BOTH;
        private boolean capDamageAtPhase;
        private @Nullable ToDoubleFunction<LivingEntity> progress;
        private @Nullable JsonObject drops;

        private Builder(ResourceLocation id) {
            this.id = id;
            this.name = Component.translatable("boss." + id.getNamespace() + "." + id.getPath().replace('/', '.'));
        }

        public Builder name(Component name) { this.name = name; return this; }
        public Builder color(BossEvent.BossBarColor color) { this.color = color; return this; }
        public Builder overlay(BossEvent.BossBarOverlay overlay) { this.overlay = overlay; return this; }
        /** Darken the sky while the bar shows. */
        public Builder darken(boolean darken) { this.darken = darken; return this; }
        /** Thicken the fog while the bar shows. */
        public Builder fog(boolean fog) { this.fog = fog; return this; }
        /** Vanilla's boss-music flag on the bar (the client's own music choice); our phase music is separate. */
        public Builder bossMusicFlag(boolean flag) { this.bossMusicFlag = flag; return this; }
        /** Players within {@code range} blocks see the bar (the default, 64). */
        public Builder visibleInRange(double range) { this.visibility = Visibility.RANGE; this.range = range; return this; }
        /** Only players inside the arena see the bar (needs an arena). */
        public Builder visibleInArena() { this.visibility = Visibility.ARENA; return this; }
        public Builder phase(BossPhase phase) { this.phases.add(phase); return this; }
        /** The base phase's track; phases may change or silence it. */
        public Builder music(ResourceLocation sound) { this.music = sound; return this; }
        public Builder musicFade(int inTicks, int outTicks) { this.fadeIn = inTicks; this.fadeOut = outTicks; return this; }
        public Builder arena(ArenaSpec arena) { this.arena = arena; return this; }
        public Builder record(Record record) { this.record = record; return this; }
        /** A hit that would carry the boss past its next phase's health line stops on the line. */
        public Builder capDamageAtPhase(boolean cap) { this.capDamageAtPhase = cap; return this; }
        /** The bar shows this 0..1 value instead of health (kill counters, charge, waves). */
        public Builder progress(ToDoubleFunction<LivingEntity> progress) { this.progress = progress; return this; }
        public Builder drops(JsonObject drops) { this.drops = drops; return this; }

        public BossSpec build() {
            return new BossSpec(this);
        }
    }

    // ------------------------------------------------------------------------------------------------ json
    static BossSpec fromJson(ResourceLocation id, JsonObject json) {
        Builder b = builder(id);
        if (json.has("name")) b.name(text(json.get("name")));
        if (json.has("color")) b.color(color(json.get("color").getAsString()));
        if (json.has("overlay")) b.overlay(overlay(json.get("overlay").getAsString()));
        if (json.has("darken")) b.darken(json.get("darken").getAsBoolean());
        if (json.has("fog")) b.fog(json.get("fog").getAsBoolean());
        if (json.has("boss_music_flag")) b.bossMusicFlag(json.get("boss_music_flag").getAsBoolean());
        if (json.has("visible")) {
            JsonElement v = json.get("visible");
            if (v.isJsonPrimitive() && "arena".equals(v.getAsString())) b.visibleInArena();
            else if (v.isJsonObject() && v.getAsJsonObject().has("range")) b.visibleInRange(v.getAsJsonObject().get("range").getAsDouble());
            else if (v.isJsonPrimitive()) b.visibleInRange(v.getAsDouble());
        }
        if (json.has("music") && !json.get("music").getAsString().isEmpty()) b.music(ResourceLocation.parse(json.get("music").getAsString()));
        b.musicFade(json.has("music_fade_in") ? json.get("music_fade_in").getAsInt() : 40,
            json.has("music_fade_out") ? json.get("music_fade_out").getAsInt() : 60);
        if (json.has("phases")) {
            var arr = json.getAsJsonArray("phases");
            for (int i = 0; i < arr.size(); i++) b.phase(BossPhase.fromJson(arr.get(i).getAsJsonObject(), i));
        }
        if (json.has("arena")) b.arena(ArenaSpec.fromJson(json.getAsJsonObject("arena")));
        if (json.has("record")) b.record(Record.valueOf(json.get("record").getAsString().toUpperCase(Locale.ROOT)));
        if (json.has("cap_damage_at_phase")) b.capDamageAtPhase(json.get("cap_damage_at_phase").getAsBoolean());
        if (json.has("drops")) b.drops(json.getAsJsonObject("drops"));
        BossSpec spec = b.build();
        if (spec.visibility == Visibility.ARENA && spec.arena == null) {
            throw new IllegalArgumentException("\"visible\": \"arena\" needs an arena");
        }
        return spec;
    }

    /** A plain string is literal text; {"translate": key} is a translated name. */
    static Component text(JsonElement e) {
        if (e.isJsonObject() && e.getAsJsonObject().has("translate")) {
            return Component.translatable(e.getAsJsonObject().get("translate").getAsString());
        }
        return Component.literal(e.getAsString());
    }

    static BossEvent.BossBarColor color(String s) {
        return BossEvent.BossBarColor.valueOf(s.toUpperCase(Locale.ROOT));
    }

    static BossEvent.BossBarOverlay overlay(String s) {
        return BossEvent.BossBarOverlay.valueOf(s.toUpperCase(Locale.ROOT));
    }
}
