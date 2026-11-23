package com.fundamentum.element;

import com.fundamentum.damage.Elements;
import com.fundamentum.damage.SharedDamage;
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
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EntitySpawnReason;
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
    private static final Map<Identifier, Integer> FIRED = new HashMap<>();
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
            for (int x = -14; x < 32; x++) {
                for (int z = -4; z < 4; z++) {
                    level.setBlock(BASE.offset(x, -1, z), Blocks.GLASS.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
            return;
        }
        if (now == start + 20) {
            // the dev world is kept between runs: clear what earlier runs left
            level.getEntitiesOfClass(LivingEntity.class, new AABB(BASE).inflate(32), e -> e.entityTags().contains(TAG)).forEach(LivingEntity::discard);
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
            // the datapack element checks, out of reach of the echo and electrocute bursts
            MOBS.put(23, spawn(level, EntityType.PIG, 23));
            MOBS.put(25, spawn(level, EntityType.PIG, 25));
            MOBS.put(27, spawn(level, EntityType.SHEEP, 27));
            MOBS.put(29, spawn(level, EntityType.PIG, 29));
            MOBS.put(30, spawn(level, EntityType.ZOMBIE, 30));
            return;
        }
        if (now == start + 40) {
            LOG.info("ELEMENTTEST {} reactions loaded: {}", ElementReactions.all().size(), ElementReactions.all().stream().map(Map.Entry::getKey).toList());
            int found = level.getEntitiesOfClass(LivingEntity.class, new AABB(BASE).inflate(32), e -> e.entityTags().contains(TAG)).size();
            report("the test creatures are in the entity lookup", found == MOBS.size(), found + " of " + MOBS.size());
            report("default reactions and the test reaction loaded", ElementReactions.all().size() >= 11, ElementReactions.all().size() + " reactions");
            auras(level);
            reactions(level);
            affinities(level);
            loops(level);
            dataElements(server, level);
            return;
        }
        if (now == start + 41) {
            // a new tick: the echo reaction may fire on the same creature again
            hit(level, echo, DamageTypes.CRAMMING, 1);
            report("next tick the echo reaction fires again", FIRED.getOrDefault(id("fundamentum_test", "echo"), 0) == 2,
                FIRED.getOrDefault(id("fundamentum_test", "echo"), 0) + " times");
            long passed = RESULTS.stream().filter(r -> r.startsWith("PASS")).count();
            LOG.info("ELEMENTTEST DONE {}/{} failed={}", passed, RESULTS.size(), RESULTS.size() - passed);
            level.getEntitiesOfClass(LivingEntity.class, new AABB(BASE).inflate(32), e -> e.entityTags().contains(TAG)).forEach(LivingEntity::discard);
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
        swordPig.hurtServer(level, level.damageSources().mobAttack(holder), 1);
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

    /**
     * The datapack elements in the test data (1.26.0): {@code fundamentum_test:frost} (colour, the ice attributes, an
     * aura, the flash_freeze reaction, packed ice as its weapon, sheep weak to it), {@code hoarfrost} (a variant of frost
     * from data), {@code brimstone} (a variant of the code element fire, own lang key), {@code orphan} (an unknown parent,
     * so skipped) and {@code fundamentum:fire} (an id code already has, so skipped).
     */
    private static void dataElements(MinecraftServer server, ServerLevel level) {
        Elements.Element frost = Elements.get(id("fundamentum_test", "frost"));
        Elements.Element hoarfrost = Elements.get(id("fundamentum_test", "hoarfrost"));
        Elements.Element brimstone = Elements.get(id("fundamentum_test", "brimstone"));
        report("datapack elements load", frost != null && hoarfrost != null && brimstone != null && frost.fromData() && !Elements.FIRE.fromData(),
            Elements.all().stream().filter(Elements.Element::fromData).map(Elements.Element::id).toList().toString());
        if (frost == null || hoarfrost == null || brimstone == null) {
            return;
        }
        report("an unknown parent and a code element's id are skipped", Elements.get(id("fundamentum_test", "orphan")) == null
            && Elements.FIRE.color() < 0 && Elements.all().stream().filter(e -> e.id().equals(Elements.FIRE.id())).count() == 1, "");
        report("frost uses the default tag and the named attributes", frost.damageTypes().location().equals(id("fundamentum_test", "element/frost"))
            && frost.damage() != null && frost.damage().is(id("fundamentum", "ice_damage")) && frost.resistance() != null
            && frost.resistance().is(id("fundamentum", "ice_resistance")), frost.toString());
        report("brimstone has no attributes", brimstone.damage() == null && brimstone.resistance() == null, "");
        report("parents: hoarfrost is frost, brimstone is fire", hoarfrost.parent() == frost && hoarfrost.isA(frost) && brimstone.isA(Elements.FIRE)
            && !frost.isA(Elements.ICE), "");
        report("names and colours: frost's lang key and colour, brimstone's own key",
            frost.nameKey().equals("element.fundamentum_test.element.frost") && frost.color() == 0x9FE8FF
                && ElementRef.parse("#fundamentum_test:element/frost").name().getStyle().getColor() != null
                && brimstone.nameKey().equals("element.fundamentum_test.sulfur") && brimstone.color() == 0xFFAA00,
            frost.nameKey() + " " + Integer.toHexString(frost.color()) + " " + brimstone.nameKey());
        report("ElementRef finds the datapack element", ElementRef.parse("#fundamentum_test:element/frost").element() == frost, "");

        DamageSource hoar = new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(key("hoarfrost")));
        report("a hoarfrost hit is hoarfrost and frost", hoarfrost.matches(hoar) && frost.matches(hoar) && Elements.of(hoar).size() == 2,
            Elements.of(hoar).stream().map(Elements.Element::id).toList().toString());
        DamageSource brim = new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(key("brimstone")));
        report("a brimstone hit is fire through its parent, with no fire tag", Elements.FIRE.matches(brim) && !brim.is(Elements.FIRE.damageTypes())
            && ElementRef.parse("#fundamentum:element/fire").matches(brim), "");

        LivingEntity pig = mob(23);
        hit(level, pig, key("frost"), 1);
        ElementAuras.Aura aura = ElementAuras.find(pig, ElementRef.parse("#fundamentum_test:element/frost"));
        report("a frost hit leaves the frost aura", aura != null, describe(pig));
        hit(level, pig, key("hoarfrost"), 1);
        aura = ElementAuras.find(pig, ElementRef.parse("#fundamentum_test:element/frost"));
        report("a hoarfrost hit stacks the frost aura (its parent's shared damage type)", aura != null && aura.stacks() == 2, describe(pig));

        LivingEntity wet = mob(25);
        hit(level, wet, SharedDamage.WATER, 0.5f);
        int before = FIRED.getOrDefault(id("fundamentum_test", "flash_freeze"), 0);
        float taken = hit(level, wet, key("frost"), 2);
        report("flash_freeze: frost on water triples (2 -> 6)", near(taken, 6)
            && FIRED.getOrDefault(id("fundamentum_test", "flash_freeze"), 0) == before + 1, "took " + taken);

        taken = hit(level, mob(27), key("frost"), 2);
        report("sheep affinity: frost weakness -1 doubles frost (2 -> 4)", near(taken, 4), "took " + taken);

        LivingEntity resistant = mob(29);
        resistant.getAttribute(frost.resistance()).setBaseValue(0.5);
        taken = hit(level, resistant, key("frost"), 4);
        report("ice_resistance 0.5 halves frost through the element's attribute (4 -> 2)", near(taken, 2), "took " + taken);

        LivingEntity holder = mob(30);
        holder.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.PACKED_ICE));
        LivingEntity target = mob(23);
        ElementAuras.clear(target);
        target.invulnerableTime = 0;
        DamageSource bash = level.damageSources().mobAttack(holder);
        target.hurtServer(level, bash, 1);
        report("a plain hit with packed ice (#fundamentum_test:element/frost item tag) is frost and leaves its aura",
            frost.matches(bash) && ElementAuras.find(target, ElementRef.parse("#fundamentum_test:element/frost")) != null, describe(target));

        int listed = 0;
        try {
            listed = server.getCommands().getDispatcher().execute("fundamentum element list", server.createCommandSourceStack().withSuppressedOutput());
        } catch (Exception e) {
            LOG.info("ELEMENTTEST list failed: {}", e.toString());
        }
        report("/fundamentum element list counts the datapack elements", listed == Elements.all().size() && listed >= 14, listed + " listed");
    }

    private static ResourceKey<DamageType> key(String name) {
        return ResourceKey.create(Registries.DAMAGE_TYPE, id("fundamentum_test", name));
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
        LivingEntity e = type.create(level, EntitySpawnReason.COMMAND);
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
        target.hurtServer(level, new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(type)), amount);
        return before - target.getHealth();
    }

    private static String describe(LivingEntity e) {
        long now = e.level().getGameTime();
        return ElementAuras.get(e).stream().map(a -> a.key() + " x" + a.stacks() + " " + a.strength(now)).toList().toString();
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 0.01f;
    }

    private static Identifier id(String ns, String path) {
        return Identifier.fromNamespaceAndPath(ns, path);
    }

    private static void report(String name, boolean ok, String detail) {
        String line = (ok ? "PASS " : "FAIL ") + name + (detail.isEmpty() ? "" : " (" + detail + ")");
        RESULTS.add(line);
        LOG.info("ELEMENTTEST {}", line);
    }
}
