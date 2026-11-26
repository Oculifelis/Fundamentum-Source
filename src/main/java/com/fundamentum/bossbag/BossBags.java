package com.fundamentum.bossbag;

import com.fundamentum.Fundamentum;
import com.fundamentum.boss.Boss;
import com.fundamentum.boss.Bosses;
import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.fundamentum.port.FId;
import com.fundamentum.port.FReg;
import com.fundamentum.port.FStackData;
import com.fundamentum.event.Events;
import com.fundamentum.event.FEvents;
import com.fundamentum.port.FStreamCodec;
import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.neoforged.neoforge.common.util.TriState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Boss bags, Terraria style. A boss named by a {@code fundamentum/boss_bags} data file ({@link BagDef}) drops no loot
 * of its own: each player who took part in the fight gets a bag at their feet instead, theirs alone for a minute, and
 * opening it rolls the boss's loot table for them (and gives the boss's XP). Everything is data: which bosses, the bag
 * item, the loot table, who counts as taking part, what every bag holds besides, the puff colours and the sound.
 * Mods and scripts can also use {@link BossBagEvents} and the static calls here.
 */
public final class BossBags {
    public static final String PATH = "fundamentum/boss_bags";
    /** Every bag file, keyed by file id; a datapack file of the same id is merged over the mod's. */
    public static final DataTable<BagDef> TABLE = DataTables.register(ResourceLocation.fromNamespaceAndPath(Fundamentum.MODID, "boss_bags"), PATH,
        BagDef::fromJson).synced().onLoad(t -> clearCache());

    /** The boss a bag is of (its entity id). */
    public static final FStackData<FId> BOSS = FStackData.of(FId.of(Fundamentum.MODID, "bag_boss"), FId.CODEC, FStreamCodec.ID);
    /** The XP a bag gives. */
    public static final FStackData<Integer> XP = FStackData.of(FId.of(Fundamentum.MODID, "bag_xp"), Codec.INT, FStreamCodec.INT);
    /** How its boss fell ({@link BossBagExtras}). */
    public static final FStackData<Integer> STATE = FStackData.of(FId.of(Fundamentum.MODID, "bag_state"), Codec.INT, FStreamCodec.INT);

    /** The generic bag, {@code fundamentum:boss_bag}. */
    public static final FReg.Ref<BossBagItem> ITEM = FReg.item(FId.of(Fundamentum.MODID, "boss_bag"), BossBagItem::new,
        new Item.Properties().stacksTo(16).rarity(Rarity.RARE).fireResistant());

    private static final String HURT_BY = "fundamentum_bag_hurt_by";
    private static final String FIGHTERS = "fundamentum_bag_fighters";
    private static final String LEFT = "fundamentum_bag_left";
    private static final String OWNER = "fundamentum_bag_owner";
    private static final String LOOSE = "fundamentum_bag_loose";
    private static final String HELD_XP = "fundamentum_bag_held_xp";

    private static final Map<EntityType<?>, Optional<BagDef>> CACHE = new ConcurrentHashMap<>();

    private BossBags() { }

    public static void init(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(BossBags::hurt);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, BossBags::death);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, BossBags::drops);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, BossBags::heldXp);
        NeoForge.EVENT_BUS.addListener(BossBags::pickup);
        NeoForge.EVENT_BUS.addListener((TagsUpdatedEvent e) -> clearCache());
    }

    private static void clearCache() {
        CACHE.clear();
    }

    // ---- which bosses ----

    /** The bag entry for this entity type, or null: a file naming it wins over one naming a tag it is in. */
    public static @Nullable BagDef def(EntityType<?> type) {
        return CACHE.computeIfAbsent(type, BossBags::find).orElse(null);
    }

    public static @Nullable BagDef def(Entity e) {
        return def(e.getType());
    }

    private static Optional<BagDef> find(EntityType<?> type) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        Map<ResourceLocation, BagDef> sorted = new TreeMap<>(TABLE.entries());
        for (BagDef d : sorted.values()) {
            if (d.entities().contains(id)) return d.enabled() ? Optional.of(d) : Optional.empty();
        }
        for (BagDef d : sorted.values()) {
            for (TagKey<EntityType<?>> tag : d.tags()) {
                if (type.builtInRegistryHolder().is(tag)) return d.enabled() ? Optional.of(d) : Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** Whether this boss's loot goes into bags. */
    public static boolean bagged(Entity e) {
        return def(e) != null;
    }

    /** Whether this boss already left its bags (its XP is then kept back). */
    public static boolean leftBags(Entity e) {
        return e.getPersistentData().getBoolean(LEFT);
    }

    // ---- who takes part ----

    /** The players who hurt {@code boss}, by id. */
    public static List<UUID> hurtBy(LivingEntity boss) {
        return uuids(boss.getPersistentData().getCompound(HURT_BY));
    }

    /**
     * The players who took part in the fight with {@code boss}, as its entry's {@code participation} says: the one who
     * dealt the blow, those who hurt it, those on its Fundamentum boss bar, and those in its arena. Only players in its
     * level, never spectators.
     */
    public static List<ServerPlayer> takers(ServerLevel level, LivingEntity boss, @Nullable Entity killer) {
        BagDef def = def(boss);
        BagDef.Participation part = def != null ? def.participation() : BagDef.Participation.DEFAULT;
        List<UUID> fighters = uuids(boss.getPersistentData().getCompound(FIGHTERS));
        Boss live = Bosses.get(boss);
        if (live != null) fighters.addAll(live.fighters());
        return takers(level, boss, killer, part, part.fighters() ? fighters : List.of());
    }

    /** As {@link #takers(ServerLevel, LivingEntity, Entity)}, and also {@code fighters}: a mod's own boss bar, by id. */
    public static List<ServerPlayer> takers(ServerLevel level, LivingEntity boss, @Nullable Entity killer, Collection<UUID> fighters) {
        List<ServerPlayer> out = takers(level, boss, killer);
        for (UUID id : fighters) {
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
            if (p != null) add(out, level, p);
        }
        return out;
    }

    /** As {@link #takers(ServerLevel, LivingEntity, Entity)}, with the participation rules and fighters given. */
    public static List<ServerPlayer> takers(ServerLevel level, LivingEntity boss, @Nullable Entity killer, BagDef.Participation part,
            Collection<UUID> fighters) {
        List<ServerPlayer> out = new ArrayList<>();
        if (part.killer()) {
            if (killer instanceof ServerPlayer p) add(out, level, p);
            if (boss.getKillCredit() instanceof ServerPlayer p) add(out, level, p);
        }
        List<UUID> hurt = part.hurt() ? hurtBy(boss) : List.of();
        for (ServerPlayer p : level.players()) {
            Vec3 d = p.position().subtract(boss.position());
            if (hurt.contains(p.getUUID()) || part.inRange(d.x, d.y, d.z)) add(out, level, p);
        }
        for (UUID id : fighters) {
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
            if (p != null) add(out, level, p);
        }
        return out;
    }

    private static void add(List<ServerPlayer> out, ServerLevel level, ServerPlayer p) {
        if (p.isSpectator() || p.level() != level) return;
        for (ServerPlayer q : out) {
            if (q.getUUID().equals(p.getUUID())) return;
        }
        out.add(p);
    }

    // ---- leaving bags ----

    /** A bag of {@code boss} giving {@code xp}, as its entry's item (or the generic one). */
    public static ItemStack bag(EntityType<?> boss, int xp) {
        BagDef def = def(boss);
        Item item = def == null ? null : BuiltInRegistries.ITEM.get(def.item());
        ItemStack s = new ItemStack(item instanceof BossBagItem ? item : ITEM.get());
        BOSS.set(s, FId.from(BuiltInRegistries.ENTITY_TYPE.getKey(boss)));
        XP.set(s, Math.max(0, xp));
        return s;
    }

    /**
     * Leaves {@code boss}'s bags: one for each of {@code takers}, at their feet, each giving {@code xp}. Returns false (and
     * leaves nothing) when there is no one to take one or a {@link BossBagEvents.Leave} listener cancelled it, so the
     * caller drops the loot loose as usual.
     */
    public static boolean leave(ServerLevel level, LivingEntity boss, Collection<ServerPlayer> takers, int xp) {
        BagDef def = def(boss);
        if (def == null) return false;
        BossBagEvents.Leave event = NeoForge.EVENT_BUS.post(new BossBagEvents.Leave(level, boss, def, new ArrayList<>(takers), xp));
        if (event.isCanceled() || event.takers().isEmpty()) return false;
        if (FEvents.post(new Events.BossBagsLeft(boss, event.takers().size(), event.xp()))) return false;
        boss.getPersistentData().putBoolean(LEFT, true);
        for (ServerPlayer p : event.takers()) {
            ItemStack bag = bag(boss.getType(), event.xp());
            if (boss instanceof BossBagExtras be) STATE.set(bag, be.bagState());
            drop(level, p, bag, def.ownTicks());
        }
        return true;
    }

    /** A bag at {@code owner}'s feet, theirs alone for a minute; it never despawns or burns. */
    public static ItemEntity drop(ServerLevel level, ServerPlayer owner, ItemStack bag) {
        return drop(level, owner, bag, 1200);
    }

    private static ItemEntity drop(ServerLevel level, ServerPlayer owner, ItemStack bag, int ownTicks) {
        Vec3 at = spot(level, owner.position());
        ItemEntity e = new ItemEntity(level, at.x, at.y, at.z, bag);
        e.setDeltaMovement(Vec3.ZERO);
        e.setPickUpDelay(10);
        e.setUnlimitedLifetime();
        e.setInvulnerable(true);
        e.getPersistentData().putString(OWNER, owner.getUUID().toString());
        e.getPersistentData().putInt("fundamentum_bag_own_ticks", ownTicks);
        level.addFreshEntity(e);
        return e;
    }

    /** Whose a bag on the ground is (null: anyone's). */
    public static @Nullable UUID owner(ItemEntity e) {
        String s = e.getPersistentData().getString(OWNER);
        try {
            return s.isEmpty() ? null : UUID.fromString(s);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * Whether {@code player} may pick up the bag {@code e} now: its owner always, anyone else once it has lain its
     * entry's {@code own_seconds} (ticks since it fell or since the world loaded it again; a bag never despawns, so its
     * age is no clock).
     */
    public static boolean mayTake(ItemEntity e, UUID player) {
        UUID owner = owner(e);
        return owner == null || owner.equals(player) || e.tickCount >= own(e);
    }

    private static int own(ItemEntity e) {
        return e.getPersistentData().contains("fundamentum_bag_own_ticks") ? e.getPersistentData().getInt("fundamentum_bag_own_ticks") : 1200;
    }

    // ---- opening ----

    /** The boss a bag is of, or null when it names none or one that is gone. */
    public static @Nullable EntityType<?> boss(ItemStack bag) {
        FId id = BOSS.get(bag);
        if (id == null) return null;
        return BuiltInRegistries.ENTITY_TYPE.getOptional(id.vanilla()).orElse(null);
    }

    public static int xp(ItemStack bag) {
        return XP.getOr(bag, 0);
    }

    /**
     * Opens {@code bag} for {@code player}: the boss's XP at their feet and its {@link #contents}, after any
     * {@link BossBagEvents.Open} listener changed them. Returns the items; the caller gives them and uses up the bag.
     */
    public static List<ItemStack> open(ServerLevel level, ServerPlayer player, ItemStack bag) {
        if (boss(bag) == null) return List.of();
        int xp = xp(bag);
        if (xp > 0) ExperienceOrb.award(level, player.position(), xp);
        BossBagEvents.Open event = NeoForge.EVENT_BUS.post(new BossBagEvents.Open(player, bag, new ArrayList<>(contents(level, player, bag))));
        FEvents.post(new Events.BossBagOpened(player, bag, event.items().size()));
        return event.items();
    }

    /** Everything a bag gives: its {@link #roll} and what every bag of its boss holds besides ({@link BossBagItem#always}). */
    public static List<ItemStack> contents(ServerLevel level, ServerPlayer player, ItemStack bag) {
        List<ItemStack> got = new ArrayList<>(roll(level, player, bag));
        if (bag.getItem() instanceof BossBagItem item) got.addAll(item.always(bag));
        return got;
    }

    /** The bag's roll for {@code player} alone: the loot table of its entry (or of the boss), with them as its slayer. */
    public static List<ItemStack> roll(ServerLevel level, ServerPlayer player, ItemStack bag) {
        EntityType<?> t = boss(bag);
        if (t == null) return List.of();
        // the table asks for its boss: a body made for the roll, never added to the world
        Entity e = t.create(level);
        if (!(e instanceof LivingEntity boss)) {
            if (e != null) e.discard();
            return List.of();
        }
        try {
            BagDef def = def(t);
            Optional<ResourceKey<LootTable>> key = def != null && def.lootTable() != null
                ? Optional.of(ResourceKey.create(Registries.LOOT_TABLE, def.lootTable())) : Optional.of(boss.getLootTable());
            if (key.isEmpty()) return List.of();
            boss.moveTo(player.getX(), player.getY(), player.getZ(), 0F, 0F);
            LootTable table = level.getServer().reloadableRegistries().getLootTable(key.get());
            LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.THIS_ENTITY, boss)
                .withParameter(LootContextParams.ORIGIN, player.position())
                .withParameter(LootContextParams.DAMAGE_SOURCE, level.damageSources().playerAttack(player))
                .withOptionalParameter(LootContextParams.ATTACKING_ENTITY, player)
                .withOptionalParameter(LootContextParams.DIRECT_ATTACKING_ENTITY, player)
                .withOptionalParameter(LootContextParams.LAST_DAMAGE_PLAYER, player)
                .withLuck(player.getLuck())
                .create(LootContextParamSets.ENTITY);
            List<ItemStack> out = new ArrayList<>(table.getRandomItems(params));
            Integer state = STATE.get(bag);
            if (state != null && boss instanceof BossBagExtras be) out.addAll(be.bagExtras(level.getRandom(), state));
            return out;
        } finally {
            boss.discard();
        }
    }

    /** Opens the bag in {@code player}'s hand as a use would: gives the items, uses up the bag, plays the puff. */
    public static boolean use(ServerLevel level, ServerPlayer player, ItemStack stack) {
        if (boss(stack) == null) return false;
        ItemStack bag = stack.copyWithCount(1);
        List<ItemStack> got = open(level, player, bag);
        if (!player.getAbilities().instabuild) stack.shrink(1);
        for (ItemStack s : got) {
            if (!player.getInventory().add(s)) player.drop(s, false);
        }
        if (bag.getItem() instanceof BossBagItem item) item.opened(level, player, bag);
        return true;
    }

    // ---- events ----

    private static void hurt(LivingDamageEvent.Post e) {
        LivingEntity boss = e.getEntity();
        if (boss.level().isClientSide() || !(e.getSource().getEntity() instanceof ServerPlayer p) || !bagged(boss)) return;
        CompoundTag by = boss.getPersistentData().getCompound(HURT_BY);
        by.putBoolean(p.getUUID().toString(), true);
        boss.getPersistentData().put(HURT_BY, by);
    }

    /** The boss's Fundamentum fighters, kept before its boss runtime ends. */
    private static void death(LivingDeathEvent e) {
        LivingEntity boss = e.getEntity();
        if (boss.level().isClientSide() || !bagged(boss)) return;
        Boss live = Bosses.get(boss);
        if (live == null) return;
        CompoundTag f = boss.getPersistentData().getCompound(FIGHTERS);
        for (UUID id : live.fighters()) f.putBoolean(id.toString(), true);
        boss.getPersistentData().put(FIGHTERS, f);
    }

    private static void drops(LivingDropsEvent e) {
        LivingEntity boss = e.getEntity();
        if (!(boss.level() instanceof ServerLevel level) || leftBags(boss)) return;
        BagDef def = def(boss);
        if (def == null) return;
        Entity killer = e.getSource().getEntity();
        int xp = def.xp() ? boss.getExperienceReward(level, killer) : 0;
        if (level.getGameRules().getBoolean(GameRules.RULE_DOMOBLOOT) && leave(level, boss, takers(level, boss, killer), xp)) {
            // the boss's own loot is in the bags; what other listeners add after this still drops
            e.getDrops().clear();
            return;
        }
        // loose after all: any XP held back (below) falls now, where it would have
        boss.getPersistentData().putBoolean(LOOSE, true);
        int held = boss.getPersistentData().getInt(HELD_XP);
        boss.getPersistentData().remove(HELD_XP);
        if (held > 0) ExperienceOrb.award(level, boss.position(), held);
    }

    /**
     * A bagged boss's XP orbs: kept back once its bags are left; let fall if its loot fell loose; and if its XP comes
     * before its drops, held until the drops say which.
     */
    private static void heldXp(LivingExperienceDropEvent e) {
        LivingEntity boss = e.getEntity();
        BagDef def = def(boss);
        if (def == null || !def.xp() || boss.getPersistentData().getBoolean(LOOSE)) return;
        if (!leftBags(boss)) boss.getPersistentData().putInt(HELD_XP, boss.getPersistentData().getInt(HELD_XP) + e.getDroppedExperience());
        e.setCanceled(true);
    }

    private static void pickup(ItemEntityPickupEvent.Pre e) {
        ItemEntity item = e.getItemEntity();
        if (item.getItem().getItem() instanceof BossBagItem && !mayTake(item, e.getPlayer().getUUID())) e.setCanPickup(TriState.FALSE);
    }

    // ---- helpers ----

    private static List<UUID> uuids(CompoundTag tag) {
        List<UUID> out = new ArrayList<>();
        for (String k : tag.getAllKeys()) {
            try {
                out.add(UUID.fromString(k));
            } catch (IllegalArgumentException ignored) {
                // not ours
            }
        }
        return out;
    }

    /** An open spot near {@code at} for a bag: clear air over solid ground, its own level first; else the nearest player. */
    public static Vec3 spot(ServerLevel level, Vec3 at) {
        BlockPos base = BlockPos.containing(at);
        if (open(level, base)) return at;
        for (int r = 0; r <= 6; r++) {
            for (int dy = 0; dy <= 8; dy++) {
                int y = (dy & 1) == 0 ? dy / 2 : -(dy + 1) / 2;
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                        BlockPos p = base.offset(dx, y, dz);
                        if (open(level, p)) return Vec3.atBottomCenterOf(p).add(0, 0.1, 0);
                    }
                }
            }
        }
        Player near = level.getNearestPlayer(at.x, at.y, at.z, 48.0, p -> !p.isSpectator());
        return near != null ? near.position().add(0, 0.2, 0) : at;
    }

    private static boolean open(ServerLevel level, BlockPos p) {
        if (!level.isLoaded(p)) return false;
        var here = level.getBlockState(p);
        var under = level.getBlockState(p.below());
        return !here.blocksMotion() && here.getFluidState().isEmpty() && under.blocksMotion() && under.getFluidState().isEmpty();
    }
}
