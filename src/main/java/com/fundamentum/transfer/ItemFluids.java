package com.fundamentum.transfer;

import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FNet;
import com.fundamentum.port.FStreamCodec;
import com.fundamentum.port.FTransfer;
import com.fundamentum.port.FVanilla;
import com.fundamentum.recipeview.RecipeViews;
import com.fundamentum.recipeview.ViewCategory;
import com.fundamentum.recipeview.ViewFluid;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Item-to-fluid declarations: "this item holds or melts into this much fluid, leaving this item behind". They come
 * from {@code data/<ns>/fundamentum/item_fluid/*.json} and from {@link #add} in code, are synced to clients and shown
 * in recipe viewers as the {@code fundamentum:item_to_fluid} category. Fundamentum does not act on them by itself; a
 * machine or tank block asks {@link #find} and does the filling. File format:
 * <pre>{"item": "minecraft:water_bucket", "fluid": "minecraft:water", "amount": 1000, "remainder": "minecraft:bucket"}</pre>
 * {@code remainder} is optional. A data file with the same id as a code entry replaces it.
 */
public final class ItemFluids {
    private ItemFluids() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");

    /** One declaration. {@code remainder} is {@link FluidTank.Contents#EMPTY_ID} when nothing is left behind. */
    public record ItemFluid(FId id, FId item, FId fluid, int amount, FId remainder) {
        public static final FStreamCodec<ItemFluid> CODEC = FStreamCodec.composite(
            FStreamCodec.ID, ItemFluid::id, FStreamCodec.ID, ItemFluid::item, FStreamCodec.ID, ItemFluid::fluid,
            FStreamCodec.INT, ItemFluid::amount, FStreamCodec.ID, ItemFluid::remainder, ItemFluid::new);

        public Fluid toFluid() {
            return FTransfer.fluid(fluid);
        }

        public ItemStack itemStack() {
            Item i = FVanilla.item(item);
            return i == null || i == Items.AIR ? ItemStack.EMPTY : new ItemStack(i);
        }

        /** What is left after taking the fluid out, empty when nothing. */
        public ItemStack remainderStack() {
            if (remainder.equals(FluidTank.Contents.EMPTY_ID)) return ItemStack.EMPTY;
            Item i = FVanilla.item(remainder);
            return i == null || i == Items.AIR ? ItemStack.EMPTY : new ItemStack(i);
        }

        /** True when the item and fluid both exist in this game. */
        public boolean valid() {
            return !itemStack().isEmpty() && toFluid() != Fluids.EMPTY && amount > 0;
        }
    }

    /** The full list for clients. */
    public record Sync(List<ItemFluid> entries) {
        public static final FStreamCodec<Sync> CODEC = ItemFluid.CODEC.list().map(Sync::new, Sync::entries);
    }

    public static final FId SYNC = FId.of("fundamentum", "item_fluids");
    public static final String CATEGORY = "fundamentum:item_to_fluid";

    private static final Map<FId, ItemFluid> CODE = new TreeMap<>();
    private static volatile Map<FId, ItemFluid> data = Map.of();
    private static volatile List<ItemFluid> merged = List.of();
    private static volatile List<ItemFluid> client = List.of();
    private static MinecraftServer server;

    static void init() {
        FNet.register(SYNC, Sync.class, Sync.CODEC, FNet.Side.TO_CLIENT, (msg, ctx) -> {
            client = List.copyOf(msg.entries());
            RecipeViews.refresh();
        });
        FLife.jsonData(FId.of("fundamentum", "item_fluid"), "fundamentum/item_fluid", ItemFluids::load);
        FLife.serverAboutToStart(s -> server = s);
        FLife.serverStopped(s -> server = null);
        FLife.playerLoggedIn(p -> FNet.toPlayer(p, new Sync(all())));

        ViewCategory<ItemFluid> c = RecipeViews.category(CATEGORY);
        c.title(Component.translatableWithFallback("fundamentum.recipeview.item_to_fluid", "Item to Fluid"))
            .icon(() -> new ItemStack(Items.BUCKET))
            .size(110, 26)
            .arrow(26, 5, 0);
        int in = c.input(4, 5);
        int out = c.fluidOutput(58, 5);
        int rest = c.output(86, 5);
        c.recipes(ItemFluids::clientList)
            .key(r -> r.id().toString().replace(':', '/'))
            .fill((r, v) -> {
                v.item(in, r.itemStack());
                Fluid f = r.toFluid();
                if (f != Fluids.EMPTY && r.amount() > 0) v.fluid(out, new ViewFluid(f, r.amount()));
                ItemStack rem = r.remainderStack();
                if (!rem.isEmpty()) v.item(rest, rem);
            });
    }

    /** Declares one in code. Call while the mod loads. */
    public static synchronized void add(FId id, FId item, FId fluid, int amount, FId remainder) {
        if (amount <= 0) throw new IllegalArgumentException("item fluid " + id + " needs an amount above 0");
        CODE.put(id, new ItemFluid(id, item, fluid, amount, remainder == null ? FluidTank.Contents.EMPTY_ID : remainder));
        rebuild();
    }

    /** Every declaration known on this side (server: code and data; client: what the server sent). */
    public static List<ItemFluid> all() {
        return server != null || client.isEmpty() ? merged : client;
    }

    /** What recipe viewers show: the server's list once it arrived, else this side's own. */
    public static Collection<ItemFluid> clientList() {
        List<ItemFluid> src = client.isEmpty() ? merged : client;
        List<ItemFluid> out = new ArrayList<>();
        for (ItemFluid f : src) if (f.valid()) out.add(f);
        return out;
    }

    /** The declaration for an item, or null. */
    public static ItemFluid find(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        FId id = FVanilla.idOf(stack.getItem());
        for (ItemFluid f : all()) if (f.item().equals(id)) return f;
        return null;
    }

    /** The declarations that give a fluid. */
    public static List<ItemFluid> forFluid(Fluid fluid) {
        List<ItemFluid> out = new ArrayList<>();
        for (ItemFluid f : all()) if (f.toFluid() == fluid) out.add(f);
        return out;
    }

    private static synchronized void rebuild() {
        Map<FId, ItemFluid> m = new TreeMap<>(CODE);
        m.putAll(data);
        merged = Collections.unmodifiableList(new ArrayList<>(m.values()));
    }

    private static void load(Map<FId, List<JsonElement>> files) {
        Map<FId, ItemFluid> out = new TreeMap<>();
        files.forEach((id, copies) -> {
            try {
                JsonObject o = copies.get(copies.size() - 1).getAsJsonObject();
                FId item = FId.of(o.get("item").getAsString());
                FId fluid = FId.of(o.get("fluid").getAsString());
                int amount = o.has("amount") ? o.get("amount").getAsInt() : 1000;
                FId rem = o.has("remainder") ? FId.of(o.get("remainder").getAsString()) : FluidTank.Contents.EMPTY_ID;
                if (amount <= 0) throw new IllegalArgumentException("amount must be above 0");
                out.put(id, new ItemFluid(id, item, fluid, amount, rem));
            } catch (RuntimeException e) {
                LOG.error("Item fluid {} could not be read: {}", id, e.toString());
            }
        });
        data = Collections.unmodifiableMap(out);
        rebuild();
        LOG.info("Loaded {} item fluid declarations", out.size());
        MinecraftServer s = server;
        if (s != null) {
            Sync msg = new Sync(all());
            for (ServerPlayer p : List.copyOf(s.getPlayerList().getPlayers())) FNet.toPlayer(p, msg);
        }
    }
}
