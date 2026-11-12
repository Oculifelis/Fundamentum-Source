package com.fundamentum.food;

import com.fundamentum.event.FEvents;
import com.fundamentum.port.FFood;
import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FVanilla;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The food test, only in {@code gradlew runServer -Pfoodtest} (world under run-foodtest). The same file on every
 * version. A test player really eats and drinks (the vanilla finish-using path), so the hook, the cancellable event,
 * the modifier pipeline (hunger, saturation, effects, priorities), drinks, the consumed event, food groups from data
 * and tags, JSON values, recipe guessing and the command are all checked. Writes foodtest-result.txt and stops the
 * server. Log lines start with FOODTEST.
 */
public final class FoodSelfTest {
    private FoodSelfTest() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    private static final List<String> LINES = new ArrayList<>();
    private static final List<FoodEaten> EATEN = new ArrayList<>();
    private static final List<FoodConsumed> CONSUMED = new ArrayList<>();
    private static final FId FRUITS = FId.of("fundamentum", "fruits");
    private static final FId VEGETABLES = FId.of("fundamentum", "vegetables");
    private static final FId PROTEINS = FId.of("fundamentum", "proteins");
    private static final FId GRAINS = FId.of("fundamentum", "grains");
    private static final FId SUGARS = FId.of("fundamentum", "sugars");
    private static final FId TEST = FId.of("foodtest", "testgroup");
    private static boolean failed;
    private static boolean cancelNext;
    private static int ticks;

    public static void init() {
        if (System.getProperty("fundamentum.foodtest") == null) return;
        FEvents.listen(FoodEaten.class, (FoodEaten e, FEvents.Call call) -> {
            EATEN.add(e);
            if (cancelNext) call.cancel();
        });
        FEvents.listen(FoodConsumed.class, (FoodConsumed e) -> CONSUMED.add(e));
        FLife.serverTick(FoodSelfTest::tick);
    }

    private static void check(boolean ok, String what) {
        String line = (ok ? "PASS " : "FAIL ") + what;
        if (!ok) failed = true;
        LINES.add(line);
        if (ok) LOG.info("FOODTEST {}", line);
        else LOG.error("FOODTEST {}", line);
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-4;
    }

    private static void tick(MinecraftServer server) {
        if (++ticks != 40) return;
        try {
            run(server);
        } catch (Throwable t) {
            LOG.error("FOODTEST threw", t);
            check(false, "threw " + t);
        }
        LINES.add(failed ? "RESULT FAIL" : "RESULT PASS");
        LOG.info("FOODTEST RESULT {}", failed ? "FAIL" : "PASS");
        try {
            Files.write(Path.of("foodtest-result.txt"), LINES);
        } catch (IOException e) {
            LOG.error("FOODTEST could not write the result file", e);
        }
        server.halt(false);
    }

    private static ItemStack stack(String id) {
        return new ItemStack(FVanilla.item(FId.of(id)));
    }

    private static Method complete;

    /** The player finishes using the stack in their main hand, the way vanilla does when the use timer runs out. */
    private static void eat(ServerPlayer p, ItemStack s) throws Exception {
        p.stopUsingItem();
        p.setItemInHand(InteractionHand.MAIN_HAND, s);
        p.startUsingItem(InteractionHand.MAIN_HAND);
        if (complete == null) {
            for (String name : new String[] {"completeUsingItem", "m_8095_"}) {
                try {
                    complete = LivingEntity.class.getDeclaredMethod(name);
                    break;
                } catch (NoSuchMethodException ignored) {
                    // try the next name
                }
            }
            complete.setAccessible(true);
        }
        complete.invoke(p);
    }

    private static void hunger(ServerPlayer p, int food, float saturation) {
        p.getFoodData().setFoodLevel(food);
        p.getFoodData().setSaturation(saturation);
    }

    private static Set<FId> effects(ServerPlayer p) {
        Set<FId> out = new HashSet<>();
        for (MobEffectInstance e : FFood.active(p)) out.add(FFood.effectId(e));
        return out;
    }

    private static int command(MinecraftServer server, ServerPlayer p, String text) {
        try {
            return server.getCommands().getDispatcher().execute(text, server.createCommandSourceStack().withSuppressedOutput().withEntity(p));
        } catch (Exception e) {
            return -1;
        }
    }

    /** Borrows a fake player's packet-swallowing connection, so effects and item use on the test player can send packets. */
    private static ServerPlayer connect(MinecraftServer server, ServerPlayer p) {
        p.connection = FVanilla.fakePlayer(server.overworld(), "fundamentum_test_connection").connection;
        try {
            // newer versions keep a joining player invulnerable until its client reports loaded
            p.connection.getClass().getMethod("markClientLoaded").invoke(p.connection);
        } catch (ReflectiveOperationException ignored) { }
        return p;
    }

    private static void run(MinecraftServer server) throws Exception {
        ServerPlayer p = connect(server, FVanilla.testPlayer(server.overworld(), "foodtest"));

        // base values
        FoodValues bread = Food.base(p, stack("minecraft:bread"));
        check(bread != null && bread.nutrition() == 5 && near(bread.saturation(), 6.0) && !bread.drink(),
            "bread gives 5 hunger and 6 saturation (" + bread + ")");
        check(!Food.edible(p, stack("minecraft:stick")) && Food.base(p, stack("minecraft:stick")) == null, "a stick is not food");
        check(Food.drink(stack("minecraft:potion")) && Food.edible(p, stack("minecraft:potion")), "a potion is a drink");
        check(Food.drink(stack("minecraft:honey_bottle")) && !Food.drink(stack("minecraft:bread")), "honey is a drink, bread is not");
        FoodValues gapple = Food.base(p, stack("minecraft:golden_apple"));
        check(gapple != null && gapple.effects().size() == 2, "a golden apple has two effects (" + (gapple == null ? null : gapple.effects()) + ")");

        // a plain eat is left as vanilla does it
        hunger(p, 10, 0);
        ItemStack two = stack("minecraft:bread");
        two.setCount(2);
        eat(p, two);
        check(p.getFoodData().getFoodLevel() == 15 && near(p.getFoodData().getSaturationLevel(), 6), "a plain eat gives vanilla hunger and saturation ("
            + p.getFoodData().getFoodLevel() + ", " + p.getFoodData().getSaturationLevel() + ")");
        check(p.getMainHandItem().getCount() == 1, "the eaten bread is used up");
        check(EATEN.size() == 1 && EATEN.get(0).nutrition() == 5 && !EATEN.get(0).drink(), "the eaten event fires with the values");
        check(CONSUMED.size() == 1 && CONSUMED.get(0).player() == p, "the consumed event fires after the eat");

        // cancelling
        cancelNext = true;
        hunger(p, 10, 0);
        ItemStack one = stack("minecraft:bread");
        eat(p, one);
        cancelNext = false;
        check(EATEN.size() == 2, "the eaten event fires for the cancelled eat");
        check(p.getFoodData().getFoodLevel() == 10 && p.getMainHandItem().getCount() == 1 && !p.isUsingItem(),
            "a cancelled eat gives nothing and keeps the food (" + p.getFoodData().getFoodLevel() + ", " + p.getMainHandItem().getCount() + ")");
        check(CONSUMED.size() == 1, "no consumed event for a cancelled eat");

        // modifiers, by priority
        Food.Modifier plusOne = (pl, s, f) -> FVanilla.idOf(s.getItem()).equals(FId.of("minecraft", "bread")) ? f.withNutrition(f.nutrition() + 1) : f;
        Food.Modifier doubled = (pl, s, f) -> FVanilla.idOf(s.getItem()).equals(FId.of("minecraft", "bread")) ? f.scaled(2) : f;
        Food.modify(10, plusOne);
        Food.modify(-5, doubled);
        FoodValues mod = Food.values(p, stack("minecraft:bread"));
        check(mod != null && mod.nutrition() == 11 && near(mod.saturation(), 12), "modifiers run lowest priority first (" + mod + ")");
        check(Food.base(p, stack("minecraft:bread")).nutrition() == 5, "base values ignore modifiers");
        hunger(p, 4, 0);
        eat(p, stack("minecraft:bread"));
        check(p.getFoodData().getFoodLevel() == 15 && near(p.getFoodData().getSaturationLevel(), 12),
            "a modified eat gives the modified hunger and saturation (" + p.getFoodData().getFoodLevel() + ", " + p.getFoodData().getSaturationLevel() + ")");
        check(CONSUMED.get(CONSUMED.size() - 1).nutrition() == 11, "the consumed event carries the modified values");
        hunger(p, 15, 0);
        eat(p, stack("minecraft:bread"));
        check(p.getFoodData().getFoodLevel() == 20 && near(p.getFoodData().getSaturationLevel(), 12),
            "modified hunger is capped at 20 (" + p.getFoodData().getFoodLevel() + ", " + p.getFoodData().getSaturationLevel() + ")");
        check(Food.removeModifier(plusOne) && Food.removeModifier(doubled) && Food.values(p, stack("minecraft:bread")).nutrition() == 5,
            "modifiers can be removed");

        // a broken modifier is skipped
        Food.Modifier broken = (pl, s, f) -> {
            throw new IllegalStateException("broken on purpose");
        };
        Food.modify(broken);
        check(Food.values(p, stack("minecraft:bread")).nutrition() == 5, "a modifier that throws is skipped");
        Food.removeModifier(broken);

        // effects
        Food.Modifier swap = (pl, s, f) -> FVanilla.idOf(s.getItem()).equals(FId.of("minecraft", "golden_apple"))
            ? f.withEffects(List.of(new FoodValues.Effect(FFood.effect(FId.of("minecraft", "speed"), 200, 0), 1))) : f;
        Food.modify(swap);
        p.removeAllEffects();
        hunger(p, 10, 0);
        eat(p, stack("minecraft:golden_apple"));
        Set<FId> fx = effects(p);
        check(fx.contains(FId.of("minecraft", "speed")) && !fx.contains(FId.of("minecraft", "regeneration")) && !fx.contains(FId.of("minecraft", "absorption")),
            "a modifier can swap a food's effects (" + fx + ")");
        Food.removeModifier(swap);
        p.removeAllEffects();
        p.addEffect(FFood.effect(FId.of("minecraft", "regeneration"), 9999, 4));
        Food.Modifier none = (pl, s, f) -> FVanilla.idOf(s.getItem()).equals(FId.of("minecraft", "golden_apple")) ? f.withEffects(List.of()) : f;
        Food.modify(none);
        eat(p, stack("minecraft:golden_apple"));
        MobEffectInstance regen = null;
        for (MobEffectInstance e : FFood.active(p)) if (FFood.effectId(e).equals(FId.of("minecraft", "regeneration"))) regen = e;
        check(regen != null && regen.getAmplifier() == 4 && !effects(p).contains(FId.of("minecraft", "absorption")),
            "removing a food's effects keeps the effects the player already had");
        Food.removeModifier(none);
        p.removeAllEffects();

        // drinks
        int before = EATEN.size();
        eat(p, stack("minecraft:potion"));
        check(EATEN.size() == before + 1 && EATEN.get(EATEN.size() - 1).drink(), "drinking a potion fires the eaten event as a drink");
        check(CONSUMED.get(CONSUMED.size() - 1).drink(), "the consumed event marks drinks");
        Food.Modifier honey = (pl, s, f) -> f.drink() ? new FoodValues(0, 0, false, f.effects()) : f;
        Food.modify(honey);
        hunger(p, 10, 0);
        eat(p, stack("minecraft:honey_bottle"));
        check(p.getFoodData().getFoodLevel() == 10, "a modifier can take the hunger out of a drink (" + p.getFoodData().getFoodLevel() + ")");
        FoodValues fake = Food.values(p, stack("minecraft:honey_bottle"));
        check(fake != null && fake.drink(), "a modifier cannot turn a drink into food");
        Food.removeModifier(honey);

        // food groups
        check(FoodGroups.all().keySet().containsAll(List.of(FRUITS, VEGETABLES, PROTEINS, GRAINS, SUGARS)), "the five built-in groups load " + FoodGroups.all().keySet());
        check(FoodGroups.in(stack("minecraft:apple"), FRUITS) && FoodGroups.in(stack("minecraft:carrot"), VEGETABLES)
            && FoodGroups.in(stack("minecraft:cooked_beef"), PROTEINS) && FoodGroups.in(stack("minecraft:bread"), GRAINS)
            && FoodGroups.in(stack("minecraft:sugar"), SUGARS), "vanilla foods are in their groups");
        check(FoodGroups.in(stack("minecraft:kelp"), FRUITS), "a diet:fruits tag puts an item in fruits");
        check(FoodGroups.in(stack("minecraft:cactus"), SUGARS), "the group's own tag fundamentum:food_groups/sugars counts");
        check(FoodGroups.group(FId.of("foodtest", "off")) == null, "a disabled group is left out");
        check(near(FoodGroups.value(stack("minecraft:pumpkin_pie"), TEST), 0.25), "JSON values give a share (" + FoodGroups.value(stack("minecraft:pumpkin_pie"), TEST) + ")");
        check(FoodGroups.in(stack("minecraft:stick"), TEST) && !FoodGroups.in(stack("minecraft:apple"), TEST), "listed items are in the test group");
        check(!FoodGroups.in(stack("minecraft:bread"), TEST), "excluded items stay out even when a tag has them");
        Map<FId, Double> cookie = FoodGroups.groups(stack("minecraft:cookie"));
        check(near(cookie.getOrDefault(GRAINS, 0.0), 1.0) && !cookie.containsKey(FRUITS), "a cookie is guessed all grains from its recipe " + cookie);
        Map<FId, Double> pickle = FoodGroups.groups(stack("minecraft:sea_pickle"));
        check(near(pickle.getOrDefault(FRUITS, 0.0), 1.0 / 3) && near(pickle.getOrDefault(VEGETABLES, 0.0), 2.0 / 3),
            "a recipe of apple and two carrots is a third fruit " + pickle);
        Map<FId, Double> bush = FoodGroups.groups(stack("minecraft:dead_bush"));
        check(near(bush.getOrDefault(FRUITS, 0.0), 1.0 / 6) && near(bush.getOrDefault(VEGETABLES, 0.0), 1.0 / 3) && near(bush.getOrDefault(GRAINS, 0.0), 0.5),
            "guessing goes through a recipe made from another guessed food " + bush);
        check(!FoodGroups.groups(stack("minecraft:sea_pickle")).containsKey(TEST), "groups with guess off are not guessed");
        check(FoodGroups.groups(stack("minecraft:cobblestone")).isEmpty(), "cobblestone is in no group");
        check(!FoodGroups.recipeOf(FId.of("minecraft", "sea_pickle")).isEmpty(), "recipes are read for guessing");
        check(FoodGroups.liveRecipe(FId.of("minecraft", "cookie")) && FoodGroups.liveRecipe(FId.of("minecraft", "sea_pickle")),
            "recipes come from the live recipe manager");
        // a recipe added in code, with no JSON file, is guessed once the live recipes are read again
        FId codeItem = FId.of("minecraft", "bedrock");
        check(FoodGroups.groups(stack("minecraft:bedrock")).isEmpty() && !FoodGroups.liveRecipe(codeItem), "bedrock has no recipe and no group");
        Runnable undo = null;
        try {
            undo = CodeRecipe.add(server, FId.of("foodtest", "code_bedrock"), stack("minecraft:bedrock").getItem(),
                stack("minecraft:apple").getItem(), stack("minecraft:apple").getItem(), stack("minecraft:carrot").getItem());
            FoodGroups.rebuild(server);
            Map<FId, Double> code = FoodGroups.groups(stack("minecraft:bedrock"));
            check(FoodGroups.liveRecipe(codeItem) && near(code.getOrDefault(FRUITS, 0.0), 2.0 / 3) && near(code.getOrDefault(VEGETABLES, 0.0), 1.0 / 3),
                "a recipe added in code is guessed from the live recipes " + code);
        } catch (ReflectiveOperationException | RuntimeException e) {
            check(false, "a recipe can be added in code: " + e);
        } finally {
            if (undo != null) undo.run();
            FoodGroups.rebuild(server);
        }
        check(FoodGroups.groups(stack("minecraft:bedrock")).isEmpty() && !FoodGroups.liveRecipe(codeItem), "the guess goes with the code recipe");

        // command
        p.setItemInHand(InteractionHand.MAIN_HAND, stack("minecraft:bread"));
        check(command(server, p, "fundamentum food") == 1, "/fundamentum food reports the held food");
        p.setItemInHand(InteractionHand.MAIN_HAND, stack("minecraft:stick"));
        check(command(server, p, "fundamentum food") == 0, "/fundamentum food says a stick is not food");
    }
}
