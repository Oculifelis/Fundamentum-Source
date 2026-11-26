package com.fundamentum.enchant;

import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Enchantments for Fundamentum weapons, as plain enchantment JSON files. Besides the vanilla effect components, an
 * enchantment may carry {@code fundamentum:weapon_effects}: rules that run at a weapon hook (see {@link WeaponHook} for
 * the hooks and {@link WeaponRules} for the format). Weapon code calls {@link #fire}; mods may add Java listeners for a
 * hook with {@link #listen}. The same files load on every game version.
 */
public final class FEnchantments {
    private FEnchantments() {}

    private static final DeferredRegister<DataComponentType<?>> EFFECT_TYPES =
        DeferredRegister.create(Registries.ENCHANTMENT_EFFECT_COMPONENT_TYPE, "fundamentum");

    /** The weapon_effects enchantment component: one rule or a list of rules. */
    public static final Supplier<DataComponentType<JsonElement>> WEAPON_EFFECTS = EFFECT_TYPES.register("weapon_effects",
        () -> DataComponentType.<JsonElement>builder().persistent(ExtraCodecs.JSON).build());

    private static final Map<String, List<Consumer<WeaponHook>>> LISTENERS = new ConcurrentHashMap<>();
    private static final Map<Enchantment, List<WeaponRules.Rule>> PARSED = Collections.synchronizedMap(new WeakHashMap<>());

    public static void init(IEventBus modBus) {
        EFFECT_TYPES.register(modBus);
        NeoForge.EVENT_BUS.addListener(FEnchantments::onDeath);
    }

    /** Runs {@code listener} every time {@code hook} fires, after the enchantments on the weapon. */
    public static void listen(String hook, Consumer<WeaponHook> listener) {
        LISTENERS.computeIfAbsent(hook, h -> new CopyOnWriteArrayList<>()).add(listener);
    }

    /** Lets the weapon's enchantments and the listeners change the hook's numbers, then returns it. */
    public static WeaponHook fire(WeaponHook ctx) {
        if (!ctx.stack.isEmpty()) {
            EnchantmentHelper.runIterationOnItem(ctx.stack, (holder, level) -> apply(ctx, holder, level));
        }
        List<Consumer<WeaponHook>> listeners = LISTENERS.get(ctx.hook);
        if (listeners != null) {
            for (Consumer<WeaponHook> l : listeners) {
                l.accept(ctx);
            }
        }
        return ctx;
    }

    /** The level of the enchantment {@code id} on {@code stack}, or 0. */
    public static int level(ItemStack stack, ResourceLocation id) {
        int[] out = {0};
        EnchantmentHelper.runIterationOnItem(stack, (holder, level) -> {
            if (holder.is(ResourceKey.create(Registries.ENCHANTMENT, id))) {
                out[0] = level;
            }
        });
        return out[0];
    }

    /** True when any enchantment on {@code stack} has a rule for {@code hook}. */
    public static boolean has(ItemStack stack, String hook) {
        boolean[] out = {false};
        EnchantmentHelper.runIterationOnItem(stack, (holder, level) -> {
            for (WeaponRules.Rule r : rules(holder.value())) {
                out[0] |= r.hook().equals(hook);
            }
        });
        return out[0];
    }

    private static List<WeaponRules.Rule> rules(Enchantment enchantment) {
        JsonElement json = enchantment.effects().get(WEAPON_EFFECTS.get());
        if (json == null) {
            return List.of();
        }
        return PARSED.computeIfAbsent(enchantment, e -> WeaponRules.read(json));
    }

    private static void apply(WeaponHook ctx, Holder<Enchantment> holder, int level) {
        for (WeaponRules.Rule rule : rules(holder.value())) {
            if (!rule.hook().equals(ctx.hook)) {
                continue;
            }
            double chance = rule.chance().at(level);
            if (chance < 1 && ctx.level.getRandom().nextDouble() >= chance) {
                continue;
            }
            for (WeaponRules.Effect e : rule.effects()) {
                effect(ctx, e, level);
            }
        }
    }

    private static void effect(WeaponHook ctx, WeaponRules.Effect e, int level) {
        double value = e.value().at(level);
        Entity who = "user".equals(e.to()) ? ctx.user : ctx.target;
        switch (e.type()) {
            case "modify" -> ctx.field(e.field(), WeaponRules.modify(ctx.field(e.field()), e.op(), value, ctx.charge, ctx.count));
            case "heal" -> {
                LivingEntity healed = "target".equals(e.to()) && ctx.target instanceof LivingEntity t ? t : ctx.user;
                healed.heal((float) value);
            }
            case "ignite" -> {
                if (who != null) {
                    who.igniteForSeconds((float) value);
                }
            }
            case "mob_effect" -> {
                ResourceLocation id = ResourceLocation.tryParse(e.id());
                if (id != null && who instanceof LivingEntity living) {
                    BuiltInRegistries.MOB_EFFECT.getHolder(id).ifPresent(effect -> living.addEffect(
                        new MobEffectInstance(effect, (int) e.duration().at(level), (int) e.amplifier().at(level)), ctx.user));
                }
            }
            case "particles" -> {
                ResourceLocation id = ResourceLocation.tryParse(e.id());
                Entity at = who == null ? ctx.user : who;
                if (id != null && BuiltInRegistries.PARTICLE_TYPE.get(id) instanceof SimpleParticleType particle) {
                    spawn(ctx.level, particle, at, (int) Math.max(1, value));
                }
            }
            default -> { }
        }
    }

    private static void spawn(ServerLevel level, ParticleOptions particle, Entity at, int count) {
        level.sendParticles(particle, at.getX(), at.getY() + at.getBbHeight() * 0.5, at.getZ(), count,
            at.getBbWidth() * 0.4, at.getBbHeight() * 0.3, at.getBbWidth() * 0.4, 0.05);
    }

    /** Fires on_kill for the killer's main hand. */
    private static void onDeath(LivingDeathEvent event) {
        if (!(event.getSource().getEntity() instanceof LivingEntity killer) || !(killer.level() instanceof ServerLevel level)) {
            return;
        }
        ItemStack hand = killer.getMainHandItem();
        if (!hand.isEmpty()) {
            fire(new WeaponHook("on_kill", level, killer, hand, event.getEntity()));
        }
    }

    /** Every hook name the shipped weapons fire, for self tests and docs. */
    public static List<String> hooks() {
        return new ArrayList<>(List.of("on_sweep", "on_scythe_hit", "on_flail_hit", "on_block", "on_staggered", "on_bash",
            "on_combo", "on_thrown_hit", "on_kill"));
    }
}
