package com.fundamentum.sickness;

import com.fundamentum.attribute.StatAttributes;
import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.fundamentum.item.ItemMatcher;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Terraria-style use sickness: using a matching item gives an effect, and while that effect lasts the item (and every
 * other item of the same rule's effect) cannot be used. Fundamentum assigns no items; mods and datapacks do, in
 * {@code data/<namespace>/fundamentum/sickness/<name>.json}:
 * <pre>{"items": ["#mymod:healing_potions", "mymod:elixir"], "effect": "fundamentum:potion_sickness", "duration": 1200,
 *  "trigger": "finish"}</pre>
 * "effect" is any mob effect (default {@code fundamentum:potion_sickness}, the shared one; a mod may point a rule at its
 * own, e.g. a mana sickness). "duration" is in ticks and is scaled by the user's {@code fundamentum:sickness_duration}
 * attribute. "trigger" is "finish" (after drinking or eating, the default) or "use" (on right click, for instant items).
 * Mods with their own use logic call {@link #isBlocked} and {@link #trigger}.
 */
public final class Sickness {
    private static final DeferredRegister<MobEffect> EFFECTS = DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, "fundamentum");
    /** The shared sickness for healing items. No item uses it until a mod or datapack says so. */
    public static final RegistryObject<MobEffect> POTION_SICKNESS = EFFECTS.register("potion_sickness", () -> new SicknessEffect(0x7A9A3A));

    public record Rule(ItemMatcher items, Supplier<MobEffect> effect, int duration, boolean onUse) {
        static Rule fromJson(JsonObject json) {
            Supplier<MobEffect> effect = POTION_SICKNESS;
            if (json.has("effect")) {
                ResourceLocation id = new ResourceLocation(json.get("effect").getAsString());
                MobEffect found = ForgeRegistries.MOB_EFFECTS.getValue(id);
                if (found == null || !ForgeRegistries.MOB_EFFECTS.containsKey(id)) {
                    throw new IllegalArgumentException("unknown effect " + id);
                }
                effect = () -> found;
            }
            String trigger = json.has("trigger") ? json.get("trigger").getAsString() : "finish";
            if (!trigger.equals("finish") && !trigger.equals("use")) {
                throw new IllegalArgumentException("trigger must be \"finish\" or \"use\", not " + trigger);
            }
            return new Rule(ItemMatcher.parse(json.get("items")), effect, json.get("duration").getAsInt(), trigger.equals("use"));
        }
    }

    public static final DataTable<Rule> RULES = DataTables
        .register(new ResourceLocation("fundamentum", "sickness"), "fundamentum/sickness", Rule::fromJson)
        .synced()
        .onLoad(t -> clearCache());

    /** Rules per item, filled on first use and cleared when rules or tags reload. */
    private static final Map<Item, List<Rule>> CACHE = new ConcurrentHashMap<>();

    public static List<Rule> rules(ItemStack stack) {
        if (stack.isEmpty()) {
            return List.of();
        }
        return CACHE.computeIfAbsent(stack.getItem(), item -> {
            List<Rule> out = new ArrayList<>();
            for (Rule rule : RULES.entries().values()) {
                if (rule.items().test(item)) {
                    out.add(rule);
                }
            }
            return out.isEmpty() ? List.of() : List.copyOf(out);
        });
    }

    /** The active sickness that blocks this item, or null. */
    public static MobEffect blockedBy(LivingEntity user, ItemStack stack) {
        for (Rule rule : rules(stack)) {
            if (user.hasEffect(rule.effect().get())) {
                return rule.effect().get();
            }
        }
        return null;
    }

    public static boolean isBlocked(LivingEntity user, ItemStack stack) {
        return blockedBy(user, stack) != null;
    }

    /** Applies every rule of this item (server side), scaled by the user's sickness_duration. */
    public static void trigger(LivingEntity user, ItemStack stack) {
        apply(user, stack, null);
    }

    private static void apply(LivingEntity user, ItemStack stack, Boolean onUse) {
        if (user.level().isClientSide()) {
            return;
        }
        var attribute = StatAttributes.SICKNESS_DURATION.get();
        double scale = user.getAttributes().hasAttribute(attribute) ? user.getAttributeValue(attribute) : 1;
        for (Rule rule : rules(stack)) {
            int ticks = (int) Math.round(rule.duration() * scale);
            if ((onUse == null || rule.onUse() == onUse) && ticks > 0) {
                user.addEffect(new MobEffectInstance(rule.effect().get(), ticks, 0, false, true, true));
            }
        }
    }

    public static void init(IEventBus modBus) {
        EFFECTS.register(modBus);
        MinecraftForge.EVENT_BUS.addListener(Sickness::onRightClick);
        MinecraftForge.EVENT_BUS.addListener(Sickness::onUseStart);
        MinecraftForge.EVENT_BUS.addListener(Sickness::onUseFinish);
        MinecraftForge.EVENT_BUS.addListener((TagsUpdatedEvent e) -> clearCache());
    }

    private static void clearCache() {
        CACHE.clear();
    }

    private static void onRightClick(PlayerInteractEvent.RightClickItem event) {
        if (rules(event.getItemStack()).isEmpty()) {
            return;
        }
        if (block(event.getEntity(), event.getItemStack())) {
            event.setCanceled(true);
        } else {
            apply(event.getEntity(), event.getItemStack(), true);
        }
    }

    private static void onUseStart(LivingEntityUseItemEvent.Start event) {
        if (!rules(event.getItem()).isEmpty() && block(event.getEntity(), event.getItem())) {
            event.setCanceled(true);
        }
    }

    private static void onUseFinish(LivingEntityUseItemEvent.Finish event) {
        if (!rules(event.getItem()).isEmpty()) {
            apply(event.getEntity(), event.getItem(), false);
        }
    }

    /** True when the item is blocked; tells a player why (once, from the server). */
    private static boolean block(LivingEntity user, ItemStack stack) {
        MobEffect by = blockedBy(user, stack);
        if (by != null && user instanceof Player player && !player.level().isClientSide()) {
            player.displayClientMessage(Component.translatable("fundamentum.sickness.blocked", by.getDisplayName()), true);
        }
        return by != null;
    }

    private static final class SicknessEffect extends MobEffect {
        SicknessEffect(int color) {
            super(MobEffectCategory.HARMFUL, color);
        }
    }

    private Sickness() {}
}
