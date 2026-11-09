package com.fundamentum.relic;

import com.fundamentum.Fundamentum;
import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Boss relics: a Terraria-style trophy block per boss, the boss's gold likeness floating, bobbing and turning slowly over a
 * base (a redstone signal stops the turn and faces it forward). A mod declares each relic in
 * {@code data/<modid>/fundamentum/relics/<name>.json} ({@link RelicDef}) and registers them with {@link #register} into its
 * own registers, so the blocks, items, grand relics' filler blocks and (optionally) the block entity type keep the mod's
 * ids. Fundamentum supplies the blocks' behaviour, the block entity, the renderer, the boss drop and the JEI page. The
 * same files are also a synced data table ({@link #TABLE}), so a datapack can retune a relic's look and drop; what makes
 * the block itself (size, shape, strength, light, rarity, stack) is read once, at registration, from the owning mod.
 */
public final class Relics {
    private static final Logger LOG = LoggerFactory.getLogger("fundamentum");
    public static final String PATH = "fundamentum/relics";
    /** Every relic file, keyed by its block id; a datapack file of the same id is merged over the mod's. */
    public static final DataTable<RelicDef> TABLE = DataTables.register(Identifier.fromNamespaceAndPath(Fundamentum.MODID, "relics"), PATH,
        json -> RelicDef.parse(json, "relic")).synced();

    private static final DeferredRegister<BlockEntityType<?>> OWN_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Fundamentum.MODID);
    private static final List<DeferredBlock<RelicBlock>> SHARED = new ArrayList<>();
    /** The block entity type for relics whose mod brings none of its own: {@code fundamentum:relic}. */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RelicBlockEntity>> SHARED_ENTITY = OWN_ENTITIES.register("relic",
        () -> new BlockEntityType<>(RelicBlockEntity::new, java.util.Set.copyOf(SHARED.stream().<Block>map(DeferredBlock::get).toList())));

    private static final List<RelicSet> SETS = new ArrayList<>();
    private static final List<DeferredHolder<BlockEntityType<?>, BlockEntityType<RelicBlockEntity>>> ENTITY_TYPES = new ArrayList<>(List.of(SHARED_ENTITY));

    private Relics() {}

    public static void init(IEventBus modBus) {
        OWN_ENTITIES.register(modBus);
        NeoForge.EVENT_BUS.addListener(Relics::drops);
        NeoForge.EVENT_BUS.addListener(Relics::death);
    }

    /**
     * Registers {@code names}' relics, each read from {@code data/<modid>/fundamentum/relics/<name>.json} in the jar of
     * {@code owner} (any class of the calling mod), into the mod's own registers: block and item {@code <modid>:<name>},
     * a grand relic's filler {@code <modid>:<part>} (shared by every grand relic naming the same part), and, when
     * {@code blockEntities} is given, one block entity type {@code <modid>:<entityName>} for them all (otherwise
     * {@code fundamentum:relic}). Call it while the mod is constructed, before its registers are added to the bus.
     */
    public static RelicSet register(Class<?> owner, String modid, DeferredRegister.Blocks blocks, DeferredRegister.Items items,
                                    @Nullable DeferredRegister<BlockEntityType<?>> blockEntities, String entityName, String... names) {
        RelicSet set = new RelicSet(modid);
        Map<String, DeferredBlock<RelicPartBlock>> parts = new LinkedHashMap<>();
        List<DeferredBlock<RelicBlock>> mine = new ArrayList<>();
        for (String name : names) {
            RelicDef def = read(owner, modid, name);
            DeferredBlock<RelicPartBlock> part = null;
            if (def.grand()) {
                part = parts.computeIfAbsent(def.part, p -> blocks.registerBlock(p, q -> new RelicPartBlock(q, RelicBlock.shape(def.partShape)),
                    props -> stone(props, def.strength, def.resistance, def.partLight).noLootTable()));
            }
            DeferredBlock<RelicPartBlock> partFinal = part;
            DeferredBlock<RelicBlock> block = blocks.registerBlock(name, p -> new RelicBlock(p, def, partFinal, set),
                props -> stone(props, def.strength, def.resistance, def.light));
            Rarity rarity = rarity(def.rarity);
            DeferredItem<BlockItem> item = items.registerSimpleBlockItem(name, block, p -> {
                Item.Properties q = p.stacksTo(Math.max(1, Math.min(99, def.stack))).rarity(rarity);
                return def.fireResistant ? q.fireResistant() : q;
            });
            set.add(name, def, block, item, part);
            mine.add(block);
        }
        if (blockEntities != null) {
            set.entity = blockEntities.register(entityName, () -> new BlockEntityType<>(RelicBlockEntity::new,
                java.util.Set.copyOf(mine.stream().<Block>map(DeferredBlock::get).toList())));
            ENTITY_TYPES.add(set.entity);
        } else {
            set.entity = SHARED_ENTITY;
            SHARED.addAll(mine);
        }
        SETS.add(set);
        return set;
    }

    /** {@link #register} with Fundamentum's shared block entity type. */
    public static RelicSet register(Class<?> owner, String modid, DeferredRegister.Blocks blocks, DeferredRegister.Items items, String... names) {
        return register(owner, modid, blocks, items, null, "", names);
    }

    /** Every registered relic set, in registration order. */
    public static List<RelicSet> sets() {
        return Collections.unmodifiableList(SETS);
    }

    /** Every relic block entity type (Fundamentum's shared one first), for the renderer. */
    public static List<DeferredHolder<BlockEntityType<?>, BlockEntityType<RelicBlockEntity>>> entityTypes() {
        return Collections.unmodifiableList(ENTITY_TYPES);
    }

    /** {@code block}'s declaration as it stands: the data table's entry when one loaded, else what it was registered with. */
    public static RelicDef def(RelicBlock block) {
        RelicDef live = TABLE.get(BuiltInRegistries.BLOCK.getKey(block));
        return live != null ? live : block.registered();
    }

    /** The relic item for a block id, or null. */
    public static @Nullable Item item(Identifier relic) {
        Block b = BuiltInRegistries.BLOCK.getValue(relic);
        return b instanceof RelicBlock ? b.asItem() : null;
    }

    /** Hands {@code relic} to each of {@code players} (into the inventory, or dropped at their feet when it is full). */
    public static void hand(Identifier relic, List<ServerPlayer> players) {
        Item item = item(relic);
        if (item == null) {
            return;
        }
        for (ServerPlayer p : players) {
            give(p, new ItemStack(item));
        }
    }

    private static void give(ServerPlayer p, ItemStack stack) {
        if (!p.getInventory().add(stack)) {
            p.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
        }
    }

    /** The relics a boss of this type gives, with how. */
    private static List<RelicBlock> relicsOf(Entity boss, RelicDef.Drop how) {
        Identifier type = BuiltInRegistries.ENTITY_TYPE.getKey(boss.getType());
        List<RelicBlock> out = new ArrayList<>();
        for (RelicSet set : SETS) {
            for (DeferredBlock<RelicBlock> b : set.blocks().values()) {
                RelicDef d = def(b.get());
                if (d.drop == how && type.toString().equals(d.boss)) {
                    out.add(b.get());
                }
            }
        }
        return out;
    }

    /** "drops": the relic falls with the boss's drops. */
    private static void drops(LivingDropsEvent event) {
        Entity boss = event.getEntity();
        for (RelicBlock b : relicsOf(boss, RelicDef.Drop.DROPS)) {
            event.getDrops().add(new ItemEntity(boss.level(), boss.getX(), boss.getY(), boss.getZ(), new ItemStack(b)));
        }
    }

    /** "killer": the relic goes straight to the player who landed the killing blow (or drops where the boss died). */
    private static void death(LivingDeathEvent event) {
        Entity boss = event.getEntity();
        if (boss.level().isClientSide()) {
            return;
        }
        for (RelicBlock b : relicsOf(boss, RelicDef.Drop.KILLER)) {
            ItemStack stack = new ItemStack(b);
            if (event.getSource().getEntity() instanceof ServerPlayer player) {
                give(player, stack);
            } else {
                boss.spawnAtLocation((ServerLevel) boss.level(), stack);
            }
        }
    }

    /** A gold relic: blast-proof as declared, unmovable (a grand relic is never split apart), lit as declared. */
    private static BlockBehaviour.Properties stone(BlockBehaviour.Properties p, float strength, float resistance, int light) {
        int l = Math.max(0, Math.min(15, light));
        return p.mapColor(MapColor.GOLD).strength(strength, resistance).sound(SoundType.METAL).noOcclusion().lightLevel(s -> l)
            .pushReaction(PushReaction.IMMOVEABLE);
    }

    private static Rarity rarity(String name) {
        try {
            return Rarity.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Rarity.RARE;
        }
    }

    /** Reads a relic's declaration from the owning mod's jar; a missing or bad file stops the game, naming it. */
    static RelicDef read(Class<?> owner, String modid, String name) {
        String path = "data/" + modid + "/" + PATH + "/" + name + ".json";
        try (InputStream in = open(owner, path)) {
            if (in == null) {
                throw new IllegalStateException("relic " + modid + ":" + name + ": no " + path + " in the mod");
            }
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonObject json = JsonParser.parseReader(r).getAsJsonObject();
                return RelicDef.parse(json, name);
            }
        } catch (java.io.IOException | RuntimeException e) {
            throw new IllegalStateException("relic " + modid + ":" + name + ": cannot read " + path, e);
        }
    }

    private static @Nullable InputStream open(Class<?> owner, String path) {
        InputStream in = owner.getResourceAsStream("/" + path);
        if (in == null && owner.getClassLoader() != null) {
            in = owner.getClassLoader().getResourceAsStream(path);
        }
        if (in == null) {
            LOG.debug("relic file {} not found next to {}", path, owner.getName());
        }
        return in;
    }
}
