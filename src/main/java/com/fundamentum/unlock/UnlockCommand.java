package com.fundamentum.unlock;

import com.fundamentum.port.FCommands;
import com.fundamentum.port.FId;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.Collection;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /fundamentum unlock grant <players> <id> [level]}, {@code revoke <players> <id>}, {@code has <player> <id>} and
 * {@code list <player>}. Grants here skip the parent check. Team-shared unlocks go to the player's team. Operators only.
 */
final class UnlockCommand {
    private UnlockCommand() { }

    private static final SuggestionProvider<CommandSourceStack> IDS = (c, b) ->
        SharedSuggestionProvider.suggest(Unlocks.defs().keySet().stream().map(FId::toString), b);

    static void init() {
        FCommands.listen(d -> d.register(Commands.literal("fundamentum").requires(FCommands.op())
            .then(Commands.literal("unlock")
                .then(Commands.literal("grant")
                    .then(Commands.argument("players", EntityArgument.players())
                        .then(Commands.argument("id", FCommands.id()).suggests(IDS)
                            .executes(c -> grant(c, 1))
                            .then(Commands.argument("level", IntegerArgumentType.integer(1))
                                .executes(c -> grant(c, IntegerArgumentType.getInteger(c, "level")))))))
                .then(Commands.literal("revoke")
                    .then(Commands.argument("players", EntityArgument.players())
                        .then(Commands.argument("id", FCommands.id()).suggests(IDS).executes(UnlockCommand::revoke))))
                .then(Commands.literal("has")
                    .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("id", FCommands.id()).suggests(IDS).executes(UnlockCommand::has))))
                .then(Commands.literal("list")
                    .then(Commands.argument("player", EntityArgument.player()).executes(UnlockCommand::list))))));
    }

    private static int grant(CommandContext<CommandSourceStack> c, int level) throws CommandSyntaxException {
        Collection<ServerPlayer> players = EntityArgument.getPlayers(c, "players");
        FId id = FCommands.getId(c, "id");
        int n = 0;
        for (ServerPlayer p : players) {
            if (Unlocks.grant(Unlocks.holderFor(p, id), id, level, false, p)) n++;
        }
        int done = n;
        c.getSource().sendSuccess(() -> Component.literal("Granted " + id + " (level " + level + ") to " + done + " of " + players.size()), true);
        return n;
    }

    private static int revoke(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Collection<ServerPlayer> players = EntityArgument.getPlayers(c, "players");
        FId id = FCommands.getId(c, "id");
        int n = 0;
        for (ServerPlayer p : players) {
            if (Unlocks.revoke(p, id)) n++;
        }
        int done = n;
        c.getSource().sendSuccess(() -> Component.literal("Revoked " + id + " from " + done + " of " + players.size()), true);
        return n;
    }

    private static int has(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = EntityArgument.getPlayer(c, "player");
        FId id = FCommands.getId(c, "id");
        int level = Unlocks.level(p, id);
        c.getSource().sendSuccess(() -> Component.literal(p.getScoreboardName() + (level > 0 ? " has " + id + " at level " + level : " does not have " + id)), false);
        return level;
    }

    private static int list(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = EntityArgument.getPlayer(c, "player");
        Map<FId, Unlocks.Entry> all = Unlocks.all(p);
        UnlockHolder team = Unlocks.team(p);
        c.getSource().sendSuccess(() -> Component.literal(p.getScoreboardName() + ": " + all.size() + " unlocks"
            + (team == null ? "" : " (team " + team.key() + ")")).withStyle(ChatFormatting.GOLD), false);
        all.forEach((id, e) -> c.getSource().sendSuccess(() -> Component.literal("  " + id + (e.level() > 1 ? " " + e.level() : ""))
            .withStyle(team != null && Unlocks.has(team, id) ? ChatFormatting.AQUA : ChatFormatting.GRAY), false));
        return all.size();
    }
}
