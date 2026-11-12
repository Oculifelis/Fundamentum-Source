package com.fundamentum.port;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Commands with the same calls on every version: where to add them, the operator check and the id argument (whose
 * class and permission check were renamed between versions). Register listeners while the mod is constructed.
 *
 * <pre>{@code FCommands.listen(d -> d.register(Commands.literal("fundamentum").requires(FCommands.op())
 *     .then(Commands.literal("thing").then(Commands.argument("id", FCommands.id()).executes(c -> run(FCommands.getId(c, "id")))))));}</pre>
 */
public final class FCommands {
    private FCommands() { }

    private static final List<Consumer<CommandDispatcher<CommandSourceStack>>> LISTENERS = new ArrayList<>();
    private static boolean hooked;

    /** Runs every time the server builds its command tree (start and reload). */
    public static synchronized void listen(Consumer<CommandDispatcher<CommandSourceStack>> listener) {
        LISTENERS.add(listener);
        if (!hooked) {
            hooked = true;
            NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, e -> LISTENERS.forEach(l -> l.accept(e.getDispatcher())));
        }
    }

    /** Operator permission (level 2, the level of /gamemode). */
    public static Predicate<CommandSourceStack> op() {
        return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);
    }

    /** A namespaced id argument ({@code mymod:thing}). */
    public static ArgumentType<?> id() {
        return IdentifierArgument.id();
    }

    public static FId getId(CommandContext<CommandSourceStack> context, String name) {
        return FId.from(IdentifierArgument.getId(context, name));
    }
}
