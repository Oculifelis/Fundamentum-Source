package com.fundamentum.survival;

import com.fundamentum.climate.Climate;
import com.fundamentum.port.FFood;
import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FVanilla;
import com.fundamentum.stat.Stats;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The survival meter test, only in {@code gradlew runServer -Psurvivaltest} (world under run-survivaltest). The same
 * file on every version. Checks that meters load from data (broken and disabled files left out), start values, drain
 * rules with activities and conditions, the climate rule and conditions, threshold effects and damage, eating and
 * drinking items through the food hooks, drinking from water, death resets, the client sync message, the HUD layout
 * and the command. Writes survivaltest-result.txt and stops the server. Log lines start with SURVIVALTEST.
 */
public final class SurvivalSelfTest {
    private SurvivalSelfTest() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    private static final List<String> LINES = new ArrayList<>();
    private static final FId THIRST = FId.of("survivaltest", "thirst");
    private static final FId WARMTH = FId.of("survivaltest", "warmth");
    private static final FId KEEP = FId.of("survivaltest", "keep");
    private static final int Y = 200;
    private static boolean failed;
    private static int ticks;

    public static void init() {
        if (System.getProperty("fundamentum.survivaltest") == null) return;
        FLife.serverTick(SurvivalSelfTest::tick);
    }

    private static boolean isType(net.minecraft.world.damagesource.DamageSource src, String id) {
        return src.is(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DAMAGE_TYPE, FId.of(id).vanilla()));
    }

    private static String deathKey(net.minecraft.world.damagesource.DamageSource src, LivingEntity victim) {
        return src.getLocalizedDeathMessage(victim).getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
            ? t.getKey() : "";
    }

    private static void check(boolean ok, String what) {
        String line = (ok ? "PASS " : "FAIL ") + what;
        if (!ok) failed = true;
        LINES.add(line);
        if (ok) LOG.info("SURVIVALTEST {}", line);
        else LOG.error("SURVIVALTEST {}", line);
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-6;
    }

    private static void tick(MinecraftServer server) {
        ticks++;
        if (ticks == 5) server.overworld().setChunkForced(0, 0, true);
        if (ticks != 40) return;
        try {
            run(server);
        } catch (Throwable t) {
            LOG.error("SURVIVALTEST threw", t);
            check(false, "threw " + t);
        }
        LINES.add(failed ? "RESULT FAIL" : "RESULT PASS");
        LOG.info("SURVIVALTEST RESULT {}", failed ? "FAIL" : "PASS");
        try {
            Files.write(Path.of("survivaltest-result.txt"), LINES);
        } catch (IOException e) {
            LOG.error("SURVIVALTEST could not write the result file", e);
        }
        server.halt(false);
    }

    private static ItemStack stack(String id) {
        return new ItemStack(FVanilla.item(FId.of(id)));
    }

    private static void eat(ServerPlayer p, ItemStack s) throws Exception {
        p.stopUsingItem();
        p.setItemInHand(InteractionHand.MAIN_HAND, s);
        p.startUsingItem(InteractionHand.MAIN_HAND);
        Method complete = null;
        for (String name : new String[] {"completeUsingItem", "m_8095_"}) {
            try {
                complete = LivingEntity.class.getDeclaredMethod(name);
                break;
            } catch (NoSuchMethodException ignored) {
                // try the next name
            }
        }
        complete.setAccessible(true);
        complete.invoke(p);
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }

    private static boolean has(ServerPlayer p, String effect, int amplifier) {
        for (MobEffectInstance e : FFood.active(p)) {
            if (FFood.effectId(e).equals(FId.of(effect)) && e.getAmplifier() == amplifier) return true;
        }
        return false;
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
        ServerLevel level = server.overworld();
        level.setRainLevel(0);

        // data
        Set<FId> ids = Survival.meters().keySet();
        check(ids.equals(Set.of(THIRST, WARMTH, KEEP)), "three meters load, the broken and disabled files left out " + ids);
        Meter thirst = Survival.meter(THIRST);
        Meter warmth = Survival.meter(WARMTH);
        Meter keep = Survival.meter(KEEP);
        check(thirst.stat().equals(THIRST) && keep.stat().equals(FId.of("survivaltest", "kept")), "a meter's stat defaults to its id");
        check(near(thirst.start(), 20) && near(thirst.respawn(), 15) && near(warmth.start(), 0) && near(keep.respawn(), 5), "start and respawn values");
        check(thirst.resetOnDeath() && !keep.resetOnDeath(), "reset_on_death reads");
        check("none".equals(keep.hud().style()) && "bar".equals(thirst.hud().style()) && "left".equals(warmth.hud().side()), "HUD styles and sides read");
        boolean threw = false;
        try {
            Meter.parse(FId.of("survivaltest", "bad"), JsonParser.parseString("{\"thresholds\": [{\"damage\": 1}]}").getAsJsonObject());
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "a threshold with no limit is refused");
        Map<FId, Meter> synced = Survival.parseAll(Survival.defsMessage().json());
        check(synced.keySet().equals(ids) && synced.get(THIRST).equals(thirst), "the client gets the same meters");

        ServerPlayer p = connect(server, FVanilla.testPlayer(level, "survivaltest"));
        p.setPos(0.5, Y, 0.5);
        p.setHealth(20);

        // start values and drain
        check(near(Survival.get(p, thirst), 20) && near(Survival.get(p, keep), 5), "a new player starts at the start values");
        Survival.evaluate(p);
        check(near(Survival.get(p, thirst), 19), "thirst drains one a second (" + Survival.get(p, thirst) + ")");
        check(near(Stats.get(p, THIRST), 19), "the value is a stat");
        check(near(Survival.get(p, keep), 6), "a climate condition with a wide range holds (" + Survival.get(p, keep) + ")");
        double temp = Climate.temperature(level, p.blockPosition());
        check(near(Survival.get(p, warmth), Math.max(-10, Math.min(10, temp))), "the climate rule follows the temperature (" + Survival.get(p, warmth) + " vs " + temp + ")");
        p.setSprinting(true);
        Survival.evaluate(p);
        check(near(Survival.get(p, thirst), 16), "sprinting drains more (" + Survival.get(p, thirst) + ")");
        p.setShiftKeyDown(true);
        Survival.evaluate(p);
        check(near(Survival.get(p, thirst), 9), "a list of activities must all hold (" + Survival.get(p, thirst) + ")");
        check(near(Survival.get(p, keep), 10), "an activity condition holds while sneaking (" + Survival.get(p, keep) + ")");
        p.setSprinting(false);
        p.setShiftKeyDown(false);
        check(Survival.activity(p, "still") && !Survival.activity(p, "moving") && !Survival.activity(p, "in_water") && !Survival.activity(p, "nonsense"),
            "activities read the player");

        // thresholds
        Survival.set(p, thirst, 6);
        Survival.evaluate(p);
        check(near(Survival.get(p, thirst), 5) && has(p, "minecraft:slowness", 1), "below a threshold the effect applies");
        p.removeAllEffects();
        Survival.set(p, thirst, -50);
        check(near(Survival.get(p, thirst), 0), "values stay inside the meter's range");
        Survival.evaluate(p);
        check(near(p.getHealth(), 20), "no damage until the interval passes (" + p.getHealth() + ")");
        Survival.evaluate(p);
        check(near(p.getHealth(), 18), "at zero the meter hurts every interval (" + p.getHealth() + ")");
        FVanilla.clearHurtCooldown(p);
        Survival.set(p, thirst, 10);
        Survival.evaluate(p);
        Survival.evaluate(p);
        check(near(p.getHealth(), 18), "no damage once refilled");
        p.removeAllEffects();

        // each meter dies its own death
        net.minecraft.world.damagesource.DamageSource named = Survival.meterDamage(p, thirst, FId.of("minecraft", "out_of_world"));
        check(isType(named, "minecraft:out_of_world"), "a threshold's damage_type is used as named");
        net.minecraft.world.damagesource.DamageSource own = Survival.meterDamage(p, thirst, null);
        check(isType(own, "fundamentum:thirst"), "a thirst meter with no damage_type hurts as fundamentum:thirst");
        check(own.is(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.DAMAGE_TYPE, FId.of("fundamentum", "meter_damage").vanilla())),
            "fundamentum:thirst is in the fundamentum:meter_damage tag");
        check(own.is(net.minecraft.tags.DamageTypeTags.BYPASSES_ARMOR), "meter damage bypasses armour like starving");
        check(deathKey(own, p).equals("death.attack.fundamentum.thirst"), "thirst has its own death message (" + deathKey(own, p) + ")");
        net.minecraft.world.damagesource.DamageSource other = Survival.meterDamage(p, keep, null);
        check(isType(other, "fundamentum:meter") && deathKey(other, p).equals("death.attack.fundamentum.meter"),
            "a meter with no damage type of its own hurts as fundamentum:meter");
        com.google.gson.JsonObject lang;
        try (java.io.InputStream in = SurvivalSelfTest.class.getResourceAsStream("/assets/fundamentum/lang/en_us.json")) {
            lang = JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }
        for (String t : new String[] {"thirst", "overheating", "hypothermia", "exhaustion", "meter"}) {
            check(lang.has("death.attack.fundamentum." + t) && lang.has("death.attack.fundamentum." + t + ".player"), "en_us has death messages for " + t);
            check(isType(Survival.meterDamage(p, thirst, FId.of("fundamentum", t)), "fundamentum:" + t), "the damage type fundamentum:" + t + " loads");
        }
        // a longer survival step does the same change in one go
        Survival.set(p, thirst, 15);
        Survival.evaluate(p, 3);
        check(near(Survival.get(p, thirst), 12), "three seconds in one step drain three (" + Survival.get(p, thirst) + ")");

        // eating and drinking items
        Survival.set(p, thirst, 10);
        eat(p, stack("minecraft:potion"));
        check(near(Survival.get(p, thirst), 15), "drinking a potion fills thirst (" + Survival.get(p, thirst) + ")");
        eat(p, stack("minecraft:honey_bottle"));
        check(near(Survival.get(p, thirst), 18), "an item tag entry works (" + Survival.get(p, thirst) + ")");
        eat(p, stack("minecraft:bread"));
        check(near(Survival.get(p, thirst), 18), "bread does nothing to thirst");

        // drinking from water
        BlockPos water = new BlockPos(0, Y - 1, 0);
        level.setBlock(water, FVanilla.block(FId.of("minecraft", "water")).defaultBlockState(), 2);
        level.setBlock(water.above(), FVanilla.block(FId.of("minecraft", "air")).defaultBlockState(), 2);
        level.setBlock(water.above(2), FVanilla.block(FId.of("minecraft", "air")).defaultBlockState(), 2);
        p.setPos(0.5, Y, 0.5);
        p.setXRot(90);
        p.setYRot(0);
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        Survival.set(p, thirst, 10);
        check(Survival.drinkable(Survival.meters(), Survival.drinkTarget(p)), "looking down at water finds it");
        check(Survival.drinkFromBlock(p) && near(Survival.get(p, thirst), 14), "drinking from water fills thirst (" + Survival.get(p, thirst) + ")");
        check(!Survival.drinkFromBlock(p) && near(Survival.get(p, thirst), 14), "drinking again at once waits for the cooldown");
        Survival.resetCooldown(p);
        Survival.set(p, thirst, 20);
        check(!Survival.drinkFromBlock(p), "a full meter does not drink");
        Survival.set(p, thirst, 10);
        p.setItemInHand(InteractionHand.MAIN_HAND, stack("minecraft:stick"));
        check(!Survival.drinkFromBlock(p), "a full hand does not drink");
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        level.setBlock(water, FVanilla.block(FId.of("minecraft", "stone")).defaultBlockState(), 2);
        check(!Survival.drinkFromBlock(p) && !Survival.drinkable(Survival.meters(), Survival.drinkTarget(p)), "stone is not drinkable");

        // death and respawn
        Survival.set(p, thirst, 3);
        Survival.set(p, keep, 40);
        Map<String, Double> saved = new HashMap<>(Stats.ENTITY.get(p));
        p.setHealth(0);
        Survival.evaluate(p);
        check(near(Survival.get(p, thirst), 3), "a dead player's meters stand still");
        ServerPlayer reborn = connect(server, FVanilla.testPlayer(level, "survivaltest"));
        reborn.setPos(0.5, Y + 5, 0.5);
        Stats.ENTITY.update(reborn, old -> saved);
        Survival.evaluate(reborn);
        check(near(Survival.get(reborn, thirst), 14), "after death thirst restarts at its respawn value (" + Survival.get(reborn, thirst) + ")");
        check(near(Survival.get(reborn, keep), 41), "a meter with reset_on_death false keeps its value (" + Survival.get(reborn, keep) + ")");
        Survival.evaluate(reborn);
        check(near(Survival.get(reborn, thirst), 13), "the same entity again is not a respawn");

        // HUD
        Map<FId, Double> values = new HashMap<>();
        values.put(THIRST, 10.0);
        values.put(WARMTH, 2.0);
        values.put(FId.of("survivaltest", "kept"), 7.0);
        List<SurvivalHud.Row> rows = SurvivalHud.rows(Survival.meters(), values, 400, 300);
        check(rows.size() == 2, "meters with no HUD are left off (" + rows.size() + ")");
        SurvivalHud.Row bar = rows.stream().filter(r -> r.meter().equals(THIRST)).findFirst().orElse(null);
        SurvivalHud.Row text = rows.stream().filter(r -> r.meter().equals(WARMTH)).findFirst().orElse(null);
        check(bar != null && bar.bar() && bar.x() == 210 && bar.y() == 243 && near(bar.fill(), 0.5) && bar.color() == 0xFF3F76E4,
            "thirst is a half full bar above the hunger bar " + bar);
        check(text != null && !text.bar() && text.x() == 109 && text.y() == 241 && near(text.fill(), 0.6), "warmth is text above the hearts " + text);
        check(SurvivalHud.rows(Survival.meters(), Map.of(), 400, 300).isEmpty(), "nothing shows before values arrive");
        check(SurvivalHud.fallbackName(FId.of("a", "b/body_temperature")).equals("Body Temperature"), "names fall back to the id");

        // command
        check(command(server, reborn, "fundamentum survival") == 3, "/fundamentum survival lists the meters");
    }
}
