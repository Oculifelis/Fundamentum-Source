package com.fundamentum.shield;

import com.mojang.logging.LogUtils;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.forgespi.language.IModFileInfo;
import org.slf4j.Logger;

/**
 * Finds shields that would draw as the magenta and black missing texture, without a client: every {@link ShieldTypeItem}
 * {@code <ns>:<name>} needs its mod to ship {@code assets/<ns>/textures/entity/shield/<name>.png} and
 * {@code <name>_nopattern.png}. Runs once when a server (dedicated or single player) starts and logs each gap as an error.
 * Pictures that only a resource pack supplies are not seen here; the client logs {@code Shield texture missing} for those.
 */
public final class ShieldTextures {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean done;

    /** @param checked every shield item checked; @param missing "item (file)" for each absent picture */
    public record Result(List<String> checked, List<String> missing) {}

    static void init() {
        MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent e) -> {
            if (done) return;
            done = true;
            for (String m : check().missing()) {
                LOGGER.error("Shield texture missing: {}", m);
            }
        });
    }

    public static Result check() {
        List<String> checked = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (!(item instanceof ShieldTypeItem)) continue;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            checked.add(id.toString());
            for (String suffix : new String[] {"", "_nopattern"}) {
                String[] path = {"assets", id.getNamespace(), "textures", "entity", "shield", id.getPath() + suffix + ".png"};
                if (!exists(id.getNamespace(), path)) {
                    missing.add(id + " (" + String.join("/", path) + ")");
                }
            }
        }
        return new Result(checked, missing);
    }

    /** The mod named by the namespace first, then every other mod file (a jar may hold several namespaces). */
    private static boolean exists(String namespace, String[] path) {
        IModFileInfo own = ModList.get().getModFileById(namespace);
        if (own != null && Files.exists(own.getFile().findResource(path))) return true;
        for (IModFileInfo f : ModList.get().getModFiles()) {
            if (f != own && Files.exists(f.getFile().findResource(path))) return true;
        }
        return false;
    }

    private ShieldTextures() {}
}
