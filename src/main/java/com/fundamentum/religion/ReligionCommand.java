package com.fundamentum.religion;

import com.fundamentum.port.FCommands;
import com.fundamentum.port.FId;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /gods} (devotion, favour and patrons), {@code /gods follow|forsake <god>} and {@code /gods boon <god> <boon>}
 * for every player. Operators also get {@code /fundamentum god list}, {@code info <player>},
 * {@code devotion|favour <player> <god> <value>} and {@code wrath <player> <god>}.
 */
final class ReligionCommand {
    private ReligionCommand() { }

    private static final SuggestionProvider<CommandSourceStack> GODS = (c, b) ->
        SharedSuggestionProvider.suggest(Religion.deities().keySet().stream().map(FId::toString), b);
    private static final SuggestionProvider<CommandSourceStack> BOONS = (c, b) -> {
        Deity d = Religion.deity(FCommands.getId(c, "god"));
        return SharedSuggestionProvider.suggest(d == null ? List.<String>of() : List.copyOf(d.boons().keySet()), b);
    };

    static void init() {
        FCommands.listen(d -> {
            d.register(Commands.literal("gods")
                .executes(c -> info(c, c.getSource().getPlayerOrException()))
                .then(Commands.literal("follow").then(Commands.argument("god", FCommands.id()).suggests(GODS)
                    .executes(ReligionCommand::follow)))
                .then(Commands.literal("forsake").then(Commands.argument("god", FCommands.id()).suggests(GODS)
                    .executes(ReligionCommand::forsake)))
                .then(Commands.literal("boon").then(Commands.argument("god", FCommands.id()).suggests(GODS)
                    .then(Commands.argument("boon", StringArgumentType.word()).suggests(BOONS).executes(ReligionCommand::boon)))));
            d.register(Commands.literal("fundamentum").requires(FCommands.op())
                .then(Commands.literal("god")
                    .then(Commands.literal("list").executes(ReligionCommand::list))
                    .then(Commands.literal("info").then(Commands.argument("player", EntityArgument.player())
                        .executes(c -> info(c, EntityArgument.getPlayer(c, "player")))))
                    .then(Commands.literal("devotion").then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("god", FCommands.id()).suggests(GODS)
                            .then(Commands.argument("value", IntegerArgumentType.integer()).executes(ReligionCommand::setDevotion)))))
                    .then(Commands.literal("favour").then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("god", FCommands.id()).suggests(GODS)
                            .then(Commands.argument("value", DoubleArgumentType.doubleArg(0)).executes(ReligionCommand::setFavour)))))
                    .then(Commands.literal("wrath").then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("god", FCommands.id()).suggests(GODS).executes(ReligionCommand::wrath))))));
        });
    }

    private static Deity god(CommandContext<CommandSourceStack> c) {
        Deity d = Religion.deity(FCommands.getId(c, "god"));
        if (d == null) c.getSource().sendFailure(Component.literal("Unknown god " + FCommands.getId(c, "god")));
        return d;
    }

    private static int list(CommandContext<CommandSourceStack> c) {
        List<String> names = new ArrayList<>();
        Religion.deities().values().forEach(d -> names.add(d.id() + " (" + d.displayName() + ")"));
        c.getSource().sendSuccess(() -> Component.literal(names.size() + " gods, " + Religion.pantheons().size() + " pantheons, "
            + Religion.shrines().size() + " shrines, " + Religion.rituals().size() + " rituals: " + String.join(", ", names)), false);
        return names.size();
    }

    private static int info(CommandContext<CommandSourceStack> c, ServerPlayer p) {
        List<FId> patrons = Religion.patrons(p);
        StringBuilder sb = new StringBuilder(p.getName().getString()).append(" follows ")
            .append(patrons.isEmpty() ? "no god" : String.join(", ", patrons.stream().map(g -> Religion.deity(g).displayName()).toList()));
        for (Deity d : Religion.deities().values()) {
            int dev = Religion.devotion(p, d.id());
            double fav = Religion.favour(p, d.id());
            if (dev == 0 && fav == 0 && !patrons.contains(d.id())) continue;
            sb.append("\n").append(d.displayName()).append(": devotion ").append(dev);
            if (d.faction().rankAt(dev) != null) sb.append(" (").append(d.faction().rankAt(dev).id()).append(")");
            sb.append(", favour ").append(Math.round(fav)).append("/").append(Math.round(d.favourMax()));
        }
        c.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return patrons.size();
    }

    private static int follow(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        Deity d = god(c);
        if (d == null) return 0;
        String why = Religion.follow(p, d.id());
        if (why != null) {
            c.getSource().sendFailure(Component.literal("Cannot follow " + d.displayName() + ": " + why));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.literal("Now following " + d.displayName()), false);
        return 1;
    }

    private static int forsake(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        Deity d = god(c);
        if (d == null) return 0;
        if (!Religion.forsake(p, d.id())) {
            c.getSource().sendFailure(Component.literal("Not a follower of " + d.displayName()));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.literal("Forsook " + d.displayName()), false);
        return 1;
    }

    private static int boon(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        Deity d = god(c);
        if (d == null) return 0;
        String boon = StringArgumentType.getString(c, "boon");
        String why = Religion.boon(p, d.id(), boon);
        if (why != null) {
            c.getSource().sendFailure(Component.literal("No boon: " + why));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.literal(d.displayName() + " grants " + boon), false);
        return 1;
    }

    private static int setDevotion(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = EntityArgument.getPlayer(c, "player");
        Deity d = god(c);
        if (d == null) return 0;
        com.fundamentum.faction.Factions.setRep(p, d.id(), IntegerArgumentType.getInteger(c, "value"));
        int v = Religion.devotion(p, d.id());
        c.getSource().sendSuccess(() -> Component.literal(d.displayName() + " devotion is now " + v), true);
        return 1;
    }

    private static int setFavour(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = EntityArgument.getPlayer(c, "player");
        Deity d = god(c);
        if (d == null) return 0;
        Religion.setFavour(p, d.id(), DoubleArgumentType.getDouble(c, "value"));
        double v = Religion.favour(p, d.id());
        c.getSource().sendSuccess(() -> Component.literal(d.displayName() + " favour is now " + Math.round(v)), true);
        return 1;
    }

    private static int wrath(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = EntityArgument.getPlayer(c, "player");
        Deity d = god(c);
        if (d == null) return 0;
        boolean sent = Religion.wrath(p, d, true);
        c.getSource().sendSuccess(() -> Component.literal(sent ? "Wrath sent" : "No wrath (off, devotion too high, or cooling down)"), true);
        return sent ? 1 : 0;
    }
}
