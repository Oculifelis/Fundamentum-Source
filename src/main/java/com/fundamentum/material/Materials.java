package com.fundamentum.material;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.context.CommandContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Sets up material sets, palettes, the {@code fundamentum:material_swap} processor and {@code /fundamentum materials dump}. */
public final class Materials {
    private Materials() { }

    public static void init(IEventBus modBus) {
        MaterialSets.init();
        Palettes.init();
        MaterialSwapProcessor.init(modBus);
        NeoForge.EVENT_BUS.addListener(Materials::commands);
    }

    private static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("fundamentum")
            .requires(s -> s.hasPermission(2))
            .then(Commands.literal("materials")
                .then(Commands.literal("dump").executes(Materials::dump))));
    }

    private static int dump(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Path file = source.getServer().getServerDirectory().resolve("fundamentum-materials.json");
        try {
            int n = dump(file);
            source.sendSuccess(() -> Component.translatable("fundamentum.materials.dumped", n, file.toAbsolutePath().toString()), false);
            return n;
        } catch (IOException e) {
            source.sendFailure(Component.literal(e.toString()));
            return 0;
        }
    }

    /** Writes every set (turned off ones flagged) to a JSON file; returns how many. */
    public static int dump(Path file) throws IOException {
        JsonObject root = new JsonObject();
        for (Map.Entry<ResourceLocation, MaterialSet> e : MaterialSets.allIncludingDisabled().entrySet()) root.add(e.getKey().toString(), json(e.getValue()));
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
        return root.size();
    }

    static JsonObject json(MaterialSet s) {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", MaterialSets.enabled(s.id()));
        o.addProperty("detected", s.detected());
        o.addProperty("kind", s.kind().id());
        o.addProperty("family", s.family());
        o.addProperty("source", s.source());
        o.addProperty("weight", s.weight());
        o.addProperty("dimension", s.dimension());
        o.addProperty("color", s.hex());
        o.addProperty("lightness", Math.round(s.lightness() * 10) / 10.0);
        JsonArray tags = new JsonArray();
        s.tags().forEach(tags::add);
        o.add("tags", tags);
        if (!s.biomeTags().isEmpty()) {
            JsonArray bt = new JsonArray();
            s.biomeTags().forEach(bt::add);
            o.add("biome_tags", bt);
        }
        s.light().ifPresent(b -> o.addProperty("light", MaterialSet.key(b)));
        s.bars().ifPresent(b -> o.addProperty("bars", MaterialSet.key(b)));
        JsonObject slots = new JsonObject();
        s.slots().forEach((slot, shapes) -> {
            JsonObject so = new JsonObject();
            shapes.forEach((shape, wears) -> wears.forEach((wear, b) -> so.addProperty(wear.isEmpty() ? shape : shape + ":" + wear, MaterialSet.key(b))));
            so.addProperty("usable", s.usable(slot));
            slots.add(slot, so);
        });
        o.add("slots", slots);
        return o;
    }
}
