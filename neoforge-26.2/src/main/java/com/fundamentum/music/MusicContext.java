package com.fundamentum.music;

import com.fundamentum.climate.Season;
import com.fundamentum.climate.Seasons;
import com.fundamentum.port.FClimate;
import com.fundamentum.port.FId;
import com.fundamentum.port.FTags;
import com.fundamentum.port.FTime;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/**
 * Where a player is and what is going on, as music rules see it. {@code structures} holds the structure ids and
 * {@code #tags} the player stands in (the server works these out, only for the ones rules name). {@code season} is
 * null while seasons are off. The same file on every version.
 */
public record MusicContext(FId dimension, FId biome, Predicate<FId> biomeTag, Set<String> structures, long dayTime,
                           Weather weather, FId season, boolean bossFight, FId bossType, boolean combat) {

    public enum Weather { CLEAR, RAIN, THUNDER }

    /** The context for a player in any level (client or server); the boss, combat and structure parts are passed in. */
    public static MusicContext of(Player player, Set<String> structures, boolean bossFight, FId bossType, boolean combat) {
        Level level = player.level();
        BlockPos pos = player.blockPosition();
        Holder<Biome> biome = level.getBiome(pos);
        Weather w = level.isThundering() ? Weather.THUNDER : level.isRaining() ? Weather.RAIN : Weather.CLEAR;
        Season season = Seasons.current(level);
        return new MusicContext(FClimate.dimensionId(level), FClimate.biomeId(biome), tag -> biome.is(FTags.biome(tag)),
            structures, FTime.dayTime(level), w, season == null ? null : season.id(), bossFight, bossType, combat);
    }
}
