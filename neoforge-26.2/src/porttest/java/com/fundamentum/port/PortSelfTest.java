package com.fundamentum.port;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;

/**
 * The porting layer test, only in {@code gradlew runServer -Pporttest} (world under run-porttest). The same file on
 * every version: it is written only against the port types and vanilla names that did not change. Checks ids, entity /
 * chunk / level / block entity attachments with a save and load, copy-on-death, level data written to disk, stack data
 * through a save and the network codec, packet codecs and registration, registration refs, tags and the vanilla
 * wrappers. Writes porttest-result.txt and stops the server. Log lines start with PORTTEST.
 */
public final class PortSelfTest {
    private PortSelfTest() { }

    private static final Logger LOG = LogUtils.getLogger();
    private static final List<String> LINES = new ArrayList<>();
    private static boolean failed;

    public record Points(int amount, String label) {
        static final Codec<Points> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("amount").forGetter(Points::amount),
            Codec.STRING.fieldOf("label").forGetter(Points::label)).apply(i, Points::new));
        static final FStreamCodec<Points> STREAM = FStreamCodec.composite(
            FStreamCodec.INT, Points::amount, FStreamCodec.STRING, Points::label, Points::new);
    }

    public enum Mode { ONE, TWO, THREE }

    public record Message(int number, String text, FId id, List<Integer> list, Optional<UUID> who, Mode mode) { }

    static final FStreamCodec<Message> MESSAGE_CODEC = FStreamCodec.composite(
        FStreamCodec.INT, Message::number,
        FStreamCodec.STRING, Message::text,
        FStreamCodec.ID, Message::id,
        FStreamCodec.INT.list(), Message::list,
        FStreamCodec.UUID.optional(), Message::who,
        FStreamCodec.enumOf(Mode.class), Message::mode,
        Message::new);

    public record Ping(int n) { }

    static FAttach<Points> SAVED;
    static FAttach<Points> DEATH;
    static FAttach<Points> MEMORY;
    static FAttach<Points> OWNER_SYNCED;
    static FAttach<Points> TRACKED;
    static FLevelData<Points> LEVEL_DATA;
    static FStackData<Points> STACK_DATA;
    static FReg.Ref<Item> ITEM;
    static FReg.Ref<SoundEvent> SOUND;
    static int handled;

    private static FId id(String path) {
        return FId.of("fundamentum", "porttest_" + path);
    }

    /** Called while the mod is being constructed, when the test is on the class path. */
    public static void init() {
        if (System.getProperty("fundamentum.porttest") == null) return;
        Supplier<Points> zero = () -> new Points(0, "none");
        SAVED = FAttach.builder(id("saved"), zero).codec(Points.CODEC).build();
        DEATH = FAttach.builder(id("death"), zero).codec(Points.CODEC).copyOnDeath().build();
        MEMORY = FAttach.builder(id("memory"), zero).build();
        OWNER_SYNCED = FAttach.builder(id("owner"), zero).codec(Points.CODEC).sync(FAttach.Sync.OWNER, Points.STREAM).build();
        TRACKED = FAttach.builder(id("tracked"), zero).codec(Points.CODEC).sync(FAttach.Sync.TRACKING, Points.STREAM).build();
        LEVEL_DATA = FLevelData.of(id("level"), Points.CODEC, zero);
        STACK_DATA = FStackData.of(id("stack"), Points.CODEC, Points.STREAM);
        ITEM = FReg.item(id("item"), new Item.Properties());
        SOUND = FReg.sound(id("sound"));
        FNet.register(id("message"), Message.class, MESSAGE_CODEC, FNet.Side.BOTH, (m, ctx) -> handled++);
        FNet.register(id("ping"), Ping.class, FStreamCodec.composite(FStreamCodec.INT, Ping::n, Ping::new), FNet.Side.TO_CLIENT, (m, ctx) -> handled++);
        FLife.serverStarted(PortSelfTest::run);
    }

    private static void check(boolean ok, String what) {
        String line = (ok ? "PASS " : "FAIL ") + what;
        if (!ok) failed = true;
        LINES.add(line);
        LOG.info("PORTTEST {}", line);
    }

    private static void note(String what) {
        LINES.add("NOTE " + what);
        LOG.info("PORTTEST NOTE {}", what);
    }

    private static boolean throwsOn(Runnable r) {
        try {
            r.run();
            return false;
        } catch (RuntimeException expected) {
            return true;
        }
    }

    private static void run(MinecraftServer server) {
        List<Runnable> parts = List.of(PortSelfTest::ids, () -> attach(server), () -> levelData(server), () -> stackData(server),
            () -> net(server), () -> registration(server), () -> wrappers(server));
        for (Runnable part : parts) {
            try {
                part.run();
            } catch (Throwable t) {
                check(false, "test threw " + t);
                LOG.error("PORTTEST threw", t);
            }
        }
        LINES.add(failed ? "RESULT FAIL" : "RESULT PASS");
        LOG.info("PORTTEST RESULT {}", failed ? "FAIL" : "PASS");
        try {
            Files.write(Path.of("porttest-result.txt"), LINES);
        } catch (IOException e) {
            LOG.error("PORTTEST could not write the result file", e);
        }
        server.halt(false);
    }

    private static void ids() {
        FId a = FId.of("fundamentum:some/path");
        check(a.namespace().equals("fundamentum") && a.path().equals("some/path"), "FId splits namespace and path");
        check(FId.of("stone").equals(FId.of("minecraft", "stone")), "FId bare path means minecraft");
        check(FId.from(a.vanilla()).equals(a), "FId to vanilla id and back");
        check(a.vanilla().toString().equals("fundamentum:some/path"), "FId vanilla id prints the same");
        check(FId.tryOf("Bad:Caps") == null && !FId.isValid("a:b c") && FId.isValid("a.b-c:d_e/f"), "FId refuses what vanilla refuses");
        check(a.withSuffix("_x").toString().equals("fundamentum:some/path_x") && a.withPrefix("p/").path().equals("p/some/path"), "FId prefix and suffix");
        check(a.flat().equals("fundamentum_some_path"), "FId flat name");
        check(FId.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, FId.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, a).result().get()).result().get().equals(a), "FId codec round trip");
        check(FTags.id(FTags.item(a)).equals(a), "FTags key keeps its id");
    }

    private static void attach(MinecraftServer server) {
        ServerLevel level = server.overworld();
        RegistryAccess access = level.registryAccess();
        LivingEntity z = FVanilla.create(FId.of("zombie"), level);
        check(z != null, "create a zombie");
        check(!SAVED.has(z) && SAVED.getOrNull(z) == null, "entity has no value before set");
        check(SAVED.get(z).equals(new Points(0, "none")), "entity get gives the default");
        SAVED.set(z, new Points(5, "saved"));
        DEATH.set(z, new Points(6, "death"));
        MEMORY.set(z, new Points(7, "memory"));
        TRACKED.set(z, new Points(8, "tracked"));
        OWNER_SYNCED.set(z, new Points(9, "owner"));
        check(SAVED.get(z).equals(new Points(5, "saved")) && SAVED.has(z), "entity set and get");
        check(SAVED.update(z, p -> new Points(p.amount() + 1, p.label())).amount() == 6 && SAVED.get(z).amount() == 6, "entity update");

        CompoundTag tag = FVanilla.saveEntity(z);
        Entity loaded = FVanilla.loadEntity(tag, level);
        check(loaded != null, "entity loads from its saved tag");
        if (loaded != null) {
            check(SAVED.get(loaded).equals(new Points(6, "saved")), "entity attachment survives save and load");
            check(DEATH.get(loaded).equals(new Points(6, "death")), "copy-on-death attachment survives save and load");
            check(TRACKED.get(loaded).equals(new Points(8, "tracked")) && OWNER_SYNCED.get(loaded).equals(new Points(9, "owner")), "synced attachments survive save and load");
            check(!MEMORY.has(loaded), "attachment without a codec is not saved");
        }

        LivingEntity afterDeath = FVanilla.create(FId.of("zombie"), level);
        FAttach.copy(z, afterDeath, true);
        check(DEATH.getOrNull(afterDeath) != null && DEATH.get(afterDeath).equals(new Points(6, "death")), "copy on death keeps the copy-on-death value");
        check(!SAVED.has(afterDeath), "copy on death drops the other values");
        LivingEntity changedDim = FVanilla.create(FId.of("zombie"), level);
        FAttach.copy(z, changedDim, false);
        check(SAVED.has(changedDim) && DEATH.has(changedDim), "copy without death keeps every saved value");
        check(!MEMORY.has(changedDim), "copy without death skips values with no codec");
        check(DEATH.copiesOnDeath() && !SAVED.copiesOnDeath() && SAVED.saved() && !MEMORY.saved(), "attachment flags");
        check(TRACKED.sync() == FAttach.Sync.TRACKING && OWNER_SYNCED.sync() == FAttach.Sync.OWNER, "attachment sync modes");
        SAVED.remove(z);
        check(!SAVED.has(z), "entity remove");

        BlockPos pos = new BlockPos(0, 120, 0);
        LevelChunk chunk = level.getChunkAt(pos);
        SAVED.set(chunk, new Points(11, "chunk"));
        check(SAVED.get(chunk).equals(new Points(11, "chunk")), "chunk set and get");
        check(SAVED.roundTrip(SAVED.get(chunk), access).equals(new Points(11, "chunk")), "chunk value codec round trip");
        SAVED.remove(chunk);
        check(!SAVED.has(chunk), "chunk remove");

        SAVED.set(level, new Points(12, "level"));
        check(SAVED.get(level).equals(new Points(12, "level")), "level set and get");
        SAVED.remove(level);
        check(!SAVED.has(level), "level remove");

        BlockState chest = Blocks.CHEST.defaultBlockState();
        level.setBlockAndUpdate(pos, chest);
        BlockEntity be = level.getBlockEntity(pos);
        check(be != null, "chest has a block entity");
        if (be != null) {
            SAVED.set(be, new Points(13, "block"));
            CompoundTag beTag = FVanilla.saveBlockEntity(be, level);
            BlockEntity beLoaded = FVanilla.loadBlockEntity(pos, chest, beTag, level);
            check(beLoaded != null && SAVED.get(beLoaded).equals(new Points(13, "block")), "block entity attachment survives save and load");
        }
        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());

        check(throwsOn(() -> FAttach.builder(id("late"), () -> new Points(0, "")).build()), "attachment made after registration is refused");
    }

    private static void levelData(MinecraftServer server) {
        ServerLevel level = server.overworld();
        Points before = LEVEL_DATA.get(server);
        note("level data from the last run: " + before);
        Points now = new Points((int) (System.currentTimeMillis() % 100000), "run");
        LEVEL_DATA.set(server, now);
        check(LEVEL_DATA.get(level).equals(now), "level data set and get");
        check(LEVEL_DATA.update(server, p -> new Points(p.amount() + 1, p.label())).amount() == now.amount() + 1, "level data update");
        Points expected = new Points(now.amount() + 1, "run");
        FVanilla.saveWorld(server);
        try {
            Points disk = LEVEL_DATA.readFromDisk(level);
            check(expected.equals(disk), "level data written to disk (" + LEVEL_DATA.file(level).getFileName() + ")");
        } catch (IOException e) {
            check(false, "level data file read: " + e);
        }
        ServerLevel nether = server.getLevel(net.minecraft.world.level.Level.NETHER);
        if (nether != null) {
            check(LEVEL_DATA.get(nether).equals(new Points(0, "none")), "level data is per dimension");
        }
    }

    private static void stackData(MinecraftServer server) {
        ServerLevel level = server.overworld();
        ItemStack stack = new ItemStack(Items.DIAMOND_SWORD);
        check(!STACK_DATA.has(stack) && STACK_DATA.get(stack) == null, "stack has no value before set");
        check(STACK_DATA.getOr(stack, new Points(1, "x")).equals(new Points(1, "x")), "stack getOr fallback");
        STACK_DATA.set(stack, new Points(21, "stack"));
        check(STACK_DATA.get(stack).equals(new Points(21, "stack")), "stack set and get");
        ItemStack copy = stack.copy();
        check(STACK_DATA.get(copy).equals(new Points(21, "stack")), "stack value follows a copy");
        Tag saved = FVanilla.saveStack(stack, level);
        ItemStack loaded = FVanilla.loadStack(saved, level);
        check(STACK_DATA.get(loaded) != null && STACK_DATA.get(loaded).equals(new Points(21, "stack")), "stack value survives save and load");
        ItemStack sent = FStreamCodec.ITEM_STACK.roundTrip(stack, level.registryAccess());
        check(STACK_DATA.get(sent) != null && STACK_DATA.get(sent).equals(new Points(21, "stack")), "stack value survives the network codec");
        STACK_DATA.update(stack, new Points(0, ""), p -> new Points(p.amount() * 2, p.label()));
        check(STACK_DATA.get(stack).amount() == 42, "stack update");
        STACK_DATA.remove(stack);
        check(!STACK_DATA.has(stack), "stack remove");
        check(throwsOn(() -> FStackData.of(id("late"), Codec.INT, FStreamCodec.INT)), "stack data made after registration is refused");
    }

    private static void net(MinecraftServer server) {
        RegistryAccess access = server.overworld().registryAccess();
        Message m = new Message(-3, "hello", FId.of("fundamentum:x/y"), List.of(1, 2, 300000), Optional.of(UUID.randomUUID()), Mode.THREE);
        check(MESSAGE_CODEC.roundTrip(m, access).equals(m), "six-field composite codec round trip");
        Message empty = new Message(0, "", FId.of("a"), List.of(), Optional.empty(), Mode.ONE);
        check(FNet.roundTrip(empty, access).equals(empty), "registered message round trip");
        check(FNet.roundTrip(new Ping(77), access).equals(new Ping(77)), "small message round trip");
        check(FStreamCodec.mapOf(FStreamCodec.STRING, FStreamCodec.LONG).roundTrip(Map.of("a", 1L, "b", Long.MAX_VALUE), access).equals(Map.of("a", 1L, "b", Long.MAX_VALUE)), "map codec round trip");
        check(FStreamCodec.BLOCK_POS.roundTrip(new BlockPos(-5, 64, 9), access).equals(new BlockPos(-5, 64, 9)), "block pos codec round trip");
        CompoundTag t = new CompoundTag();
        t.putString("k", "v");
        check(FStreamCodec.NBT.roundTrip(t, access).equals(t), "tag codec round trip");
        check(FStreamCodec.DOUBLE.roundTrip(1.5, access) == 1.5 && FStreamCodec.BOOL.roundTrip(true, access) && FStreamCodec.FLOAT.roundTrip(2.5f, access) == 2.5f, "number codecs round trip");
        byte[] bytes = { 1, 2, 3 };
        check(java.util.Arrays.equals(FStreamCodec.BYTES.roundTrip(bytes, access), bytes), "bytes codec round trip");
        check(FStreamCodec.unit("same").roundTrip("same", access).equals("same"), "unit codec");
        check(FNet.registered(id("message")) && FNet.registered(id("ping")) && !FNet.registered(id("nothing")), "messages registered by id");
        check(FNet.frozen(), "loader took the registrations");
        check(throwsOn(() -> FNet.register(id("late"), String.class, FStreamCodec.STRING, FNet.Side.BOTH, (s, c) -> { })), "message registered too late is refused");
        check(throwsOn(() -> FNet.toAll("not a message")), "unregistered message is refused");
        check(throwsOn(() -> FNet.toServer(new Ping(1))), "client-only message cannot go to the server");
        check(!throwsOn(() -> FNet.toAll(new Ping(1))), "send to all players");
        check(!throwsOn(() -> FNet.toAll(m)), "send a two-way message to all players");
    }

    private static void registration(MinecraftServer server) {
        check(ITEM.bound() && SOUND.bound(), "registration refs are filled");
        check(FVanilla.item(id("item")) == ITEM.get(), "registered item found by id");
        check(FVanilla.idOf(ITEM.get()).equals(id("item")), "registered item id");
        check(FVanilla.sound(id("sound")) == SOUND.get(), "registered sound found by id");
        check(FVanilla.item(FId.of("fundamentum:no_such_item")) == null, "missing item is null");
        check(FVanilla.block(FId.of("stone")) == Blocks.STONE && FVanilla.entityType(FId.of("zombie")) != null && FVanilla.entityType(FId.of("no_such_mob")) == null, "vanilla lookups by id");
        check(FVanilla.idOf(Blocks.STONE).equals(FId.of("stone")) && FVanilla.idOf(FVanilla.entityType(FId.of("zombie"))).equals(FId.of("zombie")), "vanilla ids");
        check(new ItemStack(Items.OAK_LOG).is(FTags.item(FId.of("logs"))), "item tag");
        check(Blocks.STONE.defaultBlockState().is(FTags.block(FId.of("base_stone_overworld"))), "block tag");
        check(FTags.is(FVanilla.entityType(FId.of("zombie")), FTags.entity(FId.of("undead"))) || FTags.is(FVanilla.entityType(FId.of("skeleton")), FTags.entity(FId.of("skeletons"))), "entity tag");
    }

    private static void wrappers(MinecraftServer server) {
        ServerLevel level = server.overworld();
        LivingEntity z = FVanilla.create(FId.of("zombie"), level);
        z.setPos(0.5, 130, 0.5);
        level.addFreshEntity(z);
        try {
            check(FVanilla.attributeValue(z, Attributes.MAX_HEALTH) == 20.0, "attribute value by constant");
            check(FVanilla.attribute(z, Attributes.MAX_HEALTH) != null, "attribute instance by constant");
            // the id lost its "generic." prefix in 1.21.2
            double byId = Math.max(FVanilla.attributeValue(z, FId.of("max_health")), FVanilla.attributeValue(z, FId.of("generic.max_health")));
            check(byId == 20.0, "attribute value by id");
            float before = z.getHealth();
            check(FVanilla.hurt(z, level.damageSources().generic(), 3) && z.getHealth() < before, "hurt");
            FVanilla.clearHurtCooldown(z); // the first hit leaves the zombie briefly immune
            check(FVanilla.hurt(z, FVanilla.damage(level, FId.of("generic"), null), 1), "hurt with a damage type id");
            check(FVanilla.addEffect(z, MobEffects.GLOWING, 100, 0) && FVanilla.hasEffect(z, MobEffects.GLOWING), "effect add and check");
            ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
            FVanilla.enchant(level, sword, Enchantments.SHARPNESS, 3);
            check(FVanilla.enchantLevel(level, sword, Enchantments.SHARPNESS) == 3, "enchant and read by constant");
            check(FVanilla.enchantLevel(level, sword, FId.of("sharpness")) == 3, "enchantment level by id");
            check(FVanilla.enchantLevel(level, new ItemStack(Items.STICK), Enchantments.SHARPNESS) == 0, "no enchantment is level 0");
            CompoundTag custom = new CompoundTag();
            custom.putInt("n", 7);
            FVanilla.setCustomData(sword, custom);
            check(FVanilla.customData(sword).equals(custom), "custom data set and get");
            FVanilla.updateCustomData(sword, tag -> tag.putString("s", "x"));
            check(FVanilla.customData(sword).size() == 2, "custom data update");
            ItemStack back = FVanilla.loadStack(FVanilla.saveStack(sword, level), level);
            check(ItemStack.matches(sword, back), "stack with enchantment and custom data survives save and load");
            check(!throwsOn(() -> FVanilla.particles(level, ParticleTypes.HEART, 0.5, 130, 0.5, 3, 0.1, 0.1, 0.1, 0)), "particles");
            check(!throwsOn(() -> FVanilla.playSound(level, 0.5, 130, 0.5, SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 0.1f, 1)), "sound");
            check(!throwsOn(() -> FVanilla.playSound(level, 0.5, 130, 0.5, SOUND, SoundSource.BLOCKS, 0.1f, 1)), "registered sound");
            check(FVanilla.fakePlayer(level, "porttest") != null, "fake player");
        } finally {
            z.discard();
        }
    }
}
