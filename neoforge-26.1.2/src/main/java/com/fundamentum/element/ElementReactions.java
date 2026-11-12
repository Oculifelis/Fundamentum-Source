package com.fundamentum.element;

import com.fundamentum.event.Events;
import com.fundamentum.event.FEvents;
import com.fundamentum.port.FId;
import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Reactions: what happens when a hit of one element lands on a creature carrying an aura of another. Each is a JSON file
 * {@code data/<ns>/fundamentum/element_reaction/<name>.json}; its id is {@code <ns>:<name>}. Every field but
 * {@code trigger} and {@code aura} is optional; the full schema is in {@code docs/ELEMENTS.md}:
 * <pre>{"trigger": "#fundamentum:element/fire", "aura": "#fundamentum:element/ice", "priority": 10, "exclusive": false,
 *  "min_stacks": 1, "consume": "all", "apply_trigger_aura": false,
 *  "damage": {"multiplier": 2, "bonus": 0, "per_stack": 0}, "convert_to": "minecraft:magic",
 *  "effects": [{"id": "minecraft:slowness", "duration": 60, "amplifier": 1, "on": "target"}],
 *  "knockback": {"strength": 0.6, "up": 0.2}, "ignite": 0, "extinguish": false,
 *  "burst": {"radius": 3, "damage": 2, "scale": 0.5, "damage_type": "fundamentum:electric", "include_target": false},
 *  "fx": {"particle": {"type": "minecraft:cloud"}, "count": 12, "spread": 0.4, "speed": 0.02,
 *         "sound": "minecraft:block.fire.extinguish", "volume": 1, "pitch": 1},
 *  "function": "mypack:on_melt"}</pre>
 * Several reactions can match one hit: they run from the highest {@code priority} down (ties by id), each only if its
 * aura is still there (an earlier one may have consumed it), and an {@code exclusive} one stops the rest. Loops can't
 * happen: a reaction never fires while it is already running up the chain (its own burst or converted hit), never twice
 * on one creature in one tick, and chains stop 8 reactions deep ({@link ElementMath.LoopGuard}).
 *
 * <p>Order inside one hit (on {@link LivingIncomingDamageEvent}, low priority, so other mods' changes come first):
 * immunity from {@link ElementAffinities} cancels the hit; then the reactions; then, unless a reaction converted the hit
 * to another damage type (the hit is replaced by one of that type, which reacts and resists as that type), the
 * affinity multiplier; then the hit leaves its own aura ({@link ElementAuras}), unless a reaction fired that said
 * {@code apply_trigger_aura: false}. Hits that land inside a creature's hurt cooldown only get the affinity.
 */
public final class ElementReactions {
    private static final Logger LOG = LogUtils.getLogger();

    public enum Consume {
        ALL, ONE, NONE;
        static final Codec<Consume> CODEC = Codec.STRING.xmap(s -> valueOf(s.toUpperCase(Locale.ROOT)), c -> c.name().toLowerCase(Locale.ROOT));
    }

    public enum On {
        TARGET, ATTACKER;
        static final Codec<On> CODEC = Codec.STRING.xmap(s -> valueOf(s.toUpperCase(Locale.ROOT)), c -> c.name().toLowerCase(Locale.ROOT));
    }

    public record DamageMod(float multiplier, float bonus, float perStack) {
        public static final DamageMod NONE = new DamageMod(1, 0, 0);
        static final Codec<DamageMod> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.FLOAT.optionalFieldOf("multiplier", 1f).forGetter(DamageMod::multiplier),
            Codec.FLOAT.optionalFieldOf("bonus", 0f).forGetter(DamageMod::bonus),
            Codec.FLOAT.optionalFieldOf("per_stack", 0f).forGetter(DamageMod::perStack)
        ).apply(i, DamageMod::new));
    }

    public record Effect(Holder<MobEffect> id, int duration, int amplifier, On on) {
        static final Codec<Effect> CODEC = RecordCodecBuilder.create(i -> i.group(
            MobEffect.CODEC.fieldOf("id").forGetter(Effect::id),
            Codec.INT.optionalFieldOf("duration", 100).forGetter(Effect::duration),
            Codec.INT.optionalFieldOf("amplifier", 0).forGetter(Effect::amplifier),
            On.CODEC.optionalFieldOf("on", On.TARGET).forGetter(Effect::on)
        ).apply(i, Effect::new));
    }

    public record Knockback(float strength, float up) {
        static final Codec<Knockback> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.FLOAT.optionalFieldOf("strength", 0.5f).forGetter(Knockback::strength),
            Codec.FLOAT.optionalFieldOf("up", 0f).forGetter(Knockback::up)
        ).apply(i, Knockback::new));
    }

    /** Damage to every other living creature within {@code radius}: {@code damage + scale * the hit's damage}. */
    public record Burst(float radius, float damage, float scale, Identifier damageType, boolean includeTarget) {
        static final Codec<Burst> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.FLOAT.optionalFieldOf("radius", 3f).forGetter(Burst::radius),
            Codec.FLOAT.optionalFieldOf("damage", 0f).forGetter(Burst::damage),
            Codec.FLOAT.optionalFieldOf("scale", 0f).forGetter(Burst::scale),
            Identifier.CODEC.optionalFieldOf("damage_type", Identifier.withDefaultNamespace("magic")).forGetter(Burst::damageType),
            Codec.BOOL.optionalFieldOf("include_target", false).forGetter(Burst::includeTarget)
        ).apply(i, Burst::new));
    }

    /** Particles (any particle JSON, read when first used) and a sound at the target. */
    public record Fx(Optional<JsonElement> particle, int count, float spread, float speed, Optional<Identifier> sound, float volume, float pitch) {
        static final Codec<Fx> CODEC = RecordCodecBuilder.create(i -> i.group(
            ExtraCodecs.JSON.optionalFieldOf("particle").forGetter(Fx::particle),
            Codec.INT.optionalFieldOf("count", 12).forGetter(Fx::count),
            Codec.FLOAT.optionalFieldOf("spread", 0.4f).forGetter(Fx::spread),
            Codec.FLOAT.optionalFieldOf("speed", 0.02f).forGetter(Fx::speed),
            Identifier.CODEC.optionalFieldOf("sound").forGetter(Fx::sound),
            Codec.FLOAT.optionalFieldOf("volume", 1f).forGetter(Fx::volume),
            Codec.FLOAT.optionalFieldOf("pitch", 1f).forGetter(Fx::pitch)
        ).apply(i, Fx::new));
    }

    public record Reaction(List<ElementRef> trigger, List<ElementRef> aura, int priority, boolean exclusive, int minStacks, Consume consume,
                           boolean applyTriggerAura, DamageMod damage, Optional<Identifier> convertTo, List<Effect> effects,
                           Optional<Knockback> knockback, Optional<Burst> burst, Optional<Fx> fx, Optional<Identifier> function, int ignite,
                           boolean extinguish) {
        public static final Codec<Reaction> CODEC = RecordCodecBuilder.create(i -> i.group(
            ElementRef.LIST_CODEC.fieldOf("trigger").forGetter(Reaction::trigger),
            ElementRef.LIST_CODEC.fieldOf("aura").forGetter(Reaction::aura),
            Codec.INT.optionalFieldOf("priority", 0).forGetter(Reaction::priority),
            Codec.BOOL.optionalFieldOf("exclusive", false).forGetter(Reaction::exclusive),
            Codec.INT.optionalFieldOf("min_stacks", 1).forGetter(Reaction::minStacks),
            Consume.CODEC.optionalFieldOf("consume", Consume.ALL).forGetter(Reaction::consume),
            Codec.BOOL.optionalFieldOf("apply_trigger_aura", false).forGetter(Reaction::applyTriggerAura),
            DamageMod.CODEC.optionalFieldOf("damage", DamageMod.NONE).forGetter(Reaction::damage),
            Identifier.CODEC.optionalFieldOf("convert_to").forGetter(Reaction::convertTo),
            Effect.CODEC.listOf().optionalFieldOf("effects", List.of()).forGetter(Reaction::effects),
            Knockback.CODEC.optionalFieldOf("knockback").forGetter(Reaction::knockback),
            Burst.CODEC.optionalFieldOf("burst").forGetter(Reaction::burst),
            Fx.CODEC.optionalFieldOf("fx").forGetter(Reaction::fx),
            Identifier.CODEC.optionalFieldOf("function").forGetter(Reaction::function),
            Codec.INT.optionalFieldOf("ignite", 0).forGetter(Reaction::ignite),
            Codec.BOOL.optionalFieldOf("extinguish", false).forGetter(Reaction::extinguish)
        ).apply(i, Reaction::new));
    }

    public static final DataTable<Reaction> TABLE = DataTables.register(Identifier.fromNamespaceAndPath("fundamentum", "element_reaction"),
        "fundamentum/element_reaction", Reaction.CODEC).onLoad(ElementReactions::sort);

    private record Entry(Identifier id, Reaction reaction) {}

    private static volatile List<Entry> sorted = List.of();
    private static final ElementMath.LoopGuard<Identifier, UUID> GUARD = new ElementMath.LoopGuard<>();

    public static void init() {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, ElementReactions::onIncoming);
    }

    private static void sort(DataTable<Reaction> table) {
        List<Entry> list = new ArrayList<>();
        table.entries().forEach((id, r) -> list.add(new Entry(id, r)));
        list.sort(Comparator.comparingInt((Entry e) -> -e.reaction().priority()).thenComparing(e -> e.id().toString()));
        sorted = List.copyOf(list);
    }

    /** The loaded reactions in the order they are tried. */
    public static List<Map.Entry<Identifier, Reaction>> all() {
        return sorted.stream().map(e -> Map.entry(e.id(), e.reaction())).toList();
    }

    /** How many reactions are running up the current chain (0 outside one). */
    public static int depth() {
        return GUARD.depth();
    }

    private static void onIncoming(LivingIncomingDamageEvent event) {
        LivingEntity target = event.getEntity();
        if (!(target.level() instanceof ServerLevel level) || event.getAmount() <= 0) {
            return;
        }
        DamageSource source = event.getSource();
        ElementAffinities.Result affinity = ElementAffinities.resolve(target, source);
        if (affinity.immune()) {
            event.setCanceled(true);
            return;
        }
        if (target.invulnerableTime > 10 && !source.is(DamageTypeTags.BYPASSES_COOLDOWN)) {
            event.setAmount(event.getAmount() * affinity.multiplier());
            return;
        }
        float amount = event.getAmount();
        boolean applyAura = true;
        Identifier convert = null;
        Identifier convertedBy = null;
        long now = level.getGameTime();
        for (Entry entry : sorted) {
            Reaction r = entry.reaction();
            if (!ElementRef.any(r.trigger(), source) || !GUARD.allows(entry.id(), target.getUUID(), now)) {
                continue;
            }
            ElementAuras.Aura aura = ElementAuras.find(target, r.aura());
            if (aura == null || aura.stacks() < r.minStacks()) {
                continue;
            }
            float reacted = ElementMath.reactionDamage(amount, r.damage().multiplier(), r.damage().bonus(), r.damage().perStack(), aura.stacks());
            ElementEvents.Reaction pre = NeoForge.EVENT_BUS.post(new ElementEvents.Reaction(entry.id(), r, target, source, aura, reacted, GUARD.depth()));
            if (pre.isCanceled()) {
                continue;
            }
            amount = pre.getAmount();
            GUARD.enter(entry.id(), target.getUUID(), now);
            try {
                fire(level, entry.id(), r, target, source, aura, amount);
            } finally {
                GUARD.exit();
            }
            NeoForge.EVENT_BUS.post(new ElementEvents.Reacted(entry.id(), r, target, source));
            FEvents.post(new Events.ElementReacted(target, FId.from(entry.id()), source.getEntity()));
            applyAura &= r.applyTriggerAura();
            if (r.convertTo().isPresent() && convert == null) {
                convert = r.convertTo().get();
                convertedBy = entry.id();
            }
            if (r.exclusive()) {
                break;
            }
        }
        if (convert != null) {
            Optional<Holder.Reference<DamageType>> type = level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE)
                .get(ResourceKey.create(Registries.DAMAGE_TYPE, convert));
            if (type.isPresent()) {
                event.setCanceled(true);
                DamageSource converted = new DamageSource(type.get(), source.getDirectEntity(), source.getEntity(), source.sourcePositionRaw());
                GUARD.enter(convertedBy, target.getUUID(), now);
                try {
                    target.hurtServer(level, converted, amount);
                } finally {
                    GUARD.exit();
                }
                return;
            }
            LOG.warn("Element reaction {}: convert_to {} is not a damage type", convertedBy, convert);
        }
        amount *= affinity.multiplier();
        event.setAmount(amount);
        if (applyAura && amount > 0) {
            for (Map.Entry<ElementRef, ElementAuras.AuraDef> e : ElementAuras.of(source, target).entrySet()) {
                if (amount >= e.getValue().minDamage()) {
                    ElementAuras.apply(target, e.getKey(), e.getValue(), source);
                }
            }
        }
    }

    /** Applies a reaction's results except the damage change and the conversion, which the hit itself carries. */
    private static void fire(ServerLevel level, Identifier id, Reaction r, LivingEntity target, DamageSource source, ElementAuras.Aura aura, float amount) {
        switch (r.consume()) {
            case ALL -> ElementAuras.remove(target, aura);
            case ONE -> ElementAuras.consumeOne(target, aura);
            case NONE -> { }
        }
        Entity attacker = source.getEntity();
        for (Effect effect : r.effects()) {
            LivingEntity on = effect.on() == On.ATTACKER ? (attacker instanceof LivingEntity living ? living : null) : target;
            if (on != null) {
                on.addEffect(new MobEffectInstance(effect.id(), effect.duration(), effect.amplifier()), attacker);
            }
        }
        if (r.ignite() > 0) {
            target.igniteForSeconds(r.ignite());
        }
        if (r.extinguish()) {
            target.clearFire();
        }
        r.knockback().ifPresent(kb -> {
            Vec3 from = attacker != null ? attacker.position() : source.getSourcePosition() != null ? source.getSourcePosition() : target.position();
            target.knockback(kb.strength(), from.x - target.getX(), from.z - target.getZ());
            if (kb.up() > 0) {
                target.setDeltaMovement(target.getDeltaMovement().add(0, kb.up(), 0));
            }
            target.hurtMarked = true;
        });
        r.fx().ifPresent(fx -> fx(level, id, fx, target));
        r.function().ifPresent(fn -> function(level, id, fn, target));
        r.burst().ifPresent(burst -> burst(level, id, burst, target, attacker, amount));
    }

    private static void burst(ServerLevel level, Identifier id, Burst burst, LivingEntity target, @Nullable Entity attacker, float amount) {
        Optional<Holder.Reference<DamageType>> type = level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE)
            .get(ResourceKey.create(Registries.DAMAGE_TYPE, burst.damageType()));
        if (type.isEmpty()) {
            LOG.warn("Element reaction {}: burst damage_type {} is not a damage type", id, burst.damageType());
            return;
        }
        float damage = burst.damage() + burst.scale() * amount;
        if (damage <= 0) {
            return;
        }
        for (LivingEntity near : level.getEntitiesOfClass(LivingEntity.class, target.getBoundingBox().inflate(burst.radius()))) {
            if (near == attacker || (near == target && !burst.includeTarget()) || !near.isAlive()
                || near.distanceToSqr(target) > burst.radius() * burst.radius()) {
                continue;
            }
            near.hurtServer(level, new DamageSource(type.get(), attacker, attacker, target.position()), damage);
        }
    }

    private static void fx(ServerLevel level, Identifier id, Fx fx, LivingEntity target) {
        fx.particle().ifPresent(json -> {
            try {
                ParticleOptions particle = ParticleTypes.CODEC.parse(level.registryAccess().createSerializationContext(JsonOps.INSTANCE), json).getOrThrow();
                level.sendParticles(particle, target.getX(), target.getY(0.5), target.getZ(), fx.count(), fx.spread(), fx.spread(), fx.spread(), fx.speed());
            } catch (RuntimeException e) {
                LOG.warn("Element reaction {}: bad particle {}: {}", id, json, e.getMessage());
            }
        });
        fx.sound().ifPresent(sound -> {
            SoundEvent event = BuiltInRegistries.SOUND_EVENT.getOptional(sound).orElseGet(() -> SoundEvent.createVariableRangeEvent(sound));
            level.playSound(null, target.getX(), target.getY(), target.getZ(), event, SoundSource.NEUTRAL, fx.volume(), fx.pitch());
        });
    }

    private static void function(ServerLevel level, Identifier id, Identifier fn, LivingEntity target) {
        MinecraftServer server = level.getServer();
        server.getFunctions().get(fn).ifPresentOrElse(
            f -> server.getFunctions().execute(f, target.createCommandSourceStackForNameResolution(level)
                .withPermission(LevelBasedPermissionSet.GAMEMASTER).withSuppressedOutput()),
            () -> LOG.warn("Element reaction {}: function {} not found", id, fn));
    }

    private ElementReactions() {}
}
