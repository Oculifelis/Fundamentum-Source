package com.fundamentum;

import com.fundamentum.client.ConfigScreen;
import com.fundamentum.portal.PortalCraftingClient;
import java.util.List;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModLoadingContext;

/**
 * Client side of {@link Fundamentum}: the client's copy of the portal recipes and the Config button in the mod list
 * ({@link ConfigScreen}, every Fundamentum config file). Called from the mod's constructor on a client only.
 */
public final class FundamentumClient {
    static void init() {
        PortalCraftingClient.init();
        List<ConfigScreen.File> files = List.of(
            new ConfigScreen.File("fundamentum-client.toml", com.fundamentum.whip.WhipsConfig.SPEC),
            new ConfigScreen.File("fundamentum-sky-client.toml", com.fundamentum.sky.SkyConfig.SPEC),
            new ConfigScreen.File("fundamentum-hud-client.toml", com.fundamentum.client.ItemHudConfig.SPEC),
            new ConfigScreen.File("fundamentum-common.toml", com.fundamentum.sky.PsychedelicMoon.SPEC),
            new ConfigScreen.File("fundamentum-soulfire.toml", com.fundamentum.soulfire.SoulFireConfig.SPEC),
            new ConfigScreen.File("fundamentum-biomes.toml", com.fundamentum.biome.BiomeSlicesConfig.SPEC));
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
            () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> new ConfigScreen(parent, files)));
    }

    private FundamentumClient() {}
}
