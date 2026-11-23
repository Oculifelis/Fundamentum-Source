package com.fundamentum.flail;

import com.fundamentum.scythe.ScytheSpec;
import com.fundamentum.scythe.Scythes;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * The in-game flail and scythe tests, for development servers started with {@code -Dfundamentum.flailtest=true}
 * ({@code gradlew runServer -Pflailtest}): a fake player uses each example flail and scythe on a zombie high above spawn,
 * one case at a time; every case logs {@code FLAILTEST PASS} or {@code FLAILTEST FAIL}, then {@code FLAILTEST DONE n/m}
 * and the server stops. The drawn chain needs a client and is not covered.
 */
final class FlailSelfTest {
    private static final Logger LOG = LogUtils.getLogger();
    private static final BlockPos AT = new BlockPos(8, 200, 8);

    /** One case: what is held, where the zombie stands, what the player does on which tick, and what must be true after. */
    private interface Act {
        void at(FakePlayer p, int tick);
    }

    private interface Check {
        String fail(FakePlayer p, Zombie z, float before);
    }

    private record Case(String name, Item item, double ahead, int ticks, Act act, Check check) {}

    private static List<Case> cases() {
        Item pendulum = item("example_flail_pendulum"), links = item("example_flail_links"), launch = item("example_flail_launch");
        Item hybrid = item("example_flail_hybrid"), scythe = item("example_scythe");
        Check hurt = (p, z, before) -> z.getHealth() < before || !z.isAlive() ? null : "not hurt, health " + z.getHealth();
        return List.of(
            new Case("pendulum flail swing hits and sets alight (molten)", pendulum, 1.5, 30, (p, t) -> {
                if (t == 5 && !Flails.swing(p)) {
                    LOG.info("FLAILTEST note: swing refused");
                }
            }, (p, z, before) -> z.getHealth() >= before ? "not hurt, health " + z.getHealth() : z.getRemainingFireTicks() <= 0 ? "not on fire" : null),
            new Case("links flail swing hits", links, 1.8, 30, (p, t) -> {
                if (t == 5) {
                    Flails.swing(p);
                }
            }, hurt),
            new Case("launch flail throws its head out to range", launch, 5.0, 40, (p, t) -> {
                if (t == 5) {
                    Flails.use(p, true);
                } else if (t == 6) {
                    Flails.use(p, false);
                }
            }, (p, z, before) -> z.getHealth() >= before ? "not hurt at 5 blocks, health " + z.getHealth()
                : z.getTicksFrozen() <= 0 ? "not frozen (ice)" : null),
            new Case("hybrid flail spun and let go hits", hybrid, 3.0, 70, (p, t) -> {
                if (t == 5) {
                    Flails.use(p, true);
                } else if (t == 40) {
                    Flails.use(p, false);
                }
            }, hurt),
            new Case("a flail on cooldown doesn't swing again", links, 30.0, 12, (p, t) -> {
                if (t == 5 && !Flails.swing(p)) {
                    throw new IllegalStateException("first swing refused");
                }
                if (t == 6 && Flails.swing(p)) {
                    throw new IllegalStateException("second swing went through the cooldown");
                }
            }, (p, z, before) -> null),
            new Case("scythe whirl hits around the player", scythe, 2.0, 40, (p, t) -> {
                if (t == 5) {
                    Scythes.use(p, true, false);
                } else if (t == 35) {
                    Scythes.use(p, false, false);
                }
            }, hurt),
            new Case("scythe reap pull drags a zombie in", scythe, 5.0, 8, (p, t) -> {
                if (t == 5) {
                    Scythes.use(p, true, true);
                    Scythes.use(p, false, true);
                }
            }, (p, z, before) -> z.getDeltaMovement().z < -0.1 ? null : "not pulled, motion " + z.getDeltaMovement()),
            new Case("crescent wave travels out and hits", Items.IRON_HOE, 6.0, 30, (p, t) -> {
                if (t == 5) {
                    Scythes.use(p, true, false);
                    Scythes.use(p, false, false);
                }
            }, hurt),
            new Case("backswing sweeps the arc", Items.IRON_HOE, 2.0, 8, (p, t) -> {
                if (t == 5) {
                    ScytheSpec spec = Scythes.spec(p.getMainHandItem());
                    if (spec == null || Scythes.backswing(p, p.getMainHandItem(), spec) < 1) {
                        throw new IllegalStateException("backswing hit nothing");
                    }
                }
            }, hurt),
            new Case("a scythe kill harvests a soul", scythe, 2.0, 35, (p, t) -> {
                if (t == 5) {
                    p.level().getEntitiesOfClass(Zombie.class, p.getBoundingBox().inflate(4)).forEach(z -> z.setHealth(0.5F));
                    Scythes.use(p, true, false);
                } else if (t == 30) {
                    Scythes.use(p, false, false);
                }
            }, (p, z, before) -> Scythes.souls(p) >= 1 ? null : "no soul, alive " + z.isAlive()));
    }

    private static Item item(String path) {
        return BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("fundamentum", path));
    }

    private static List<Case> cases;
    private static int index = -1, tick, passed;
    private static FakePlayer player;
    private static Zombie zombie;
    private static float health;

    private FlailSelfTest() {}

    static void init() {
        if (!Boolean.getBoolean("fundamentum.flailtest")) {
            return;
        }
        NeoForge.EVENT_BUS.addListener(FlailSelfTest::tick);
    }

    private static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        if (index < 0) {
            if (server.getTickCount() < 40) {
                return;
            }
            level.setChunkForced(0, 0, true);
            player = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("7c1d2e3f-4a5b-4c6d-8e7f-90a1b2c3d4e5"), "flailtest"));
            for (int x = -6; x <= 6; x++) {
                for (int z = -4; z <= 12; z++) {
                    level.setBlockAndUpdate(AT.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                }
            }
            cases = cases();
            index = 0;
            tick = 0;
            // The data checks: the conflicting scythe file is left out, the good ones are in, every example flail resolved.
            boolean bad = Scythes.spec(new ItemStack(Items.GOLDEN_HOE)) == null;
            report("two scythe moves on use are rejected", bad, bad ? "left out" : "it loaded");
            boolean good = Scythes.spec(new ItemStack(item("example_scythe"))) != null && Scythes.spec(new ItemStack(Items.IRON_HOE)) != null;
            report("good scythe files load", good, "example and wave scythe");
            int flails = 0;
            for (FlailPhysics ph : FlailPhysics.values()) {
                FlailSpec s = Flails.spec(new ItemStack(item("example_flail_" + ph.id())));
                if (s != null && s.physics == ph) {
                    flails++;
                }
            }
            report("every example flail resolves", flails == 4 && Flails.errors().isEmpty(), flails + "/4, errors " + Flails.errors());
        }
        if (index >= cases.size()) {
            LOG.info("FLAILTEST DONE {}/{}", passed, cases.size() + 3);
            server.halt(false);
            index = Integer.MAX_VALUE / 2;
            return;
        }
        Case c = cases.get(index);
        // In a fresh world the chunk forced above is not ticking entities yet, so a husk added now would never be hit.
        if (tick == 0 && !level.isPositionEntityTicking(AT.offset(0, 0, 3))) {
            return;
        }
        FakePlayer p = player;
        tick++;
        try {
            if (tick == 1) {
                p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(c.item()));
                p.snapTo(AT.getX() + 0.5, AT.getY(), AT.getZ() + 0.5, 0.0F, 0.0F);
                p.setYHeadRot(0.0F);
                p.setShiftKeyDown(false);
                // A husk: a zombie that doesn't burn in daylight (a burning zombie would pass "was hurt" by itself).
                Zombie z = EntityTypes.HUSK.create(level, EntitySpawnReason.COMMAND);
                z.snapTo(AT.getX() + 0.5, AT.getY(), AT.getZ() + 0.5 + c.ahead(), 180.0F, 0.0F);
                z.setNoAi(true);
                z.setPersistenceRequired();
                level.addFreshEntity(z);
                zombie = z;
                health = z.getHealth();
            }
            p.getCooldowns().tick();
            c.act().at(p, tick);
            Flails.tick(p);
            Scythes.tick(p);
            if (tick >= c.ticks()) {
                String fail = c.check().fail(p, zombie, health);
                report(c.name(), fail == null, fail == null ? "health " + health + " -> " + zombie.getHealth() : fail);
                next();
            }
        } catch (RuntimeException e) {
            report(c.name(), false, e.toString());
            next();
        }
    }

    private static void next() {
        if (zombie != null) {
            zombie.discard();
        }
        Scythes.use(player, false, false);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        Flails.tick(player);
        index++;
        tick = 0;
    }

    private static void report(String name, boolean pass, String detail) {
        if (pass) {
            passed++;
        }
        LOG.info("FLAILTEST {} {}: {}", pass ? "PASS" : "FAIL", name, detail);
    }
}
