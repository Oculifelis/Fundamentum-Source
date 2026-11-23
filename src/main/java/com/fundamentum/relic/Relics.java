package com.fundamentum.relic;

import com.fundamentum.Fundamentum;
import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.fundamentum.port.FId;
import com.fundamentum.port.FReg;
import com.fundamentum.port.FStackData;
import com.fundamentum.port.FStreamCodec;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.context.ParsedArgument;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
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
 * Boss relics: a Terraria-style trophy, the boss's gold likeness floating, bobbing and turning slowly over a base (a
 * redstone signal stops the turn and faces it forward). A relic is only data: a file
 * {@code data/<ns>/fundamentum/relics/<name>.json} ({@link RelicDef}) makes relic {@code <ns>:<name>}, with its creative
 * entry, name, boss drop, recipe viewer page and {@code /fundamentum relic give}. Every relic is the one item
 * {@code fundamentum:relic} with the relic's id as data ({@link #RELIC}), placed on a shared base block
 * ({@link RelicBases}) that keeps the id in its block entity. The bases never reach players on their own: they have no
 * creative entry, recipe, loot table or tag, recipe viewers hide them, and a blank relic cannot be given.
 * See docs/RELICS.md.
 */
public final class Relics {
    private static final Logger LOG = LoggerFactory.getLogger("fundamentum");
    public static final String PATH = "fundamentum/relics";
    /** Every relic file, keyed by relic id; a datapack file of the same id is merged over the mod's. */
    public static final DataTable<RelicDef> TABLE = DataTables.register(Identifier.fromNamespaceAndPath(Fundamentum.MODID, "relics"), PATH,
        json -> RelicDef.parse(json, "relic")).synced();

    /** Which relic a {@code fundamentum:relic} stack is. */
    public static final FStackData<FId> RELIC = FStackData.of(FId.of(Fundamentum.MODID, "relic"), FId.CODEC, FStreamCodec.ID);

    /** Fundamentum's own base, {@code fundamentum:relic_stand}, sizes 1 to 5. */
    public static final RelicBases.Base STAND = RelicBases.registerDefault();

    /** The relic item. */
    public static final FReg.Ref<RelicItem> ITEM = FReg.item(FId.of(Fundamentum.MODID, "relic"),
        p -> new RelicItem(STAND.anchor(1), p), new Item.Properties().fireResistant().stacksTo(16).rarity(Rarity.RARE));

    /** The block entity of every base's anchors, {@code fundamentum:relic}. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static final FReg.Ref<BlockEntityType<RelicBlockEntity>> ENTITY = (FReg.Ref) FReg.<BlockEntityType<?>>other(Registries.BLOCK_ENTITY_TYPE, FId.of(Fundamentum.MODID, "relic"),
        () -> new BlockEntityType<>(RelicBlockEntity::new, anchors()));

    /** The creative tab: one entry per relic file. */
    public static final FReg.Ref<CreativeModeTab> TAB = FReg.other(Registries.CREATIVE_MODE_TAB, FId.of(Fundamentum.MODID, "relics"),
        () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.fundamentum.relics"))
            .icon(() -> TABLE.entries().isEmpty() ? new ItemStack(Items.GOLD_BLOCK) : stack(FId.from(TABLE.entries().keySet().iterator().next())))
            .displayItems((params, out) -> {
                for (Identifier id : TABLE.entries().keySet()) {
                    out.accept(stack(FId.from(id)));
                }
            })
            .build());

    private static final List<RelicSet> SETS = new ArrayList<>();
    private static final Set<String> MISSING = Collections.synchronizedSet(new HashSet<>());

    private Relics() {}

    public static void init(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(Relics::drops);
        NeoForge.EVENT_BUS.addListener(Relics::death);
        NeoForge.EVENT_BUS.addListener(Relics::commands);
        NeoForge.EVENT_BUS.addListener(Relics::command);
    }

    private static Set<Block> anchors() {
        Set<Block> out = new HashSet<>();
        for (RelicBases.Base b : RelicBases.all()) {
            for (FReg.Ref<RelicBlock> r : b.anchorRefs()) {
                out.add(r.get());
            }
        }
        return out;
    }

    /** The relic with this id, or null (an unknown non-blank id is logged once). */
    public static @Nullable RelicDef def(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        FId f = FId.tryOf(id);
        RelicDef def = f == null ? null : TABLE.get(f.vanilla());
        if (def == null && MISSING.add(id)) {
            LOG.warn("no relic file for relic {} (a removed datapack or mod?); its stand stays, plain, and drops nothing", id);
        }
        return def;
    }

    /** A stack of relic {@code id}, with that relic's rarity and stack size. */
    public static ItemStack stack(FId id) {
        ItemStack s = new ItemStack(ITEM.get());
        RELIC.set(s, id);
        RelicDef def = TABLE.get(id.vanilla());
        if (def != null) {
            s.set(DataComponents.RARITY, rarity(def.rarity));
            s.set(DataComponents.MAX_STACK_SIZE, Math.max(1, Math.min(99, def.stack)));
        }
        return s;
    }

    /** The relic a placed stand holds, or empty when its relic is unknown. */
    public static ItemStack stackOf(RelicBlockEntity be) {
        FId id = FId.tryOf(be.relicId());
        return id != null && def(id.toString()) != null ? stack(id) : ItemStack.EMPTY;
    }

    /**
     * A relic's name: {@code relic.<ns>.<path>} when a lang file has it, else {@code block.<ns>.<path>} (relics that used
     * to be blocks), else the file's {@code name}, else the id's path in words.
     */
    public static Component name(FId id) {
        String tail = id.namespace() + "." + id.path().replace('/', '.');
        Language lang = Language.getInstance();
        if (lang.has("relic." + tail)) {
            return Component.translatable("relic." + tail);
        }
        if (lang.has("block." + tail)) {
            return Component.translatable("block." + tail);
        }
        RelicDef def = TABLE.get(id.vanilla());
        String plain = def != null && def.name != null && !def.name.isEmpty() ? def.name : words(id.path());
        return Component.translatableWithFallback("relic." + tail, plain);
    }

    private static String words(String path) {
        String last = path.substring(path.lastIndexOf('/') + 1);
        StringBuilder b = new StringBuilder();
        for (String w : last.split("_")) {
            if (!w.isEmpty()) {
                b.append(b.length() == 0 ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
        }
        return b.toString();
    }

    /** Hands relic {@code relic} to each of {@code players} (into the inventory, or dropped at their feet when it is full). */
    public static void hand(Identifier relic, List<ServerPlayer> players) {
        if (TABLE.get(relic) == null) {
            return;
        }
        for (ServerPlayer p : players) {
            give(p, stack(FId.from(relic)));
        }
    }

    /** The relic item, when {@code relic} names a known relic. */
    @Deprecated
    public static @Nullable Item item(Identifier relic) {
        return TABLE.get(relic) != null ? ITEM.get() : null;
    }

    private static void give(ServerPlayer p, ItemStack stack) {
        if (!p.getInventory().add(stack)) {
            p.drop(stack, false);
        }
    }

    /** The relics a boss of this type gives, with how. */
    private static List<FId> relicsOf(Entity boss, RelicDef.Drop how) {
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(boss.getType()).toString();
        List<FId> out = new ArrayList<>();
        for (Map.Entry<Identifier, RelicDef> e : TABLE.entries().entrySet()) {
            if (e.getValue().drop == how && type.equals(e.getValue().boss)) {
                out.add(FId.from(e.getKey()));
            }
        }
        return out;
    }

    /** "drops": the relic falls with the boss's drops. */
    private static void drops(LivingDropsEvent event) {
        Entity boss = event.getEntity();
        for (FId id : relicsOf(boss, RelicDef.Drop.DROPS)) {
            event.getDrops().add(new ItemEntity(boss.level(), boss.getX(), boss.getY(), boss.getZ(), stack(id)));
        }
    }

    /** "killer": the relic goes straight to the player who landed the killing blow (or drops where the boss died). */
    private static void death(LivingDeathEvent event) {
        Entity boss = event.getEntity();
        if (boss.level().isClientSide()) {
            return;
        }
        for (FId id : relicsOf(boss, RelicDef.Drop.KILLER)) {
            if (event.getSource().getEntity() instanceof ServerPlayer player) {
                give(player, stack(id));
            } else {
                boss.spawnAtLocation((ServerLevel) boss.level(), stack(id));
            }
        }
    }

    static Rarity rarity(@Nullable String name) {
        try {
            return name == null ? Rarity.RARE : Rarity.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Rarity.RARE;
        }
    }

    // ---- commands

    /** {@code /fundamentum relic give <id> [targets]}. */
    private static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("fundamentum")
            .then(Commands.literal("relic")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("give")
                    .then(Commands.argument("id", IdentifierArgument.id())
                        .suggests((c, b) -> SharedSuggestionProvider.suggestResource(TABLE.entries().keySet(), b))
                        .executes(c -> give(c, List.of(c.getSource().getPlayerOrException())))
                        .then(Commands.argument("targets", EntityArgument.players())
                            .executes(c -> give(c, EntityArgument.getPlayers(c, "targets"))))))));
    }

    private static int give(CommandContext<CommandSourceStack> c, Collection<ServerPlayer> players) {
        Identifier id = IdentifierArgument.getId(c, "id");
        if (TABLE.get(id) == null) {
            c.getSource().sendFailure(Component.translatable("commands.fundamentum.relic.unknown", id.toString()));
            return 0;
        }
        for (ServerPlayer p : players) {
            give(p, stack(FId.from(id)));
        }
        Component name = name(FId.from(id));
        c.getSource().sendSuccess(() -> Component.translatable("commands.fundamentum.relic.give", name, players.size()), true);
        return players.size();
    }

    /** A {@code /give} of a blank relic is refused with a message. */
    private static void command(CommandEvent event) {
        if (blankGive(event.getParseResults())) {
            event.setCanceled(true);
            event.getParseResults().getContext().getSource().sendFailure(Component.translatable("commands.fundamentum.relic.blank"));
        }
    }

    /** Whether a parsed command is {@code /give} (directly or after {@code execute ... run}) of a relic with no relic data. */
    public static boolean blankGive(ParseResults<CommandSourceStack> parse) {
        CommandContextBuilder<CommandSourceStack> ctx = parse.getContext().getLastChild();
        if (ctx.getNodes().isEmpty() || !"give".equals(ctx.getNodes().get(0).getNode().getName())) {
            return false;
        }
        ParsedArgument<CommandSourceStack, ?> arg = ctx.getArguments().get("item");
        if (arg == null || !(arg.getResult() instanceof ItemInput input) || input.item().value() != ITEM.get()) {
            return false;
        }
        try {
            return !RELIC.has(input.createItemStack(1));
        } catch (Exception e) {
            return true;
        }
    }

    // ---- the old, per-mod registration

    /**
     * Before relics were data, each mod registered its relics as its own blocks and items. This still reads and checks
     * each named file from the mod's jar, but registers nothing: relic {@code <modid>:<name>} comes from the file alone,
     * and the returned set points at the shared blocks and item. Placed relics saved under the old block ids need the
     * remap in docs/RELICS.md.
     *
     * @deprecated relic files need no registration; give relics with {@link #stack}.
     */
    @Deprecated
    public static RelicSet register(Class<?> owner, String modid, DeferredRegister.Blocks blocks, DeferredRegister.Items items,
                                    @Nullable DeferredRegister<BlockEntityType<?>> blockEntities, String entityName, String... names) {
        RelicSet set = new RelicSet(modid);
        Identifier stand = FId.of(RelicBases.DEFAULT).vanilla();
        for (String name : names) {
            RelicDef def = read(owner, modid, name);
            FId base = FId.tryOf(def.base.id);
            if (base == null || RelicBases.get(base) == null || !RelicBases.get(base).sizes().contains(def.span)) {
                base = FId.from(stand);
            }
            DeferredBlock<RelicBlock> block = DeferredBlock.createBlock(base.withSuffix("_" + def.span).vanilla());
            DeferredBlock<RelicPartBlock> part = def.span > 1 ? DeferredBlock.createBlock(base.withSuffix("_filler").vanilla()) : null;
            DeferredItem<BlockItem> item = DeferredItem.createItem(Identifier.fromNamespaceAndPath(Fundamentum.MODID, "relic"));
            set.add(name, def, block, item, part);
        }
        set.entity = DeferredHolder.create(Registries.BLOCK_ENTITY_TYPE, Identifier.fromNamespaceAndPath(Fundamentum.MODID, "relic"));
        SETS.add(set);
        return set;
    }

    /** @deprecated see {@link #register(Class, String, DeferredRegister.Blocks, DeferredRegister.Items, DeferredRegister, String, String...)}. */
    @Deprecated
    public static RelicSet register(Class<?> owner, String modid, DeferredRegister.Blocks blocks, DeferredRegister.Items items, String... names) {
        return register(owner, modid, blocks, items, null, "", names);
    }

    /** @deprecated every set made by the old {@link #register}. */
    @Deprecated
    public static List<RelicSet> sets() {
        return Collections.unmodifiableList(SETS);
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
        return in;
    }
}
