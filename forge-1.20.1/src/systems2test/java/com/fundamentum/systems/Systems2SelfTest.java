package com.fundamentum.systems;

import com.fundamentum.boss.Boss;
import com.fundamentum.hud.HudLayers;
import com.fundamentum.mana.Mana;
import com.fundamentum.mana.ManaSource;
import com.fundamentum.boss.BossEvents;
import com.fundamentum.boss.BossRecords;
import com.fundamentum.boss.Bosses;
import com.fundamentum.loot.BossDrops;
import com.fundamentum.loot.LootPools;
import com.fundamentum.material.MaterialColor;
import com.fundamentum.material.MaterialSet;
import com.fundamentum.material.MaterialSets;
import com.fundamentum.material.MaterialSwapProcessor;
import com.fundamentum.material.Materials;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

/**
 * The systems test, only in {@code gradlew runServer -Psystems2test} (fresh world under run-systems2test). Bosses: phases
 * change the bar and fire events, the arena resets when empty, the defeat record is saved. Loot: an addition lands in a
 * vanilla chest, a pool keeps its weights, from_pool and the pool condition work, boss drops are per player with a
 * first-kill bonus. Material sets: oak and stone brick roles, a datapack override, fallbacks, the processor swapping a
 * template between two sets, the Orbis Novus cases (ranked queries, alien filter, seeded picks, usability, data
 * disable, colour, wear fallback, family, biome ranking) and block_swap parity. Mana: the bridge picks the source by priority
 * and handles(), spends all or nothing, restores up to max. HUD layers: order, ties, switches, hide all, the config file. Writes systems2test-result.txt and
 * stops the server. Log lines start with SYSTEMS2TEST.
 */
@EventBusSubscriber(modid = "fundamentum")
public final class Systems2SelfTest {
    private Systems2SelfTest() { }

    private static final Logger LOG = LogUtils.getLogger();
    private static final List<String> LINES = new ArrayList<>();
    private static boolean failed;

    private static void check(boolean ok, String what) {
        String line = (ok ? "PASS " : "FAIL ") + what;
        if (!ok) failed = true;
        LINES.add(line);
        LOG.info("SYSTEMS2TEST {}", line);
    }

    private static void note(String what) {
        LINES.add("NOTE " + what);
        LOG.info("SYSTEMS2TEST NOTE {}", what);
    }

    private static ResourceLocation id(String s) {
        return new ResourceLocation(s);
    }

    private static ResourceLocation test(String path) {
        return new ResourceLocation("fundamentum_test", path);
    }

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (System.getProperty("fundamentum.systems2test") == null) return;
        MinecraftServer server = event.getServer();
        for (Runnable part : List.<Runnable>of(() -> bosses(server), () -> loot(server), () -> materials(server), () -> orbis(server), () -> mana(server), () -> hud())) {
            try {
                part.run();
            } catch (Throwable t) {
                check(false, "test threw " + t);
                LOG.error("SYSTEMS2TEST threw", t);
            }
        }
        LINES.add(failed ? "RESULT FAIL" : "RESULT PASS");
        LOG.info("SYSTEMS2TEST RESULT {}", failed ? "FAIL" : "PASS");
        try {
            Files.write(Path.of("systems2test-result.txt"), LINES);
        } catch (IOException e) {
            LOG.error("SYSTEMS2TEST could not write the result file", e);
        }
        server.halt(false);
    }

    private static ServerPlayer fake(ServerLevel level, String name) {
        return FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name));
    }

    // ------------------------------------------------------------------------------------------------ bosses
    private static void bosses(MinecraftServer server) {
        ServerLevel level = server.overworld();
        BlockPos at = new BlockPos(0, 200, 0);
        level.getChunk(at);
        check(Bosses.spec(EntityType.HUSK) != null, "boss data file gives husks a spec");
        Husk husk = EntityType.HUSK.create(level);
        husk.setNoAi(true);
        husk.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        level.addFreshEntity(husk);
        Boss boss = Bosses.get(husk);
        check(boss != null, "husk became a boss on joining the level");
        if (boss == null) return;
        List<String> phases = new ArrayList<>();
        List<Boss> resets = new ArrayList<>();
        List<BossEvents.Defeated> defeats = new ArrayList<>();
        MinecraftForge.EVENT_BUS.addListener((BossEvents.Phase e) -> { if (e.boss() == boss) phases.add(e.from() + ">" + e.to()); });
        MinecraftForge.EVENT_BUS.addListener((BossEvents.Reset e) -> { if (e.boss() == boss) resets.add(e.boss()); });
        MinecraftForge.EVENT_BUS.addListener((BossEvents.Defeated e) -> { if (e.boss() == boss) defeats.add(e); });

        ServerPlayer p = fake(level, "fighter_one");
        p.setPos(at.getX() + 4, at.getY(), at.getZ());
        List<ServerPlayer> players = List.of(p);
        boss.tick(players);
        check(boss.engaged() && boss.fighters().contains(p.getUUID()), "player in the arena is a fighter");
        check(boss.bar().getPlayers().contains(p), "arena player sees the bar");
        check("base".equals(boss.phase()) && boss.bar().getColor() == BossEvent.BossBarColor.PURPLE, "starts in base phase, purple bar: " + boss.phase() + " " + boss.bar().getColor());
        check(boss.bar().shouldDarkenScreen(), "darken flag on the bar");

        husk.setHealth(husk.getMaxHealth() * 0.4f);
        boss.tick(players);
        check("enraged".equals(boss.phase()), "40% health enters phase enraged: " + boss.phase());
        check(boss.bar().getColor() == BossEvent.BossBarColor.RED, "enraged bar is red: " + boss.bar().getColor());
        check("Enraged Husk".equals(boss.bar().getName().getString()), "enraged bar renamed: " + boss.bar().getName().getString());
        check(Math.abs(boss.bar().getProgress() - 0.4f) < 0.01f, "bar progress follows health: " + boss.bar().getProgress());
        husk.setHealth(husk.getMaxHealth() * 0.2f);
        boss.tick(players);
        check("desperate".equals(boss.phase()) && boss.bar().getColor() == BossEvent.BossBarColor.WHITE, "20% health enters phase desperate, white bar");
        check(phases.equals(List.of("base>enraged", "enraged>desperate")), "phase events fired in order: " + phases);

        p.setPos(at.getX() + 100, at.getY(), at.getZ());
        for (int i = 0; i < 4; i++) boss.tick(players);
        check(resets.isEmpty() && husk.getHealth() < husk.getMaxHealth(), "no reset before reset_when_empty ticks");
        for (int i = 0; i < 2; i++) boss.tick(players);
        check(resets.size() == 1, "arena reset fired once when empty: " + resets.size());
        check(husk.getHealth() == husk.getMaxHealth(), "reset healed the boss: " + husk.getHealth());
        check("base".equals(boss.phase()) && boss.fighters().isEmpty(), "reset returned to base and forgot fighters");
        check(!boss.bar().getPlayers().contains(p), "player outside the arena lost the bar");

        p.setPos(at.getX() + 3, at.getY(), at.getZ() + 3);
        boss.tick(players);
        ResourceLocation sid = boss.spec().id();
        BossRecords records = BossRecords.get(server);
        records.clear(sid);
        check(!records.defeated(sid), "no record before the defeat");
        Bosses.defeat(server, boss);
        check(records.defeated(sid) && records.defeated(sid, p.getUUID()), "defeat recorded for world and player");
        check(!records.defeated(sid, UUID.randomUUID()), "other players have no record");
        check(defeats.size() == 1 && defeats.get(0).firstKill().contains(p.getUUID()) && defeats.get(0).firstInWorld(), "defeated event: first kill and first in world");
        check(Bosses.get(husk) == null && boss.bar().getPlayers().isEmpty(), "defeat ended the bar");
        server.saveEverything(true, true, true);
        Path data = server.getWorldPath(LevelResource.ROOT);
        Optional<Path> file = Optional.empty();
        try (Stream<Path> s = Files.walk(data, 6)) {
            file = s.filter(f -> f.getFileName().toString().contains("bosses") && f.toString().endsWith(".dat")).findFirst();
        } catch (IOException e) {
            note("walk " + e);
        }
        check(file.isPresent(), "defeat record saved under " + data + ": " + file.map(Path::toString).orElse("none"));
        if (file.isPresent()) {
            try {
                String nbt = NbtIo.readCompressed(file.get().toFile()).toString();
                check(nbt.contains(sid.toString()) && nbt.contains(p.getUUID().toString()), "saved record names the boss and the player");
            } catch (IOException e) {
                check(false, "read saved record " + e);
            }
        }
        husk.discard();
    }

    // ------------------------------------------------------------------------------------------------ loot
    private static void loot(MinecraftServer server) {
        ServerLevel level = server.overworld();
        LootTable dungeon = server.getLootData().getLootTable(id("minecraft:chests/simple_dungeon"));
        LootParams params = new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, Vec3.ZERO).create(LootContextParamSets.CHEST);
        List<ItemStack> got = dungeon.getRandomItems(params);
        check(got.stream().anyMatch(s -> s.is(Items.NETHER_STAR)), "loot addition put a nether star in simple_dungeon: " + got);
        LootTable other = server.getLootData().getLootTable(id("minecraft:chests/abandoned_mineshaft"));
        check(other.getRandomItems(params).stream().noneMatch(s -> s.is(Items.NETHER_STAR)), "addition stays out of other chests");

        RandomSource r = RandomSource.create(1234);
        int diamonds = 0, emeralds = 0, n = 4000;
        for (int i = 0; i < n; i++) {
            for (ItemStack s : LootPools.roll(test("weights"), r)) {
                if (s.is(Items.DIAMOND)) diamonds++;
                else if (s.is(Items.EMERALD)) emeralds++;
            }
        }
        double share = diamonds / (double) n;
        check(diamonds + emeralds == n && Math.abs(share - 0.75) < 0.03, "pool weights 3:1 hold over 4000 seeded rolls: " + diamonds + "/" + emeralds);
        RandomSource r1 = RandomSource.create(99), r2 = RandomSource.create(99);
        boolean same = true;
        for (int i = 0; i < 50; i++) same &= sameStacks(LootPools.roll(test("weights"), r1), LootPools.roll(test("weights"), r2));
        check(same, "same seed gives the same rolls");

        LootTable fromPool = server.getLootData().getLootTable(test("from_pool_test"));
        List<ItemStack> fp = fromPool.getRandomItems(params);
        check(fp.size() == 1 && (fp.get(0).is(Items.DIAMOND) || fp.get(0).is(Items.EMERALD)), "from_pool rolled the pool, pool condition dropped the missing one: " + fp);

        BlockPos at = new BlockPos(32, 200, 32);
        level.getChunk(at);
        Husk husk = EntityType.HUSK.create(level);
        husk.setNoAi(true);
        husk.setPos(at.getX(), at.getY(), at.getZ());
        level.addFreshEntity(husk);
        Boss boss = Bosses.get(husk);
        if (boss == null) {
            check(false, "drop test boss");
            return;
        }
        BossDrops.Spec spec = BossDrops.spec(boss);
        check(spec != null && test("boss_drop").equals(spec.pool()) && spec.perPlayer(), "boss drops read from the boss file");
        ServerPlayer p1 = fake(level, "drop_one"), p2 = fake(level, "drop_two");
        Map<UUID, List<ItemStack>> drops = BossDrops.dropFor(boss, spec, List.of(p1, p2), Set.of(p1.getUUID()));
        List<ItemStack> d1 = drops.getOrDefault(p1.getUUID(), List.of()), d2 = drops.getOrDefault(p2.getUUID(), List.of());
        check(drops.size() == 2, "each player got their own roll: " + drops.size());
        check(d1.stream().anyMatch(s -> s.is(Items.GOLD_INGOT)) && d2.stream().anyMatch(s -> s.is(Items.GOLD_INGOT)), "both players got the boss pool");
        check(d1.stream().anyMatch(s -> s.is(Items.NETHER_STAR)) && d2.stream().noneMatch(s -> s.is(Items.NETHER_STAR)), "only the first-time player got the first-kill bonus");
        husk.discard();
    }

    // ------------------------------------------------------------------------------------------------ materials
    private static Block block(String id) {
        return BuiltInRegistries.BLOCK.get(id(id));
    }

    private static boolean is(Optional<BlockState> st, String id) {
        return st.isPresent() && st.get().is(block(id));
    }

    private static String show(Optional<BlockState> st) {
        return st.map(s -> BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString()).orElse("none");
    }

    private static void materials(MinecraftServer server) {
        note("material sets: " + MaterialSets.all().size() + " on, " + MaterialSets.allIncludingDisabled().size() + " in all");
        Optional<MaterialSet> oak = MaterialSets.get(id("minecraft:oak"));
        check(oak.isPresent() && oak.get().kind() == MaterialSet.Kind.WOOD && oak.get().detected(), "oak detected as a wood set");
        oak.ifPresent(o -> {
            check(is(o.block("main", "base"), "minecraft:oak_planks") && is(o.block("main", "stairs"), "minecraft:oak_stairs")
                && is(o.block("main", "slab"), "minecraft:oak_slab"), "oak base/stairs/slab");
            check(is(o.block("main", "fence"), "minecraft:oak_fence") && is(o.block("main", "fence_gate"), "minecraft:oak_fence_gate")
                && is(o.block("main", "door"), "minecraft:oak_door") && is(o.block("main", "trapdoor"), "minecraft:oak_trapdoor"), "oak fence/gate/door/trapdoor");
            check(is(o.block("log", "base"), "minecraft:oak_log") && is(o.block("stripped", "base"), "minecraft:stripped_oak_log")
                && is(o.block("log", "wood"), "minecraft:oak_wood"), "oak log/stripped/wood");
            check(is(MaterialSets.role(o, "stairs"), "minecraft:oak_stairs"), "role(oak, stairs)");
            check(is(MaterialSets.role(o, "timber:base"), "minecraft:stripped_oak_log"), "timber falls back to stripped");
        });
        Optional<MaterialSet> sb = MaterialSets.get(id("minecraft:stone_bricks"));
        check(sb.isPresent() && sb.get().kind() == MaterialSet.Kind.STONE, "stone_bricks detected as a stone set");
        sb.ifPresent(s -> {
            check(is(s.block("main", "stairs"), "minecraft:stone_brick_stairs") && is(s.block("main", "slab"), "minecraft:stone_brick_slab")
                && is(s.block("main", "wall"), "minecraft:stone_brick_wall"), "stone brick stairs/slab/wall");
            check(is(s.block("main", "base", "mossy"), "minecraft:mossy_stone_bricks") && is(s.block("main", "base", "cracked"), "minecraft:cracked_stone_bricks")
                && is(s.block("main", "base", "chiseled"), "minecraft:chiseled_stone_bricks"), "stone brick wears mossy/cracked/chiseled");
            check(is(s.block("main", "stairs", "mossy"), "minecraft:mossy_stone_brick_stairs"), "mossy stairs as a wear");
            check(MaterialSets.of(block("minecraft:stone_brick_stairs")).map(MaterialSet::id).filter(id("minecraft:stone_bricks")::equals).isPresent(),
                "of(stone_brick_stairs) finds stone_bricks");
            check(is(s.resolve("wall_accent", "stairs", ""), "minecraft:stone_brick_stairs"), "wall_accent falls back to main");
            check(is(s.resolve("main", "button", "cracked"), "minecraft:stone_brick_wall") == false, "button stays empty, never a wall");
            check(is(s.resolve("main", "stairs", "polished"), "minecraft:stone_brick_stairs"), "missing wear falls back to plain");
            check(s.resolve("main", "fence", "").isEmpty(), "missing fence stays empty (caller keeps vanilla)");
            check(is(MaterialSets.role(s, "floor:slab"), "minecraft:stone_brick_slab"), "role(floor:slab) falls back to main");
            check(is(s.resolve("main", "door", ""), "minecraft:stone_bricks") == false, "missing door does not become the base block");
        });
        Optional<MaterialSet> bricks = MaterialSets.get(id("minecraft:bricks"));
        check(bricks.isPresent() && is(bricks.get().block("main", "wall"), "minecraft:nether_brick_wall"), "datapack override replaced the bricks wall: "
            + bricks.map(b -> show(b.block("main", "wall"))).orElse("no set"));
        check(bricks.isPresent() && bricks.get().tags().contains("test_override"), "datapack override added a tag");
        check(bricks.isPresent() && bricks.get().block("main", "base", "mossy").filter(st -> st.is(Blocks.BRICKS)).isPresent(), "missing wear on bricks gives plain bricks");
        check(MaterialSets.withTag("test_override").stream().anyMatch(m -> m.id().equals(id("minecraft:bricks"))), "withTag finds the override tag");
        check(is(MaterialSets.role(id("minecraft:bricks"), "stairs"), "minecraft:brick_stairs"), "role by id");
        check(MaterialSets.role(id("fundamentum_test:no_such_set"), "stairs").isEmpty(), "unknown set gives empty");

        // template swap between two sets
        ServerLevel level = server.overworld();
        BlockPos o = new BlockPos(64, 200, 64);
        level.getChunk(o);
        BlockState stairs = Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST);
        BlockState[] row = {Blocks.STONE_BRICKS.defaultBlockState(), stairs, Blocks.MOSSY_STONE_BRICKS.defaultBlockState(),
            Blocks.CRACKED_STONE_BRICKS.defaultBlockState(), Blocks.STONE_BRICK_WALL.defaultBlockState(), Blocks.DIRT.defaultBlockState(),
            Blocks.STONE_BRICK_SLAB.defaultBlockState()};
        for (int i = 0; i < row.length; i++) level.setBlock(o.offset(i, 0, 0), row[i], 2);
        StructureTemplate t = new StructureTemplate();
        t.fillFromWorld(level, o, new Vec3i(row.length, 1, 1), false, Blocks.STRUCTURE_VOID);
        BlockPos dst = o.offset(0, 4, 0);
        StructurePlaceSettings settings = new StructurePlaceSettings().addProcessor(
            MaterialSwapProcessor.between(id("minecraft:stone_bricks"), id("minecraft:deepslate_bricks")));
        t.placeInWorld(level, dst, dst, settings, RandomSource.create(1), 2);
        String[] want = {"minecraft:deepslate_bricks", "minecraft:deepslate_brick_stairs", "minecraft:deepslate_bricks",
            "minecraft:cracked_deepslate_bricks", "minecraft:deepslate_brick_wall", "minecraft:dirt", "minecraft:deepslate_brick_slab"};
        List<String> placed = new ArrayList<>();
        boolean all = true;
        for (int i = 0; i < want.length; i++) {
            BlockState st = level.getBlockState(dst.offset(i, 0, 0));
            placed.add(BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString());
            all &= st.is(block(want[i]));
        }
        check(all, "processor swapped the template stone_bricks -> deepslate_bricks: " + placed);
        BlockState ps = level.getBlockState(dst.offset(1, 0, 0));
        check(ps.hasProperty(StairBlock.FACING) && ps.getValue(StairBlock.FACING) == Direction.EAST, "stairs kept their facing");
        // and back the other way
        BlockPos back = o.offset(0, 8, 0);
        StructureTemplate t2 = new StructureTemplate();
        t2.fillFromWorld(level, dst, new Vec3i(row.length, 1, 1), false, Blocks.STRUCTURE_VOID);
        t2.placeInWorld(level, back, back, new StructurePlaceSettings().addProcessor(
            MaterialSwapProcessor.between(id("minecraft:deepslate_bricks"), id("minecraft:stone_bricks"))), RandomSource.create(1), 2);
        check(level.getBlockState(back.offset(3, 0, 0)).is(Blocks.CRACKED_STONE_BRICKS) && level.getBlockState(back.offset(1, 0, 0)).is(Blocks.STONE_BRICK_STAIRS),
            "processor swaps back deepslate_bricks -> stone_bricks");

        // datapack processor json (the shape a template pool uses)
        var json = JsonParser.parseString("{\"from\": \"minecraft:stone_bricks\", \"to\": [{\"set\": \"minecraft:bricks\", \"weight\": 1}]}");
        check(MaterialSwapProcessor.CODEC.codec().parse(JsonOps.INSTANCE, json).result().isPresent(), "processor json with from/to decodes");
        check(BuiltInRegistries.STRUCTURE_PROCESSOR.containsKey(id("fundamentum:material_swap")), "fundamentum:material_swap processor type registered");

        try {
            Path dump = Path.of("fundamentum-materials-test.json");
            int n = Materials.dump(dump);
            String text = Files.readString(dump);
            check(n == MaterialSets.allIncludingDisabled().size() && text.contains("\"minecraft:stone_bricks\"") && text.contains("usable"), "materials dump wrote " + n + " sets");
        } catch (IOException e) {
            check(false, "materials dump " + e);
        }
        check(server.getCommands().getDispatcher().getRoot().getChild("fundamentum") != null
            && server.getCommands().getDispatcher().getRoot().getChild("fundamentum").getChild("materials") != null, "/fundamentum materials command registered");
    }

    // ------------------------------------------------------------------------------------------------ orbis novus cases
    private static void orbis(MinecraftServer server) {
        // code sets: one only half (base + slab), one with a biome tag
        MaterialSets.register(MaterialSet.builder(test("half")).kind(MaterialSet.Kind.STONE)
            .block("main", "base", Blocks.SMOOTH_STONE).block("main", "slab", Blocks.SMOOTH_STONE_SLAB));
        MaterialSets.register(MaterialSet.builder(test("badlands_stone")).kind(MaterialSet.Kind.STONE).biomeTag("#minecraft:is_badlands")
            .block("main", "base", Blocks.CUT_RED_SANDSTONE).block("main", "stairs", Blocks.RED_SANDSTONE_STAIRS)
            .block("main", "slab", Blocks.CUT_RED_SANDSTONE_SLAB));
        MaterialSets.rebuild();

        List<MaterialSets.Match> ranked = MaterialSets.query(MaterialSet.Kind.STONE).slots("wall", "floor").color("#4a4a54").limit(12).run();
        note("ranked for stone wall+floor #4a4a54: " + ranked.stream().limit(6).map(m -> m.set().id() + " " + m.set().hex() + " " + Math.round(m.score() * 10) / 10.0).toList());
        check(!ranked.isEmpty(), "ranked query returns sets");
        check(ranked.stream().noneMatch(m -> m.set().alien()), "no alien sets without allowAlien");
        check(ranked.stream().allMatch(m -> m.set().usable(List.of("wall", "floor"))), "every answer is usable for wall and floor");
        check(ranked.stream().anyMatch(m -> m.set().isVanilla()), "vanilla is always among the answers");
        boolean sorted = true;
        for (int i = 1; i < ranked.size(); i++) sorted &= ranked.get(i - 1).score() >= ranked.get(i).score();
        check(sorted, "answers sorted by score");
        check(!ranked.isEmpty() && ranked.get(0).set().lightness() < 50, "top pick for a dark grey is dark: " + (ranked.isEmpty() ? "-" : ranked.get(0).set().id() + " L=" + ranked.get(0).set().lightness()));
        check(ranked.stream().noneMatch(m -> m.set().id().equals(test("half"))), "half set (no stairs) is not usable and left out");
        check(MaterialSets.get(test("half")).map(s -> !s.usable("main")).orElse(false), "half set exists but is not usable");

        List<MaterialSets.Match> nether = MaterialSets.query(MaterialSet.Kind.STONE).dimension("nether").limit(50).run();
        check(nether.stream().anyMatch(m -> m.set().id().equals(id("minecraft:nether_bricks"))), "dimension(nether) includes nether_bricks");
        check(nether.stream().filter(m -> !m.set().isVanilla() || m.set().alien()).allMatch(m -> "nether".equals(m.set().dimension())), "dimension(nether) holds only nether sets");
        check(MaterialSets.get(id("minecraft:nether_bricks")).map(MaterialSet::alien).orElse(false), "nether_bricks is alien");

        check(MaterialSets.allIncludingDisabled().containsKey(id("minecraft:mud_bricks")) && !MaterialSets.enabled(id("minecraft:mud_bricks")) && MaterialSets.get(id("minecraft:mud_bricks")).isEmpty(), "data file turned mud_bricks off");
        check(MaterialSets.all().stream().noneMatch(s -> s.id().equals(id("minecraft:mud_bricks"))), "off set left out of all()");
        check(MaterialSets.query(MaterialSet.Kind.STONE).limit(500).run().stream().noneMatch(m -> m.set().id().equals(id("minecraft:mud_bricks"))), "off set left out of queries");

        long salt = 7;
        MaterialSet a = MaterialSets.pick(ranked, MaterialSets.seed(12345L, 3, -9, salt), 5);
        MaterialSet b = MaterialSets.pick(ranked, MaterialSets.seed(12345L, 3, -9, salt), 5);
        check(a != null && a == b, "same world seed and chunk give the same pick");
        Set<ResourceLocation> seen = new HashSet<>();
        for (int i = 0; i < 50; i++) seen.add(MaterialSets.pick(ranked, MaterialSets.seed(12345L, i * 7, i * -3, salt), 5).id());
        check(seen.size() >= 2, "picks vary across 50 chunks: " + seen.size() + " sets");
        MaterialSet second = MaterialSets.pickMix(ranked, MaterialSets.seed(12345L, 3, -9, salt), 5, a);
        check(second != null && (second != a || ranked.size() < 2), "mix pick differs from the first");

        check(MaterialColor.parseHex("#4a4a54") == 0x4a4a54 && "#4a4a54".equals(MaterialColor.hex(0x4a4a54)), "hex parses and prints");
        float[] white = MaterialColor.lab(0xffffff), black = MaterialColor.lab(0x000000);
        check(Math.abs(white[0] - 100) < 0.5 && Math.abs(white[1]) < 0.5 && Math.abs(white[2]) < 0.5 && Math.abs(black[0]) < 0.5, "Lab of white and black");
        check(MaterialSets.get(id("minecraft:stone_bricks")).map(s -> s.lightness() > MaterialSets.get(id("minecraft:deepslate_bricks")).get().lightness()).orElse(false),
            "stone_bricks lighter than deepslate_bricks");
        check(MaterialSets.get(id("minecraft:deepslate_bricks")).map(s -> is(s.block("main", "base", "mossy"), "minecraft:deepslate_bricks")
            && is(s.block("main", "base", "cracked"), "minecraft:cracked_deepslate_bricks")).orElse(false), "block(slot, shape, wear): missing wear falls back, present wear used");
        check(MaterialSets.get(id("minecraft:deepslate_bricks")).map(s -> s.block("roof", "stairs", "").isEmpty() && s.resolve("roof", "stairs", "").isPresent()).orElse(false),
            "block() is strict on slot, resolve() follows the slot fallback");
        check(MaterialSets.get(id("minecraft:mossy_stone_bricks")).map(s -> "minecraft:stone_bricks".equals(s.family())).orElse(false), "mossy_stone_bricks is in the stone_bricks family");
        check(MaterialSets.query(MaterialSet.Kind.STONE).family("minecraft:stone_bricks").limit(20).run().stream().filter(m -> m.score() > -500)
            .allMatch(m -> "minecraft:stone_bricks".equals(m.set().family())), "family filter");

        List<MaterialSets.Match> bad = MaterialSets.query(MaterialSet.Kind.STONE).biome(id("minecraft:badlands"), List.of("#minecraft:is_badlands")).limit(500).run();
        List<MaterialSets.Match> plain = MaterialSets.query(MaterialSet.Kind.STONE).biome(id("minecraft:plains"), List.of("#minecraft:is_overworld")).limit(500).run();
        int ib = indexOf(bad, test("badlands_stone")), ip = indexOf(plain, test("badlands_stone"));
        check(ib >= 0 && ip >= 0 && ib < ip, "biome tag ranks the badlands set higher in badlands: " + ib + " vs " + ip);

        blockSwapParity(server);
    }

    private static int indexOf(List<MaterialSets.Match> l, ResourceLocation id) {
        for (int i = 0; i < l.size(); i++) if (l.get(i).set().id().equals(id)) return i;
        return -1;
    }

    // ------------------------------------------------------------------------------------------------ block_swap parity
    private static void blockSwapParity(MinecraftServer server) {
        ServerLevel level = server.overworld();
        String json = "{\"sets\": ["
            + "{\"requires\": \"minecraft\", \"swap\": {\"minecraft:stone_brick\": \"minecraft:deepslate_brick\", \"minecraft:stone_bricks\": \"minecraft:deepslate_bricks\"}, \"weight\": 1},"
            + "{\"requires\": \"minecraft\", \"swap\": {\"minecraft:stone_brick\": \"minecraft:polished_blackstone_brick\", \"minecraft:stone_bricks\": \"minecraft:polished_blackstone_bricks\"}, \"weight\": 2},"
            + "{\"requires\": \"minecraft\", \"swap\": {}, \"weight\": 1},"
            + "{\"requires\": \"no_such_mod\", \"swap\": {\"minecraft:stone_bricks\": \"minecraft:bricks\"}, \"weight\": 50}"
            + "], \"mode\": \"random\", \"mix\": 0.3, \"gate\": \"\"}";
        var parsed = MaterialSwapProcessor.CODEC.codec().parse(JsonOps.INSTANCE, JsonParser.parseString(json));
        check(parsed.result().isPresent(), "block_swap-shaped json decodes as material_swap");
        if (parsed.error().isPresent()) return;
        MaterialSwapProcessor proc = parsed.getOrThrow(false, err -> { });
        List<OrbisSet> sets = List.of(
            new OrbisSet("minecraft", Map.of("minecraft:stone_brick", "minecraft:deepslate_brick", "minecraft:stone_bricks", "minecraft:deepslate_bricks"), 1),
            new OrbisSet("minecraft", Map.of("minecraft:stone_brick", "minecraft:polished_blackstone_brick", "minecraft:stone_bricks", "minecraft:polished_blackstone_bricks"), 2),
            new OrbisSet("minecraft", Map.of(), 1),
            new OrbisSet("no_such_mod", Map.of("minecraft:stone_bricks", "minecraft:bricks"), 50));
        OrbisBlockSwap ref = new OrbisBlockSwap(sets, "random", 0.3f);
        BlockState[] inputs = {Blocks.STONE_BRICKS.defaultBlockState(), Blocks.STONE_BRICK_STAIRS.defaultBlockState(), Blocks.STONE_BRICK_WALL.defaultBlockState(),
            Blocks.STONE_BRICK_SLAB.defaultBlockState(), Blocks.DIRT.defaultBlockState()};
        int mismatches = 0;
        Set<String> outcomes = new TreeSet<>();
        for (int i = 0; i < 200; i++) {
            BlockPos pos = new BlockPos((i * 37) % 900 - 450, 60 + i % 20, (i * 53) % 900 - 450);
            BlockPos offset = pos.offset(-(i % 9), 0, -(i % 5));
            BlockState in = inputs[i % inputs.length];
            String got = BuiltInRegistries.BLOCK.getKey(proc.swap(level, offset, pos, in).getBlock()).toString();
            String want = ref.swap(level, offset, pos, BuiltInRegistries.BLOCK.getKey(in.getBlock()).toString());
            if (!got.equals(want)) {
                mismatches++;
                if (mismatches <= 3) note("parity mismatch at " + pos + ": " + got + " vs block_swap " + want);
            }
            if (i % inputs.length == 0) outcomes.add(got);
        }
        check(mismatches == 0, "material_swap matches block_swap on 200 positions (random, mix 0.3): " + mismatches + " differ");
        check(outcomes.size() >= 3, "random mode draws every usable set: " + outcomes);
        check(!outcomes.contains("minecraft:bricks"), "set whose mod is missing never drawn");

        MaterialSwapProcessor.registerGate("systems2test_off", () -> false);
        var gated = MaterialSwapProcessor.CODEC.codec().parse(JsonOps.INSTANCE, JsonParser.parseString(
            "{\"sets\": [{\"requires\": \"minecraft\", \"swap\": {\"minecraft:stone_bricks\": \"minecraft:bricks\"}}], \"gate\": \"systems2test_off\"}")).getOrThrow(false, err -> { });
        check(gated.swap(level, BlockPos.ZERO, BlockPos.ZERO, Blocks.STONE_BRICKS.defaultBlockState()).is(Blocks.STONE_BRICKS), "gate off leaves blocks alone");
        var first = MaterialSwapProcessor.CODEC.codec().parse(JsonOps.INSTANCE, JsonParser.parseString(
            "{\"sets\": [{\"requires\": \"no_such_mod\", \"swap\": {\"minecraft:stone_bricks\": \"minecraft:bricks\"}},"
            + "{\"requires\": \"minecraft\", \"swap\": {\"minecraft:stone_brick\": \"minecraft:mud_brick\", \"minecraft:stone_bricks\": \"minecraft:mud_bricks\"}}]}")).getOrThrow(false, err -> { });
        check(first.swap(level, BlockPos.ZERO, BlockPos.ZERO, Blocks.STONE_BRICK_STAIRS.defaultBlockState()).is(Blocks.MUD_BRICK_STAIRS), "first mode: first loaded set, suffix match on stairs");
        var whole = MaterialSwapProcessor.CODEC.codec().parse(JsonOps.INSTANCE, JsonParser.parseString(
            "{\"sets\": [{\"requires\": \"minecraft\", \"swap\": {\"minecraft:stone_bricks\": \"minecraft:no_such_block\"}}], \"whole\": true}")).getOrThrow(false, err -> { });
        check(whole.swap(level, BlockPos.ZERO, BlockPos.ZERO, Blocks.STONE_BRICKS.defaultBlockState()).is(Blocks.STONE_BRICKS), "whole: a set naming a missing block is skipped");
    }

    /** A plain copy of orbisnovus_dungeons:block_swap's choice, to compare against (gate and config left out). */
    private record OrbisSet(String requires, Map<String, String> swap, float weight) { }

    private static final class OrbisBlockSwap {
        private static final List<String> SUFFIXES = List.of("", "s", "_stairs", "_slab", "_wall", "_fence", "_fence_gate");
        private record Active(Map<String, String> swap, List<String> keys, float weight) { }
        private final List<Active> usable = new ArrayList<>();
        private final String mode;
        private final float mix;
        private final long salt;

        OrbisBlockSwap(List<OrbisSet> sets, String mode, float mix) {
            this.mode = mode;
            this.mix = mix;
            this.salt = String.join(",", new TreeSet<>(sets.isEmpty() ? Set.of() : sets.get(0).swap().keySet())).hashCode();
            for (OrbisSet s : sets) {
                if (!net.minecraftforge.fml.ModList.get().isLoaded(s.requires())) continue;
                List<String> keys = new ArrayList<>(s.swap().keySet());
                keys.sort(Comparator.comparingInt(String::length).reversed());
                this.usable.add(new Active(s.swap(), keys, Math.max(0f, s.weight())));
                if (!"random".equals(mode)) break;
            }
        }

        String swap(ServerLevel level, BlockPos offset, BlockPos pos, String id) {
            if (this.usable.isEmpty()) return id;
            Active a = this.usable.get(0);
            if (this.usable.size() > 1 && "random".equals(this.mode)) {
                long sd = seed(level, offset, pos);
                a = this.mix > 0f && (mix(sd ^ pos.asLong()) >>> 40) < (long) (this.mix * (1L << 24)) ? draw(sd ^ this.salt ^ 0x5DEECE66DL) : draw(sd ^ this.salt);
            }
            String to = a.swap().get(id);
            if (to == null) {
                for (String k : a.keys()) {
                    if (id.startsWith(k) && SUFFIXES.contains(id.substring(k.length()))) { to = a.swap().get(k) + id.substring(k.length()); break; }
                }
            }
            if (to == null) return id;
            ResourceLocation t = ResourceLocation.tryParse(to);
            return t != null && BuiltInRegistries.BLOCK.containsKey(t) ? to : id;
        }

        private Active draw(long seed) {
            float total = 0f;
            for (Active a : this.usable) total += a.weight();
            if (total <= 0f) return this.usable.get(0);
            float r = new Random(seed).nextFloat() * total;
            for (Active a : this.usable) {
                r -= a.weight();
                if (r < 0f) return a;
            }
            return this.usable.get(this.usable.size() - 1);
        }

        private static long seed(ServerLevel level, BlockPos offset, BlockPos pos) {
            long world = ((WorldGenLevel) level).getSeed();
            int ox = offset.getX() >> 4, oz = offset.getZ() >> 4;
            long start = ((long) oz << 32) | (ox & 0xffffffffL);
            try {
                ChunkAccess c = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.STRUCTURE_REFERENCES, false);
                if (c != null) {
                    long best = Long.MAX_VALUE;
                    for (var refs : c.getAllReferences().values()) {
                        for (long ref : refs) {
                            long dx = (int) ref - ox, dz = (int) (ref >> 32) - oz;
                            if (dx * dx + dz * dz < best) {
                                best = dx * dx + dz * dz;
                                start = ref;
                            }
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // outside the generating region
            }
            return mix(world ^ mix(start));
        }

        private static long mix(long z) {
            z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
            z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
            return z ^ (z >>> 31);
        }
    }

    /** ItemStack.listMatches arrived in 1.20.5; same comparison by hand. */
    private static boolean sameStacks(List<ItemStack> a, List<ItemStack> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!ItemStack.matches(a.get(i), b.get(i))) return false;
        return true;
    }

    // ------------------------------------------------------------------------------------------------ mana
    /** A plain pool for the test: per entity, max fixed, only entities in {@code who} (or everyone when null). */
    private static final class TestPool implements ManaSource {
        private final ResourceLocation id;
        private final int priority;
        private final double max;
        private final Set<UUID> who;
        private final Map<UUID, Double> pool = new java.util.HashMap<>();

        TestPool(ResourceLocation id, int priority, double max, Set<UUID> who) {
            this.id = id;
            this.priority = priority;
            this.max = max;
            this.who = who;
        }

        @Override public ResourceLocation id() { return this.id; }
        @Override public int priority() { return this.priority; }
        @Override public boolean handles(LivingEntity e) { return this.who == null || this.who.contains(e.getUUID()); }
        @Override public double get(LivingEntity e) { return this.pool.getOrDefault(e.getUUID(), this.max); }
        @Override public double max(LivingEntity e) { return this.max; }
        @Override public boolean spend(LivingEntity e, double amount) {
            double now = get(e);
            if (now < amount) return false;
            this.pool.put(e.getUUID(), now - amount);
            return true;
        }
        @Override public double restore(LivingEntity e, double amount) {
            double now = get(e);
            double added = Math.min(amount, this.max - now);
            this.pool.put(e.getUUID(), now + added);
            return added;
        }
        @Override public double regenPerTick(LivingEntity e) { return 0.5; }
    }

    private static void mana(MinecraftServer server) {
        ServerLevel level = server.overworld();
        ServerPlayer a = fake(level, "mana_a"), b = fake(level, "mana_b");
        ResourceLocation low = test("mana_low"), high = test("mana_high");
        check(!Mana.has(a) && Mana.get(a) == 0 && Mana.max(a) == 0 && !Mana.spend(a, 1) && Mana.spend(a, 0), "MANA no source: no pool, spend fails, spending 0 succeeds");
        Mana.register(new TestPool(low, 0, 100, null));
        check(Mana.has(a) && Mana.get(a) == 100 && Mana.max(a) == 100 && Mana.fraction(a) == 1.0, "MANA a source gives the pool: " + Mana.get(a) + "/" + Mana.max(a));
        check(Mana.spend(a, 30) && Mana.get(a) == 70 && Mana.get(b) == 100, "MANA spend 30 takes it from that entity only: " + Mana.get(a) + ", " + Mana.get(b));
        check(!Mana.spend(a, 100) && Mana.get(a) == 70, "MANA spending more than there is changes nothing");
        check(!Mana.spend(a, -5) && Mana.get(a) == 70 && Mana.restore(a, -5) == 0, "MANA negative amounts are refused");
        check(Mana.canSpend(a, 70) && !Mana.canSpend(a, 70.5), "MANA canSpend matches the pool");
        check(Mana.restore(a, 50) == 30 && Mana.get(a) == 100, "MANA restore stops at max");
        check(Math.abs(Mana.fraction(a) - 1.0) < 1e-9 && Mana.regenPerTick(a) == 0.5, "MANA fraction and regen come through");
        Set<UUID> casters = new HashSet<>();
        casters.add(b.getUUID());
        Mana.register(new TestPool(high, 10, 40, casters));
        check(Mana.source(a).map(ManaSource::id).filter(low::equals).isPresent() && Mana.source(b).map(ManaSource::id).filter(high::equals).isPresent(),
            "MANA higher priority wins only where it handles the entity");
        check(Mana.max(b) == 40 && Mana.spend(b, 15) && Mana.get(b) == 25, "MANA spends from the chosen pool: " + Mana.get(b));
        check(Mana.source(b, low).isPresent() && Mana.source(b, low).get().get(b) == 100 && Mana.source(a, high).isEmpty(), "MANA a named source can be asked for");
        List<ResourceLocation> order = Mana.sources().stream().map(ManaSource::id).toList();
        check(order.indexOf(high) >= 0 && order.indexOf(high) < order.indexOf(low), "MANA sources listed by priority: " + order);
        Mana.register(new TestPool(high, -1, 40, casters));
        check(Mana.source(b).map(ManaSource::id).filter(low::equals).isPresent() && Mana.sources().stream().filter(s -> s.id().equals(high)).count() == 1,
            "MANA registering the same id replaces it (new priority applies)");
        check(Mana.unregister(high) && Mana.unregister(low) && !Mana.has(a) && !Mana.unregister(low), "MANA unregister removes sources");
    }

    // ------------------------------------------------------------------------------------------------ hud layers
    private static void hud() {
        ResourceLocation bar = test("hud_bar"), heat = test("hud_heat"), combo = test("hud_combo"), off = test("hud_off"), late = test("hud_late");
        HudLayers.resetSettings();
        HudLayers.register(bar, 10);
        HudLayers.register(heat, -5);
        HudLayers.register(combo, 10);
        HudLayers.register(off, 0, false);
        List<ResourceLocation> mine = List.of(bar, heat, combo, off, late);
        check(only(HudLayers.ordered(), mine).equals(List.of(heat, off, bar, combo)), "HUD ordered by order, ties by id: " + only(HudLayers.ordered(), mine));
        check(only(HudLayers.visible(), mine).equals(List.of(heat, bar, combo)), "HUD default-off layer is not drawn: " + only(HudLayers.visible(), mine));
        HudLayers.setEnabled(bar, false);
        HudLayers.setEnabled(off, true);
        check(only(HudLayers.visible(), mine).equals(List.of(heat, off, combo)), "HUD switches turn layers off and on: " + only(HudLayers.visible(), mine));
        HudLayers.setEnabled(bar, null);
        HudLayers.setOrder(heat, 20);
        check(only(HudLayers.visible(), mine).equals(List.of(off, bar, combo, heat)), "HUD order override moves a layer: " + only(HudLayers.visible(), mine));
        HudLayers.setHideAll(true);
        check(HudLayers.visible().isEmpty() && HudLayers.enabled(bar), "HUD hide all hides everything and keeps the switches");
        HudLayers.setHideAll(false);
        HudLayers.setEnabled(late, false);
        HudLayers.setOrder(late, -100);
        check(!HudLayers.enabled(late) && !HudLayers.ordered().contains(late), "HUD settings for an unregistered id wait");
        HudLayers.register(late, 50);
        check(only(HudLayers.ordered(), mine).get(0).equals(late) && !HudLayers.enabled(late), "HUD waiting settings apply on register");
        HudLayers.register(combo, 5);
        check(only(HudLayers.visible(), mine).equals(List.of(off, combo, bar, heat)), "HUD registering again replaces the order: " + only(HudLayers.visible(), mine));
        try {
            Path dir = Files.createDirectories(Path.of("hudtest"));
            Path file = dir.resolve("fundamentum-hud.json");
            HudLayers.saveConfig(file);
            HudLayers.resetSettings();
            check(HudLayers.enabled(late) && HudLayers.order(heat) == -5, "HUD reset drops the settings");
            check(HudLayers.loadConfig(file) && !HudLayers.enabled(late) && HudLayers.order(heat) == 20 && HudLayers.enabled(off), "HUD config file round trip");
            Files.writeString(file, "{\"hide_all\": false, \"layers\": {\"" + bar + "\": {\"enabled\": false, \"order\": -50}, \"bad id!\": {\"order\": 1}}}");
            HudLayers.resetSettings();
            check(HudLayers.loadConfig(file) && !HudLayers.enabled(bar) && HudLayers.order(bar) == -50 && HudLayers.enabled(heat), "HUD hand-written config applies, bad ids skipped");
            Files.writeString(file, "[not json");
            check(!HudLayers.loadConfig(file) && !HudLayers.enabled(bar), "HUD broken config is refused and keeps the settings");
            check(HudLayers.loadConfig(dir.resolve("missing.json")), "HUD missing config is fine");
        } catch (IOException e) {
            check(false, "HUD config file " + e);
        }
        HudLayers.resetSettings();
        for (ResourceLocation id : mine) HudLayers.unregister(id);
        check(only(HudLayers.ordered(), mine).isEmpty(), "HUD unregister removes layers");
    }

    private static List<ResourceLocation> only(List<ResourceLocation> list, List<ResourceLocation> keep) {
        return list.stream().filter(keep::contains).toList();
    }
}
