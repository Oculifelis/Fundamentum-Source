package com.fundamentum.quest;

import com.fundamentum.port.FCommands;
import com.fundamentum.port.FId;
import com.google.gson.JsonObject;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /quests} (the player's journal), {@code /quests accept <quest>} (an open offer) and
 * {@code /quests abandon <quest>} for every player. Operators also get
 * {@code /fundamentum quest list}, {@code info <player>} and {@code give|complete|fail|reset <player> <quest>}.
 */
final class QuestCommand {
    private QuestCommand() { }

    private static final SuggestionProvider<CommandSourceStack> IDS = (c, b) ->
        SharedSuggestionProvider.suggest(Quests.all().keySet().stream().map(FId::toString), b);
    private static final SuggestionProvider<CommandSourceStack> MINE = (c, b) -> {
        ServerPlayer p = c.getSource().getPlayer();
        return SharedSuggestionProvider.suggest(p == null ? List.<String>of() : List.copyOf(Quests.state(p).active().keySet()), b);
    };

    private static final SuggestionProvider<CommandSourceStack> NPCS = (c, b) ->
        SharedSuggestionProvider.suggest(QuestNpcs.all().keySet().stream().map(FId::toString), b);

    static void init() {
        FCommands.listen(d -> {
            d.register(Commands.literal("quests")
                .executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    return QuestSync.open(p, QuestSync.JOURNAL, null) ? 1 : journal(c, p);
                })
                .then(Commands.literal("accept").then(Commands.argument("quest", FCommands.id()).executes(QuestCommand::accept)))
                .then(Commands.literal("abandon").then(Commands.argument("quest", FCommands.id()).suggests(MINE)
                    .executes(QuestCommand::abandon))));
            d.register(Commands.literal("fundamentum").requires(FCommands.op())
                .then(Commands.literal("quest")
                    .then(Commands.literal("list").executes(QuestCommand::list))
                    .then(Commands.literal("info").then(Commands.argument("player", EntityArgument.player())
                        .executes(c -> journal(c, EntityArgument.getPlayer(c, "player")))))
                    .then(op("give")).then(op("complete")).then(op("fail")).then(op("reset"))));
            d.register(Commands.literal("fundamentum").requires(FCommands.op())
                .then(Commands.literal("npc")
                    .then(Commands.literal("list").executes(QuestCommand::npcs))
                    .then(Commands.literal("spawn").then(Commands.argument("npc", FCommands.id()).suggests(NPCS)
                        .executes(QuestCommand::spawnNpc)))));
        });
    }

    private static LiteralArgumentBuilder<CommandSourceStack> op(String name) {
        return Commands.literal(name).then(Commands.argument("player", EntityArgument.player())
            .then(Commands.argument("quest", FCommands.id()).suggests(IDS).executes(c -> op(c, name))));
    }

    private static int npcs(CommandContext<CommandSourceStack> c) {
        c.getSource().sendSuccess(() -> Component.literal(QuestNpcs.all().size() + " quest-giver types: " + String.join(", ",
            QuestNpcs.all().keySet().stream().map(FId::toString).toList())), false);
        return QuestNpcs.all().size();
    }

    private static int spawnNpc(CommandContext<CommandSourceStack> c) {
        FId type = FCommands.getId(c, "npc");
        QuestNpc npc = QuestNpcs.spawn(c.getSource().getLevel(), c.getSource().getPosition(), type);
        if (npc == null) {
            c.getSource().sendFailure(Component.literal("Unknown quest-giver type " + type));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.literal("Spawned " + type), true);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> c) {
        c.getSource().sendSuccess(() -> Component.literal(Quests.all().size() + " quests: " + String.join(", ",
            Quests.all().keySet().stream().map(FId::toString).toList())), false);
        return Quests.all().size();
    }

    private static int journal(CommandContext<CommandSourceStack> c, ServerPlayer p) {
        Quests.State s = Quests.state(p);
        if (s.active().isEmpty()) {
            c.getSource().sendSuccess(() -> Component.literal("No quests taken."), false);
            return 0;
        }
        StringBuilder sb = new StringBuilder("Quests:");
        for (Map.Entry<String, Quests.Active> e : s.active().entrySet()) {
            QuestDef d = Quests.get(FId.tryOf(e.getKey()));
            if (d == null) continue;
            sb.append("\n ").append(d.displayName());
            for (int i = 0; i < d.objectives().size(); i++) {
                JsonObject o = d.objectives().get(i);
                int have = i < e.getValue().progress().size() ? e.getValue().progress().get(i) : 0;
                sb.append("\n   ").append(o.has("text") ? o.get("text").getAsString() : o.get("type").getAsString())
                    .append(" ").append(have).append("/").append(QuestDef.count(o));
            }
            if (d.turnIn() && Quests.ready(p, d.id())) sb.append("\n   ready to hand in");
        }
        c.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return s.active().size();
    }

    private static int accept(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        FId q = FCommands.getId(c, "quest");
        if (!Quests.offered(p, q)) {
            c.getSource().sendFailure(Component.literal("That quest is not on offer."));
            return 0;
        }
        String why = Quests.accept(p, q);
        if (why != null) {
            c.getSource().sendFailure(Component.literal("Cannot take it: " + why));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.literal("Quest taken: " + Quests.get(q).displayName()), false);
        return 1;
    }

    private static int abandon(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        FId q = FCommands.getId(c, "quest");
        if (!Quests.fail(p, q, true)) {
            c.getSource().sendFailure(Component.literal("Not taken."));
            return 0;
        }
        return 1;
    }

    private static int op(CommandContext<CommandSourceStack> c, String name) throws CommandSyntaxException {
        ServerPlayer p = EntityArgument.getPlayer(c, "player");
        FId q = FCommands.getId(c, "quest");
        String result;
        switch (name) {
            case "give" -> {
                String why = Quests.accept(p, q);
                result = why == null ? "gave " + q : "cannot give " + q + ": " + why;
            }
            case "complete" -> result = Quests.complete(p, q) ? "completed " + q : q + " is not ready";
            case "fail" -> result = Quests.fail(p, q, false) ? "failed " + q : q + " is not taken";
            default -> {
                Quests.reset(p, q);
                result = "reset " + q;
            }
        }
        c.getSource().sendSuccess(() -> Component.literal(result), true);
        return 1;
    }
}
