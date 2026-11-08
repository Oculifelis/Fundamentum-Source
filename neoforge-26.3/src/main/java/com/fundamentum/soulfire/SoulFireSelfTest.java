package com.fundamentum.soulfire;

import com.fundamentum.api.SoulFire;
import com.fundamentum.damage.Elements;
import com.fundamentum.damage.SharedDamage;
import com.fundamentum.element.ElementAffinities;
import com.fundamentum.element.ElementAuras;
import com.fundamentum.element.ElementEvents;
import com.fundamentum.element.ElementRef;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * The soul fire test, run beside the element test ({@code gradlew runServer -Pelementtest}, whose test data adds the
 * golden sword to {@code #fundamentum:soul_fire_weapons} and the spectral arrow to {@code #fundamentum:soul_fire_projectiles}).
 * Still creatures on a glass floor high above spawn: soul fire blocks, normal fire, the API, the burn damage, and soul
 * fire as a variant of fire. Log lines start with SOULFIRETEST; the element test waits for {@link #done} before it stops
 * the server.
 */
public final class SoulFireSelfTest {
    private static final Logger LOG = LogUtils.getLogger();
    private static final BlockPos BASE = new BlockPos(0, 240, -24);
    private static final String TAG = "fundamentum_soulfiretest";
    private static final List<String> RESULTS = new ArrayList<>();
    private static final Map<String, LivingEntity> MOBS = new HashMap<>();
    private static final Map<Identifier, Integer> FIRED = new HashMap<>();
    private static int start = -1;
    private static boolean done;
    private static float healthC;
    private static float healthD;

    public static void init() {
        if (!Boolean.getBoolean("fundamentum.elementtest")) {
            done = true;
            return;
        }
        NeoForge.EVENT_BUS.addListener(SoulFireSelfTest::tick);
        NeoForge.EVENT_BUS.addListener((ElementEvents.Reacted e) -> FIRED.merge(e.getId(), 1, Integer::sum));
    }

    public static boolean done() {
        return done;
    }

    private static void tick(ServerTickEvent.Post event) {
        if (done) {
            return;
        }
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        int now = server.getTickCount();
        if (start < 0) {
            if (now < 40) return;
            start = now;
            force(level, true);
            for (int x = -14; x <= 14; x++) {
                for (int z = -2; z <= 2; z++) {
                    level.setBlock(BASE.offset(x, -1, z), Blocks.GLASS.defaultBlockState(), Block.UPDATE_CLIENTS);
                    level.setBlock(BASE.offset(x, 0, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
            level.setBlock(BASE.offset(0, -1, 0), Blocks.SOUL_SOIL.defaultBlockState(), Block.UPDATE_CLIENTS);
            level.setBlock(BASE, Blocks.SOUL_FIRE.defaultBlockState(), Block.UPDATE_CLIENTS);
            // a glass pen round the soul fire, so the pig in it can keep its behaviour: a creature without behaviour never moves, and
            // 1.21.1 only applies the blocks an entity is inside when it moves
            for (BlockPos side : List.of(BASE.east(), BASE.west(), BASE.north(), BASE.south())) {
                level.setBlock(side, Blocks.GLASS.defaultBlockState(), Block.UPDATE_CLIENTS);
                level.setBlock(side.above(), Blocks.GLASS.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            level.setBlock(BASE.offset(4, -1, 0), Blocks.NETHERRACK.defaultBlockState(), Block.UPDATE_CLIENTS);
            return;
        }
        if (now - start == 19 && !level.isPositionEntityTicking(BASE)) {
            // 26.3: a just-forced chunk can take a few seconds to tick its entities; hold the timeline until it does
            start++;
            return;
        }
        int t = now - start;
        if (t == 20) {
            level.getEntitiesOfClass(Entity.class, new AABB(BASE).inflate(24), e -> e.entityTags().contains(TAG)).forEach(Entity::discard);
            for (String name : List.of("A:0", "B:4", "C:-4", "D:-7", "E:-10", "F:7", "G:10", "I:13", "J:-13", "K:2", "L:-2", "M:12")) {
                String[] p = name.split(":");
                MOBS.put(p[0], spawn(level, EntityTypes.PIG, Integer.parseInt(p[1]), 0));
            }
            ((Mob) MOBS.get("A")).setNoAi(false);
            MOBS.put("H", spawn(level, EntityTypes.BLAZE, 0, 2));
            MOBS.put("Z", spawn(level, EntityTypes.ZOMBIE, 4, 2));
            MOBS.put("V", spawn(level, EntityTypes.VEX, -4, 2));
            MOBS.put("W", spawn(level, EntityTypes.PIG, 8, 2));
            return;
        }
        if (t == 30) {
            LivingEntity a = MOBS.get("A");
            report("a pig standing in soul fire soul-burns", a.isOnFire() && SoulFire.isSoulBurning(a), state(a));
            LivingEntity b = MOBS.get("B");
            SoulFire.ignite(b, 200);
            report("SoulFire.ignite sets it on fire and soul-burning", b.isOnFire() && SoulFire.isSoulBurning(b), state(b));
            LivingEntity e = MOBS.get("E");
            SoulFire.ignite(e, 100);
            SoulFire.clear(e);
            report("SoulFire.clear turns it back to ordinary fire", e.isOnFire() && !SoulFire.isSoulBurning(e), state(e));
            LivingEntity c = MOBS.get("C");
            LivingEntity d = MOBS.get("D");
            SoulFire.ignite(c, 300);
            d.igniteForTicks(300);
            healthC = c.getHealth();
            healthD = d.getHealth();
            return;
        }
        if (t == 31) {
            level.setBlock(BASE.offset(4, 0, 0), Blocks.FIRE.defaultBlockState(), Block.UPDATE_CLIENTS);
            return;
        }
        if (t == 35) {
            level.setBlock(BASE, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            MOBS.get("A").extinguishFire();
            return;
        }
        if (t == 37) {
            LivingEntity a = MOBS.get("A");
            report("extinguished, the flag clears", !a.isOnFire() && !flag(a), state(a));
            LivingEntity b = MOBS.get("B");
            report("touching normal fire clears it (still burning, orange)", b.isOnFire() && !SoulFire.isSoulBurning(b), state(b));
            return;
        }
        if (t == 40) {
            elements(level);
            return;
        }
        if (t == 42) {
            LivingEntity k = MOBS.get("K");
            report("a weapon in #fundamentum:soul_fire_weapons that sets fire makes it soul fire", k.isOnFire() && SoulFire.isSoulBurning(k), state(k));
            LivingEntity l = MOBS.get("L");
            report("a projectile in #fundamentum:soul_fire_projectiles that sets fire makes it soul fire", l.isOnFire() && SoulFire.isSoulBurning(l), state(l));
            LivingEntity m = MOBS.get("M");
            report("an untagged weapon's fire stays ordinary", m.isOnFire() && !SoulFire.isSoulBurning(m), state(m));
            return;
        }
        if (t == 70) {
            LivingEntity c = MOBS.get("C");
            LivingEntity d = MOBS.get("D");
            float soul = healthC - c.getHealth();
            float plain = healthD - d.getHealth();
            report("soul burning does 2 HP a second against 1 (two burn ticks each)", near(soul, 4) && near(plain, 2),
                "soul lost " + soul + ", ordinary lost " + plain);
            DamageSource last = c.getLastDamageSource();
            report("the burn hit is fundamentum:on_soul_fire", last != null && last.is(SoulFlames.ON_SOUL_FIRE),
                last == null ? "none" : last.typeHolder().getRegisteredName());
            long passed = RESULTS.stream().filter(r -> r.startsWith("PASS")).count();
            LOG.info("SOULFIRETEST DONE {}/{} failed={}", passed, RESULTS.size(), RESULTS.size() - passed);
            level.getEntitiesOfClass(Entity.class, new AABB(BASE).inflate(24), e -> e.entityTags().contains(TAG)).forEach(Entity::discard);
            level.setBlock(BASE.offset(4, 0, 0), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            force(level, false);
            done = true;
        }
    }

    private static void elements(ServerLevel level) {
        DamageSource soul = source(level, SharedDamage.SOUL_FIRE);
        report("a soul fire hit is fire and soul fire", Elements.FIRE.matches(soul) && Elements.SOUL_FIRE.matches(soul)
                && Elements.SOUL_FIRE.isA(Elements.FIRE) && Elements.of(soul).size() == 2,
            Elements.of(soul).stream().map(Elements.Element::id).toList().toString());
        report("the soul fire tag is inside the fire tag", soul.is(Elements.FIRE.damageTypes()), "");
        LivingEntity f = MOBS.get("F");
        hit(level, f, SharedDamage.SOUL_FIRE, 1);
        report("a soul fire hit leaves a fire aura and sets it soul-burning",
            ElementAuras.find(f, ElementRef.parse("#fundamentum:element/fire")) != null && SoulFire.isSoulBurning(f), state(f));
        LivingEntity g = MOBS.get("G");
        g.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 200));
        float taken = hit(level, g, SoulFlames.ON_SOUL_FIRE, 2) + hit(level, g, SharedDamage.SOUL_FIRE, 2);
        report("fire resistance stops soul fire", taken == 0, "took " + taken);
        LivingEntity h = MOBS.get("H");
        float blaze = hit(level, h, SharedDamage.SOUL_FIRE, 2);
        report("a fire-immune blaze takes no soul fire and doesn't burn", blaze == 0 && !SoulFire.isSoulBurning(h), "took " + blaze);
        LivingEntity i = MOBS.get("I");
        hit(level, i, SharedDamage.ICE, 1);
        int melt = fired("melt");
        hit(level, i, SharedDamage.SOUL_FIRE, 1);
        report("a fire reaction (melt) fires for soul fire", fired("melt") == melt + 1, "melt " + fired("melt"));
        LivingEntity j = MOBS.get("J");
        hit(level, j, SharedDamage.SHADOW, 1);
        hit(level, j, SharedDamage.SOUL_FIRE, 1);
        report("the soul-only reaction soul_rend fires on a shadow aura", fired("soul_rend") == 1, "soul_rend " + fired("soul_rend"));
        LivingEntity w = MOBS.get("W");
        int quench = fired("quench");
        hit(level, w, SharedDamage.SOUL_FIRE, 1);
        hit(level, w, SharedDamage.ICE, 1);
        report("frost puts soul fire out (quench)", fired("quench") > quench && !w.isOnFire() && !SoulFire.isSoulBurning(w), state(w));
        float undead = ElementAffinities.resolve(MOBS.get("Z"), soul).resistance();
        float vex = ElementAffinities.resolve(MOBS.get("V"), soul).resistance();
        report("affinities to soul fire: undead -0.25, vex 0.5", near(undead, -0.25f) && near(vex, 0.5f), "undead " + undead + ", vex " + vex);
        // weapons and projectiles: the hit, then the fire it sets (as Fire Aspect and flaming arrows do)
        LivingEntity holder = MOBS.get("Z");
        holder.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.GOLDEN_SWORD));
        LivingEntity k = MOBS.get("K");
        k.damageCooldownTime = 0;
        k.hurtServer(level, level.damageSources().mobAttack(holder), 1);
        k.igniteForSeconds(4);
        AbstractArrow arrow = EntityTypes.SPECTRAL_ARROW.create(level, EntitySpawnReason.COMMAND);
        LivingEntity l = MOBS.get("L");
        l.damageCooldownTime = 0;
        l.hurtServer(level, level.damageSources().arrow(arrow, null), 1);
        l.igniteForSeconds(5);
        LivingEntity plainHolder = MOBS.get("V");
        plainHolder.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        LivingEntity m = MOBS.get("M");
        m.damageCooldownTime = 0;
        m.hurtServer(level, level.damageSources().mobAttack(plainHolder), 1);
        m.igniteForSeconds(4);
    }

    private static int fired(String path) {
        return FIRED.getOrDefault(Identifier.fromNamespaceAndPath("fundamentum", path), 0);
    }

    private static boolean flag(Entity e) {
        return e.hasData(SoulFlames.SOUL_BURNING) && e.getData(SoulFlames.SOUL_BURNING);
    }

    private static String state(Entity e) {
        return "fire " + e.getRemainingFireTicks() + ", soul flag " + flag(e);
    }

    private static DamageSource source(ServerLevel level, ResourceKey<DamageType> type) {
        return new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(type));
    }

    private static float hit(ServerLevel level, LivingEntity target, ResourceKey<DamageType> type, float amount) {
        target.damageCooldownTime = 0;
        float before = target.getHealth();
        target.hurtServer(level, source(level, type), amount);
        return before - target.getHealth();
    }

    private static LivingEntity spawn(ServerLevel level, EntityType<? extends LivingEntity> type, int x, int z) {
        LivingEntity e = type.create(level, EntitySpawnReason.COMMAND);
        e.setPos(BASE.getX() + x + 0.5, BASE.getY(), BASE.getZ() + z + 0.5);
        e.addTag(TAG);
        if (e instanceof Mob mob) {
            mob.setNoAi(true);
            mob.setPersistenceRequired();
        }
        e.setHealth(e.getMaxHealth());
        level.addFreshEntity(e);
        return e;
    }

    private static void force(ServerLevel level, boolean force) {
        for (int cx = -1; cx <= 1; cx++) {
            level.setChunkForced(cx, -2, force);
        }
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 0.01f;
    }

    private static void report(String name, boolean ok, String detail) {
        String line = (ok ? "PASS " : "FAIL ") + name + (detail.isEmpty() ? "" : " (" + detail + ")");
        RESULTS.add(line);
        LOG.info("SOULFIRETEST {}", line);
    }

    private SoulFireSelfTest() {}
}
