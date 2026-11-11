package com.fundamentum;

import com.fundamentum.ammo.Ammo;
import com.fundamentum.ammo.QuiverItem;
import com.fundamentum.ammo.Quivers;
import com.fundamentum.armorset.ArmorSetEvent;
import com.fundamentum.armorset.ArmorSets;
import com.fundamentum.attribute.StatAttributes;
import com.fundamentum.curios.Slots;
import com.fundamentum.effect.ActiveEffect;
import com.fundamentum.effect.EffectEvent;
import com.fundamentum.effect.Effects;
import com.fundamentum.shield.ShieldBlockEvent;
import com.fundamentum.shield.ShieldTypeItem;
import com.fundamentum.shield.Shields;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

/**
 * The systems test, for development servers started with {@code gradlew runServer -Psystemstest}: status effects (each
 * stacking mode, events, a cure tag, the element link), armour set tiers gained and lost on a husk, ammo search order and
 * conservation and the vanilla bow drawing from a quiver on a fake player, and the shield block events. Log lines start with
 * SYSTEMSTEST; the server stops when done. Its example quiver and shield live only in this test folder.
 */
public final class SystemsSelfTest {
    private static final Logger LOG = LogUtils.getLogger();
    private static final BlockPos BASE = new BlockPos(40, 210, 40);
    private static final String TAG = "fundamentum_systemstest";
    private static final List<String> RESULTS = new ArrayList<>();

    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("fundamentum");
    public static final DeferredItem<Item> EXAMPLE_QUIVER = ITEMS.registerItem("example_quiver", ExampleQuiver::new, new Item.Properties().stacksTo(1));
    public static final DeferredItem<Item> EXAMPLE_BLOCK_SHIELD = ITEMS.registerItem("example_block_shield", ShieldTypeItem::new,
        ShieldTypeItem.properties(new Item.Properties()).durability(300));

    /** A quiver of 4 stacks, using the standard container storage. */
    public static final class ExampleQuiver extends Item implements QuiverItem {
        public ExampleQuiver(Item.Properties properties) {
            super(properties);
        }

        @Override
        public com.fundamentum.ammo.QuiverAccess quiverAccess(ItemStack stack) {
            return Quivers.container(4);
        }
    }

    private static int start = -1;
    private static LivingEntity effectHusk, setHusk, defender, cancelDefender, changeDefender, attacker;
    private static FakePlayer archer;
    private static int applied, stacked, expired, cured, gained, lost, blockPre, blockPost;
    private static final java.util.Map<LivingEntity, ShieldBlockEvent.Post> POSTS = new java.util.HashMap<>();
    private static int arrowsBefore, arrowsAfter;

    public static void init(IEventBus modBus) {
        ITEMS.register(modBus);
        if (!Boolean.getBoolean("fundamentum.systemstest")) {
            return;
        }
        NeoForge.EVENT_BUS.addListener(SystemsSelfTest::tick);
        NeoForge.EVENT_BUS.addListener((EffectEvent.Applied e) -> applied++);
        NeoForge.EVENT_BUS.addListener((EffectEvent.Stacked e) -> stacked++);
        NeoForge.EVENT_BUS.addListener((EffectEvent.Expired e) -> expired++);
        NeoForge.EVENT_BUS.addListener((EffectEvent.Cured e) -> cured++);
        NeoForge.EVENT_BUS.addListener((ArmorSetEvent.TierGained e) -> { if (e.getEntity() == setHusk) gained++; });
        NeoForge.EVENT_BUS.addListener((ArmorSetEvent.TierLost e) -> { if (e.getEntity() == setHusk) lost++; });
        NeoForge.EVENT_BUS.addListener((ShieldBlockEvent.Pre e) -> {
            blockPre++;
            if (e.getDefender() == cancelDefender) {
                e.setCanceled(true);
            }
            if (e.getDefender() == changeDefender) {
                e.setBlockedAmount(1);
                e.setParried(true);
                e.data().put("systemstest", "riposte");
            }
        });
        NeoForge.EVENT_BUS.addListener((ShieldBlockEvent.Post e) -> {
            blockPost++;
            POSTS.put(e.getDefender(), e);
        });
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("fundamentum_test", path);
    }

    private static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        int now = server.getTickCount();
        if (start < 0) {
            if (now < 40) {
                return;
            }
            start = now;
            force(level, true);
            for (int x = -4; x < 16; x++) {
                for (int z = -4; z < 16; z++) {
                    level.setBlock(BASE.offset(x, -1, z), Blocks.GLASS.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
            return;
        }
        int t = now - start;
        try {
            step(server, level, t);
        } catch (RuntimeException e) {
            LOG.error("SYSTEMSTEST crashed at step " + t, e);
            report("no crash at step " + t, false, e.toString());
            finish(server, level);
        }
    }

    private static void step(MinecraftServer server, ServerLevel level, int t) {
        switch (t) {
            case 10 -> {
                level.getEntitiesOfClass(Entity.class, new AABB(BASE).inflate(32), e -> e.getTags().contains(TAG)).forEach(Entity::discard);
                effectHusk = spawn(level, EntityType.PIG, 0, 0);
                setHusk = spawn(level, 4, 0);
                defender = spawn(level, 0, 8);
                cancelDefender = spawn(level, 0, 10);
                changeDefender = spawn(level, 0, 12);
                attacker = spawn(level, 2, 10);
                archer = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("5e7a3b0c-1d2e-4f50-8a6b-7c8d9e0f1a2b"), "systemstest"));
                archer.moveTo(BASE.getX() + 8.5, BASE.getY(), BASE.getZ() + 4.5, 0, 0);
            }
            case 12 -> effects(level);
            case 14 -> {
                report("effects: Applied and Stacked fired", applied >= 6 && stacked >= 6, "applied " + applied + ", stacked " + stacked);
                Effects.apply(effectHusk, id("refresh"), 5, 0);
            }
            case 25 -> report("effects: a 5 tick effect expired and fired Expired", !Effects.has(effectHusk, id("refresh")) && expired >= 1,
                "expired " + expired);
            case 26 -> {
                setHusk.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
                setHusk.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            }
            case 30 -> {
                report("sets: 2 pieces give tier 2 (flag, TierGained)",
                    ArmorSets.pieces(setHusk, "fundamentum_test:test_iron") == 2 && ArmorSets.hasFlag(setHusk, "test_two")
                        && !ArmorSets.hasFlag(setHusk, "test_four") && gained == 1,
                    "pieces " + ArmorSets.pieces(setHusk, "fundamentum_test:test_iron") + ", flags " + ArmorSets.flags(setHusk) + ", gained " + gained);
                setHusk.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
                setHusk.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.CHAINMAIL_BOOTS));
            }
            case 34 -> {
                report("sets: 4 pieces give tier 4 (flag, effects, TierGained)",
                    ArmorSets.hasFlag(setHusk, "test_four") && ArmorSets.hasFlag(setHusk, "test_two") && gained == 2
                        && setHusk.hasEffect(MobEffects.DAMAGE_RESISTANCE) && Effects.has(setHusk, id("extend")),
                    "flags " + ArmorSets.flags(setHusk) + ", gained " + gained + ", resistance " + setHusk.hasEffect(MobEffects.DAMAGE_RESISTANCE)
                        + ", extend " + Effects.has(setHusk, id("extend")));
                setHusk.setItemSlot(EquipmentSlot.LEGS, ItemStack.EMPTY);
                setHusk.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
            }
            case 38 -> {
                report("sets: back to 2 pieces loses tier 4 only (TierLost, effects gone)",
                    !ArmorSets.hasFlag(setHusk, "test_four") && ArmorSets.hasFlag(setHusk, "test_two") && lost == 1
                        && !setHusk.hasEffect(MobEffects.DAMAGE_RESISTANCE) && !Effects.has(setHusk, id("extend")),
                    "flags " + ArmorSets.flags(setHusk) + ", lost " + lost);
                setHusk.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
                setHusk.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
            }
            case 42 -> {
                report("sets: no pieces, no tiers", ArmorSets.flags(setHusk).isEmpty() && lost == 2, "flags " + ArmorSets.flags(setHusk) + ", lost " + lost);
                ammo(level);
            }
            case 44 -> {
                bow(level);
            }
            case 47 -> {
                ItemStack quiver = Slots.stacks(archer, Slots.QUIVER).isEmpty() ? ItemStack.EMPTY : Slots.stacks(archer, Slots.QUIVER).getFirst();
                int left = arrows(quiver);
                int arrows = arrowsAfter;
                report("ammo: the vanilla bow shoots an arrow from the quiver", left == 9 && arrows > arrowsBefore,
                    "quiver arrows " + left + ", arrows around " + arrows + " (before " + arrowsBefore + ")");
                shieldStart(level);
            }
            case 51 -> shieldHits(level);
            case 53 -> finish(server, level);
            default -> { }
        }
    }

    // ---- effects ----------------------------------------------------------------------------------------------------

    private static void effects(ServerLevel level) {
        LivingEntity h = effectHusk;
        Effects.apply(h, id("refresh"), 100, 0);
        Effects.apply(h, id("refresh"), 50, 1);
        report("effects: refresh keeps the higher level and the longer time",
            Effects.amplifier(h, id("refresh")) == 1 && Effects.remaining(h, id("refresh")) > 90,
            "amp " + Effects.amplifier(h, id("refresh")) + ", left " + Effects.remaining(h, id("refresh")));
        Effects.remove(h, id("refresh"));

        Effects.apply(h, id("extend"), 100, 0);
        Effects.apply(h, id("extend"), 100, 0);
        report("effects: extend adds the times", Effects.remaining(h, id("extend")) > 190, "left " + Effects.remaining(h, id("extend")));

        Effects.apply(h, id("amplify"), 100, 0);
        Effects.apply(h, id("amplify"), 100, 0);
        Effects.apply(h, id("amplify"), 100, 0);
        int three = Effects.amplifier(h, id("amplify"));
        Effects.apply(h, id("amplify"), 100, 0);
        Effects.apply(h, id("amplify"), 100, 0);
        report("effects: amplify climbs one level a time up to its max", three == 2 && Effects.amplifier(h, id("amplify")) == 3,
            "after 3: " + three + ", after 5: " + Effects.amplifier(h, id("amplify")));

        Effects.apply(h, id("independent"), 100, 0);
        Effects.apply(h, id("independent"), 60, 0);
        Effects.apply(h, id("independent"), 80, 0);
        ActiveEffect ind = Effects.get(h, id("independent"));
        report("effects: independent keeps separate stacks up to max_stacks (the shortest replaced)",
            ind != null && ind.timedStacks() == 2 && ind.stacks().stream().noneMatch(s -> s.expiresAt() - level.getGameTime() < 70),
            ind == null ? "none" : ind.stacks().toString());

        Effects.apply(h, id("cursed"), 200, 0);
        Effects.apply(h, id("extend"), 100, 0);
        h.addEffect(new MobEffectInstance(MobEffects.POISON, 200, 0));
        int before = cured;
        int n = Effects.cure(h, Effects.CURE_HOLY);
        report("effects: the holy cure takes cursed and vanilla poison, leaves the rest",
            n >= 2 && !Effects.has(h, id("cursed")) && !h.hasEffect(MobEffects.POISON) && Effects.has(h, id("extend")) && cured == before + 1,
            "cured " + n + ", events " + (cured - before) + ", cursed " + Effects.has(h, id("cursed")) + ", poison " + h.hasEffect(MobEffects.POISON));

        var resistance = BuiltInRegistries.ATTRIBUTE.getHolder(ResourceLocation.fromNamespaceAndPath("fundamentum", "fire_resistance")).orElseThrow();
        h.getAttribute(resistance).setBaseValue(1);
        boolean refused = !Effects.apply(h, id("burning"), 100, 0);
        h.getAttribute(resistance).setBaseValue(0.5);
        boolean took = Effects.apply(h, id("burning"), 100, 0);
        double scale = Effects.elementScale(h, Effects.definition(id("burning")), null);
        report("effects: the element link (full fire resistance refuses a fire effect, half halves it)",
            refused && took && Math.abs(scale - 0.5) < 1e-6, "refused " + refused + ", took " + took + ", scale " + scale);
        Effects.remove(h, id("burning"));
        h.getAttribute(resistance).setBaseValue(0);
    }

    // ---- ammo -------------------------------------------------------------------------------------------------------

    private static int arrows(ItemStack quiver) {
        var access = Quivers.get(quiver);
        if (access == null) {
            return -1;
        }
        return access.contents(quiver).stream().filter(s -> s.is(Items.ARROW)).mapToInt(ItemStack::getCount).sum();
    }

    private static void ammo(ServerLevel level) {
        FakePlayer p = archer;
        ItemStack bow = new ItemStack(Items.BOW);
        p.setItemSlot(EquipmentSlot.MAINHAND, bow);
        ItemStack quiver = new ItemStack(EXAMPLE_QUIVER.get());
        Quivers.insert(quiver, new ItemStack(Items.ARROW, 10));
        // a fake player never joins the level, so Curios has not laid out its slots yet
        top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(p).ifPresent(inv -> {
            if (!inv.getCurios().containsKey(Slots.QUIVER)) {
                inv.reset();
            }
        });
        boolean equipped = Slots.equip(p, Slots.QUIVER, 0, quiver);
        if (!equipped) {
            report("ammo: the quiver goes into the quiver slot", false, "slots " + Slots.stacks(p, Slots.QUIVER).size());
            return;
        }
        p.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.ARROW, 5));
        p.getInventory().setItem(20, new ItemStack(Items.ARROW, 7));
        java.util.function.Predicate<ItemStack> isArrow = s -> s.is(Items.ARROW);
        Ammo.Found first = Ammo.find(p, bow, isArrow);
        p.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        Ammo.Found second = Ammo.find(p, bow, isArrow);
        ItemStack worn = Slots.stacks(p, Slots.QUIVER).isEmpty() ? ItemStack.EMPTY : Slots.stacks(p, Slots.QUIVER).getFirst();
        ItemStack held = worn.copy();
        Quivers.get(worn).setContents(worn, List.of());
        Ammo.Found third = Ammo.find(p, bow, isArrow);
        Quivers.get(worn).setContents(worn, Quivers.get(held).contents(held));
        report("ammo: search order offhand > quiver > inventory",
            equipped && first != null && first.source() == Ammo.Source.OFFHAND && second != null && second.source() == Ammo.Source.QUIVER
                && second.insideQuiver() && third != null && third.source() == Ammo.Source.INVENTORY && third.slot() == 20,
            "equipped " + equipped + ", " + (first == null ? null : first.source()) + " > " + (second == null ? null : second.source())
                + " > " + (third == null ? null : third.source()));

        p.getAttribute(StatAttributes.AMMO_CONSERVATION).setBaseValue(1);
        Ammo.Found f = Ammo.find(p, bow, isArrow);
        int used = f == null ? -1 : Ammo.consume(p, bow, f, 3);
        report("ammo: conservation 1.0 uses nothing", used == 0 && arrows(worn) == 10, "used " + used + ", quiver " + arrows(worn));
        p.getAttribute(StatAttributes.AMMO_CONSERVATION).setBaseValue(0);
        p.getInventory().setItem(20, ItemStack.EMPTY);
    }

    private static void bow(ServerLevel level) {
        FakePlayer p = archer;
        ItemStack bow = p.getMainHandItem();
        arrowsBefore = level.getEntitiesOfClass(AbstractArrow.class, new AABB(p.blockPosition()).inflate(8)).size();
        ((BowItem) Items.BOW).releaseUsing(bow, level, p, bow.getUseDuration(p) - 20);
        arrowsAfter = level.getEntitiesOfClass(AbstractArrow.class, new AABB(p.blockPosition()).inflate(8)).size();
    }

    // ---- shield block events ----------------------------------------------------------------------------------------

    private static void shieldStart(ServerLevel level) {
        for (LivingEntity e : List.of(defender, cancelDefender, changeDefender)) {
            e.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(EXAMPLE_BLOCK_SHIELD.get()));
            e.startUsingItem(InteractionHand.MAIN_HAND);
        }
    }

    private static void shieldHits(ServerLevel level) {
        for (LivingEntity e : List.of(defender, cancelDefender, changeDefender)) {
            e.invulnerableTime = 0;
            e.hurt(level.damageSources().mobAttack(attacker), 4);
        }
        ShieldBlockEvent.Post plain = POSTS.get(defender);
        report("shield block: Pre and Post fire, Post reports the blocked amount",
            blockPre == 3 && plain != null && plain.wasBlocked() && Math.abs(plain.getBlockedAmount() - 4) < 1e-4 && !hurt(defender),
            "pre " + blockPre + ", post " + blockPost + ", amount " + (plain == null ? "none" : plain.getBlockedAmount()) + ", health " + defender.getHealth());
        ShieldBlockEvent.Post canceled = POSTS.get(cancelDefender);
        report("shield block: a canceled Pre lets the hit through", canceled != null && !canceled.wasBlocked() && hurt(cancelDefender),
            "blocked " + (canceled == null ? "none" : canceled.wasBlocked()) + ", health " + cancelDefender.getHealth());
        ShieldBlockEvent.Post changed = POSTS.get(changeDefender);
        report("shield block: Pre changes the amount, sets parried and leaves data for Post",
            changed != null && changed.wasBlocked() && Math.abs(changed.getBlockedAmount() - 1) < 1e-4 && changed.isParried()
                && "riposte".equals(changed.data().get("systemstest")) && hurt(changeDefender),
            changed == null ? "none" : "amount " + changed.getBlockedAmount() + ", parried " + changed.isParried() + ", health " + changeDefender.getHealth());
    }

    private static boolean hurt(LivingEntity e) {
        return e.getHealth() < e.getMaxHealth();
    }

    // ---- plumbing ---------------------------------------------------------------------------------------------------

    private static void finish(MinecraftServer server, ServerLevel level) {
        long passed = RESULTS.stream().filter(r -> r.startsWith("PASS")).count();
        LOG.info("SYSTEMSTEST DONE {}/{}", passed, RESULTS.size());
        level.getEntitiesOfClass(Entity.class, new AABB(BASE).inflate(32), e -> e.getTags().contains(TAG) || e instanceof AbstractArrow)
            .forEach(Entity::discard);
        force(level, false);
        server.halt(false);
    }

    private static void force(ServerLevel level, boolean on) {
        for (int cx = 1; cx <= 4; cx++) {
            for (int cz = 1; cz <= 4; cz++) {
                level.setChunkForced(cx, cz, on);
            }
        }
    }

    private static LivingEntity spawn(ServerLevel level, int x, int z) {
        return spawn(level, EntityType.HUSK, x, z);
    }

    private static LivingEntity spawn(ServerLevel level, EntityType<? extends LivingEntity> type, int x, int z) {
        LivingEntity e = type.create(level);
        float yaw = x > 0 ? 90.0F : -90.0F;
        e.moveTo(BASE.getX() + x + 0.5, BASE.getY(), BASE.getZ() + z + 0.5, yaw, 0.0F);
        e.setYHeadRot(yaw);
        e.addTag(TAG);
        if (e instanceof Mob mob) {
            mob.setNoAi(true);
            mob.setPersistenceRequired();
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                mob.setDropChance(slot, 0);
            }
        }
        e.setHealth(e.getMaxHealth());
        level.addFreshEntity(e);
        return e;
    }

    private static void report(String name, boolean ok, String detail) {
        String line = (ok ? "PASS " : "FAIL ") + name + (detail.isEmpty() ? "" : " (" + detail + ")");
        RESULTS.add(line);
        LOG.info("SYSTEMSTEST {}", line);
    }

    private SystemsSelfTest() { }
}
