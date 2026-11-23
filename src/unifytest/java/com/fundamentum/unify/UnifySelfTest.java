package com.fundamentum.unify;

import com.fundamentum.event.Events;
import com.fundamentum.event.FEvents;
import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FNet;
import com.fundamentum.port.FReg;
import com.fundamentum.port.FVanilla;
import com.fundamentum.recipeview.RecipeViews;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The unification test, only in {@code gradlew runServer -Punifytest} (world under run-unifytest). The same file on
 * every version; version calls go through {@link UnifyHooks}. Test items join the {@code c:} ingot and ore tags, and
 * the test data keeps one material's item, turns another off and adds a fix file. Checks the map, the recipes as loaded,
 * recipe JSON rewriting with fixes, dropped items, hoppers, item handlers, inventories, viewer hiding, ore dedupe, the
 * {@code material_unified} event and the sync codec. Writes unifytest-result.txt and stops the server. Log lines start
 * with UNIFYTEST.
 */
public final class UnifySelfTest {
    private UnifySelfTest() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    private static final List<String> LINES = new ArrayList<>();
    private static final List<Events.MaterialUnified> EVENTS = new ArrayList<>();
    private static boolean failed;
    private static int ticks;

    static FReg.Ref<Item> COPPER_INGOT, GOLD_INGOT, IRON_INGOT, COPPER_ORE_ITEM, DEEP_ORE_ITEM;
    static FReg.Ref<Block> COPPER_ORE, DEEP_ORE;

    public static void init() {
        if (System.getProperty("fundamentum.unifytest") == null) return;
        COPPER_INGOT = FReg.item(id("copper_ingot"), new Item.Properties());
        GOLD_INGOT = FReg.item(id("gold_ingot"), new Item.Properties());
        IRON_INGOT = FReg.item(id("iron_ingot"), new Item.Properties());
        COPPER_ORE = FReg.block(id("copper_ore"), Block::new, BlockBehaviour.Properties.of());
        DEEP_ORE = FReg.block(id("deepslate_copper_ore"), Block::new, BlockBehaviour.Properties.of());
        COPPER_ORE_ITEM = FReg.blockItem(COPPER_ORE, new Item.Properties());
        DEEP_ORE_ITEM = FReg.blockItem(DEEP_ORE, new Item.Properties());
        FEvents.listen(Events.MaterialUnified.class, (Events.MaterialUnified e) -> EVENTS.add(e));
        FLife.serverTick(UnifySelfTest::tick);
    }

    private static FId id(String s) {
        return FId.of("unifytest", s);
    }

    private static FId mc(String s) {
        return FId.of("minecraft", s);
    }

    private static void check(boolean ok, String what) {
        String line = (ok ? "PASS " : "FAIL ") + what;
        if (!ok) failed = true;
        LINES.add(line);
        if (ok) LOG.info("UNIFYTEST {}", line);
        else LOG.error("UNIFYTEST {}", line);
    }

    private static void tick(MinecraftServer server) {
        if (++ticks != 40) return;
        try {
            run(server);
        } catch (Throwable t) {
            LOG.error("UNIFYTEST threw", t);
            check(false, "threw " + t);
        }
        LINES.add(failed ? "RESULT FAIL" : "RESULT PASS");
        LOG.info("UNIFYTEST RESULT {}", failed ? "FAIL" : "PASS");
        try {
            Files.write(Path.of("unifytest-result.txt"), LINES);
        } catch (IOException e) {
            LOG.error("UNIFYTEST could not write the result file", e);
        }
        server.halt(false);
    }

    private static boolean is(ItemStack stack, Item item, int count) {
        return stack.getItem() == item && stack.getCount() == count;
    }

    private static void run(MinecraftServer server) {
        UnifyMap map = Unify.map();
        ServerLevel level = server.overworld();

        // the map
        check(!map.isEmpty(), "the map is built on load (" + map.replacements().size() + " items replaced)");
        check(mc("copper_ingot").equals(map.target(id("copper_ingot"))), "a second copper ingot points at the first mod in the order");
        check(map.target(mc("copper_ingot")) == null, "the kept item is never replaced");
        check(id("gold_ingot").equals(map.target(mc("gold_ingot"))), "a material's keep wins over the mod order");
        check(map.target(id("iron_ingot")) == null, "a material with unify off is left alone");
        check(mc("deepslate_copper_ore").equals(map.target(id("deepslate_copper_ore"))), "a deepslate ore finds the deepslate kept ore");
        check(mc("copper_ore").equals(map.target(id("copper_ore"))), "a stone ore finds the stone kept ore");
        check(FId.of("c", "ingots/copper").equals(map.tagOf(id("copper_ingot"))), "a replaced item knows its tag");
        check(map.oreRemoved(id("copper_ore")) && !map.oreRemoved(id("copper_ingot")), "only ores are marked for worldgen removal");

        // asking
        check(Unify.preferred(id("copper_ingot")).equals(mc("copper_ingot")) && Unify.preferred(mc("dirt")).equals(mc("dirt")), "preferred by id");
        check(Unify.preferred(COPPER_INGOT.get()) == Items.COPPER_INGOT, "preferred by item");
        ItemStack five = new ItemStack(COPPER_INGOT.get(), 5);
        check(is(Unify.preferred(five), Items.COPPER_INGOT, 5), "preferred by stack keeps the count");
        ItemStack dirt = new ItemStack(Items.DIRT);
        check(Unify.preferred(dirt) == dirt, "a stack that is not replaced comes back as is");
        check(Unify.replaced(five) && !Unify.replaced(new ItemStack(Items.COPPER_INGOT)), "replaced");

        // recipes as loaded
        check(mc("copper_ingot").equals(UnifyHooks.shapelessResult(server, id("make_copper"))), "a loaded recipe now gives the kept item");
        check(id("copper_ingot").equals(UnifyHooks.shapelessResult(server, id("keep_copper"))), "a recipe in a fix file's skip list is left alone");

        // recipe JSON with fixes
        JsonObject plain = JsonParser.parseString("{\"type\":\"x:y\",\"result\":{\"id\":\"unifytest:copper_ingot\",\"count\":2}}").getAsJsonObject();
        check(map.rewriteRecipe(id("t1"), plain) && plain.getAsJsonObject("result").get("id").getAsString().equals("minecraft:copper_ingot")
            && plain.getAsJsonObject("result").get("count").getAsInt() == 2, "a result object is rewritten and keeps its count");
        JsonObject str = JsonParser.parseString("{\"type\":\"x:y\",\"result\":\"unifytest:copper_ingot\",\"results\":[{\"item\":\"unifytest:copper_ingot\"},\"minecraft:dirt\"]}").getAsJsonObject();
        check(map.rewriteRecipe(id("t2"), str) && str.get("result").getAsString().equals("minecraft:copper_ingot")
            && str.getAsJsonArray("results").get(0).getAsJsonObject().get("item").getAsString().equals("minecraft:copper_ingot")
            && str.getAsJsonArray("results").get(1).getAsString().equals("minecraft:dirt"), "string results and result lists are rewritten");
        JsonObject crusher = JsonParser.parseString("{\"type\":\"unifytest:crusher\",\"outputs\":[{\"stack\":{\"id\":\"unifytest:copper_ingot\"},\"chance\":0.5}]}").getAsJsonObject();
        check(map.rewriteRecipe(id("t3"), crusher) && crusher.getAsJsonArray("outputs").get(0).getAsJsonObject().getAsJsonObject("stack").get("id").getAsString()
            .equals("minecraft:copper_ingot"), "a fix file's path reaches a nested output");
        JsonObject press = JsonParser.parseString("{\"type\":\"unifytest:press\",\"result\":\"unifytest:copper_ingot\",\"product\":\"unifytest:copper_ingot\"}").getAsJsonObject();
        check(map.rewriteRecipe(id("t4"), press) && press.get("result").getAsString().equals("unifytest:copper_ingot")
            && press.get("product").getAsString().equals("minecraft:copper_ingot"), "replace_defaults uses only the fix file's paths");
        JsonObject skipped = JsonParser.parseString("{\"type\":\"unifytest:no_touch\",\"result\":\"unifytest:copper_ingot\"}").getAsJsonObject();
        check(!map.rewriteRecipe(id("t5"), skipped), "a skipped recipe type is left alone");
        JsonObject cond = JsonParser.parseString("{\"type\":\"forge:conditional\",\"recipes\":[{\"conditions\":[],\"recipe\":{\"type\":\"x:y\",\"result\":{\"item\":\"unifytest:copper_ingot\"}}}]}").getAsJsonObject();
        check(map.rewriteRecipe(id("t6"), cond) && cond.getAsJsonArray("recipes").get(0).getAsJsonObject().getAsJsonObject("recipe")
            .getAsJsonObject("result").get("item").getAsString().equals("minecraft:copper_ingot"), "a Forge conditional recipe is rewritten inside");

        // run-time swaps
        EVENTS.clear();
        ItemEntity drop = new ItemEntity(level, 0.5, 200, 0.5, new ItemStack(COPPER_INGOT.get(), 3));
        level.addFreshEntity(drop);
        check(is(drop.getItem(), Items.COPPER_INGOT, 3), "a dropped item is swapped as it appears");
        drop.discard();
        check(!EVENTS.isEmpty() && EVENTS.get(0).tag().equals(FId.of("c", "ingots/copper")) && EVENTS.get(0).from().equals(id("copper_ingot"))
            && EVENTS.get(0).to().equals(mc("copper_ingot")), "a swap posts material_unified with the tag");
        SimpleContainer box = new SimpleContainer(3);
        ItemStack left = HopperBlockEntity.addItem(null, box, new ItemStack(COPPER_INGOT.get(), 4), null);
        check(left.isEmpty() && is(box.getItem(0), Items.COPPER_INGOT, 4), "a hopper insert is swapped");
        check(is(UnifyHooks.handlerInsert(new ItemStack(COPPER_INGOT.get(), 2)), Items.COPPER_INGOT, 2), "an item handler insert is swapped");
        SimpleContainer chest = new SimpleContainer(2);
        chest.setItem(0, new ItemStack(Items.GOLD_INGOT, 7));
        chest.setItem(1, new ItemStack(IRON_INGOT.get()));
        check(Unify.convertAll(chest) == 1 && is(chest.getItem(0), GOLD_INGOT.get(), 7) && chest.getItem(1).getItem() == IRON_INGOT.get(), "convertAll swaps only replaced stacks");
        ServerPlayer player = FVanilla.testPlayer(level, "unifytest");
        player.getInventory().setItem(0, new ItemStack(COPPER_ORE_ITEM.get(), 2));
        check(Unify.convertInventory(player) == 1 && is(player.getInventory().getItem(0), Items.COPPER_ORE, 2), "an inventory is swapped");
        check(!Unify.PICKUP.get() && Unify.ITEM_ENTITIES.get() && Unify.CONTAINERS.get(), "pickup is off and the other swaps on by default");

        // viewers
        check(RecipeViews.hidden(new ItemStack(COPPER_INGOT.get())) && !RecipeViews.hidden(new ItemStack(Items.COPPER_INGOT)), "replaced items are hidden from recipe viewers");

        // ores
        check(UnifyHooks.oreModifierLoaded(server), "the ore biome modifier is loaded");
        check(UnifyOreModifier.duplicate(ore(DEEP_ORE.get(), COPPER_ORE.get())), "an ore feature of replaced ores only is a duplicate");
        check(!UnifyOreModifier.duplicate(ore(COPPER_ORE.get(), Blocks.COPPER_ORE)), "an ore feature that also places a kept ore stays");

        // sync
        Unify.Sync sync = new Unify.Sync(map.toStrings());
        check(FNet.roundTrip(sync, server.registryAccess()).equals(sync), "the sync survives the network codec");
        UnifyMap client = UnifyMap.fromStrings(sync.map());
        check(client.replacements().equals(map.replacements()) && client.oreRemoved(id("copper_ore"))
            && FId.of("c", "ingots/copper").equals(client.tagOf(id("copper_ingot"))), "the client copy matches");
    }

    private static PlacedFeature ore(Block a, Block b) {
        OreConfiguration cfg = new OreConfiguration(List.of(
            OreConfiguration.target(new BlockMatchTest(Blocks.STONE), a.defaultBlockState()),
            OreConfiguration.target(new BlockMatchTest(Blocks.DEEPSLATE), b.defaultBlockState())), 9);
        return new PlacedFeature(Holder.direct(new ConfiguredFeature<>(Feature.ORE, cfg)), List.of());
    }
}
