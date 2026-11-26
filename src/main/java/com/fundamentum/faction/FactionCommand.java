package com.fundamentum.faction;

import com.fundamentum.port.FCommands;
import com.fundamentum.port.FId;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /fundamentum faction list}, {@code info <player>}, {@code rep <player> <faction> [set|add <n>]},
 * {@code join|leave <player> <faction>} and {@code align <player> <axis> [set <n>]}. Operators only.
 */
final class FactionCommand {
    private FactionCommand() { }

    private static final SuggestionProvider<CommandSourceStack> IDS = (c, b) ->
        SharedSuggestionProvider.suggest(Factions.all().keySet().stream().map(FId::toString), b);
    private static final SuggestionProvider<CommandSourceStack> AXES = (c, b) ->
        SharedSuggestionProvider.suggest(Factions.axes().keySet().stream().map(FId::toString), b);

    static void init() {
        FCommands.listen(d -> d.register(Commands.literal("fundamentum").requires(FCommands.op())
            .then(Commands.literal("faction")
                .then(Commands.literal("list").executes(FactionCommand::list))
                .then(Commands.literal("info")
                    .then(Commands.argument("player", EntityArgument.player()).executes(FactionCommand::info)))
                .then(Commands.literal("rep")
                    .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("faction", FCommands.id()).suggests(IDS)
                            .executes(c -> rep(c, null, 0))
                            .then(Commands.literal("set").then(Commands.argument("n", IntegerArgumentType.integer())
                                .executes(c -> rep(c, "set", IntegerArgumentType.getInteger(c, "n")))))
                            .then(Commands.literal("add").then(Commands.argument("n", IntegerArgumentType.integer())
                                .executes(c -> rep(c, "add", IntegerArgumentType.getInteger(c, "n"))))))))
                .then(Commands.literal("join")
                    .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("faction", FCommands.id()).suggests(IDS).executes(FactionCommand::join))))
                .then(Commands.literal("leave")
                    .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("faction", FCommands.id()).suggests(IDS).executes(FactionCommand::leave))))
                .then(Commands.literal("align")
                    .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("axis", FCommands.id()).suggests(AXES)
                            .executes(c -> align(c, null))
                            .then(Commands.literal("set").then(Commands.argument("n", DoubleArgumentType.doubleArg())
                                .executes(c -> align(c, DoubleArgumentType.getDouble(c, "n")))))))))));
    }

    private static int list(CommandContext<CommandSourceStack> c) {
        c.getSource().sendSuccess(() -> Component.literal(Factions.all().size() + " factions: " + String.join(", ",
            Factions.all().keySet().stream().map(FId::toString).toList())), false);
        return Factions.all().size();
    }

    private static int info(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = EntityArgument.getPlayer(c, "player");
        StringBuilder sb = new StringBuilder(p.getName().getString());
        for (FactionDef d : Factions.all().values()) {
            FactionDef.Rank r = Factions.rank(p, d.id());
            sb.append("\n").append(d.id()).append(": ").append(Factions.rep(p, d.id()));
            if (r != null) sb.append(" (").append(r.id()).append(")");
            if (Factions.member(p, d.id())) sb.append(" member");
        }
        for (FId a : Factions.axes().keySet()) sb.append("\n").append(a).append(": ").append(Factions.alignment(p, a));
        c.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int rep(CommandContext<CommandSourceStack> c, String op, int n) throws CommandSyntaxException {
        ServerPlayer p = EntityArgument.getPlayer(c, "player");
        FId f = FCommands.getId(c, "faction");
        if (Factions.get(f) == null) {
            c.getSource().sendFailure(Component.literal("Unknown faction " + f));
            return 0;
        }
        if ("set".equals(op)) Factions.setRep(p, f, n);
        if ("add".equals(op)) Factions.addRep(p, f, n);
        int now = Factions.rep(p, f);
        FactionDef.Rank r = Factions.rank(p, f);
        c.getSource().sendSuccess(() -> Component.literal(f + " rep " + now + (r == null ? "" : " (" + r.id() + ")")), op != null);
        return now;
    }

    private static int join(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = EntityArgument.getPlayer(c, "player");
        FId f = FCommands.getId(c, "faction");
        String why = Factions.join(p, f);
        if (why != null) {
            c.getSource().sendFailure(Component.literal("Cannot join " + f + ": " + why));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.literal(p.getName().getString() + " joined " + f), true);
        return 1;
    }

    private static int leave(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = EntityArgument.getPlayer(c, "player");
        FId f = FCommands.getId(c, "faction");
        boolean ok = Factions.leave(p, f);
        c.getSource().sendSuccess(() -> Component.literal(ok ? p.getName().getString() + " left " + f : "Not a member of " + f), ok);
        return ok ? 1 : 0;
    }

    private static int align(CommandContext<CommandSourceStack> c, Double set) throws CommandSyntaxException {
        ServerPlayer p = EntityArgument.getPlayer(c, "player");
        FId a = FCommands.getId(c, "axis");
        if (set != null) Factions.setAlignment(p, a, set);
        double now = Factions.alignment(p, a);
        c.getSource().sendSuccess(() -> Component.literal(a + " alignment " + now), set != null);
        return (int) Math.round(now);
    }
}
