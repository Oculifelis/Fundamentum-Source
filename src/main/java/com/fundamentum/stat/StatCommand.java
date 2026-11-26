package com.fundamentum.stat;

import com.fundamentum.port.FCommands;
import com.fundamentum.port.FId;
import com.fundamentum.attribute.AttributeBonus;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
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
 * {@code /fundamentum stat get <target> <id>}, {@code base <target> <id>} (the value before modifiers),
 * {@code set <targets> <id> <value>}, {@code add <targets> <id> <amount>},
 * {@code modifier add <targets> <id> <modifier> <amount> [operation]} and {@code modifier remove <targets> <id> <modifier>}.
 * Item stats act on the stack in the target's main hand. {@code get} and {@code base} return the value rounded down.
 * Operators only.
 */
final class StatCommand {
    private StatCommand() { }

    private static final SuggestionProvider<CommandSourceStack> IDS = (c, b) ->
        SharedSuggestionProvider.suggest(Stats.defs().keySet().stream().map(FId::toString), b);

    private static final SuggestionProvider<CommandSourceStack> OPS = (c, b) ->
        SharedSuggestionProvider.suggest(new String[] {"add_value", "add_multiplied_base", "add_multiplied_total"}, b);

    static void init() {
        FCommands.listen(d -> d.register(Commands.literal("fundamentum").requires(FCommands.op())
            .then(Commands.literal("stat")
                .then(Commands.literal("get")
                    .then(Commands.argument("target", EntityArgument.entity())
                        .then(Commands.argument("id", FCommands.id()).suggests(IDS).executes(c -> get(c, false)))))
                .then(Commands.literal("base")
                    .then(Commands.argument("target", EntityArgument.entity())
                        .then(Commands.argument("id", FCommands.id()).suggests(IDS).executes(c -> get(c, true)))))
                .then(Commands.literal("modifier")
                    .then(Commands.literal("add")
                        .then(Commands.argument("targets", EntityArgument.entities())
                            .then(Commands.argument("id", FCommands.id()).suggests(IDS)
                                .then(Commands.argument("modifier", FCommands.id())
                                    .then(Commands.argument("amount", DoubleArgumentType.doubleArg()).executes(c -> modifier(c, true, false))
                                        .then(Commands.argument("operation", StringArgumentType.word()).suggests(OPS)
                                            .executes(c -> modifier(c, true, true))))))))
                    .then(Commands.literal("remove")
                        .then(Commands.argument("targets", EntityArgument.entities())
                            .then(Commands.argument("id", FCommands.id()).suggests(IDS)
                                .then(Commands.argument("modifier", FCommands.id()).executes(c -> modifier(c, false, false)))))))
                .then(Commands.literal("set")
                    .then(Commands.argument("targets", EntityArgument.entities())
                        .then(Commands.argument("id", FCommands.id()).suggests(IDS)
                            .then(Commands.argument("value", DoubleArgumentType.doubleArg()).executes(c -> write(c, false))))))
                .then(Commands.literal("add")
                    .then(Commands.argument("targets", EntityArgument.entities())
                        .then(Commands.argument("id", FCommands.id()).suggests(IDS)
                            .then(Commands.argument("value", DoubleArgumentType.doubleArg()).executes(c -> write(c, true)))))))));
    }

    private static int get(CommandContext<CommandSourceStack> c, boolean base) throws CommandSyntaxException {
        Entity e = EntityArgument.getEntity(c, "target");
        FId id = FCommands.getId(c, "id");
        if (!(e instanceof LivingEntity living)) {
            c.getSource().sendFailure(Component.literal(e.getScoreboardName() + " has no stats"));
            return 0;
        }
        double v = base ? Stats.base(living, id) : Stats.get(living, id);
        c.getSource().sendSuccess(() -> Component.literal(e.getScoreboardName() + " " + id + (base ? " base = " : " = ")
            + Stats.format(id, v)), false);
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

    private static int modifier(CommandContext<CommandSourceStack> c, boolean add, boolean withOp) throws CommandSyntaxException {
        Collection<? extends Entity> targets = EntityArgument.getEntities(c, "targets");
        FId id = FCommands.getId(c, "id");
        FId mod = FCommands.getId(c, "modifier");
        double amount = add ? DoubleArgumentType.getDouble(c, "amount") : 0;
        AttributeBonus.Op op;
        try {
            op = withOp ? AttributeBonus.op(StringArgumentType.getString(c, "operation")) : AttributeBonus.Op.ADD_VALUE;
        } catch (IllegalArgumentException ex) {
            c.getSource().sendFailure(Component.literal(ex.getMessage()));
            return 0;
        }
        int n = 0;
        for (Entity e : targets) {
            if (!(e instanceof LivingEntity living)) continue;
            if (add ? Stats.addModifier(living, id, mod, amount, op) : Stats.removeModifier(living, id, mod)) n++;
        }
        int done = n;
        if (n == 0) {
            c.getSource().sendFailure(Component.literal(add ? "Could not add a modifier to " + id + " (item stat, or no living target)"
                : "No target had modifier " + mod + " on " + id));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.literal((add ? "Added modifier " : "Removed modifier ") + mod + " on " + id + " for "
            + done + (done == 1 ? " target" : " targets")), true);
        return n;
    }
}
