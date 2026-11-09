package com.fundamentum.shield;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforgespi.language.IModFileInfo;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Finds shields that would draw as the magenta and black missing texture, without a client: every item model a mod ships
 * with a {@code fundamentum:shield} part names a texture {@code <ns>:<name>}, and the mod {@code <ns>} must ship
 * {@code textures/entity/shield/<name>.png} and {@code <name>_nopattern.png}. Runs once when a server (dedicated or
 * single player) starts and logs each gap as an error. Pictures that only a resource pack supplies are not seen here;
 * the client logs {@code Shield texture missing} for those.
 */
public final class ShieldTextures {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean done;

    /** @param checked every shield texture found in an item model; @param missing "texture (file)" for each absent picture */
    public record Result(List<String> checked, List<String> missing) {}

    static void init() {
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> {
            if (done) return;
            done = true;
            Result r = check();
            for (String m : r.missing()) {
                LOGGER.error("Shield texture missing: {}", m);
            }
        });
    }

    public static Result check() {
        Set<String> textures = new LinkedHashSet<>();
        for (Item item : BuiltInRegistries.ITEM) {
            Identifier model = item.components().get(DataComponents.ITEM_MODEL);
            if (model == null || model.getNamespace().equals("minecraft")) continue;
            String json = read(model.getNamespace(), "assets/" + model.getNamespace() + "/items/" + model.getPath() + ".json");
            if (json == null || !json.contains("fundamentum:shield")) continue;
            try {
                collect(JsonParser.parseString(json), textures);
            } catch (RuntimeException bad) {
                LOGGER.error("Shield item model {} could not be read: {}", model, bad.getMessage());
            }
        }
        List<String> missing = new ArrayList<>();
        for (String texture : textures) {
            Identifier id = Identifier.tryParse(texture);
            if (id == null) {
                missing.add(texture + " (not a valid id)");
                continue;
            }
            for (String suffix : new String[] {"", "_nopattern"}) {
                String file = "assets/" + id.getNamespace() + "/textures/entity/shield/" + id.getPath() + suffix + ".png";
                if (!exists(id.getNamespace(), file)) {
                    missing.add(texture + " (" + file + ")");
                }
            }
        }
        return new Result(List.copyOf(textures), missing);
    }

    private static void collect(JsonElement e, Set<String> out) {
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("type") && "fundamentum:shield".equals(o.get("type").getAsString()) && o.has("texture")) {
                out.add(o.get("texture").getAsString());
            }
            for (var entry : o.entrySet()) collect(entry.getValue(), out);
        } else if (e.isJsonArray()) {
            for (JsonElement c : e.getAsJsonArray()) collect(c, out);
        }
    }

    /** The mod named by the namespace first, then every other mod file (a jar may hold several namespaces). */
    private static List<IModFileInfo> files(String namespace) {
        List<IModFileInfo> files = new ArrayList<>();
        IModFileInfo own = ModList.get().getModFileById(namespace);
        if (own != null) files.add(own);
        for (IModFileInfo f : ModList.get().getModFiles()) {
            if (f != own) files.add(f);
        }
        return files;
    }

    private static boolean exists(String namespace, String path) {
        for (IModFileInfo f : files(namespace)) {
            if (f.getFile().getContents().containsFile(path)) return true;
        }
        return false;
    }

    private static @Nullable String read(String namespace, String path) {
        for (IModFileInfo f : files(namespace)) {
            if (!f.getFile().getContents().containsFile(path)) continue;
            try (InputStream in = f.getFile().getContents().openFile(path)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    private ShieldTextures() {}
}
