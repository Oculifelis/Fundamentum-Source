package com.fundamentum.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /fundamentum tps}: the whole server's ticks per second and milliseconds per tick, then every dimension's, with
 * how many entities and loaded chunks each has, the slowest first. {@code /fundamentum tps <dimension>}: that
 * dimension alone, with its ten most common entity types. TPS is green at the full tick rate, red near 0; ms per tick
 * turns yellow past 40 and red past the tick budget (50 at 20 TPS). Averages are over the last 100 ticks (the server's
 * own record, the one {@code /neoforge tps} reads). Needs operator permission.
 */
public final class Tps {
    public static void init() {
        NeoForge.EVENT_BUS.addListener(Tps::register);
    }

    private static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("fundamentum")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("tps")
                .executes(Tps::all)
                .then(Commands.argument("dimension", DimensionArgument.dimension())
                    .executes(Tps::one))));
    }

    private static int all(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        source.sendSuccess(() -> line(Component.translatable("fundamentum.tps.server").withStyle(ChatFormatting.GOLD), server.getTickTimesNanos(),
            server.tickRateManager()), false);
        List<ServerLevel> levels = new ArrayList<>();
        server.getAllLevels().forEach(levels::add);
        levels.sort((a, b) -> Double.compare(mspt(server.getTickTime(b.dimension())), mspt(server.getTickTime(a.dimension()))));
        for (ServerLevel level : levels) {
            source.sendSuccess(() -> dimension(server, level), false);
        }
        return levels.size();
    }

    private static int one(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerLevel level = DimensionArgument.getDimension(context, "dimension");
        source.sendSuccess(() -> dimension(source.getServer(), level), false);
        Map<EntityType<?>, Integer> counts = new HashMap<>();
        for (Entity e : level.getAllEntities()) {
            counts.merge(e.getType(), 1, Integer::sum);
        }
        counts.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(10).forEach(e ->
            source.sendSuccess(() -> Component.literal("  " + e.getValue() + " ").withStyle(ChatFormatting.WHITE)
                .append(Component.literal(BuiltInRegistries.ENTITY_TYPE.getKey(e.getKey()).toString()).withStyle(ChatFormatting.GRAY)), false));
        return 1;
    }

    private static Component dimension(MinecraftServer server, ServerLevel level) {
        int entities = 0;
        for (Entity ignored : level.getAllEntities()) {
            entities++;
        }
        return line(Component.literal(level.dimension().location().toString()).withStyle(ChatFormatting.GREEN), server.getTickTime(level.dimension()),
            level.tickRateManager())
            .append(Component.translatable("fundamentum.tps.counts", entities, level.getChunkSource().getLoadedChunksCount()).withStyle(ChatFormatting.DARK_GRAY));
    }

    /** "<name>: <tps> TPS, <mspt> ms/tick" coloured by how close it is to the target rate. */
    private static MutableComponent line(Component name, long @Nullable [] times, TickRateManager rate) {
        double mspt = mspt(times);
        double target = rate.millisecondsPerTick();
        double tps = 1000.0 / Math.max(mspt, target);
        int tpsColor = Mth.hsvToRgb((float) (Mth.clamp(tps / (1000.0 / target), 0.0, 1.0) * 0.33), 1.0F, 1.0F);
        ChatFormatting msptColor = mspt > target ? ChatFormatting.RED : mspt > target * 0.8 ? ChatFormatting.YELLOW : ChatFormatting.GRAY;
        return Component.empty().append(name).append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(String.format("%.1f", tps)).withColor(tpsColor))
            .append(Component.literal(" TPS, ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(String.format("%.2f", mspt)).withStyle(msptColor))
            .append(Component.literal(" ms/tick").withStyle(ChatFormatting.GRAY));
    }

    /** The mean of the recorded tick times, in milliseconds (0 when none are recorded yet). */
    private static double mspt(long @Nullable [] times) {
        if (times == null || times.length == 0) {
            return 0.0;
        }
        long sum = 0;
        for (long t : times) {
            sum += t;
        }
        return sum / (double) times.length / 1.0E6;
    }

    private Tps() {}
}
