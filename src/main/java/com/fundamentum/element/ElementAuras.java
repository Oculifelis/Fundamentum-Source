package com.fundamentum.element;

import com.fundamentum.damage.Elements;
import com.fundamentum.damage.SharedDamage;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;
import org.jspecify.annotations.Nullable;

/**
 * Auras: the elemental status a hit leaves on a creature, which later hits of another element react with (see
 * {@link ElementReactions}). Which hits leave one is data: the data map {@code fundamentum:element_aura} on damage types
 * ({@code data/<ns>/data_maps/damage_type/element_aura.json}, keys are damage types or {@code #tags}):
 * <pre>{"values": {"#fundamentum:element/fire": {"aura": "#fundamentum:element/fire", "strength": 1, "duration": 120,
 *   "decay": 0, "max_stacks": 3, "min_damage": 0}}}</pre>
 * {@code aura} names the aura (default: the damage type itself), so every fire damage type can leave one shared fire
 * aura. Each further hit adds a stack (up to {@code max_stacks}) and {@code strength}, and refreshes {@code duration}
 * (ticks); {@code decay} is strength lost per second; the aura ends when its time runs out or its strength reaches 0.
 * A hit that is of one of Fundamentum's {@link Elements} through its weapon (a fire sword's plain hit) leaves the aura of
 * that element's shared damage type ({@code fundamentum:fire}). Auras are an attachment saved with the creature and
 * synced to clients ({@link #get}); they are a server-side state otherwise.
 */
public final class ElementAuras {
    /** One entry of the aura data map. */
    public record AuraDef(Optional<ElementRef> aura, float strength, int duration, float decay, int maxStacks, float minDamage) {
        public static final Codec<AuraDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            ElementRef.CODEC.optionalFieldOf("aura").forGetter(AuraDef::aura),
            Codec.FLOAT.optionalFieldOf("strength", 1f).forGetter(AuraDef::strength),
            Codec.INT.optionalFieldOf("duration", 100).forGetter(AuraDef::duration),
            Codec.FLOAT.optionalFieldOf("decay", 0f).forGetter(AuraDef::decay),
            Codec.INT.optionalFieldOf("max_stacks", 1).forGetter(AuraDef::maxStacks),
            Codec.FLOAT.optionalFieldOf("min_damage", 0f).forGetter(AuraDef::minDamage)
        ).apply(i, AuraDef::new));
    }

    /** An aura on a creature. Times are game ticks; {@link #strength(long)} is its strength now. */
    public record Aura(String key, float strength, int stacks, long since, long expires, float decay) {
        static final Codec<Aura> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("key").forGetter(Aura::key),
            Codec.FLOAT.fieldOf("strength").forGetter(Aura::strength),
            Codec.INT.fieldOf("stacks").forGetter(Aura::stacks),
            Codec.LONG.fieldOf("since").forGetter(Aura::since),
            Codec.LONG.fieldOf("expires").forGetter(Aura::expires),
            Codec.FLOAT.fieldOf("decay").forGetter(Aura::decay)
        ).apply(i, Aura::new));
        static final StreamCodec<io.netty.buffer.ByteBuf, Aura> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, Aura::key, ByteBufCodecs.FLOAT, Aura::strength, ByteBufCodecs.VAR_INT, Aura::stacks,
            ByteBufCodecs.VAR_LONG, Aura::since, ByteBufCodecs.VAR_LONG, Aura::expires, ByteBufCodecs.FLOAT, Aura::decay, Aura::new);

        public ElementRef element() {
            return ElementRef.parse(this.key);
        }

        public float strength(long now) {
            return ElementMath.decayed(this.strength, this.decay, now - this.since);
        }

        public boolean active(long now) {
            return now < this.expires && strength(now) > 0;
        }

        public int ticksLeft(long now) {
            return (int) Math.max(0, this.expires - now);
        }
    }

    /** All auras on one creature. Never mutated: changes set a new one. */
    public record Auras(List<Aura> list) {
        public static final Auras EMPTY = new Auras(List.of());
        static final Codec<Auras> CODEC = Aura.CODEC.listOf().xmap(Auras::new, Auras::list);
        static final StreamCodec<io.netty.buffer.ByteBuf, Auras> STREAM_CODEC = Aura.STREAM_CODEC.apply(ByteBufCodecs.list()).map(Auras::new, Auras::list);

        public boolean isEmpty() {
            return this.list.isEmpty();
        }
    }

    public static final DataMapType<DamageType, AuraDef> AURA = DataMapType.builder(
        Identifier.fromNamespaceAndPath("fundamentum", "element_aura"), Registries.DAMAGE_TYPE, AuraDef.CODEC).build();

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, "fundamentum");

    public static final Supplier<AttachmentType<Auras>> AURAS = ATTACHMENTS.register("element_auras", () -> AttachmentType.builder(() -> Auras.EMPTY)
        .serialize(Auras.CODEC.fieldOf("auras"), auras -> !auras.isEmpty())
        .sync(Auras.STREAM_CODEC)
        .build());

    public static void init(IEventBus modBus) {
        ATTACHMENTS.register(modBus);
        modBus.addListener((RegisterDataMapTypesEvent event) -> event.register(AURA));
        NeoForge.EVENT_BUS.addListener(ElementAuras::tick);
    }

    /** The active auras on a creature (both sides; the client gets them synced). */
    public static List<Aura> get(LivingEntity entity) {
        if (!entity.hasData(AURAS)) {
            return List.of();
        }
        long now = entity.level().getGameTime();
        return entity.getData(AURAS).list().stream().filter(a -> a.active(now)).toList();
    }

    /** The first active aura on a creature that counts as {@code element} (see {@link ElementRef#covers}), or null. */
    public static @Nullable Aura find(LivingEntity entity, ElementRef element) {
        for (Aura aura : get(entity)) {
            if (element.covers(aura.element(), entity.registryAccess())) {
                return aura;
            }
        }
        return null;
    }

    public static @Nullable Aura find(LivingEntity entity, List<ElementRef> elements) {
        for (ElementRef element : elements) {
            Aura aura = find(entity, element);
            if (aura != null) {
                return aura;
            }
        }
        return null;
    }

    /** The aura definitions a hit brings, keyed by the aura they leave (one per aura). */
    public static Map<ElementRef, AuraDef> of(DamageSource source, LivingEntity target) {
        Map<ElementRef, AuraDef> out = new LinkedHashMap<>();
        add(out, source.typeHolder());
        for (Elements.Element element : Elements.of(source)) {
            target.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).get(SharedDamage.of(element)).ifPresent(type -> add(out, type));
        }
        return out;
    }

    private static void add(Map<ElementRef, AuraDef> out, Holder<DamageType> type) {
        AuraDef def = type.getData(AURA);
        if (def != null && type.unwrapKey().isPresent()) {
            out.putIfAbsent(def.aura().orElse(ElementRef.of(type.unwrapKey().get())), def);
        }
    }

    /**
     * Puts (or stacks) an aura on a creature, server side; it syncs. {@link ElementEvents.AuraApply} may change or
     * cancel it first. Returns the aura now on the creature, or null if cancelled.
     */
    public static @Nullable Aura apply(LivingEntity entity, ElementRef key, AuraDef def, @Nullable DamageSource source) {
        ElementEvents.AuraApply event = NeoForge.EVENT_BUS.post(new ElementEvents.AuraApply(entity, key, def, source));
        if (event.isCanceled()) {
            return null;
        }
        def = event.getDef();
        long now = entity.level().getGameTime();
        List<Aura> list = new ArrayList<>(entity.getData(AURAS).list());
        list.removeIf(a -> !a.active(now));
        String text = key.toString();
        Aura old = null;
        for (Aura a : list) {
            if (a.key().equals(text)) {
                old = a;
            }
        }
        Aura aura = old == null
            ? new Aura(text, def.strength(), 1, now, now + def.duration(), def.decay())
            : new Aura(text, ElementMath.stackedStrength(old.strength(now), def.strength(), def.maxStacks()),
                ElementMath.stacked(old.stacks(), def.maxStacks()), now, now + def.duration(), def.decay());
        if (old != null) {
            list.set(list.indexOf(old), aura);
        } else {
            list.add(aura);
        }
        entity.setData(AURAS, new Auras(List.copyOf(list)));
        return aura;
    }

    /** Takes one stack off an aura (removing it at 0), server side. */
    public static void consumeOne(LivingEntity entity, Aura aura) {
        long now = entity.level().getGameTime();
        if (aura.stacks() <= 1) {
            remove(entity, aura);
            return;
        }
        replace(entity, aura, new Aura(aura.key(), ElementMath.afterConsumingOne(aura.strength(now), aura.stacks()), aura.stacks() - 1, now,
            aura.expires(), aura.decay()));
    }

    public static void remove(LivingEntity entity, Aura aura) {
        replace(entity, aura, null);
    }

    public static void clear(LivingEntity entity) {
        if (entity.hasData(AURAS)) {
            entity.setData(AURAS, Auras.EMPTY);
        }
    }

    private static void replace(LivingEntity entity, Aura aura, @Nullable Aura with) {
        List<Aura> list = new ArrayList<>(entity.getData(AURAS).list());
        int i = list.indexOf(aura);
        if (i < 0) {
            return;
        }
        if (with == null) {
            list.remove(i);
        } else {
            list.set(i, with);
        }
        entity.setData(AURAS, new Auras(List.copyOf(list)));
    }

    /** Once a second, drops auras that ran out, so the saved and synced list stays short. */
    private static void tick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof LivingEntity entity) || entity.level().isClientSide() || entity.tickCount % 20 != 0 || !entity.hasData(AURAS)) {
            return;
        }
        List<Aura> list = entity.getData(AURAS).list();
        if (list.isEmpty()) {
            return;
        }
        long now = entity.level().getGameTime();
        List<Aura> kept = list.stream().filter(a -> a.active(now)).toList();
        if (kept.size() != list.size()) {
            entity.setData(AURAS, new Auras(kept));
        }
    }

    private ElementAuras() {}
}
