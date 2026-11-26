package com.fundamentum.enchant;

import com.fundamentum.thrown.ThrownEnchantment;
import com.fundamentum.whip.Whips;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentCategory;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.ItemAttributeModifierEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Enchantments for Fundamentum weapons. On 1.20.1 enchantments are code, so the nine weapon enchantments are
 * {@link ThrownEnchantment}s with the weight, levels and costs of their 1.21 files, one category per
 * {@code #fundamentum:enchantable/...} item tag. Their {@code fundamentum:weapon_effects} rules still come from the same
 * files, {@code data/fundamentum/enchantment/<name>.json}, read on every data reload and keyed by enchantment id; rules
 * run at a weapon hook (see {@link WeaponHook} for the hooks and {@link WeaponRules} for the format). Weapon code calls
 * {@link #fire}; mods may add Java listeners for a hook with {@link #listen}.
 */
public final class FEnchantments {
    private FEnchantments() {}

    private static final Map<String, List<Consumer<WeaponHook>>> LISTENERS = new ConcurrentHashMap<>();
    /** The weapon_effects rules of every enchantment file, by enchantment id; replaced on each data reload. */
    private static volatile Map<ResourceLocation, List<WeaponRules.Rule>> RULES = Map.of();

    public static final TagKey<Item> ENCHANTABLE_SHIELD = itemTag("enchantable/shield");
    public static final TagKey<Item> ENCHANTABLE_FLAIL = itemTag("enchantable/flail");
    public static final TagKey<Item> ENCHANTABLE_SCYTHE = itemTag("enchantable/scythe");
    public static final TagKey<Item> ENCHANTABLE_GAUNTLET = itemTag("enchantable/gauntlet");
    public static final TagKey<Item> ENCHANTABLE_WHIP = itemTag("enchantable/whip");

    /** The enchantment categories of the five tags (Forge's extensible enum; they read the item tags). */
    public static final EnchantmentCategory CATEGORY_SHIELD = category("FUNDAMENTUM_SHIELD", ENCHANTABLE_SHIELD);
    public static final EnchantmentCategory CATEGORY_FLAIL = category("FUNDAMENTUM_FLAIL", ENCHANTABLE_FLAIL);
    public static final EnchantmentCategory CATEGORY_SCYTHE = category("FUNDAMENTUM_SCYTHE", ENCHANTABLE_SCYTHE);
    public static final EnchantmentCategory CATEGORY_GAUNTLET = category("FUNDAMENTUM_GAUNTLET", ENCHANTABLE_GAUNTLET);
    public static final EnchantmentCategory CATEGORY_WHIP = category("FUNDAMENTUM_WHIP", ENCHANTABLE_WHIP);

    private static final DeferredRegister<Enchantment> ENCHANTMENTS = DeferredRegister.create(ForgeRegistries.ENCHANTMENTS, "fundamentum");

    public static final RegistryObject<Enchantment> REBOUND = enchantment("rebound", Enchantment.Rarity.UNCOMMON, CATEGORY_SHIELD, 3);
    public static final RegistryObject<Enchantment> BULWARK = enchantment("bulwark", Enchantment.Rarity.UNCOMMON, CATEGORY_SHIELD, 3);
    public static final RegistryObject<Enchantment> SPIKED = enchantment("spiked", Enchantment.Rarity.UNCOMMON, CATEGORY_SHIELD, 3);
    public static final RegistryObject<Enchantment> MOMENTUM = enchantment("momentum", Enchantment.Rarity.UNCOMMON, CATEGORY_FLAIL, 3);
    public static final RegistryObject<Enchantment> HEAVY_HEAD = enchantment("heavy_head", Enchantment.Rarity.UNCOMMON, CATEGORY_FLAIL, 2);
    public static final RegistryObject<Enchantment> REAPING = enchantment("reaping", Enchantment.Rarity.UNCOMMON, CATEGORY_SCYTHE, 3);
    public static final RegistryObject<Enchantment> SOUL_SIPHON = enchantment("soul_siphon", Enchantment.Rarity.RARE, CATEGORY_SCYTHE, 2);
    public static final RegistryObject<Enchantment> FLURRY = enchantment("flurry", Enchantment.Rarity.UNCOMMON, CATEGORY_GAUNTLET, 3);
    public static final RegistryObject<Enchantment> LASH = enchantment("lash", Enchantment.Rarity.UNCOMMON, CATEGORY_WHIP, 3);

    private static final UUID[] LASH_IDS = {UUID.fromString("5e0d2b7a-3c41-4f8e-9a16-1b2c3d4e5f11"), UUID.fromString("5e0d2b7a-3c41-4f8e-9a16-1b2c3d4e5f12")};

    public static void init(IEventBus modBus) {
        ENCHANTMENTS.register(modBus);
        MinecraftForge.EVENT_BUS.addListener(FEnchantments::onDeath);
        MinecraftForge.EVENT_BUS.addListener(FEnchantments::lashModifier);
        MinecraftForge.EVENT_BUS.addListener((AddReloadListenerEvent e) -> e.addListener(new Loader()));
    }

    /** Runs {@code listener} every time {@code hook} fires, after the enchantments on the weapon. */
    public static void listen(String hook, Consumer<WeaponHook> listener) {
        LISTENERS.computeIfAbsent(hook, h -> new CopyOnWriteArrayList<>()).add(listener);
    }

    /** Lets the weapon's enchantments and the listeners change the hook's numbers, then returns it. */
    public static WeaponHook fire(WeaponHook ctx) {
        if (!ctx.stack.isEmpty() && ctx.stack.isEnchanted()) {
            for (Map.Entry<Enchantment, Integer> e : EnchantmentHelper.getEnchantments(ctx.stack).entrySet()) {
                apply(ctx, rules(e.getKey()), e.getValue());
            }
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
        Enchantment enchantment = ForgeRegistries.ENCHANTMENTS.getValue(id);
        return enchantment == null || !ForgeRegistries.ENCHANTMENTS.containsKey(id) ? 0 : EnchantmentHelper.getItemEnchantmentLevel(enchantment, stack);
    }

    /** True when any enchantment on {@code stack} has a rule for {@code hook}. */
    public static boolean has(ItemStack stack, String hook) {
        if (!stack.isEnchanted()) {
            return false;
        }
        for (Enchantment enchantment : EnchantmentHelper.getEnchantments(stack).keySet()) {
            for (WeaponRules.Rule r : rules(enchantment)) {
                if (r.hook().equals(hook)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<WeaponRules.Rule> rules(Enchantment enchantment) {
        ResourceLocation id = ForgeRegistries.ENCHANTMENTS.getKey(enchantment);
        return id == null ? List.of() : RULES.getOrDefault(id, List.of());
    }

    private static void apply(WeaponHook ctx, List<WeaponRules.Rule> rules, int level) {
        for (WeaponRules.Rule rule : rules) {
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
                    who.setSecondsOnFire((int) Math.round(value));
                }
            }
            case "mob_effect" -> {
                ResourceLocation id = ResourceLocation.tryParse(e.id());
                MobEffect effect = id == null ? null : BuiltInRegistries.MOB_EFFECT.get(id);
                if (effect != null && who instanceof LivingEntity living) {
                    living.addEffect(new MobEffectInstance(effect, (int) e.duration().at(level), (int) e.amplifier().at(level)), ctx.user);
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

    /** Lash's longer cord on a held whip (1.21 lists the modifier in lash.json, slot "hand"). */
    private static void lashModifier(ItemAttributeModifierEvent event) {
        int hand = event.getSlotType() == EquipmentSlot.MAINHAND ? 0 : event.getSlotType() == EquipmentSlot.OFFHAND ? 1 : -1;
        ItemStack stack = event.getItemStack();
        if (hand < 0 || stack.isEmpty() || !stack.isEnchanted()) {
            return;
        }
        int lash = EnchantmentHelper.getItemEnchantmentLevel(LASH.get(), stack);
        if (lash > 0) {
            event.addModifier(Whips.LENGTH.get(), new AttributeModifier(LASH_IDS[hand], "fundamentum:enchantment.lash",
                0.15 * lash, AttributeModifier.Operation.MULTIPLY_BASE));
        }
    }

    /** Every hook name the shipped weapons fire, for self tests and docs. */
    public static List<String> hooks() {
        return new ArrayList<>(List.of("on_sweep", "on_scythe_hit", "on_flail_hit", "on_block", "on_staggered", "on_bash",
            "on_combo", "on_thrown_hit", "on_kill"));
    }

    private static TagKey<Item> itemTag(String path) {
        return TagKey.create(Registries.ITEM, new ResourceLocation("fundamentum", path));
    }

    private static EnchantmentCategory category(String name, TagKey<Item> tag) {
        return EnchantmentCategory.create(name, item -> item.builtInRegistryHolder().is(tag));
    }

    /** Weight, levels and costs as in the 1.21 file: costs 8 + 10 per level up to 58 + 10 per level. */
    private static RegistryObject<Enchantment> enchantment(String name, Enchantment.Rarity rarity, EnchantmentCategory category, int maxLevel) {
        return ENCHANTMENTS.register(name, () -> new ThrownEnchantment(rarity, category, maxLevel, 8, 10, 58, 10));
    }

    /** Reads {@code effects."fundamentum:weapon_effects"} from every {@code data/<ns>/enchantment/<name>.json}. */
    private static final class Loader extends SimpleJsonResourceReloadListener {
        Loader() {
            super(new com.google.gson.Gson(), "enchantment");
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
            Map<ResourceLocation, List<WeaponRules.Rule>> out = new ConcurrentHashMap<>();
            files.forEach((id, json) -> {
                if (!json.isJsonObject()) {
                    return;
                }
                JsonObject root = json.getAsJsonObject();
                if (!root.has("effects") || !root.get("effects").isJsonObject()) {
                    return;
                }
                JsonElement rules = root.getAsJsonObject("effects").get("fundamentum:weapon_effects");
                if (rules == null) {
                    return;
                }
                try {
                    out.put(id, WeaponRules.read(rules));
                } catch (RuntimeException ex) {
                    org.slf4j.LoggerFactory.getLogger("Fundamentum").error("Bad weapon_effects in enchantment {}: {}", id, ex.getMessage());
                }
            });
            RULES = Map.copyOf(out);
        }
    }
}
