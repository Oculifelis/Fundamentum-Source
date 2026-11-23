package com.fundamentum.effect;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.damagesource.DamageSource;

/**
 * The action types status effects can use, by id. Fundamentum brings two:
 * <ul>
 * <li>{@code fundamentum:damage}: {@code amount} (per level unless {@code "per_level": false}) of {@code damage_type}
 * (default {@code minecraft:magic}) every {@code interval} ticks (default 20); scaled by the target's resistance to the
 * effect's element.</li>
 * <li>{@code fundamentum:heal}: heals {@code amount} (per level) every {@code interval} ticks.</li>
 * </ul>
 * Other mods add their own with {@link #register} (a Codec, or a function of the JSON object), before data loads.
 */
public final class EffectActions {
    private static final Map<Identifier, Function<JsonObject, ? extends EffectAction>> TYPES = new ConcurrentHashMap<>();

    public static final Identifier DAMAGE = Identifier.fromNamespaceAndPath("fundamentum", "damage");
    public static final Identifier HEAL = Identifier.fromNamespaceAndPath("fundamentum", "heal");

    static {
        register(DAMAGE, json -> new Damage(GsonHelper.getAsFloat(json, "amount"), Math.max(1, GsonHelper.getAsInt(json, "interval", 20)),
            GsonHelper.getAsBoolean(json, "per_level", true), Identifier.parse(GsonHelper.getAsString(json, "damage_type", "minecraft:magic"))));
        register(HEAL, json -> new Heal(GsonHelper.getAsFloat(json, "amount"), Math.max(1, GsonHelper.getAsInt(json, "interval", 20)),
            GsonHelper.getAsBoolean(json, "per_level", true)));
    }

    /** Registers an action type read with a Codec (the {@code type} field is ignored by it). */
    public static void register(Identifier id, Codec<? extends EffectAction> codec) {
        register(id, json -> decode(codec, json));
    }

    /** Registers an action type read by a function of its JSON object. */
    public static void register(Identifier id, Function<JsonObject, ? extends EffectAction> decoder) {
        TYPES.put(id, decoder);
    }

    public static Set<Identifier> types() {
        return Set.copyOf(TYPES.keySet());
    }

    /** Reads one action object; throws with a readable message if its type is unknown or it is broken. */
    public static EffectAction parse(JsonObject json) {
        Identifier type = Identifier.parse(GsonHelper.getAsString(json, "type"));
        Function<JsonObject, ? extends EffectAction> decoder = TYPES.get(type);
        if (decoder == null) {
            throw new IllegalArgumentException("unknown effect action " + type);
        }
        return decoder.apply(json);
    }

    static <T> T decode(Codec<T> codec, JsonObject json) {
        DataResult<T> r = codec.parse(JsonOps.INSTANCE, json);
        return r.result().orElseThrow(() -> new IllegalArgumentException(r.error().map(e -> e.message()).orElse("?")));
    }

    /** Damage over time. */
    public record Damage(float amount, int interval, boolean perLevel, Identifier damageType) implements EffectAction {
        @Override
        public void onTick(Context c) {
            if (c.age() <= 0 || c.age() % this.interval != 0) {
                return;
            }
            DamageSource source = new DamageSource(c.serverLevel().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE)
                .getOrThrow(ResourceKey.create(Registries.DAMAGE_TYPE, this.damageType)));
            float amount = (float) (this.amount * (this.perLevel ? c.level() : 1) * Effects.elementScale(c.entity(), c.definition(), source));
            if (amount > 0) {
                c.entity().hurtServer(c.serverLevel(), source, amount);
            }
        }
    }

    /** Healing over time. */
    public record Heal(float amount, int interval, boolean perLevel) implements EffectAction {
        @Override
        public void onTick(Context c) {
            if (c.age() > 0 && c.age() % this.interval == 0) {
                c.entity().heal(this.amount * (this.perLevel ? c.level() : 1));
            }
        }
    }

    private EffectActions() {}
}
