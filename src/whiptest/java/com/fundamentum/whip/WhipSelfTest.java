package com.fundamentum.whip;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The in-game half of the whip tests, for development servers started with {@code -Dfundamentum.whiptest=true}
 * ({@code gradlew runServer -Pwhiptest}): a fake player swings the example whip at real creatures high above spawn, one
 * case at a time, and every case logs {@code WHIPTEST PASS} or {@code WHIPTEST FAIL}; then {@code WHIPTEST DONE n/m} and
 * the server stops. It covers the server side (hits along the cord, aim assist, spared targets, end crystals, walls,
 * reach, hooks, summoner marks); the drawn cord needs a client and is not covered.
 */
final class WhipSelfTest {
    private static final Logger LOG = LogUtils.getLogger();
    private static final BlockPos AT = new BlockPos(8, 200, 8);

    /**
     * One case: what stands where (blocks ahead, degrees off the crosshair toward the whip hand) and whether it should be
     * hit. The off-crosshair cases stand 28 degrees off at 2.3 blocks: the whole body outside the aim assist cone, but
     * inside the cord's path on both swing sides (checked offline against {@link WhipSwing} with the default tuning).
     */
    private record Case(String name, EntityType<?> type, double ahead, float off, boolean spared, boolean wall, boolean expectHit,
                        double homing, boolean twin, boolean baby) {
        Case(String name, EntityType<?> type, double ahead, float off, boolean spared, boolean wall, boolean expectHit) {
            this(name, type, ahead, off, spared, wall, expectHit, 0, false, false);
        }

        Case(String name, EntityType<?> type, double ahead, float off, boolean spared, boolean wall, boolean expectHit,
             double homing, boolean twin) {
            this(name, type, ahead, off, spared, wall, expectHit, homing, twin, false);
        }

        /** A baby zombie (under a block tall) standing {@code ahead} blocks out, on the crosshair's line. */
        static Case baby(String name, double ahead) {
            return new Case(name, EntityTypes.ZOMBIE, ahead, 0, false, false, true, 0, false, true);
        }
    }

    private static final List<Case> CASES = List.of(
        new Case("zombie ahead is hit", EntityTypes.ZOMBIE, 4.2, 0, false, false, true),
        new Case("villager on the crosshair is hit", EntityTypes.VILLAGER, 4.2, 0, false, false, true),
        new Case("zombie off the crosshair is hit (hostile)", EntityTypes.ZOMBIE, 2.3, 28, false, false, true),
        new Case("villager off the crosshair is spared (aim assist)", EntityTypes.VILLAGER, 2.3, 28, false, false, false),
        new Case("spared companion on the crosshair is not hit", EntityTypes.VILLAGER, 4.2, 0, true, false, false),
        new Case("end crystal is hit", EntityTypes.END_CRYSTAL, 4.2, 0, false, false, true),
        new Case("zombie behind a wall is not hit", EntityTypes.ZOMBIE, 4.2, 0, false, true, false),
        new Case("zombie past the cord is not hit", EntityTypes.ZOMBIE, 7.5, 0, false, false, false),
        new Case("zombie 60 degrees off is missed without homing", EntityTypes.ZOMBIE, 3.5, 60, false, false, false),
        new Case("homing whip turns to a zombie 60 degrees off", EntityTypes.ZOMBIE, 3.5, 60, false, false, true, 70, false),
        new Case("homing whip does not turn to a villager", EntityTypes.VILLAGER, 3.5, 60, false, false, false, 70, false),
        new Case("two strands go for two zombies either side", EntityTypes.ZOMBIE, 3.5, 50, false, false, true, 70, true),
        // 1.22.3: the swing aims from the hand at the crosshair and comes straight down below the aim. Before, a short
        // mob dead ahead was missed on every other swing (the tilted follow-through carried the cord aside).
        Case.baby("baby zombie dead ahead is hit", 3.0),
        Case.baby("baby zombie close in is hit", 1.8),
        Case.baby("baby zombie at the tip is hit", 4.2));

    private static final int RUNS = CASES.size() * 2;

    private static final AtomicInteger STARTS = new AtomicInteger(), ENDS = new AtomicInteger(), CORD = new AtomicInteger(),
        TIPS = new AtomicInteger(), LANDED = new AtomicInteger(), CRACKS = new AtomicInteger(), STRUCK = new AtomicInteger();
    /** Hits whose contact was not on the creature it hurt (in the air beside it), since the case began. */
    private static final AtomicInteger AIR = new AtomicInteger();
    /** Where this case's swing cracked, or null. */
    private static net.minecraft.world.phys.@Nullable Vec3 crackAt;
    /** {@link #STRUCK} when the case began. */
    private static int struckBefore;
    /** Everything the whip hurt (from {@link WhipHooks#landed}). */
    private static final Set<UUID> HURT = new HashSet<>();
    private static final List<String> RESULTS = new ArrayList<>();
    private static int index = -1, tick;
    private static @Nullable Entity target, twin;
    /** The example whip as the test registers it; homing cases swap in a homing copy for their swing. */
    private static @Nullable WhipDefinition base;
    private static @Nullable FakePlayer player;
    private static float health;

    static void init() {
        if (!Boolean.getBoolean("fundamentum.whiptest")) {
            return;
        }
        WhipTargets.spare((owner, t) -> t.entityTags().contains("whiptest_spared"));
        base = ExampleWhip.definition().toBuilder().hooks(new WhipHooks() {
            @Override
            public void swingStart(net.minecraft.server.level.ServerPlayer p, ItemStack stack, WhipDefinition whip) {
                STARTS.incrementAndGet();
            }

            @Override
            public void swingEnd(net.minecraft.server.level.ServerPlayer p, ItemStack stack, WhipDefinition whip, int targets) {
                ENDS.incrementAndGet();
            }

            @Override
            public void cordHit(WhipHit hit) {
                CORD.incrementAndGet();
                // The hit must be where the cord touches the creature, not in the air beside it.
                if (hit.touched.getBoundingBox().inflate(hit.whip.tuning.hitRadius() + 0.05).contains(hit.contact) == false) {
                    AIR.incrementAndGet();
                    LOG.info("WHIPTEST hit in the air: contact {} off {}", hit.contact, hit.touched.getBoundingBox());
                }
                LOG.info("WHIPTEST hit {} along {} tip {} crit {} speed {} damage {}", hit.target.getType().toShortString(),
                    String.format("%.2f", hit.along), hit.tip, hit.crit, String.format("%.1f", hit.tipSpeed), hit.damage);
            }

            @Override
            public void tipHit(WhipHit hit) {
                TIPS.incrementAndGet();
            }

            @Override
            public void landed(WhipHit hit, boolean hurt) {
                LANDED.incrementAndGet();
                if (hurt) {
                    HURT.add(hit.target.getUUID());
                }
            }

            @Override
            public void crack(net.minecraft.server.level.ServerPlayer p, ItemStack stack, WhipDefinition whip, net.minecraft.world.phys.Vec3 tip) {
                CRACKS.incrementAndGet();
                crackAt = tip;
            }

            @Override
            public void struckBlock(net.minecraft.server.level.ServerPlayer p, ItemStack stack, WhipDefinition whip, BlockPos pos,
                                    net.minecraft.world.phys.Vec3 tip) {
                // Only the test wall counts (the cord also strikes the floor now).
                if (pos.getZ() == AT.getZ() + 2 && pos.getY() >= AT.getY()) {
                    STRUCK.incrementAndGet();
                }
                LOG.info("WHIPTEST struck {} at {}", p.level().getBlockState(pos).getBlock(), pos);
            }
        }).build();
        Whips.register(Whips.EXAMPLE_WHIP.getId(), base);
        Whips.trace = line -> LOG.info("WHIPTEST trace {}", line);
        NeoForge.EVENT_BUS.addListener(WhipSelfTest::tick);
    }

    private static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        if (index < 0) {
            if (server.getTickCount() < 40) {
                return;
            }
            level.setChunkForced(0, 0, true);
            player = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("5a3b0f2e-9c41-4d7e-a6b2-0f1e2d3c4b5a"), "whiptest"));
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Whips.EXAMPLE_WHIP.get()));
            index = 0;
            tick = 0;
            // A floor, as in play (1.22.3): the swing's follow-through comes down onto it. Floating, the cord fell
            // past where a real floor stops it.
            for (int x = -5; x <= 5; x++) {
                for (int z = -3; z <= 7; z++) {
                    level.setBlockAndUpdate(AT.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                }
            }
        }
        FakePlayer p = player;
        if (index >= RUNS) {
            extras(server, level, p);
            return;
        }
        // Each case twice: swings alternate sides, so once on each.
        Case c = CASES.get(index / 2);
        tick++;
        p.getCooldowns().tick();
        Whips.tick(p);
        if (tick == 1) {
            struckBefore = STRUCK.get();
            AIR.set(0);
            crackAt = null;
            p.snapTo(AT.getX() + 0.5, AT.getY(), AT.getZ() + 0.5, 0.0F, 0.0F);
            p.setYHeadRot(0.0F);
            if (c.wall) {
                for (int x = -2; x <= 2; x++) {
                    for (int y = -1; y <= 4; y++) {
                        level.setBlockAndUpdate(AT.offset(x, y, 2), Blocks.STONE.defaultBlockState());
                    }
                }
            }
            if (c.homing > 0) {
                WhipDefinition.Builder b = base.toBuilder().homing(c.homing);
                if (c.twin) {
                    b.tuning(t -> t.strands(2)).strandTargets();
                }
                Whips.register(Whips.EXAMPLE_WHIP.getId(), b.build());
            }
            Entity e = spawn(level, c, c.off);
            twin = c.twin ? spawn(level, c, -c.off) : null;
            if (c.spared) {
                e.addTag("whiptest_spared");
            }
            target = e;
            health = e instanceof LivingEntity l ? l.getHealth() : 0;
        } else if (tick == 5) {
            if (!Whips.swing(p)) {
                report(c, false, "the swing did not start");
            }
        } else if (tick == 40) {
            Entity e = target;
            boolean hit = e instanceof LivingEntity ? HURT.contains(e.getUUID()) : e.isRemoved();
            if (twin != null) {
                hit &= HURT.contains(twin.getUUID());
            }
            String detail = "side " + (index % 2 == 0 ? "first" : "second") + ", "
                + (e instanceof LivingEntity l ? "whip hurt it " + hit + ", health " + health + " -> " + l.getHealth() : "removed " + e.isRemoved())
                + (twin instanceof LivingEntity l2 ? ", twin health " + l2.getHealth() : "");
            if (index == 0) {
                detail += ", marked " + WhipTags.marked(e) + ", hooks start/cord/tip/landed/crack " + STARTS + "/" + CORD + "/" + TIPS + "/"
                    + LANDED + "/" + CRACKS;
                hit &= WhipTags.marked(e) && STARTS.get() == 1 && CORD.get() >= 1 && LANDED.get() >= 1 && CRACKS.get() >= 1;
            }
            boolean pass = hit == c.expectHit;
            if (AIR.get() > 0) {
                detail += ", " + AIR + " hits in the air";
                pass = false;
            }
            if (!c.wall && c.homing == 0) {
                // The crack goes off out in front at about hand height (1.22.1 cracked on the backswing, 1.6 above the eyes).
                net.minecraft.world.phys.Vec3 eye = p.getEyePosition(), at = crackAt;
                double out = at == null ? 0 : at.subtract(eye).dot(p.getLookAngle()), up = at == null ? 0 : at.y - eye.y;
                detail += at == null ? ", no crack" : String.format(", crack %.1f out %.1f up", out, up);
                pass &= at != null && out >= 2.5 && up <= 0.8 && up >= -2.0;
            }
            if (c.wall) {
                // The tip snaps against the wall: the strike hook (sound and sparks) fires.
                int struck = STRUCK.get() - struckBefore;
                detail += ", wall strikes " + struck;
                pass &= struck >= 1;
            }
            report(c, pass, detail);
            e.discard();
            if (twin != null) {
                twin.discard();
            }
            Whips.register(Whips.EXAMPLE_WHIP.getId(), base);
            if (c.wall) {
                for (int x = -2; x <= 2; x++) {
                    for (int y = 0; y <= 4; y++) {
                        level.setBlockAndUpdate(AT.offset(x, y, 2), Blocks.AIR.defaultBlockState());
                    }
                }
            }
            index++;
            tick = 0;
            if (index >= RUNS) {
                extra = 0;
            }
        }
    }

    /** Past the swing cases: a full slam on a zombie ahead, then a homing bolt to a zombie 40 degrees off (1.22.0). */
    private static int extra = -1;

    private static void extras(MinecraftServer server, ServerLevel level, FakePlayer p) {
        tick++;
        if (extra == 0 && tick == 1) {
            p.snapTo(AT.getX() + 0.5, AT.getY(), AT.getZ() + 0.5, 0.0F, 0.0F);
            Case c = new Case("slam", EntityTypes.ZOMBIE, 2.5, 0, false, false, true);
            target = spawn(level, c, 0);
            WhipDefinition slammer = base.toBuilder().slam(WhipDefinition.Slam.of(3.0, 8.0)).build();
            WhipSlam.slam(p, p.getMainHandItem(), slammer, 1.0F);
        } else if (extra == 0 && tick == 5) {
            float hp = ((LivingEntity) target).getHealth();
            report(new Case("full slam hurts a zombie ahead", EntityTypes.ZOMBIE, 2.5, 0, false, false, true), hp < 20.0F,
                "health 20.0 -> " + hp);
            target.discard();
            extra = 1;
            tick = 0;
        } else if (extra == 1 && tick == 1) {
            Case c = new Case("bolt", EntityTypes.ZOMBIE, 4.0, 40, false, false, true);
            target = spawn(level, c, 40);
            WhipFx.bolt(p, p.getEyePosition(), p.getLookAngle(), target, WhipFx.BoltSpec.homing(4.0F, 0xFF40E080),
                p.damageSources().playerAttack(p), (at, hit) -> LOG.info("WHIPTEST bolt ended at {} hitting {} (target at {}, player at {} look {})", at,
                    hit == null ? "nothing" : hit.getType(), target.position(), p.position(), p.getLookAngle()));
        } else if (extra == 1 && tick == 50) {
            float hp = ((LivingEntity) target).getHealth();
            report(new Case("homing bolt turns to a zombie 40 degrees off", EntityTypes.ZOMBIE, 4.0, 40, false, false, true), hp < 20.0F,
                "health 20.0 -> " + hp);
            target.discard();
            long passed = RESULTS.stream().filter(r -> r.startsWith("PASS")).count();
            LOG.info("WHIPTEST DONE {}/{} (swing ends {})", passed, RUNS + 2, ENDS);
            level.setChunkForced(0, 0, false);
            server.halt(false);
            extra = 2;
        }
    }

    /** The case's creature, {@code off} degrees off the crosshair ({@code c.ahead} blocks out), placed and added. */
    private static Entity spawn(ServerLevel level, Case c, float off) {
        Entity e = c.type.create(level, EntitySpawnReason.COMMAND);
        // Yaw 0 looks along +z; turning toward the right hand (yaw up) moves toward -x.
        double rad = Math.toRadians(off);
        e.snapTo(AT.getX() + 0.5 - Math.sin(rad) * c.ahead, AT.getY(), AT.getZ() + 0.5 + Math.cos(rad) * c.ahead, 180.0F + off, 0.0F);
        e.setNoGravity(true);
        if (c.baby && e instanceof net.minecraft.world.entity.monster.zombie.Zombie z) {
            z.setBaby(true);
        }
        if (e instanceof Mob mob) {
            mob.setNoAi(true);
        }
        if (e instanceof LivingEntity l) {
            // High above spawn in daylight: without this the zombies burn, which blurs the health numbers.
            l.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 1200, 0, false, false));
        }
        level.addFreshEntity(e);
        return e;
    }

    private static void report(Case c, boolean pass, String detail) {
        String line = (pass ? "PASS " : "FAIL ") + c.name + ": " + detail;
        RESULTS.add(line);
        LOG.info("WHIPTEST {}", line);
    }

    private WhipSelfTest() {}
}
