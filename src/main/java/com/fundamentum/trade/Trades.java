package com.fundamentum.trade;

import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.village.VillagerTradesEvent;
import net.minecraftforge.event.village.WandererTradesEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Villager and wandering trader trades from data/<namespace>/fundamentum/trades/<name>.json. Entries are keyed by file, so a
 * pack overrides a shipped trade by putting a file at the same path (and removes it with {@code "disabled": true}). Trades
 * are built when the server starts.
 */
public final class Trades {
    private static final Logger LOG = LoggerFactory.getLogger("fundamentum/trades");
    private static final ResourceLocation WANDERER = new ResourceLocation("wandering_trader");

    private record Trade(ResourceLocation profession, int level, boolean rare, Item wants, int wantsCount, Item wantsB, int wantsBCount,
                         Item gives, int givesCount, int maxUses, int xp, float priceMultiplier) {
        VillagerTrades.ItemListing listing() {
            return (trader, random) -> new MerchantOffer(new ItemStack(wants, wantsCount),
                wantsB == null ? ItemStack.EMPTY : new ItemStack(wantsB, wantsBCount),
                new ItemStack(gives, givesCount), maxUses, xp, priceMultiplier);
        }
    }

    private static volatile List<Trade> trades = List.of();

    public static final DataTable<JsonObject> TABLE = DataTables.registerJson(new ResourceLocation("fundamentum", "trades"),
        "fundamentum/trades").onLoad(t -> trades = read(t.rawEntries()));

    private Trades() {}

    public static void init() {
        MinecraftForge.EVENT_BUS.addListener(Trades::villager);
        MinecraftForge.EVENT_BUS.addListener(Trades::wanderer);
    }

    private static void villager(VillagerTradesEvent event) {
        ResourceLocation profession = BuiltInRegistries.VILLAGER_PROFESSION.getKey(event.getType());
        for (Trade t : trades) {
            if (t.profession().equals(profession)) {
                event.getTrades().computeIfAbsent(t.level(), k -> new ArrayList<>()).add(t.listing());
            }
        }
    }

    private static void wanderer(WandererTradesEvent event) {
        for (Trade t : trades) {
            if (t.profession().equals(WANDERER)) {
                (t.rare() ? event.getRareTrades() : event.getGenericTrades()).add(t.listing());
            }
        }
    }

    private static List<Trade> read(Map<ResourceLocation, JsonObject> raw) {
        List<Trade> out = new ArrayList<>();
        for (var e : raw.entrySet()) {
            try {
                if (!GsonHelper.getAsBoolean(e.getValue(), "disabled", false)) {
                    out.add(parse(e.getValue()));
                }
            } catch (RuntimeException ex) {
                LOG.error("trade {} skipped: {}", e.getKey(), ex.getMessage());
            }
        }
        return List.copyOf(out);
    }

    private static Trade parse(JsonObject json) {
        ResourceLocation profession = new ResourceLocation(GsonHelper.getAsString(json, "profession"));
        if (!profession.equals(WANDERER) && !BuiltInRegistries.VILLAGER_PROFESSION.containsKey(profession)) {
            throw new IllegalArgumentException("unknown profession " + profession);
        }
        int level = GsonHelper.getAsInt(json, "level", 1);
        if (level < 1 || level > 5) {
            throw new IllegalArgumentException("level must be 1 to 5");
        }
        JsonObject wants = GsonHelper.getAsJsonObject(json, "wants");
        JsonObject wantsB = json.has("wants_b") ? GsonHelper.getAsJsonObject(json, "wants_b") : null;
        JsonObject gives = GsonHelper.getAsJsonObject(json, "gives");
        return new Trade(profession, level, GsonHelper.getAsBoolean(json, "rare", false),
            item(wants), GsonHelper.getAsInt(wants, "count", 1),
            wantsB == null ? null : item(wantsB), wantsB == null ? 0 : GsonHelper.getAsInt(wantsB, "count", 1),
            item(gives), GsonHelper.getAsInt(gives, "count", 1),
            GsonHelper.getAsInt(json, "max_uses", 12), GsonHelper.getAsInt(json, "xp", 1), GsonHelper.getAsFloat(json, "price_multiplier", 0.05F));
    }

    private static Item item(JsonObject json) {
        ResourceLocation id = new ResourceLocation(GsonHelper.getAsString(json, "item"));
        if (!BuiltInRegistries.ITEM.containsKey(id)) {
            throw new IllegalArgumentException("unknown item " + id);
        }
        return BuiltInRegistries.ITEM.get(id);
    }
}
