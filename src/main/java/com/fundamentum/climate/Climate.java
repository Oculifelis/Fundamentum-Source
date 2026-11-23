package com.fundamentum.climate;

import com.fundamentum.port.FClimate;
import com.fundamentum.port.FId;
import com.fundamentum.port.FTags;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Temperature and humidity at a spot: the biome's own values, the season's offsets, height, heat and cold blocks
 * nearby ({@code data/<ns>/fundamentum/climate_blocks/*.json}) and the weather. Temperature uses the vanilla scale
 * (plains 0.8, snow below 0.15, desert 2.0) and is not clamped; humidity runs from 0 to 1.
 *
 * <p>On a server level everything counts. A client level knows the season's flat offsets only (per-biome season
 * rules stay on the server).
 */
public final class Climate {
    private Climate() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    /** Blocks farther than this never count, whatever a file says. */
    public static final int MAX_RADIUS = 8;
    /** Per block above sea level + 17, as vanilla cools high ground. */
    public static final double HEIGHT_COOLING = 0.00125;
    public static final double RAIN_TEMPERATURE = -0.1;
    public static final double RAIN_HUMIDITY = 0.2;

    /** One heat or cold source: blocks (ids or {@code #tag}), what they add at distance 0, and how far they reach. */
    public record BlockSource(FId file, List<String> blocks, double temperature, double humidity, int radius) {
        boolean matches(BlockState state) {
            for (String b : blocks) {
                if (b.startsWith("#")) {
                    if (state.is(FTags.block(FId.of(b.substring(1))))) return true;
                } else if (com.fundamentum.port.FVanilla.idOf(state.getBlock()).equals(FId.of(b))) {
                    return true;
                }
            }
            return false;
        }

        /** Share of the full effect at this distance: 1 on the spot, fading to 0 just past the radius. */
        double falloff(double distance) {
            return Math.max(0, 1 - distance / (radius + 1));
        }
    }

    /** The parts a reading is made of. */
    public record Reading(double biome, double season, double height, double blocks, double weather) {
        public double total() {
            return biome + season + height + blocks + weather;
        }
    }

    private static volatile List<BlockSource> sources = List.of();

    public static List<BlockSource> sources() {
        return sources;
    }

    static void loadSources(Map<FId, List<JsonElement>> files) {
        List<BlockSource> out = new ArrayList<>();
        new TreeMap<>(files).forEach((id, copies) -> {
            try {
                JsonObject o = copies.get(copies.size() - 1).getAsJsonObject();
                List<String> blocks = new ArrayList<>();
                JsonElement b = o.get("blocks");
                if (b == null) throw new IllegalArgumentException("missing blocks");
                if (b.isJsonArray()) for (JsonElement e : b.getAsJsonArray()) blocks.add(e.getAsString());
                else blocks.add(b.getAsString());
                for (String s : blocks) {
                    if (!FId.isValid(s.startsWith("#") ? s.substring(1) : s)) throw new IllegalArgumentException("bad id " + s);
                }
                int radius = o.has("radius") ? o.get("radius").getAsInt() : 4;
                if (radius < 0) throw new IllegalArgumentException("radius can't be negative");
                out.add(new BlockSource(id, List.copyOf(blocks), o.has("temperature") ? o.get("temperature").getAsDouble() : 0,
                    o.has("humidity") ? o.get("humidity").getAsDouble() : 0, Math.min(MAX_RADIUS, radius)));
            } catch (RuntimeException e) {
                LOG.error("Climate block file {} could not be read: {}", id, e.toString());
            }
        });
        sources = List.copyOf(out);
        LOG.info("Loaded {} climate block sources", out.size());
    }

    /** Temperature at a spot, everything counted. */
    public static double temperature(Level level, BlockPos pos) {
        return temperatureParts(level, pos).total();
    }

    /** Humidity at a spot, 0 to 1. */
    public static double humidity(Level level, BlockPos pos) {
        return clamp01(humidityParts(level, pos).total());
    }

    public static Reading temperatureParts(Level level, BlockPos pos) {
        Holder<Biome> biome = level.getBiome(pos);
        Biome b = biome.value();
        double base = b.getModifiedClimateSettings().temperatureModifier().modifyTemperature(pos, b.getBaseTemperature());
        Season s = Seasons.current(level);
        double season = s == null ? 0 : level.isClientSide() ? s.temperature() : s.temperatureFor(biome);
        int above = pos.getY() - level.getSeaLevel() - 17;
        double height = above > 0 ? -above * HEIGHT_COOLING : 0;
        double weather = wet(level, pos) ? RAIN_TEMPERATURE : 0;
        return new Reading(base, season, height, blocks(level, pos, true), weather);
    }

    public static Reading humidityParts(Level level, BlockPos pos) {
        Holder<Biome> biome = level.getBiome(pos);
        double base = FClimate.downfall(biome.value());
        Season s = Seasons.current(level);
        double season = s == null ? 0 : level.isClientSide() ? s.rainfall() : s.rainfallFor(biome);
        double weather = wet(level, pos) ? RAIN_HUMIDITY : 0;
        return new Reading(base, season, 0, blocks(level, pos, false), weather);
    }

    private static boolean wet(Level level, BlockPos pos) {
        return level.isRaining() && level.canSeeSky(pos);
    }

    /**
     * What heat and cold blocks nearby add. For each source only its nearest block counts, so a lava lake is no hotter
     * than one lava block at the same distance; different sources add up.
     */
    public static double blocks(Level level, BlockPos pos, boolean temperature) {
        List<BlockSource> list = sources;
        if (list.isEmpty()) return 0;
        int r = 0;
        for (BlockSource s : list) if ((temperature ? s.temperature() : s.humidity()) != 0) r = Math.max(r, s.radius());
        if (r == 0) return 0;
        double[] best = new double[list.size()];
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    m.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (!level.isLoaded(m)) continue;
                    BlockState state = level.getBlockState(m);
                    if (state.isAir()) continue;
                    double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    for (int i = 0; i < list.size(); i++) {
                        BlockSource s = list.get(i);
                        double v = temperature ? s.temperature() : s.humidity();
                        if (v == 0 || d > s.radius() || !s.matches(state)) continue;
                        double e = v * s.falloff(d);
                        if (Math.abs(e) > Math.abs(best[i])) best[i] = e;
                    }
                }
            }
        }
        double sum = 0;
        for (double v : best) sum += v;
        return sum;
    }

    static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
