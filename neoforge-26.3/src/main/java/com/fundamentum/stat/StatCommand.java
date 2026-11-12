package com.fundamentum.stat;

import com.fundamentum.port.FCommands;
import com.fundamentum.port.FId;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.Collection;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * {@code /fundamentum stat get <target> <id>}, {@code set <targets> <id> <value>} and {@code add <targets> <id> <amount>}.
 * Item stats act on the stack in the target's main hand. {@code get} returns the value rounded down. Operators only.
 */
final class StatCommand {
    private StatCommand() { }

    private static final SuggestionProvider<CommandSourceStack> IDS = (c, b) ->
        SharedSuggestionProvider.suggest(Stats.defs().keySet().stream().map(FId::toString), b);

    static void init() {
        FCommands.listen(d -> d.register(Commands.literal("fundamentum").requires(FCommands.op())
            .then(Commands.literal("stat")
                .then(Commands.literal("get")
                    .then(Commands.argument("target", EntityArgument.entity())
                        .then(Commands.argument("id", FCommands.id()).suggests(IDS).executes(StatCommand::get))))
                .then(Commands.literal("set")
                    .then(Commands.argument("targets", EntityArgument.entities())
                        .then(Commands.argument("id", FCommands.id()).suggests(IDS)
                            .then(Commands.argument("value", DoubleArgumentType.doubleArg()).executes(c -> write(c, false))))))
                .then(Commands.literal("add")
                    .then(Commands.argument("targets", EntityArgument.entities())
                        .then(Commands.argument("id", FCommands.id()).suggests(IDS)
                            .then(Commands.argument("value", DoubleArgumentType.doubleArg()).executes(c -> write(c, true)))))))));
    }

    private static int get(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Entity e = EntityArgument.getEntity(c, "target");
        FId id = FCommands.getId(c, "id");
        if (!(e instanceof LivingEntity living)) {
            c.getSource().sendFailure(Component.literal(e.getScoreboardName() + " has no stats"));
            return 0;
        }
        double v = Stats.get(living, id);
        c.getSource().sendSuccess(() -> Component.literal(e.getScoreboardName() + " " + id + " = " + Stats.format(id, v)), false);
        return (int) Math.floor(v);
    }

    private static int write(CommandContext<CommandSourceStack> c, boolean add) throws CommandSyntaxException {
        Collection<? extends Entity> targets = EntityArgument.getEntities(c, "targets");
        FId id = FCommands.getId(c, "id");
        double value = DoubleArgumentType.getDouble(c, "value");
        int n = 0;
        double last = 0;
        for (Entity e : targets) {
            if (!(e instanceof LivingEntity living)) continue;
            if (add ? Stats.add(living, id, value) : Stats.set(living, id, value)) {
                n++;
                last = Stats.get(living, id);
            }
        }
        int done = n;
        double shown = last;
        if (n == 0) {
            c.getSource().sendFailure(Component.literal("Could not change " + id + " (not writable, or no living target)"));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.literal((add ? "Added to " : "Set ") + id + " on " + done
            + (done == 1 ? " target, now " + Stats.format(id, shown) : " targets")), true);
        return n;
    }
}
