package com.fundamentum.element;

import com.fundamentum.damage.SharedDamage;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * The in-game elemental interaction test, for development servers started with {@code gradlew runServer -Pelementtest},
 * which also loads the test data in {@code src/elementtest} (a cow's affinity and the self-looping reaction
 * {@code fundamentum_test:echo}) beside the built-in vanilla elements pack. It spawns still creatures on a glass floor
 * high above spawn, hits them and checks auras, reactions, affinities and the loop guard, then stops the server. Log
 * lines start with ELEMENTTEST.
 */
public final class ElementSelfTest {
    private ElementSelfTest() { }

    private static final Logger LOG = LogUtils.getLogger();
    private static final BlockPos BASE = new BlockPos(0, 240, 8);
    private static final String TAG = "fundamentum_elementtest";
    private static final List<String> RESULTS = new ArrayList<>();
    private static final Map<ResourceLocation, Integer> FIRED = new HashMap<>();
    private static int start = -1;
    private static LivingEntity echo;
    private static final Map<Integer, LivingEntity> MOBS = new HashMap<>();

    public static void init() {
        if (!Boolean.getBoolean("fundamentum.elementtest")) return;
        NeoForge.EVENT_BUS.addListener(ElementSelfTest::tick);
        NeoForge.EVENT_BUS.addListener((ElementEvents.Reacted e) -> FIRED.merge(e.getId(), 1, Integer::sum));
    }

    private static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        int now = server.getTickCount();
        if (start < 0) {
            if (now < 40) return;
            start = now;
            forceChunks(level, true);
            for (int x = -14; x < 20; x++) {
                for (int z = -4; z < 4; z++) {
                    level.setBlock(BASE.offset(x, -1, z), Blocks.GLASS.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
            return;
        }
        if (now == start + 20) {
            // the dev world is kept between runs: clear what earlier runs left
            level.getEntitiesOfClass(LivingEntity.class, new AABB(BASE).inflate(24), e -> e.getTags().contains(TAG)).forEach(LivingEntity::discard);
            // spawned a second before the hits: creatures added this tick aren't in the entity lookup yet (bursts would miss them)
            MOBS.put(-13, spawn(level, EntityType.PIG, -13));
            MOBS.put(-10, spawn(level, EntityType.PIG, -10));
            MOBS.put(-11, spawn(level, EntityType.ZOMBIE, -11));
            MOBS.put(-8, spawn(level, EntityType.PIG, -8));
            MOBS.put(-5, spawn(level, EntityType.PIG, -5));
            MOBS.put(0, spawn(level, EntityType.PIG, 0));
            MOBS.put(1, spawn(level, EntityType.PIG, 1));
            MOBS.put(5, spawn(level, EntityType.BLAZE, 5));
            MOBS.put(8, spawn(level, EntityType.COW, 8));
            MOBS.put(11, spawn(level, EntityType.PIG, 11));
            MOBS.put(14, spawn(level, EntityType.ZOMBIE, 14));
            MOBS.put(18, spawn(level, EntityType.PIG, 18));
            return;
        }
        if (now == start + 40) {
            LOG.info("ELEMENTTEST {} reactions loaded: {}", ElementReactions.all().size(), ElementReactions.all().stream().map(Map.Entry::getKey).toList());
            List<LivingEntity> seen = level.getEntitiesOfClass(LivingEntity.class, new AABB(BASE).inflate(24), e -> e.getTags().contains(TAG));
            int found = seen.size();
            report("the test creatures are in the entity lookup", found == MOBS.size(), found + " of " + MOBS.size()
                + ", missing at x " + MOBS.entrySet().stream().filter(m -> !seen.contains(m.getValue())).map(m -> m.getKey() + (m.getValue().isRemoved() ? " (removed)" : "")).toList());
            report("default reactions and the test reaction loaded", ElementReactions.all().size() >= 11, ElementReactions.all().size() + " reactions");
            auras(level);
            reactions(level);
            affinities(level);
            loops(level);
            return;
        }
        if (now == start + 41) {
            // a new tick: the echo reaction may fire on the same creature again
            hit(level, echo, DamageTypes.CRAMMING, 1);
            report("next tick the echo reaction fires again", FIRED.getOrDefault(id("fundamentum_test", "echo"), 0) == 2,
                FIRED.getOrDefault(id("fundamentum_test", "echo"), 0) + " times");
            long passed = RESULTS.stream().filter(r -> r.startsWith("PASS")).count();
            LOG.info("ELEMENTTEST DONE {}/{} failed={}", passed, RESULTS.size(), RESULTS.size() - passed);
            level.getEntitiesOfClass(LivingEntity.class, new AABB(BASE).inflate(24), e -> e.getTags().contains(TAG)).forEach(LivingEntity::discard);
            forceChunks(level, false);
        }
        // the soul fire test (soulfire/SoulFireSelfTest) runs beside this one and needs a few seconds more
        if (now >= start + 41 && com.fundamentum.soulfire.SoulFireSelfTest.done()) {
            server.halt(false);
        }
    }

    private static void auras(ServerLevel level) {
        LivingEntity pig = mob(-13);
        hit(level, pig, SharedDamage.FIRE, 1);
        ElementAuras.Aura fire = ElementAuras.find(pig, ElementRef.parse("#fundamentum:element/fire"));
        report("a fire hit leaves a fire aura", fire != null && fire.stacks() == 1, describe(pig));
        report("the aura is synced data on the creature", pig.hasData(ElementAuras.AURAS) && !pig.getData(ElementAuras.AURAS).isEmpty(), "");
        for (int i = 0; i < 4; i++) {
            hit(level, pig, SharedDamage.FIRE, 0.5f);
        }
        fire = ElementAuras.find(pig, ElementRef.parse("#fundamentum:element/fire"));
        report("fire stacks to its max_stacks (3)", fire != null && fire.stacks() == 3, describe(pig));
        report("a tag ref covers a typed aura key", ElementRef.parse("#minecraft:is_fire").covers(ElementRef.parse("fundamentum:fire"), level.registryAccess()), "");
        // a plain hit from a weapon in #fundamentum:element/fire (the test data puts blaze rods there) leaves the fire aura
        LivingEntity swordPig = mob(-10);
        LivingEntity holder = mob(-11);
        holder.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BLAZE_ROD));
        swordPig.invulnerableTime = 0;
        swordPig.hurt(level.damageSources().mobAttack(holder), 1);
        report("a fire-tagged weapon's plain hit leaves the fire aura", ElementAuras.find(swordPig, ElementRef.parse("#fundamentum:element/fire")) != null,
            describe(swordPig));
        ElementAuras.clear(swordPig);
        ElementAuras.apply(swordPig, ElementRef.parse("#fundamentum:element/water"), new ElementAuras.AuraDef(java.util.Optional.empty(), 1, 40, 0, 1, 0), null);
        report("code can apply an aura directly", ElementAuras.find(swordPig, ElementRef.parse("#fundamentum:element/water")) != null, describe(swordPig));
        ElementAuras.clear(swordPig);
        report("clear removes every aura", ElementAuras.get(swordPig).isEmpty(), describe(swordPig));
    }

    private static void reactions(ServerLevel level) {
        LivingEntity pig = mob(-8);
        hit(level, pig, SharedDamage.ICE, 1);
        report("an ice hit leaves an ice aura", ElementAuras.find(pig, ElementRef.parse("#fundamentum:element/ice")) != null, describe(pig));
        float taken = hit(level, pig, SharedDamage.FIRE, 2);
        report("melt: fire on ice deals double (2 -> 4)", near(taken, 4), "took " + taken);
        report("melt consumed the ice aura", ElementAuras.find(pig, ElementRef.parse("#fundamentum:element/ice")) == null, describe(pig));
        report("melt kept the fire hit from leaving its aura", ElementAuras.find(pig, ElementRef.parse("#fundamentum:element/fire")) == null, describe(pig));

        LivingEntity frozen = mob(-5);
        hit(level, frozen, SharedDamage.ICE, 1);
        taken = hit(level, frozen, DamageTypes.EXPLOSION, 2);
        report("shatter: an explosion on ice turns into magic x1.5 (2 -> 3)", near(taken, 3), "took " + taken);
        report("the converted hit left the magic (arcane) aura", ElementAuras.find(frozen, ElementRef.parse("#fundamentum:element/arcane")) != null,
            describe(frozen));

        LivingEntity a = mob(0);
        LivingEntity b = mob(1);
        hit(level, a, SharedDamage.WATER, 0.5f);
        hit(level, b, SharedDamage.WATER, 0.5f);
        float bBefore = b.getHealth();
        int before = FIRED.getOrDefault(id("fundamentum", "electrocute"), 0);
        hit(level, a, SharedDamage.ELECTRIC, 2);
        int fired = FIRED.getOrDefault(id("fundamentum", "electrocute"), 0) - before;
        report("electrocute's burst reaches the wet neighbour", b.getHealth() < bBefore, "neighbour took " + (bBefore - b.getHealth()));
        report("electrocute fired once, not again on the neighbour's water (loop guard)", fired == 1, fired + " times");
        report("electrocute keeps the water aura (consume none)", ElementAuras.find(a, ElementRef.parse("#fundamentum:element/water")) != null, describe(a));
    }

    private static void affinities(ServerLevel level) {
        LivingEntity blaze = mob(5);
        float taken = hit(level, blaze, SharedDamage.WATER, 2);
        report("blaze: water weakness -1 doubles water (2 -> 4)", near(taken, 4), "took " + taken);
        LivingEntity cow = mob(8);
        taken = hit(level, cow, DamageTypes.CACTUS, 4);
        report("test cow resists cactus 0.5 (4 -> 2)", near(taken, 2), "took " + taken);
        taken = hit(level, cow, DamageTypes.SWEET_BERRY_BUSH, 4);
        report("test cow is immune to sweet berry bushes", near(taken, 0), "took " + taken);
        LivingEntity pig = mob(11);
        ItemStack charm = new ItemStack(Items.STICK);
        charm.set(ElementAffinities.ITEM.get(), new ElementAffinities.Affinity(Map.of(ElementRef.parse("#fundamentum:element/fire"), 0.5f), List.of()));
        pig.setItemSlot(EquipmentSlot.MAINHAND, charm);
        taken = hit(level, pig, SharedDamage.FIRE, 4);
        report("an item's element_affinity component halves fire (4 -> 2)", near(taken, 2), "took " + taken);
        ElementAffinities.Result zombie = ElementAffinities.resolve(mob(14),
            new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(SharedDamage.HOLY)));
        report("undead tag: zombies are weak to holy (-0.5)", near(zombie.resistance(), -0.5f), "resistance " + zombie.resistance());
    }

    private static void loops(ServerLevel level) {
        echo = mob(18);
        ElementAuras.apply(echo, ElementRef.parse("#fundamentum:element/arcane"), new ElementAuras.AuraDef(java.util.Optional.empty(), 1, 200, 0, 1, 0), null);
        hit(level, echo, DamageTypes.CRAMMING, 1);
        int fired = FIRED.getOrDefault(id("fundamentum_test", "echo"), 0);
        report("echo (a burst that hits its own target again) fired once, no runaway loop", fired == 1, fired + " times");
        report("the guard unwound", ElementReactions.depth() == 0, "depth " + ElementReactions.depth());
        hit(level, echo, DamageTypes.CRAMMING, 1);
        fired = FIRED.getOrDefault(id("fundamentum_test", "echo"), 0);
        report("echo can't fire twice on one creature in one tick", fired == 1, fired + " times");
    }

    private static void forceChunks(ServerLevel level, boolean force) {
        for (int cx = -1; cx <= 1; cx++) {
            level.setChunkForced(cx, 0, force);
        }
    }

    private static LivingEntity mob(int x) {
        return MOBS.get(x);
    }

    private static LivingEntity spawn(ServerLevel level, EntityType<? extends LivingEntity> type, int x) {
        LivingEntity e = type.create(level);
        e.setPos(BASE.getX() + x + 0.5, BASE.getY(), BASE.getZ() + 0.5);
        e.addTag(TAG);
        if (e instanceof Mob mob) {
            mob.setNoAi(true);
            mob.setPersistenceRequired();
        }
        e.setHealth(e.getMaxHealth());
        level.addFreshEntity(e);
        return e;
    }

    /** Hits with the hurt cooldown reset, and returns the health it took. */
    private static float hit(ServerLevel level, LivingEntity target, ResourceKey<DamageType> type, float amount) {
        target.invulnerableTime = 0;
        float before = target.getHealth();
        target.hurt(new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(type)), amount);
        return before - target.getHealth();
    }

    private static String describe(LivingEntity e) {
        long now = e.level().getGameTime();
        return ElementAuras.get(e).stream().map(a -> a.key() + " x" + a.stacks() + " " + a.strength(now)).toList().toString();
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 0.01f;
    }

    private static ResourceLocation id(String ns, String path) {
        return ResourceLocation.fromNamespaceAndPath(ns, path);
    }

    private static void report(String name, boolean ok, String detail) {
        String line = (ok ? "PASS " : "FAIL ") + name + (detail.isEmpty() ? "" : " (" + detail + ")");
        RESULTS.add(line);
        LOG.info("ELEMENTTEST {}", line);
    }
}
