package com.fundamentum.recipeview;

import com.fundamentum.port.FLife;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The recipe viewer test, only in {@code gradlew runServer -Precipeviewtest} (world under run-recipeviewtest). Checks the
 * declaration registry (ids, slots, building, text, arrows, a filler that throws), hiding, subtypes, information pages,
 * refresh listeners, Fundamentum's own declarations, and that each viewer adapter this version builds is in the jar.
 * The viewers themselves are client mods, so their plugins are proven by compiling against them. Writes
 * recipeviewtest-result.txt and stops the server. Log lines start with RECIPEVIEWTEST.
 */
public final class RecipeViewSelfTest {
    private RecipeViewSelfTest() { }

    /** The adapter classes this version compiles (JEI, REI and EMI). */
    private static final List<String> ADAPTERS = List.of(
            "com/fundamentum/recipeview/jei/RecipeViewJei.class",
            "com/fundamentum/recipeview/jei/JeiViewCategory.class",
            "com/fundamentum/recipeview/rei/RecipeViewRei.class",
            "com/fundamentum/recipeview/rei/ReiViewCategory.class",
            "com/fundamentum/recipeview/rei/ReiViewDisplay.class",
            "com/fundamentum/recipeview/emi/RecipeViewEmi.class",
            "com/fundamentum/recipeview/emi/EmiViewRecipe.class");

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    private static final List<String> LINES = new ArrayList<>();
    private static boolean failed;
    private static int ticks;

    public static void init() {
        if (System.getProperty("fundamentum.recipeviewtest") == null) return;
        FLife.serverTick(RecipeViewSelfTest::tick);
    }

    private static void check(boolean ok, String what) {
        String line = (ok ? "PASS " : "FAIL ") + what;
        if (!ok) failed = true;
        LINES.add(line);
        if (ok) LOG.info("RECIPEVIEWTEST {}", line);
        else LOG.error("RECIPEVIEWTEST {}", line);
    }

    private static void tick(MinecraftServer server) {
        if (++ticks != 40) return;
        try {
            run();
        } catch (Throwable t) {
            LOG.error("RECIPEVIEWTEST threw", t);
            check(false, "threw " + t);
        }
        LINES.add(failed ? "RESULT FAIL" : "RESULT PASS");
        LOG.info("RECIPEVIEWTEST RESULT {}", failed ? "FAIL" : "PASS");
        try {
            Files.write(Path.of("recipeviewtest-result.txt"), LINES);
        } catch (IOException e) {
            LOG.error("RECIPEVIEWTEST could not write the result file", e);
        }
        server.halt(false);
    }

    private static boolean throwsAny(Runnable r) {
        try {
            r.run();
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private record Smelt(String name, ItemStack in, ItemStack out, boolean broken) {}

    private static void run() {
        // ids
        check(throwsAny(() -> RecipeViews.category("NoColon")), "an id without a namespace is refused");
        check(throwsAny(() -> RecipeViews.category("Bad:Upper")), "an upper-case id is refused");
        ViewCategory<Smelt> c = RecipeViews.category("recipeviewtest:smelt");
        check(throwsAny(() -> RecipeViews.category("recipeviewtest:smelt")), "a second category with the same id is refused");
        check(RecipeViews.find("recipeviewtest:smelt") == c, "find returns the declared category");
        check(c.namespace().equals("recipeviewtest") && c.path().equals("smelt"), "namespace and path split");

        // layout
        List<Smelt> recipes = new ArrayList<>();
        c.title(Component.literal("Smelt")).icon(() -> new ItemStack(Items.FURNACE)).size(120, 40).arrow(50, 10, 100);
        int in = c.input(10, 10);
        int water = c.fluidInput(30, 10);
        int out = c.output(90, 10);
        check(in == 0 && water == 1 && out == 2, "slot indices count up");
        check(throwsAny(() -> c.input(110, 10)), "a slot outside the area is refused");
        check(throwsAny(() -> c.size(4, 4)), "an area under 18 pixels is refused");
        check(c.slots().size() == 3 && c.slot(1).kind() == ViewSlot.Kind.FLUID, "slot list and kinds");
        check(c.arrow() != null && c.arrow().ticks() == 100, "arrow kept");
        check(c.icon().is(Items.FURNACE), "icon");
        c.catalysts(() -> List.of(new ItemStack(Items.FURNACE)));
        check(c.catalysts().size() == 1, "catalysts");

        // building
        recipes.add(new Smelt("iron", new ItemStack(Items.RAW_IRON), new ItemStack(Items.IRON_INGOT), false));
        recipes.add(new Smelt("broken", new ItemStack(Items.STONE), ItemStack.EMPTY, true));
        recipes.add(new Smelt("gold", new ItemStack(Items.RAW_GOLD, 2), new ItemStack(Items.GOLD_INGOT), false));
        c.recipes(() -> recipes).key(Smelt::name).fill((s, r) -> {
            if (s.broken()) throw new IllegalStateException("broken on purpose");
            r.items(in, List.of(s.in(), ItemStack.EMPTY)).fluid(water, new ViewFluid(Fluids.WATER, 250)).item(out, s.out());
            r.text(Component.literal("Takes " + s.name()), 30).text(Component.literal("left"), 4, 2);
        });
        List<ViewRecipe> built = c.build();
        check(built.size() == 2, "a filler that throws skips only that recipe (" + built.size() + " built)");
        ViewRecipe iron = built.isEmpty() ? null : built.get(0);
        check(iron != null && iron.id().equals("recipeviewtest:smelt/iron"), "recipe id is category/key");
        check(iron != null && iron.items(in).size() == 1, "empty stacks are dropped from a slot");
        check(iron != null && iron.items(in).get(0) != recipes.get(0).in(), "slot stacks are copies");
        check(iron != null && iron.fluids(water).size() == 1 && iron.fluids(water).get(0).amount() == 250, "fluid slot");
        check(iron != null && iron.stacks(ViewSlot.Role.OUTPUT).size() == 1 && iron.stacks(ViewSlot.Role.OUTPUT).get(0).is(Items.IRON_INGOT), "outputs by role");
        check(iron != null && iron.text().size() == 2 && iron.text().get(0).x() < 0 && iron.text().get(1).x() == 4, "centred and placed text");
        check(iron != null && iron.text().get(0).colour() == c.textColour(), "text takes the category colour");
        check(iron != null && throwsAny(() -> iron.item(water, new ItemStack(Items.STONE))), "an item in a fluid slot is refused");
        check(iron != null && throwsAny(() -> iron.item(9, new ItemStack(Items.STONE))), "an unknown slot is refused");
        check(throwsAny(() -> new ViewFluid(Fluids.WATER, 0)), "a fluid amount of 0 is refused");
        recipes.clear();
        check(c.build().isEmpty(), "build reads the recipe supplier each time");

        // hiding
        RecipeViews.hide(() -> Items.BEDROCK);
        RecipeViews.hide(s -> s.getCount() == 63);
        RecipeViews.hide(s -> { throw new IllegalStateException("bad rule"); });
        check(RecipeViews.hidden(new ItemStack(Items.BEDROCK)), "hide by item");
        check(RecipeViews.hidden(new ItemStack(Items.DIRT, 63)), "hide by rule");
        check(!RecipeViews.hidden(new ItemStack(Items.DIRT)), "other stacks stay shown, a throwing rule counts as no match");
        check(!RecipeViews.hidden(ItemStack.EMPTY), "an empty stack is never hidden");

        // subtypes
        RecipeViews.subtypes(() -> Items.PAPER, s -> s.getCount() > 1 ? "many" : "");
        check(RecipeViews.subtypeKey(new ItemStack(Items.PAPER, 3)).equals("many"), "subtype key");
        check(RecipeViews.subtypeKey(new ItemStack(Items.PAPER)).isEmpty(), "plain subtype key is empty");
        check(RecipeViews.subtypeKey(new ItemStack(Items.STONE)).isEmpty(), "items without a rule have no key");

        // information pages
        int before = RecipeViews.builtInfo().size();
        RecipeViews.info(() -> List.of(new ItemStack(Items.COMPASS)), () -> List.of(Component.literal("Points home")));
        RecipeViews.info(() -> List.of(new ItemStack(Items.CLOCK)), List::of);
        RecipeViews.info(() -> { throw new IllegalStateException("bad page"); }, List::of);
        RecipeViews.infoPages(() -> List.of(new RecipeViews.Info(() -> List.of(new ItemStack(Items.MAP)), () -> List.of(Component.literal("A map")))));
        List<Map.Entry<List<ItemStack>, List<Component>>> pages = RecipeViews.builtInfo();
        check(pages.size() == before + 2, "info pages: empty and broken ones dropped, late sources read (" + (pages.size() - before) + " new)");

        // refresh
        AtomicInteger heard = new AtomicInteger();
        RecipeViews.onRefresh(heard::incrementAndGet);
        RecipeViews.onRefresh(() -> { throw new IllegalStateException("bad listener"); });
        RecipeViews.onRefresh(heard::incrementAndGet);
        RecipeViews.refresh();
        check(heard.get() == 2, "refresh reaches every listener past one that throws");

        // Fundamentum's own declarations
        ViewCategory<?> portal = RecipeViews.find("fundamentum:portal_crafting");
        check(portal != null && portal.slots().size() == 2 && portal.catalysts().size() == 3, "portal crafting category declared");
        check(portal != null && portal.build() != null, "portal crafting builds without client data");

        // the adapters are in the jar
        ClassLoader cl = RecipeViewSelfTest.class.getClassLoader();
        for (String a : ADAPTERS) check(cl.getResource(a) != null, "adapter compiled: " + a);
    }
}
