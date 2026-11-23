package com.fundamentum.multiblock;

import com.fundamentum.port.FCommands;
import com.fundamentum.port.FId;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /fundamentum multiblock check <pos>} (match and form the structure whose controller is there),
 * {@code preview <id> <pos>} (a ghost facing the player) and {@code preview clear}. Operators only.
 */
final class MultiblockCommand {
    private MultiblockCommand() { }

    private static final SuggestionProvider<CommandSourceStack> IDS = (c, b) ->
        SharedSuggestionProvider.suggest(Multiblocks.patterns().keySet().stream().map(FId::toString), b);

    static void init() {
        FCommands.listen(d -> d.register(Commands.literal("fundamentum").requires(FCommands.op())
            .then(Commands.literal("multiblock")
                .then(Commands.literal("check")
                    .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(MultiblockCommand::check)))
                .then(Commands.literal("preview")
                    .then(Commands.literal("clear").executes(MultiblockCommand::clear))
                    .then(Commands.argument("id", FCommands.id()).suggests(IDS)
                        .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(MultiblockCommand::preview)))))));
    }

    private static int check(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(c, "pos");
        Multiblocks.Match m = Multiblocks.check(c.getSource().getLevel(), pos);
        if (m == null) {
            c.getSource().sendFailure(Component.literal("No multiblock with its controller at " + pos.toShortString()));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.literal(m.id() + " formed, facing " + m.facing().getName() + (m.mirrored() ? ", mirrored" : "")), false);
        return 1;
    }

    private static int preview(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        FId id = FCommands.getId(c, "id");
        if (Multiblocks.pattern(id) == null) {
            c.getSource().sendFailure(Component.literal("No multiblock " + id));
            return 0;
        }
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(c, "pos");
        Multiblocks.preview(player, id, pos, player.getDirection().getOpposite(), false);
        c.getSource().sendSuccess(() -> Component.literal("Showing " + id + " at " + pos.toShortString()), false);
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Multiblocks.clearPreview(c.getSource().getPlayerOrException());
        return 1;
    }
}
