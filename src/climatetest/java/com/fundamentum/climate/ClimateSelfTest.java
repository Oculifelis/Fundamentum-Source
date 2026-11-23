package com.fundamentum.climate;

import com.fundamentum.event.FEvents;
import com.fundamentum.port.FClimate;
import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FNet;
import com.fundamentum.port.FVanilla;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The climate test, only in {@code gradlew runServer -Pclimatetest} (world under run-climatetest). The same file on
 * every version. Checks that seasons start off, load in order (a broken file left out), map calendar days, change
 * with the event, give per-biome offsets, and that temperature and humidity add up their biome, season, height,
 * block and weather parts. Then crop growth slowing and speeding, the client sync and tint blend, the commands, and
 * that the Serene Seasons bridge stays out of the way when it isn't installed. Writes climatetest-result.txt and
 * stops the server. Log lines start with CLIMATETEST.
 */
public final class ClimateSelfTest {
    private ClimateSelfTest() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    private static final List<String> LINES = new ArrayList<>();
    private static final List<SeasonChanged> EVENTS = new ArrayList<>();
    private static final int Y = 200;
    private static final FId SPRING = FId.of("fundamentum", "spring");
    private static final FId SUMMER = FId.of("fundamentum", "summer");
    private static final FId AUTUMN = FId.of("fundamentum", "autumn");
    private static final FId WINTER = FId.of("fundamentum", "winter");
    private static boolean failed;
    private static int ticks;
    private static int refreshes;

    public static void init() {
        if (System.getProperty("fundamentum.climatetest") == null) return;
        FEvents.listen(SeasonChanged.class, (SeasonChanged e) -> EVENTS.add(e));
        FLife.serverTick(ClimateSelfTest::tick);
    }

    private static void check(boolean ok, String what) {
        String line = (ok ? "PASS " : "FAIL ") + what;
        if (!ok) failed = true;
        LINES.add(line);
        if (ok) LOG.info("CLIMATETEST {}", line);
        else LOG.error("CLIMATETEST {}", line);
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-6;
    }

    private static void tick(MinecraftServer server) {
        ticks++;
        if (ticks == 5) {
            for (int cx = -1; cx <= 0; cx++) {
                for (int cz = -1; cz <= 0; cz++) server.overworld().setChunkForced(cx, cz, true);
            }
        }
        if (ticks != 40) return;
        try {
            run(server);
        } catch (Throwable t) {
            LOG.error("CLIMATETEST threw", t);
            check(false, "threw " + t);
        }
        try {
            Seasons.enabledValue().reset();
            Seasons.cropGrowthValue().reset();
            Seasons.tintValue().reset();
            Seasons.seasonLengthValue().reset();
            Seasons.seasonLengthsValue().reset();
            Seasons.seasonListValue().reset();
        } catch (Throwable t) {
            check(false, "config reset threw " + t);
        }
        LINES.add(failed ? "RESULT FAIL" : "RESULT PASS");
        LOG.info("CLIMATETEST RESULT {}", failed ? "FAIL" : "PASS");
        try {
            Files.write(Path.of("climatetest-result.txt"), LINES);
        } catch (IOException e) {
            LOG.error("CLIMATETEST could not write the result file", e);
        }
        server.halt(false);
    }

    private static BlockState block(String id) {
        Block b = FVanilla.block(FId.of(id));
        return b.defaultBlockState();
    }

    private static int command(MinecraftServer server, String text) {
        try {
            return server.getCommands().getDispatcher().execute(text, server.createCommandSourceStack().withSuppressedOutput());
        } catch (Exception e) {
            return -1;
        }
    }

    private static void run(MinecraftServer server) {
        ServerLevel level = server.overworld();
        // a clean slate: earlier runs saved their config into this world
        Seasons.enabledValue().reset();
        Seasons.cropGrowthValue().reset();
        Seasons.tintValue().reset();
        Seasons.refresh(server);
        level.setRainLevel(0);

        // off by default
        check(!Seasons.enabled(), "seasons are off by default");
        check(Seasons.current(level) == null, "no season while off");
        check(Seasons.syncFor(server).id().isEmpty(), "clients are told nothing while off");

        // data
        List<FId> ids = new ArrayList<>();
        for (Season s : Seasons.all()) ids.add(s.id());
        check(ids.equals(List.of(SPRING, SUMMER, AUTUMN, WINTER)), "four seasons in year order, the broken file left out " + ids);
        check(Seasons.yearLength() == 32, "a year is 32 days (" + Seasons.yearLength() + ")");
        check(Climate.sources().size() == 3, "three climate block files load (" + Climate.sources().size() + ")");

        // calendar
        check(Seasons.at(0).id().equals(SPRING) && Seasons.at(7).id().equals(SPRING), "days 0-7 are spring");
        check(Seasons.at(8).id().equals(SUMMER), "day 8 is summer");
        check(Seasons.at(31).id().equals(WINTER), "day 31 is winter");
        check(Seasons.at(32).id().equals(SPRING) && Seasons.at(-1).id().equals(WINTER), "the year wraps both ways");
        check(Seasons.dayInSeason(10) == 2, "day 10 is the third day of summer");

        // the config's lengths and season list
        Seasons.seasonLengthValue().set(5);
        check(Seasons.yearLength() == 20 && Seasons.at(5).id().equals(SUMMER), "season_length 5 makes day 5 summer (" + Seasons.yearLength() + ")");
        Seasons.seasonLengthsValue().set("fundamentum:spring=10, fundamentum:winter=2, junk");
        check(Seasons.lengthOf(Seasons.byId(SPRING)) == 10 && Seasons.lengthOf(Seasons.byId(WINTER)) == 2
            && Seasons.lengthOf(Seasons.byId(SUMMER)) == 5, "season_lengths beats season_length for the seasons it names");
        check(Seasons.yearLength() == 22 && Seasons.at(9).id().equals(SPRING) && Seasons.at(10).id().equals(SUMMER),
            "with spring at 10 days day 10 is summer (" + Seasons.yearLength() + ")");
        Seasons.seasonLengthValue().reset();
        Seasons.seasonLengthsValue().reset();
        check(Seasons.yearLength() == 32 && Seasons.at(5).id().equals(SPRING), "back to the files' lengths");
        Seasons.seasonListValue().set(List.of(SUMMER, WINTER));
        check(Seasons.active().size() == 2 && Seasons.yearLength() == 16 && Seasons.at(0).id().equals(SUMMER) && Seasons.at(8).id().equals(WINTER),
            "season_list runs only summer and winter");
        Seasons.seasonListValue().reset();
        check(Seasons.active().size() == 4, "an empty season_list runs all four");

        // parse rules
        Season parsed = Season.parse(FId.of("climatetest", "parsed"), JsonParser.parseString(
            "{\"length\": 3, \"crop_growth\": 0.5, \"crops\": {\"minecraft:carrots\": 2}, \"foliage\": \"#102030\"}").getAsJsonObject());
        check(near(parsed.cropGrowthFor(block("minecraft:carrots")), 2) && near(parsed.cropGrowthFor(block("minecraft:wheat")), 0.5),
            "a per-crop multiplier beats the season's");
        check(parsed.foliage() == 0x102030 && parsed.grass() == -1 && near(parsed.tint(), 0.25), "colours parse and tint defaults to 0.25");
        boolean threw = false;
        try {
            Season.parse(FId.of("climatetest", "zero"), JsonParser.parseString("{\"length\": 0}").getAsJsonObject());
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "a zero-day season is refused");

        // turning on, changing season, the event
        EVENTS.clear();
        Seasons.enabledValue().set(true);
        Seasons.refresh(server);
        check(Seasons.enabled() && Seasons.current(level) != null, "seasons run once turned on");
        check(!EVENTS.isEmpty() && EVENTS.get(EVENTS.size() - 1).from() == null, "turning on posts season_changed from nothing");
        // an earlier run saved its calendar into this world, so start from spring to be sure summer is a change
        Seasons.set(server, SPRING);
        EVENTS.clear();
        check(Seasons.set(server, SUMMER), "set accepts a known season");
        check(Seasons.current(level).id().equals(SUMMER) && Seasons.dayInSeason(Seasons.day(server)) == 0, "set starts summer today");
        check(EVENTS.size() == 1 && SUMMER.equals(EVENTS.get(0).to()), "the change posts season_changed once (" + EVENTS.size() + ")");
        check(!Seasons.set(server, FId.of("climatetest", "nope")), "set refuses an unknown season");
        Seasons.setDay(server, 31);
        check(Seasons.current(level).id().equals(WINTER), "setDay 31 is winter");
        check(FEvents.type(Seasons.SEASON_CHANGED) != null, "season_changed is a registered event");

        // per-biome offsets
        Season winter = Seasons.byId(WINTER);
        Holder<Biome> desert = FClimate.biome(level, FId.of("minecraft", "desert"));
        Holder<Biome> plains = FClimate.biome(level, FId.of("minecraft", "plains"));
        check(desert != null && near(winter.temperatureFor(desert), -0.1), "winter in the desert is only -0.1");
        check(plains != null && near(winter.temperatureFor(plains), -0.35), "winter on the plains is -0.35");

        // the parts of a reading
        BlockPos pos = new BlockPos(0, Y, 0);
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                level.setBlock(new BlockPos(x, Y - 1, z), block("minecraft:stone"), 2);
                for (int y = 0; y < 6; y++) level.setBlock(new BlockPos(x, Y + y, z), block("minecraft:air"), 2);
            }
        }
        Holder<Biome> here = level.getBiome(pos);
        Climate.Reading t = Climate.temperatureParts(level, pos);
        Biome hb = here.value();
        double base = hb.getModifiedClimateSettings().temperatureModifier().modifyTemperature(pos, hb.getBaseTemperature());
        double height = -(Y - level.getSeaLevel() - 17) * Climate.HEIGHT_COOLING;
        check(near(t.biome(), base), "the biome part is the biome's temperature (" + t.biome() + ")");
        check(near(t.season(), winter.temperatureFor(here)), "the season part is winter's for this biome (" + t.season() + ")");
        check(near(t.height(), height), "high ground is colder (" + t.height() + ")");
        check(near(t.blocks(), 0) && near(t.weather(), 0), "no blocks or weather yet");
        check(near(Climate.temperature(level, pos), t.total()), "temperature is the sum of the parts");

        level.setBlock(pos.east(2), block("minecraft:blue_ice"), 2);
        // the test's own cold file (-1.0, radius 4) and the built-in one (-0.4, radius 3) both count
        check(near(Climate.blocks(level, pos, true), -1.0 * (1 - 2 / 5.0) - 0.4 * (1 - 2 / 4.0)), "blue ice two blocks off ("
            + Climate.blocks(level, pos, true) + ")");
        level.setBlock(pos.west(), block("minecraft:blue_ice"), 2);
        double cold = -1.0 * (1 - 1 / 5.0) - 0.4 * (1 - 1 / 4.0);
        check(near(Climate.blocks(level, pos, true), cold), "only the nearest block of each file counts (" + Climate.blocks(level, pos, true) + ")");
        level.setBlock(pos.north(3), block("minecraft:magma_block"), 2);
        check(near(Climate.blocks(level, pos, true), cold + 0.6 * (1 - 3 / 5.0)), "heat and cold add up (" + Climate.blocks(level, pos, true) + ")");
        check(near(Climate.blocks(level, pos, false), -0.1 * (1 - 3 / 5.0)), "magma dries the air (" + Climate.blocks(level, pos, false) + ")");
        level.setBlock(pos.north(3), block("minecraft:air"), 2);
        level.setBlock(pos.west(), block("minecraft:air"), 2);
        level.setBlock(pos.east(2), block("minecraft:air"), 2);
        check(near(Climate.blocks(level, pos, true), 0), "removing the blocks removes their effect");

        level.setRainLevel(1);
        Climate.Reading wetT = Climate.temperatureParts(level, pos);
        Climate.Reading wetH = Climate.humidityParts(level, pos);
        check(near(wetT.weather(), Climate.RAIN_TEMPERATURE) && near(wetH.weather(), Climate.RAIN_HUMIDITY), "rain cools and wets an open spot");
        check(near(wetH.biome(), FClimate.downfall(hb)) && near(wetH.season(), winter.rainfallFor(here)), "humidity has the biome's rainfall and the season's");
        double hum = Climate.humidity(level, pos);
        check(hum >= 0 && hum <= 1, "humidity stays between 0 and 1 (" + hum + ")");
        level.setRainLevel(0);

        // crops: winter slows them
        BlockPos crop = pos.south(2);
        level.setBlock(crop.below(), block("minecraft:farmland"), 2);
        BlockState wheat0 = block("minecraft:wheat");
        level.setBlock(crop, wheat0, 2);
        check(near(Seasons.cropMultiplier(level, wheat0), 0.25), "winter wheat grows at a quarter speed");
        int denied = 0;
        for (int i = 0; i < 400; i++) if (Boolean.FALSE.equals(FClimate.preGrow(level, crop, wheat0))) denied++;
        check(denied > 240 && denied < 360, "about three in four winter growth steps are stopped (" + denied + "/400)");

        // summer speeds them
        Seasons.set(server, SUMMER);
        BlockState wheat1 = Seasons.ageUp(wheat0);
        int none = 0;
        for (int i = 0; i < 100; i++) if (FClimate.preGrow(level, crop, wheat0) == null) none++;
        check(none == 100, "summer never stops a step");
        int bumped = 0;
        for (int i = 0; i < 400; i++) {
            level.setBlock(crop, wheat1, 2);
            FClimate.postGrow(level, crop, wheat0, wheat1);
            if (!level.getBlockState(crop).equals(wheat1)) bumped++;
        }
        check(bumped > 60 && bumped < 140, "about one in four summer steps grows an extra stage (" + bumped + "/400)");
        Seasons.cropGrowthValue().set(false);
        check(near(Seasons.cropMultiplier(level, wheat0), 1), "the crop_growth config turns it off");
        Seasons.cropGrowthValue().reset();
        level.setBlock(crop, block("minecraft:air"), 2);
        BlockState ripe = wheat0;
        while (Seasons.ageUp(ripe) != null) ripe = Seasons.ageUp(ripe);
        check(Seasons.ageUp(ripe) == null && ripe != wheat0, "a ripe crop gets no extra stage");

        // the client copy and tint
        Seasons.set(server, AUTUMN);
        Seasons.Sync sync = Seasons.syncFor(server);
        Season autumn = Seasons.byId(AUTUMN);
        check(sync.id().equals(AUTUMN.toString()) && sync.foliage() == autumn.foliage() && near(sync.tint(), autumn.tint()),
            "the sync carries autumn's colours");
        check(FNet.roundTrip(sync, server.registryAccess()).equals(sync), "the sync survives the network round trip");
        refreshes = 0;
        Seasons.clientRefresh(() -> refreshes++);
        Seasons.applySync(sync);
        check(Seasons.clientSync().equals(sync) && refreshes == 1, "a new tint redraws the world once");
        Seasons.applySync(sync);
        check(refreshes == 1, "the same tint again redraws nothing");
        int green = 0x59AE30;
        check(SeasonTint.foliage(green) == SeasonTint.blend(green, autumn.foliage(), autumn.tint()), "leaves blend toward autumn's colour");
        check(SeasonTint.blend(0x000000, 0xFFFFFF, 0.5) == 0x808080 && SeasonTint.blend(0x123456, 0xFFFFFF, 0) == 0x123456, "the blend maths");
        Seasons.tintValue().set(false);
        check(Seasons.syncFor(server).foliage() == -1 && Seasons.syncFor(server).grass() == -1, "the tint config turns the colours off");
        Seasons.tintValue().reset();
        Seasons.applySync(Seasons.Sync.NONE);
        Seasons.clientRefresh(null);
        check(SeasonTint.grass(green) == green, "no season leaves grass alone");

        // commands
        Seasons.set(server, WINTER);
        check(command(server, "fundamentum season") == 4, "the season command reports winter as the fourth");
        check(command(server, "fundamentum season set summer") == 1 && Seasons.current(level).id().equals(SUMMER), "the set command changes the season");
        check(command(server, "fundamentum season set nonsense") <= 0, "the set command refuses an unknown season");
        check(command(server, "fundamentum climate") != -1, "the climate command runs");

        // Serene Seasons is not in this run
        check(!SereneBridge.available() && !Seasons.sereneActive(), "the Serene Seasons bridge stays off without it");

        // turning off
        EVENTS.clear();
        Seasons.enabledValue().reset();
        Seasons.refresh(server);
        check(Seasons.current(level) == null && !EVENTS.isEmpty() && EVENTS.get(EVENTS.size() - 1).to() == null,
            "turning off posts season_changed to nothing");
        check(near(Seasons.cropMultiplier(level, wheat0), 1), "crops grow normally with seasons off");
    }
}
