package com.fundamentum.food;

import com.fundamentum.event.FEvents;
import com.fundamentum.port.FCommands;
import com.fundamentum.port.FFood;
import com.fundamentum.port.FId;
import com.fundamentum.port.FVanilla;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Food hooks, the same calls on every version. When a player finishes eating or drinking, Fundamentum works out the
 * stack's {@link FoodValues}, runs every {@link #modify modifier} over them (lowest priority first), posts the
 * cancellable {@link FoodEaten} event, lets the eat happen, corrects hunger, saturation and effects to the modified
 * values, then posts {@link FoodConsumed}. With no modifier or listener the eat is left exactly as vanilla does it.
 * Food groups are in {@link FoodGroups}. Nothing here stores player data.
 */
public final class Food {
    private Food() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");

    public static final FId FOOD_EATEN = FId.of("fundamentum", "food_eaten");
    public static final FId FOOD_CONSUMED = FId.of("fundamentum", "food_consumed");

    /** Changes what a food gives before it applies. Return {@code food} itself to leave it alone. */
    @FunctionalInterface
    public interface Modifier {
        FoodValues modify(ServerPlayer player, ItemStack stack, FoodValues food);
    }

    private record Entry(int priority, long order, Modifier modifier) { }

    /** What the hook carries from before the eat to after it. */
    private record Pending(ItemStack stack, FoodValues base, FoodValues food, int foodLevel, float saturation, List<MobEffectInstance> effects) { }

    /** Returned by {@link #beforeEat} when the eat was cancelled. */
    public static final Object CANCELLED = new Object();

    private static final List<Entry> MODIFIERS = new CopyOnWriteArrayList<>();
    private static long added;
    private static boolean initialised;

    /** Called once while Fundamentum loads. */
    public static void init() {
        if (initialised) return;
        initialised = true;
        FEvents.register(FOOD_EATEN, FoodEaten.class, true);
        FEvents.register(FOOD_CONSUMED, FoodConsumed.class, false);
        FoodGroups.init();
        FCommands.listen(d -> d.register(Commands.literal("fundamentum").requires(FCommands.op())
            .then(Commands.literal("food").executes(Food::foodCommand))));
    }

    /** Adds a modifier at priority 0. */
    public static void modify(Modifier modifier) {
        modify(0, modifier);
    }

    /** Adds a modifier; lower priorities run first, equal ones in the order they were added. */
    public static synchronized void modify(int priority, Modifier modifier) {
        List<Entry> l = new ArrayList<>(MODIFIERS);
        l.add(new Entry(priority, added++, modifier));
        l.sort(Comparator.comparingInt(Entry::priority).thenComparingLong(Entry::order));
        MODIFIERS.clear();
        MODIFIERS.addAll(l);
    }

    public static synchronized boolean removeModifier(Modifier modifier) {
        return MODIFIERS.removeIf(e -> e.modifier == modifier);
    }

    /** True when the stack is food or a drink. */
    public static boolean edible(LivingEntity entity, ItemStack stack) {
        return FFood.base(entity, stack) != null;
    }

    public static boolean drink(ItemStack stack) {
        return FFood.drink(stack);
    }

    /** The stack's own values (before modifiers), or null when it is neither food nor a drink. */
    public static FoodValues base(LivingEntity entity, ItemStack stack) {
        return FFood.base(entity, stack);
    }

    /** What the stack would give this player now, after every modifier; null when it is neither food nor a drink. */
    public static FoodValues values(ServerPlayer player, ItemStack stack) {
        FoodValues base = FFood.base(player, stack);
        return base == null ? null : modified(player, stack, base);
    }

    private static FoodValues modified(ServerPlayer player, ItemStack stack, FoodValues food) {
        for (Entry e : MODIFIERS) {
            try {
                FoodValues next = e.modifier.modify(player, stack, food);
                if (next != null) food = next.drink() == food.drink() ? next : new FoodValues(next.nutrition(), next.saturation(), food.drink(), next.effects());
            } catch (RuntimeException ex) {
                LOG.error("Food modifier {} failed", e.modifier, ex);
            }
        }
        return food;
    }

    // ---- the hook (called from the mixin on LivingEntity.completeUsingItem)

    /**
     * Before the entity finishes using its item: null when there is nothing to do, {@link #CANCELLED} when a listener
     * cancelled the eat (the caller then skips it), else a value to hand to {@link #afterEat}.
     */
    public static Object beforeEat(LivingEntity entity) {
        if (!(entity instanceof ServerPlayer p) || !p.isUsingItem()) return null;
        ItemStack use = p.getUseItem();
        if (use.isEmpty() || use != p.getItemInHand(p.getUsedItemHand())) return null;
        FoodValues base = FFood.base(p, use);
        if (base == null) return null;
        FoodValues food = modified(p, use, base);
        ItemStack copy = use.copy();
        if (FEvents.post(new FoodEaten(p, copy, food.drink(), food.nutrition(), food.saturation(), food))) {
            p.stopUsingItem();
            FFood.resync(p);
            return CANCELLED;
        }
        FoodData fd = p.getFoodData();
        List<MobEffectInstance> before = base.effects().equals(food.effects()) ? List.of() : FFood.active(p);
        return new Pending(copy, base, food, fd.getFoodLevel(), fd.getSaturationLevel(), before);
    }

    /** After the entity finished using its item; {@code pending} is what {@link #beforeEat} returned. */
    public static void afterEat(LivingEntity entity, Object pending) {
        if (!(pending instanceof Pending pd) || !(entity instanceof ServerPlayer p)) return;
        FoodData fd = p.getFoodData();
        if (pd.food.nutrition() != pd.base.nutrition() || pd.food.saturation() != pd.base.saturation()) {
            int fb = clamp(pd.foodLevel + pd.base.nutrition(), 0, 20);
            int fm = clamp(pd.foodLevel + pd.food.nutrition(), 0, 20);
            float sb = clamp(pd.saturation + pd.base.saturation(), 0, fb);
            float sm = clamp(pd.saturation + pd.food.saturation(), 0, fm);
            int nf = clamp(fd.getFoodLevel() + fm - fb, 0, 20);
            fd.setFoodLevel(nf);
            fd.setSaturation(clamp(fd.getSaturationLevel() + sm - sb, 0, nf));
        }
        if (!pd.base.effects().equals(pd.food.effects())) {
            Set<FId> types = new HashSet<>();
            for (FoodValues.Effect e : pd.base.effects()) types.add(FFood.effectId(e.effect()));
            FFood.restore(p, types, pd.effects);
            for (FoodValues.Effect e : pd.food.effects()) {
                if (e.chance() >= 1 || p.getRandom().nextFloat() < e.chance()) p.addEffect(new MobEffectInstance(e.effect()));
            }
        }
        FEvents.post(new FoodConsumed(p, pd.stack, pd.food.drink(), pd.food.nutrition(), pd.food.saturation(), pd.food));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ---- command

    /** {@code /fundamentum food}: the held item's values (after modifiers) and food groups. */
    private static int foodCommand(CommandContext<CommandSourceStack> c) {
        if (!(c.getSource().getEntity() instanceof ServerPlayer p)) return 0;
        ItemStack stack = p.getMainHandItem();
        FoodValues v = values(p, stack);
        Map<FId, Double> groups = FoodGroups.groups(stack);
        StringBuilder sb = new StringBuilder(FVanilla.idOf(stack.getItem()).toString());
        if (v == null) {
            sb.append(": not food");
        } else {
            sb.append(String.format(Locale.ROOT, ": hunger %d, saturation %.1f%s, %d effect(s)", v.nutrition(), v.saturation(),
                v.drink() ? ", drink" : "", v.effects().size()));
        }
        sb.append("; groups ").append(groups.isEmpty() ? "none" : groups.toString());
        c.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return v == null ? 0 : 1;
    }
}
