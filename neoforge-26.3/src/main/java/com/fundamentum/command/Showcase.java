package com.fundamentum.command;

import com.fundamentum.creature.CreatureTypes;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /fundamentum showcase <mod>}: builds a gallery high in the sky above the player and takes them there. Every mob
 * from the mod (its namespace, or {@code minecraft}) gets its own glass display case, sized to fit it: stone posts,
 * foot and cornice, glass sides and roof, a grass floor (water over sandstone for aquatic creatures), lit inside, with a
 * lantern post in front. The cases stand in rows facing each other across lit avenues, with a glass rail round the
 * platform. The mobs are alive, wandering and making their sounds, but they target nothing, take no damage, are named
 * and never despawn. Bosses and the mobs in {@code #fundamentum:showcase/still} (ones that would leave their case or
 * wreck it, like vexes or the wither) stand still instead.
 * {@code /fundamentum showcase return} takes the player back to where they started; {@code /fundamentum showcase clear}
 * removes the mobs and the gallery (back to air) and takes them back. One gallery per player; a new one replaces the
 * old. Where it is lives in the player's persistent data, so clear still works after a restart. Needs operator
 * permission.
 */
public final class Showcase {
    /** Tag on every showcased mob, so {@code clear} (or {@code @e[tag=fundamentum_showcase]}) finds them. */
    public static final String TAG = "fundamentum_showcase";
    /** Mobs that stand still (no behaviour) in the gallery. */
    public static final TagKey<EntityType<?>> STILL = TagKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath("fundamentum", "showcase/still"));

    private static final String DATA = "fundamentum_showcase";
    private static final int AVENUE = 7;
    private static final int GAP = 2;
    private static final int MARGIN = 3;
    private static final BlockState FRAME = Blocks.POLISHED_DEEPSLATE.defaultBlockState();
    private static final BlockState TRIM = Blocks.DEEPSLATE_TILES.defaultBlockState();
    private static final BlockState FLOOR = Blocks.SMOOTH_STONE.defaultBlockState();
    private static final BlockState TILES = Blocks.DEEPSLATE_BRICKS.defaultBlockState();
    private static final BlockState LAMP = Blocks.SEA_LANTERN.defaultBlockState();
    private static final BlockState GLASS = Blocks.GLASS.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /** One display case: its creature, its interior (size by size, height tall) and whether it is flooded. */
    private record Pen(Mob mob, int size, int height, boolean water) {}

    /** Where a case goes, relative to the gallery's north-west corner. */
    private record Spot(Pen pen, int x, int z, boolean facingSouth) {}

    public static void init() {
        NeoForge.EVENT_BUS.addListener(Showcase::register);
    }

    private static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("fundamentum")
            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
            .then(Commands.literal("showcase")
                .then(Commands.literal("clear").executes(Showcase::clear))
                .then(Commands.literal("return").executes(Showcase::goBack))
                .then(Commands.argument("mod", StringArgumentType.word())
                    .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                        BuiltInRegistries.ENTITY_TYPE.keySet().stream().map(id -> id.getNamespace()).distinct(), builder))
                    .executes(Showcase::summon))));
    }

    private static int summon(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        String mod = StringArgumentType.getString(context, "mod");
        ServerLevel level = player.level();
        List<Pen> pens = new ArrayList<>();
        BuiltInRegistries.ENTITY_TYPE.entrySet().stream()
            .filter(e -> e.getKey().identifier().getNamespace().equals(mod) && e.getValue().canSummon())
            .sorted(Comparator.comparing(e -> e.getKey().identifier().getPath()))
            .forEach(e -> {
                Entity entity = e.getValue().create(level, EntitySpawnReason.COMMAND);
                if (entity instanceof Mob mob) {
                    boolean water = mob.is(EntityTypeTags.AQUATIC) || CreatureTypes.is(mob, "aquatic");
                    pens.add(new Pen(mob, Math.max(5, Mth.ceil(mob.getBbWidth()) + 2), Math.max(4, Mth.ceil(mob.getBbHeight()) + 2), water));
                } else if (entity != null) {
                    entity.discard();
                }
            });
        if (pens.isEmpty()) {
            source.sendFailure(Component.translatable("fundamentum.showcase.none", mod));
            return 0;
        }
        CompoundTag old = player.getPersistentData().getCompoundOrEmpty(DATA);
        boolean wasInside = inGallery(player, old);
        removeGallery(player);

        // Rows of pens along +X, in pairs facing each other across an avenue: the first of a pair faces south, the
        // second north. Laid out from (0, 0) first, then moved so the gallery is centred on the player.
        int columns = Math.max(1, (int) Math.ceil(Math.sqrt(pens.size() * 1.4)));
        List<List<Pen>> rows = new ArrayList<>();
        for (int i = 0; i < pens.size(); i += columns) {
            rows.add(pens.subList(i, Math.min(i + columns, pens.size())));
        }
        List<Spot> spots = new ArrayList<>();
        List<Integer> avenues = new ArrayList<>();
        int width = 0;
        int z = 0;
        int tallest = 0;
        for (int r = 0; r < rows.size(); r += 2) {
            int north = rows.get(r).stream().mapToInt(Pen::size).max().orElse(5);
            int front = z + north + 1;                                   // the north row's glass fronts
            width = Math.max(width, lay(rows.get(r), spots, front, true));
            avenues.add(front + 1 + AVENUE / 2);
            z = front + AVENUE + 1;                                      // the south row's glass fronts
            if (r + 1 < rows.size()) {
                width = Math.max(width, lay(rows.get(r + 1), spots, z, false));
                z += rows.get(r + 1).stream().mapToInt(Pen::size).max().orElse(5) + 1 + GAP;
            } else {
                z -= 1;
            }
            for (Pen pen : rows.get(r)) {
                tallest = Math.max(tallest, pen.height());
            }
            if (r + 1 < rows.size()) {
                for (Pen pen : rows.get(r + 1)) {
                    tallest = Math.max(tallest, pen.height());
                }
            }
        }
        int floorY = level.getMaxY() - tallest - 3;
        BlockPos min = new BlockPos(player.getBlockX() - width / 2 - MARGIN, floorY, player.getBlockZ() - z / 2 - MARGIN);
        BlockPos max = new BlockPos(min.getX() + width + 2 * MARGIN, floorY + tallest + 2, min.getZ() + z + 2 * MARGIN);
        if (!clearSky(level, min, max)) {
            pens.forEach(p -> p.mob().discard());
            source.sendFailure(Component.translatable("fundamentum.showcase.blocked"));
            return 0;
        }
        int ox = min.getX() + MARGIN;
        int oz = min.getZ() + MARGIN;

        // the platform: tiles under the pens, a pale avenue floor with lamps, a glass rail round the edge
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int zz = min.getZ(); zz <= max.getZ(); zz++) {
                boolean edge = x == min.getX() || x == max.getX() || zz == min.getZ() || zz == max.getZ();
                int rel = zz - oz;
                boolean avenue = avenues.stream().anyMatch(a -> Math.abs(rel - a) <= AVENUE / 2);
                boolean lamp = avenue && rel == avenues.stream().filter(a -> Math.abs(rel - a) <= AVENUE / 2).findFirst().orElse(-99)
                    && Math.floorMod(x - ox, 6) == 3;
                set(level, new BlockPos(x, floorY, zz), edge ? TRIM : lamp ? LAMP : avenue ? FLOOR : TILES);
                if (edge) {
                    set(level, new BlockPos(x, floorY + 1, zz), GLASS);
                }
            }
        }
        int spawned = 0;
        for (Spot spot : spots) {
            if (place(level, spot.pen(), ox + spot.x(), floorY, oz + spot.z(), spot.facingSouth())) {
                spawned++;
            }
        }

        CompoundTag data = new CompoundTag();
        data.putString("dimension", level.dimension().identifier().toString());
        data.putLong("min", min.asLong());
        data.putLong("max", max.asLong());
        if (wasInside && old.contains("back_dimension")) {
            for (String key : List.of("back_dimension", "back_x", "back_y", "back_z", "back_yaw")) {
                data.put(key, old.get(key));
            }
        } else {
            data.putString("back_dimension", level.dimension().identifier().toString());
            data.putDouble("back_x", player.getX());
            data.putDouble("back_y", player.getY());
            data.putDouble("back_z", player.getZ());
            data.putFloat("back_yaw", player.getYRot());
        }
        player.getPersistentData().put(DATA, data);
        // the first avenue, at its west end, looking along it
        teleport(player, level, min.getX() + 2.5, floorY + 1, oz + avenues.getFirst() + 0.5, -90.0F);
        int count = spawned;
        source.sendSuccess(() -> Component.translatable("fundamentum.showcase.summoned", count, mod), true);
        return count;
    }

    /** Lays one row out along +X with its glass fronts on {@code front}; returns the row's length. */
    private static int lay(List<Pen> row, List<Spot> spots, int front, boolean facingSouth) {
        int x = 1;
        for (Pen pen : row) {
            int z0 = facingSouth ? front - pen.size() : front + 1;
            spots.add(new Spot(pen, x, z0, facingSouth));
            x += pen.size() + 2 + GAP;
        }
        return x - GAP - 1;
    }

    /**
     * Builds one display case with its north-west interior corner at (x0, z0): stone corner posts, a stone foot and
     * cornice, glass sides and roof, a lamp post in front on the avenue. Then puts its mob in it.
     */
    private static boolean place(ServerLevel level, Pen pen, int x0, int floorY, int z0, boolean facingSouth) {
        int s = pen.size();
        int h = pen.height();
        BlockState ground = pen.water() ? Blocks.SMOOTH_SANDSTONE.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
        BlockState inside = pen.water() ? Blocks.WATER.defaultBlockState() : AIR;
        for (int dx = -1; dx <= s; dx++) {
            for (int dz = -1; dz <= s; dz++) {
                boolean sideX = dx == -1 || dx == s;
                boolean sideZ = dz == -1 || dz == s;
                BlockPos foot = new BlockPos(x0 + dx, floorY, z0 + dz);
                set(level, foot, sideX || sideZ ? FRAME : ground);
                for (int dy = 1; dy <= h; dy++) {
                    set(level, foot.above(dy), sideX && sideZ ? FRAME : sideX || sideZ ? GLASS : inside);
                }
                set(level, foot.above(h + 1), sideX || sideZ ? TRIM : GLASS);
            }
        }
        int cx = x0 + s / 2;
        int cz = z0 + s / 2;
        if (pen.water()) {
            set(level, new BlockPos(cx, floorY, cz), LAMP);
        } else {
            set(level, new BlockPos(cx, floorY + h, cz), Blocks.LIGHT.defaultBlockState());
        }
        BlockPos post = new BlockPos(cx, floorY + 1, facingSouth ? z0 + s + 1 : z0 - 2);
        set(level, post, Blocks.POLISHED_DEEPSLATE_WALL.defaultBlockState());
        set(level, post.above(), Blocks.LANTERN.defaultBlockState());

        Mob mob = pen.mob();
        float yaw = facingSouth ? 0.0F : 180.0F;
        mob.snapTo(x0 + s / 2.0, floorY + 1, z0 + s / 2.0, yaw, 0.0F);
        mob.setYHeadRot(yaw);
        mob.setYBodyRot(yaw);
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()), EntitySpawnReason.COMMAND, null);
        mob.targetSelector.removeAllGoals(g -> true);
        mob.setTarget(null);
        if (mob.is(STILL) || CreatureTypes.is(mob, "boss")) {
            mob.setNoAi(true);
        }
        mob.setPermanentlyInvulnerable(true);
        mob.setPersistenceRequired();
        mob.setCustomName(mob.getType().getDescription());
        mob.setCustomNameVisible(true);
        mob.addTag(TAG);
        return level.addFreshEntity(mob);
    }

    private static boolean clearSky(ServerLevel level, BlockPos min, BlockPos max) {
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (!level.getBlockState(pos).isAir()) {
                return false;
            }
        }
        return true;
    }

    private static int goBack(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        if (!inGallery(player, player.getPersistentData().getCompoundOrEmpty(DATA)) || !sendBack(player)) {
            context.getSource().sendFailure(Component.translatable("fundamentum.showcase.no_gallery"));
            return 0;
        }
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> context) {
        int removed = 0;
        for (ServerLevel level : context.getSource().getServer().getAllLevels()) {
            List<Entity> tagged = new ArrayList<>();
            level.getAllEntities().forEach(e -> {
                if (e.entityTags().contains(TAG)) {
                    tagged.add(e);
                }
            });
            tagged.forEach(Entity::discard);
            removed += tagged.size();
        }
        if (context.getSource().getEntity() instanceof ServerPlayer player) {
            if (inGallery(player, player.getPersistentData().getCompoundOrEmpty(DATA))) {
                sendBack(player);
            }
            removeGallery(player);
            player.getPersistentData().remove(DATA);
        }
        int count = removed;
        context.getSource().sendSuccess(() -> Component.translatable("fundamentum.showcase.cleared", count), true);
        return count;
    }

    /** Whether the player stands in (or just above) their gallery. */
    private static boolean inGallery(ServerPlayer player, CompoundTag data) {
        if (!data.contains("dimension") || !player.level().dimension().identifier().toString().equals(data.getStringOr("dimension", ""))) {
            return false;
        }
        BlockPos min = BlockPos.of(data.getLongOr("min", 0));
        BlockPos max = BlockPos.of(data.getLongOr("max", 0));
        return new AABB(Vec3.atCenterOf(min), Vec3.atCenterOf(max)).inflate(32).contains(player.position());
    }

    /** Turns the player's gallery (if any) back to air, mobs and all. */
    private static void removeGallery(ServerPlayer player) {
        CompoundTag data = player.getPersistentData().getCompoundOrEmpty(DATA);
        if (!data.contains("dimension")) {
            return;
        }
        ServerLevel level = level(player, data.getStringOr("dimension", ""));
        if (level != null) {
            BlockPos min = BlockPos.of(data.getLongOr("min", 0));
            BlockPos max = BlockPos.of(data.getLongOr("max", 0));
            for (Entity e : level.getEntities((Entity) null, new AABB(Vec3.atCenterOf(min), Vec3.atCenterOf(max)).inflate(2), e -> e.entityTags().contains(TAG))) {
                e.discard();
            }
            for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
                if (!level.getBlockState(pos).isAir()) {
                    level.setBlock(pos, AIR, Block.UPDATE_CLIENTS);
                }
            }
        }
        data.remove("dimension");
    }

    private static boolean sendBack(ServerPlayer player) {
        CompoundTag data = player.getPersistentData().getCompoundOrEmpty(DATA);
        ServerLevel level = level(player, data.getStringOr("back_dimension", ""));
        if (level == null) {
            return false;
        }
        teleport(player, level, data.getDoubleOr("back_x", 0), data.getDoubleOr("back_y", 0), data.getDoubleOr("back_z", 0), data.getFloatOr("back_yaw", 0));
        return true;
    }

    private static ServerLevel level(ServerPlayer player, String dimension) {
        Identifier id = Identifier.tryParse(dimension);
        return id == null || dimension.isEmpty() ? null : player.level().getServer().getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    private static void teleport(ServerPlayer player, ServerLevel level, double x, double y, double z, float yaw) {
        player.teleportTo(level, x, y, z, Set.of(), yaw, 10.0F, true);
        player.resetFallDistance();
        level.sendParticles(ParticleTypes.PORTAL, x, y + 1, z, 40, 0.4, 0.8, 0.4, 0.3);
        level.playSound(null, x, y, z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.6F, 1.2F);
    }

    private static void set(Level level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
    }

    private Showcase() {}
}
