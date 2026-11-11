package com.fundamentum;

import com.fundamentum.config.client.ConfigClient;
import com.fundamentum.config.client.FConfigScreen;
import com.fundamentum.portal.PortalCraftingClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** Client side of {@link Fundamentum}: the Config button in the mod list (the shared settings screen, with the older config files behind "Classic settings"), and the client's copy of the portal recipes. */
@Mod(value = Fundamentum.MODID, dist = Dist.CLIENT)
public final class FundamentumClient {
    public FundamentumClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class,
            (c, parent) -> new FConfigScreen(parent, p -> new ConfigurationScreen(c, p)));
        ConfigClient.init();
        PortalCraftingClient.init();
        com.fundamentum.armorset.ArmorSetTooltip.init();
    }
}
