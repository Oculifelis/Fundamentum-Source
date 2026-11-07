package com.fundamentum.whip;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.lang.reflect.RecordComponent;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Everything about one whip: its numbers ({@link WhipTuning}), its look (cord texture, colours, width), its sounds and
 * particles, who it may hit ({@link WhipTargets.Rule}), the mark it leaves for minions ({@link SummonTag}) and its
 * {@link WhipHooks}. Register one per item with {@link Whips#register}; the physics, the hits and the renderer are
 * Fundamentum's.
 * <pre>
 * Whips.register(MyItems.THORN_WHIP, WhipDefinition.builder(id("thorn_whip"))
 *     .tuning(t -> t.length(5.5).damage(8).strands(3))
 *     .texture(id("textures/entity/whip/thorn.png")).color(0xFF5A8F3C).tipColor(0xFFB8E07A)
 *     .summonTag(new WhipDefinition.SummonTag(240, 4, 1, 0.1))
 *     .hooks(new WhipHooks() { public void landed(WhipHit hit, boolean hurt) { ... } })
 *     .build());
 * </pre>
 * A data file {@code data/<namespace>/fundamentum/whips/<name>.json} keyed by "item" overrides any of the tuning
 * (snake case: {@code "length"}, {@code "crack_speed"}, {@code "tip_crit_multiplier"} ...) and the look
 * ({@code "texture"}, {@code "color"}, {@code "tip_color"} as {@code "#AARRGGBB"} or a number, {@code "width"},
 * {@code "tip_width"}, {@code "idle_length"}, {@code "idle_coil"}, {@code "shockwave"}, {@code "cord_fx"}, {@code "hip_coil"}) and aim ({@code "homing_degrees"},
 * {@code "strand_targets"}) of a registered whip, or makes any item a plain whip with those numbers.
 */
public final class WhipDefinition {
    public static final Identifier DEFAULT_TEXTURE = Identifier.fromNamespaceAndPath("fundamentum", "textures/entity/whip/cord.png");

    /**
     * Terraria's summoner-whip tag: a struck mob carries the striker's mark for {@code ticks}, and the striker's minions
     * focus it and deal {@code (damage + flat) * multiplier} to it, critting (double) with {@code critChance}. Fundamentum's
     * own minions ({@code com.fundamentum.summon}) read it; a mod's own minions read {@link WhipTags#mark}.
     */
    public record SummonTag(int ticks, double flat, double multiplier, double critChance) {}

    /**
     * A charged ground slam (1.22.0): hold use for up to {@code chargeTicks} (at least a quarter of it, or nothing
     * happens), let go, and the cord comes down {@code reach} blocks ahead: everything it may hit within {@code radius}
     * takes up to {@code damage} (less toward the edge) and is thrown out by {@code knockback} and up by {@code lift}, all
     * scaled by the charge. Block debris flies from a ring that widens to the radius. {@code cooldownTicks} after.
     */
    public record Slam(int chargeTicks, double reach, double radius, double damage, double knockback, double lift, int cooldownTicks) {
        public static Slam of(double radius, double damage) {
            return new Slam(24, 2.5, radius, damage, 1.0, 0.45, 30);
        }
    }

    /** Client side, every tick of a swing, for every segment: spawn particles along the cord ({@code level.addParticle}). */
    public interface SegmentParticles {
        void spawn(Level level, int strand, int segment, double along, Vec3 pos, double speed, RandomSource random);
    }

    /** The damage source of a hit; the default is a player melee attack (melee class, the item's element tags). */
    public interface DamageMaker {
        DamageSource make(ServerPlayer player, ItemStack stack);
    }

    public final Identifier id;
    public final WhipTuning tuning;
    public final Identifier texture;
    /** ARGB at the handle and at the tip; the cord blends between them over the texture. */
    public final int color, tipColor;
    /** Cord width in blocks at the handle and at the tip. */
    public final float width, tipWidth;
    /**
     * How much of the cord hangs loose from the hand while the whip is held and not swinging, as a fraction of its
     * length (0 for none: then only the item model shows).
     */
    public final double idleLength;
    /** Between swings, the cord sits coiled in a few loops under the handle end instead of hanging ({@link Builder#coiled}). */
    public final boolean idleCoil;
    /**
     * Homing: at the start of a swing the stroke turns toward the nearest hostile within this many degrees of the
     * player's look and within the cord's reach (0 for none).
     */
    public final double homingDegrees;
    /** With homing, each strand turns toward a different hostile in the cone (the rest keep the look). */
    public final boolean strandTargets;
    /** The colour (ARGB) of the ring that bursts from the tip when it cracks; 0 for none. */
    public final int shockwave;
    /** How the cord is drawn (1.22.0): plain texture, or one of the animated cord shaders ({@link WhipCordFx}). */
    public final WhipCordFx cordFx;
    /**
     * The soft halo drawn round the cord (1.22.2), ARGB; 0 for none. {@link #AUTO_GLOW} (the default) gives the glowing
     * cord shaders (glow, energy, lightning, fire) a halo in the cord texture's own colour and the rest none.
     */
    public final int glow;
    /** {@link #glow}'s default: a halo for the glowing cord shaders, none otherwise. */
    public static final int AUTO_GLOW = 0x00FFFFFF;
    /** While the whip sits in the hotbar and another item is held, its cord is drawn coiled at the hip (1.22.0). */
    public final boolean hipCoil;
    /**
     * Where the cord leaves a 3D held handle (1.22.1), in pixels of the handheld 16x16 frame from the top left, so a
     * handle model can sit centred in the fist with the cord leaving its top. Defaults to the flat sprite's end
     * ({@link WhipsClient#HANDLE_END_U}); with {@code whips.flatHandles} on the flat sprite's end is used whatever this is.
     */
    public final float handleEndU, handleEndV;
    /** Hold use (right click) to charge, let go to slam the ground (1.22.0); null for none. */
    public final @Nullable Slam slam;
    public final WhipHooks hooks;
    public final WhipTargets.Rule targets;
    public final @Nullable SummonTag summonTag;
    public final DamageMaker damage;
    public final @Nullable SoundEvent swingSound, crackSound;
    public final @Nullable ParticleOptions crackParticle;
    public final @Nullable SegmentParticles particles;

    private WhipDefinition(Builder b) {
        id = b.id;
        tuning = b.tuning.build();
        texture = b.texture;
        color = b.color;
        tipColor = b.tipColor;
        width = b.width;
        tipWidth = b.tipWidth;
        idleLength = b.idleLength;
        idleCoil = b.idleCoil;
        homingDegrees = b.homingDegrees;
        strandTargets = b.strandTargets;
        shockwave = b.shockwave;
        cordFx = b.cordFx;
        glow = b.glow;
        hipCoil = b.hipCoil;
        handleEndU = b.handleEndU;
        handleEndV = b.handleEndV;
        slam = b.slam;
        hooks = b.hooks;
        targets = b.targets;
        summonTag = b.summonTag;
        damage = b.damage;
        swingSound = b.swingSound;
        crackSound = b.crackSound;
        crackParticle = b.crackParticle;
        particles = b.particles;
    }

    public static Builder builder(Identifier id) {
        return new Builder(id);
    }

    public Builder toBuilder() {
        Builder b = new Builder(id);
        b.tuning = tuning.toBuilder();
        b.texture = texture;
        b.color = color;
        b.tipColor = tipColor;
        b.width = width;
        b.tipWidth = tipWidth;
        b.idleLength = idleLength;
        b.idleCoil = idleCoil;
        b.homingDegrees = homingDegrees;
        b.strandTargets = strandTargets;
        b.shockwave = shockwave;
        b.cordFx = cordFx;
        b.glow = glow;
        b.hipCoil = hipCoil;
        b.handleEndU = handleEndU;
        b.handleEndV = handleEndV;
        b.slam = slam;
        b.hooks = hooks;
        b.targets = targets;
        b.summonTag = summonTag;
        b.damage = damage;
        b.swingSound = swingSound;
        b.crackSound = crackSound;
        b.crackParticle = crackParticle;
        b.particles = particles;
        return b;
    }

    /** This whip with a data file's fields laid over it (see the class comment). */
    public WhipDefinition withJson(JsonObject json) {
        Builder b = toBuilder();
        b.tuning = tuningWithJson(tuning, json).toBuilder();
        if (json.has("texture")) {
            b.texture = Identifier.parse(json.get("texture").getAsString());
        }
        if (json.has("color")) {
            b.color = color(json.get("color"));
        }
        if (json.has("tip_color")) {
            b.tipColor = color(json.get("tip_color"));
        }
        if (json.has("width")) {
            b.width = json.get("width").getAsFloat();
        }
        if (json.has("tip_width")) {
            b.tipWidth = json.get("tip_width").getAsFloat();
        }
        if (json.has("idle_length")) {
            b.idleLength = json.get("idle_length").getAsDouble();
        }
        if (json.has("idle_coil")) {
            b.idleCoil = json.get("idle_coil").getAsBoolean();
        }
        if (json.has("homing_degrees")) {
            b.homingDegrees = json.get("homing_degrees").getAsDouble();
        }
        if (json.has("strand_targets")) {
            b.strandTargets = json.get("strand_targets").getAsBoolean();
        }
        if (json.has("shockwave")) {
            b.shockwave = color(json.get("shockwave"));
        }
        if (json.has("cord_fx")) {
            b.cordFx = WhipCordFx.valueOf(json.get("cord_fx").getAsString().toUpperCase(java.util.Locale.ROOT));
        }
        if (json.has("glow")) {
            b.glow = json.get("glow").isJsonPrimitive() && json.get("glow").getAsJsonPrimitive().isBoolean()
                ? (json.get("glow").getAsBoolean() ? AUTO_GLOW : 0) : color(json.get("glow"));
        }
        if (json.has("hip_coil")) {
            b.hipCoil = json.get("hip_coil").getAsBoolean();
        }
        if (json.has("handle_end")) {
            var end = json.getAsJsonArray("handle_end");
            b.handleEnd(end.get(0).getAsFloat(), end.get(1).getAsFloat());
        }
        return b.build();
    }

    /** The tuning's record components by snake-case name, read from the JSON where present. */
    static WhipTuning tuningWithJson(WhipTuning base, JsonObject json) {
        RecordComponent[] parts = WhipTuning.class.getRecordComponents();
        Object[] values = new Object[parts.length];
        Class<?>[] types = new Class<?>[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                RecordComponent c = parts[i];
                types[i] = c.getType();
                values[i] = c.getAccessor().invoke(base);
                String key = c.getName().replaceAll("([A-Z])", "_$1").toLowerCase(java.util.Locale.ROOT);
                if (json.has(key)) {
                    JsonElement e = json.get(key);
                    values[i] = types[i] == int.class ? e.getAsInt() : types[i] == boolean.class ? e.getAsBoolean() : e.getAsDouble();
                }
            }
            return WhipTuning.class.getDeclaredConstructor(types).newInstance(values);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("WhipTuning record changed shape", e);
        }
    }

    private static int color(JsonElement e) {
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            return (int) e.getAsLong();
        }
        String s = e.getAsString().replace("#", "");
        int c = (int) Long.parseLong(s, 16);
        return s.length() <= 6 ? 0xFF000000 | c : c;
    }

    /** Builds a {@link WhipDefinition}; only the id is required. */
    public static final class Builder {
        final Identifier id;
        WhipTuning.Builder tuning = WhipTuning.builder();
        Identifier texture = DEFAULT_TEXTURE;
        int color = 0xFFFFFFFF, tipColor = 0xFFFFFFFF;
        float width = 0.09F, tipWidth = 0.03F;
        double idleLength = 0.35;
        boolean idleCoil;
        double homingDegrees;
        boolean strandTargets;
        int shockwave;
        WhipCordFx cordFx = WhipCordFx.NONE;
        int glow = AUTO_GLOW;
        boolean hipCoil = true;
        float handleEndU = 11.5F, handleEndV = 4.5F;
        @Nullable Slam slam;
        WhipHooks hooks = WhipHooks.NONE;
        WhipTargets.Rule targets = WhipTargets.DEFAULT;
        @Nullable SummonTag summonTag;
        DamageMaker damage = (player, stack) -> player.damageSources().playerAttack(player);
        @Nullable SoundEvent swingSound = SoundEvents.PLAYER_ATTACK_SWEEP, crackSound = SoundEvents.PLAYER_ATTACK_KNOCKBACK;
        @Nullable ParticleOptions crackParticle = ParticleTypes.CRIT;
        @Nullable SegmentParticles particles;

        Builder(Identifier id) {
            this.id = id;
        }

        public Builder tuning(WhipTuning tuning) {
            this.tuning = tuning.toBuilder();
            return this;
        }

        /** Changes some numbers of the tuning so far: {@code .tuning(t -> t.length(6).damage(9))}. */
        public Builder tuning(java.util.function.UnaryOperator<WhipTuning.Builder> edit) {
            this.tuning = edit.apply(this.tuning);
            return this;
        }

        /** The cord texture: u across the cord, v from handle (0) to tip (1). See {@code fundamentum:textures/entity/whip/cord.png}. */
        public Builder texture(Identifier texture) {
            this.texture = texture;
            return this;
        }

        /** One tint for the whole cord (ARGB). */
        public Builder color(int argb) {
            this.color = argb;
            this.tipColor = argb;
            return this;
        }

        /** The tint at the tip (ARGB); call after {@link #color}. */
        public Builder tipColor(int argb) {
            this.tipColor = argb;
            return this;
        }

        public Builder width(float handle, float tip) {
            this.width = handle;
            this.tipWidth = tip;
            return this;
        }

        /** The fraction of the cord that hangs from the hand between swings; 0 shows only the item model. */
        public Builder idle(double fraction) {
            this.idleLength = Math.clamp(fraction, 0.0, 1.0);
            return this;
        }

        /** Between swings the cord sits coiled under the handle end (and does not hang: idle length 0). */
        public Builder coiled() {
            this.idleCoil = true;
            this.idleLength = 0;
            return this;
        }

        /**
         * Homing: each swing turns toward the nearest hostile within {@code degrees} of the look and in reach, so the
         * stroke comes down on it. Hits still follow the cord.
         */
        public Builder homing(double degrees) {
            this.homingDegrees = Math.clamp(degrees, 0.0, 90.0);
            return this;
        }

        /** With {@link #homing}, each strand goes for a different hostile in the cone, nearest first. */
        public Builder strandTargets() {
            this.strandTargets = true;
            return this;
        }

        /** A ring of this colour (ARGB) bursts from the tip when it cracks. */
        public Builder shockwave(int argb) {
            this.shockwave = argb;
            return this;
        }

        /** Draws the cord with an animated shader (see {@link WhipCordFx}); the cord texture and colours still apply. */
        public Builder cordFx(WhipCordFx fx) {
            this.cordFx = fx;
            return this;
        }

        /**
         * A soft, unlit halo round the cord in this colour (ARGB, the alpha is its strength), drawn on swings, coils and
         * slam spins; 0 for none. Unset, the glowing cord shaders get one in the texture's colour (see {@link WhipDefinition#glow}).
         */
        public Builder glow(int argb) {
            this.glow = argb;
            return this;
        }

        /** False keeps the whip off the hip while it sits in the hotbar. */
        public Builder hipCoil(boolean hip) {
            this.hipCoil = hip;
            return this;
        }

        /** Where the cord leaves a 3D held handle model, in handheld 16x16 pixels from the top left (default 11.5, 4.5). */
        public Builder handleEnd(float u, float v) {
            this.handleEndU = u;
            this.handleEndV = v;
            return this;
        }

        /** Hold use to charge a ground slam, let go to bring it down. */
        public Builder slam(@Nullable Slam slam) {
            this.slam = slam;
            return this;
        }

        public Builder hooks(WhipHooks hooks) {
            this.hooks = hooks;
            return this;
        }

        public Builder targets(WhipTargets.Rule targets) {
            this.targets = targets;
            return this;
        }

        public Builder summonTag(@Nullable SummonTag tag) {
            this.summonTag = tag;
            return this;
        }

        public Builder damage(DamageMaker damage) {
            this.damage = damage;
            return this;
        }

        /** Swing (stroke start, at the player) and crack (at the tip) sounds; null for none. */
        public Builder sounds(@Nullable SoundEvent swing, @Nullable SoundEvent crack) {
            this.swingSound = swing;
            this.crackSound = crack;
            return this;
        }

        /** The burst at the tip when it cracks (client side); null for none. */
        public Builder crackParticle(@Nullable ParticleOptions particle) {
            this.crackParticle = particle;
            return this;
        }

        public Builder particles(@Nullable SegmentParticles particles) {
            this.particles = particles;
            return this;
        }

        public WhipDefinition build() {
            return new WhipDefinition(this);
        }
    }
}
