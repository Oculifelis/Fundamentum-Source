package com.fundamentum.biome;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;

/** {@code /fundamentum biomes slices}: each biome slice, where it went and how, then each dimension's path (operator only). */
public final class BiomeSlicesCommand {
    private BiomeSlicesCommand() { }

    static void init() {
        MinecraftForge.EVENT_BUS.addListener(BiomeSlicesCommand::register);
    }

    private static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("fundamentum")
            .requires(s -> s.hasPermission(2))
            .then(Commands.literal("biomes")
                .then(Commands.literal("slices").executes(BiomeSlicesCommand::slices))));
    }

    private static int slices(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        var status = BiomeSlices.status();
        if (status.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No biome slices loaded."), false);
        }
        for (BiomeSlices.Status s : status) {
            Component line = Component.literal(s.active() ? "+ " : "- ").withStyle(s.active() ? ChatFormatting.GREEN : ChatFormatting.GRAY)
                .append(Component.literal(s.id().toString()).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" " + s.biome() + " in " + s.dimension() + ", " + s.mode() + ": " + s.detail())
                    .withStyle(ChatFormatting.GRAY));
            source.sendSuccess(() -> line, false);
        }
        for (BiomeSlices.DimensionReport d : BiomeSlices.dimensions()) {
            source.sendSuccess(() -> Component.literal(d.dimension() + ": " + d.path() + ", " + d.pointsBefore() + " -> "
                + d.pointsAfter() + " climate points").withStyle(ChatFormatting.AQUA), false);
        }
        for (String rule : BiomeSlices.surfaceRules()) {
            source.sendSuccess(() -> Component.literal("surface rule " + rule).withStyle(ChatFormatting.DARK_AQUA), false);
        }
        return status.size();
    }
}
