package com.fundamentum.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /fundamentum showcase <mod> [gap]}: summons one of every mob from a mod (its namespace, or {@code minecraft})
 * in a grid in front of the player, each standing still on the ground below it (no behaviour), silent, invulnerable, persistent and named after its
 * type, facing back toward where the command was run. {@code /fundamentum showcase clear} removes them all. Needs
 * operator permission.
 */
public final class Showcase {
    /** Tag on every showcased mob, so {@code clear} (or {@code @e[tag=fundamentum_showcase]}) finds them. */
    public static final String TAG = "fundamentum_showcase";

    public static void init() {
        NeoForge.EVENT_BUS.addListener(Showcase::register);
    }

    private static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("fundamentum")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("showcase")
                .then(Commands.literal("clear").executes(Showcase::clear))
                .then(Commands.argument("mod", StringArgumentType.word())
                    .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                        BuiltInRegistries.ENTITY_TYPE.keySet().stream().map(id -> id.getNamespace()).distinct(), builder))
                    .executes(context -> summon(context, 2))
                    .then(Commands.argument("gap", IntegerArgumentType.integer(0, 16))
                        .executes(context -> summon(context, IntegerArgumentType.getInteger(context, "gap")))))));
    }

    private static int summon(CommandContext<CommandSourceStack> context, int gap) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        String mod = StringArgumentType.getString(context, "mod");
        List<Mob> mobs = new ArrayList<>();
        BuiltInRegistries.ENTITY_TYPE.entrySet().stream()
            .filter(e -> e.getKey().location().getNamespace().equals(mod) && e.getValue().canSummon())
            .sorted(Comparator.comparing(e -> e.getKey().location().getPath()))
            .forEach(e -> {
                Entity entity = e.getValue().create(level);
                if (entity instanceof Mob mob) {
                    mobs.add(mob);
                } else if (entity != null) {
                    entity.discard();
                }
            });
        if (mobs.isEmpty()) {
            source.sendFailure(Component.translatable("fundamentum.showcase.none", mod));
            return 0;
        }
        // Rows of about sqrt(n) mobs along +X, each as wide as its creature plus the gap; rows step along +Z.
        Vec3 origin = source.getPosition().add(2.0, 0.0, 3.0);
        int columns = (int) Math.ceil(Math.sqrt(mobs.size()));
        double rowZ = origin.z;
        int spawned = 0;
        for (int start = 0; start < mobs.size(); start += columns) {
            List<Mob> row = mobs.subList(start, Math.min(start + columns, mobs.size()));
            double depth = row.stream().mapToDouble(Entity::getBbWidth).max().orElse(1.0);
            double x = origin.x;
            for (Mob mob : row) {
                double half = mob.getBbWidth() / 2.0;
                double mx = x + half;
                double mz = rowZ + depth / 2.0;
                double y = ground(level, mx, origin.y, mz);
                mob.moveTo(mx, y, mz, 180.0F, 0.0F);
                mob.setYHeadRot(180.0F);
                mob.setYBodyRot(180.0F);
                mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()), MobSpawnType.COMMAND, null);
                mob.setNoAi(true);
                mob.setSilent(true);
                mob.setInvulnerable(true);
                mob.setPersistenceRequired();
                mob.setCustomName(mob.getType().getDescription());
                mob.setCustomNameVisible(true);
                mob.addTag(TAG);
                if (level.addFreshEntity(mob)) {
                    spawned++;
                }
                x += mob.getBbWidth() + gap;
            }
            rowZ += depth + gap;
        }
        int count = spawned;
        source.sendSuccess(() -> Component.translatable("fundamentum.showcase.summoned", count, mod), true);
        return count;
    }

    /** The first floor at or below a little over the start height (mobs without behaviour do not fall), else the surface. */
    private static double ground(ServerLevel level, double x, double startY, double z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(Mth.floor(x), Mth.floor(startY) + 3, Mth.floor(z));
        for (int i = 0; i < 64; i++, pos.move(Direction.DOWN)) {
            BlockPos below = pos.below();
            if (level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                && !level.getBlockState(below).getCollisionShape(level, below).isEmpty()) {
                return pos.getY();
            }
        }
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ());
    }

    private static int clear(CommandContext<CommandSourceStack> context) {
        int removed = 0;
        for (ServerLevel level : context.getSource().getServer().getAllLevels()) {
            List<Entity> tagged = new ArrayList<>();
            level.getAllEntities().forEach(e -> {
                if (e.getTags().contains(TAG)) {
                    tagged.add(e);
                }
            });
            tagged.forEach(Entity::discard);
            removed += tagged.size();
        }
        int count = removed;
        context.getSource().sendSuccess(() -> Component.translatable("fundamentum.showcase.cleared", count), true);
        return count;
    }

    private Showcase() {}
}
