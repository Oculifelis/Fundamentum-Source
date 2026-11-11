package com.fundamentum.config;

import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FNet;
import com.fundamentum.port.FVanilla;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The config test, only in {@code gradlew runServer -Pconfigtest} (world under run-configtest). The same file on every
 * version. Declares a config in code, reads one declared by the test datapack, and checks the JSON5 reader, datapack
 * defaults, the written file and its comments, file and per-world layers picked up by the 2-second watcher, ranges,
 * startup entries, client entries, writes and resets, sync messages, the operator check on edits, and the screen
 * model. Writes configtest-result.txt and stops the server. Log lines start with CONFIGTEST.
 */
public final class ConfigSelfTest {
    private ConfigSelfTest() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    private static final List<String> LINES = new ArrayList<>();
    private static final List<ConfigEvent.Changed> EVENTS = new CopyOnWriteArrayList<>();
    private static boolean failed;

    public enum Mode { A, B, C }

    static final FId MAIN_ID = FId.of("configtest", "main");
    static final FId EXTRA_ID = FId.of("configtest", "extra");
    static FConfig main;
    static ConfigValue<Boolean> flag;
    static ConfigValue<Integer> count;
    static ConfigValue<Double> ratio;
    static ConfigValue<String> name;
    static ConfigValue<Mode> mode;
    static ConfigValue<FId> target;
    static ConfigValue<List<FId>> targets;
    static ConfigValue<Integer> tint;
    static ConfigValue<Integer> view;
    static ConfigValue<Integer> slots;

    private record Step(String name, int waitAfter, Runnable action) { }

    private static final List<Step> STEPS = new ArrayList<>();
    private static MinecraftServer server;
    private static int stepIndex = -1;
    private static int waitUntil;
    private static int ticks;
    private static CompletableFuture<Void> reload;

    public static void init() {
        if (System.getProperty("fundamentum.configtest") == null) return;
        deleteTree(Path.of("config", "fundamentum", "configtest"));
        deleteTree(Path.of("world", "serverconfig"));
        main = FConfig.create(MAIN_ID);
        flag = main.bool("flag", true);
        count = main.integer("count", 5, 0, 10);
        ratio = main.decimal("ratio", 0.5, 0, 1);
        name = main.string("name", "hello");
        mode = main.enumValue("mode", Mode.class, Mode.B);
        target = main.id("target", FId.of("minecraft", "stone"));
        targets = main.idList("targets", List.of(FId.of("minecraft", "dirt")));
        tint = main.colour("tint", 0x33AA55);
        view = main.integer("view", 2, 0, 8).side(FConfig.Side.CLIENT).category("client");
        slots = main.integer("slots", 3, 1, 9).side(FConfig.Side.STARTUP).category("startup");
        ConfigEvent.listen(EVENTS::add);
        steps();
        FLife.serverStarted(s -> {
            server = s;
            stepIndex = 0;
        });
        FLife.serverTick(s -> tick());
    }

    private static void check(boolean ok, String what) {
        String line = (ok ? "PASS " : "FAIL ") + what;
        if (!ok) failed = true;
        LINES.add(line);
        if (ok) LOG.info("CONFIGTEST {}", line);
        else LOG.error("CONFIGTEST {}", line);
    }

    private static void step(String name, int waitAfter, Runnable action) {
        STEPS.add(new Step(name, waitAfter, action));
    }

    private static void tick() {
        ticks++;
        if (stepIndex < 0 || ticks < waitUntil) return;
        if (reload != null && !reload.isDone() && ticks < waitUntil + 600) return;
        if (stepIndex >= STEPS.size()) {
            finish();
            stepIndex = -1;
            return;
        }
        Step s = STEPS.get(stepIndex++);
        try {
            s.action.run();
        } catch (Throwable t) {
            LOG.error("CONFIGTEST step {} threw", s.name, t);
            check(false, s.name + " threw " + t);
        }
        waitUntil = ticks + s.waitAfter;
    }

    private static void finish() {
        LINES.add(failed ? "RESULT FAIL" : "RESULT PASS");
        LOG.info("CONFIGTEST RESULT {}", failed ? "FAIL" : "PASS");
        try {
            Files.write(Path.of("configtest-result.txt"), LINES);
        } catch (IOException e) {
            LOG.error("CONFIGTEST could not write the result file", e);
        }
        server.halt(false);
    }

    // ---- helpers ----

    private static Path global() {
        return main.globalFile();
    }

    private static Path world() {
        return main.worldFile();
    }

    private static void write(Path path, String text) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static void deleteTree(Path path) {
        if (!Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean changed(FId id, String key) {
        for (ConfigEvent.Changed e : EVENTS) {
            if (e.id().equals(id) && e.keys().contains(key)) return true;
        }
        return false;
    }

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }

    // ---- the steps ----

    private static void steps() {
        step("json5", 0, () -> {
            JsonObject o = Json5.parse("// a comment\n{\n  /* block */ a: 1, // trailing\n  'b': 'x // not a comment',\n  \"c\": [1, 2, ],\n}\n").getAsJsonObject();
            check(o.get("a").getAsInt() == 1 && o.get("b").getAsString().equals("x // not a comment") && o.getAsJsonArray("c").size() == 2,
                "json5: comments, unquoted keys, single quotes and trailing commas read");
            boolean threw = false;
            try {
                Json5.parse("{ \"a\": }");
            } catch (IllegalArgumentException e) {
                threw = true;
            }
            check(threw, "json5: a broken file is refused with a reason");
        });

        step("initial", 0, () -> {
            check(EVENTS.isEmpty(), "no change event on the first load (" + EVENTS.size() + ")");
            check(flag.get() && ratio.get() == 0.5 && name.get().equals("hello") && mode.get() == Mode.B
                    && target.get().equals(FId.of("minecraft", "stone")) && targets.get().equals(List.of(FId.of("minecraft", "dirt")))
                    && tint.get() == 0x33AA55 && view.get() == 2,
                "code defaults read through typed handles");
            check(count.get() == 7 && count.isDefault(), "datapack default (7) replaces the code default (5): " + count.get());
            check(main.entry("count").defaultValue().equals(7), "reset target is the datapack default");
            FConfig extra = FConfig.of(EXTRA_ID);
            check(extra.dataDeclared() && extra.bool("enabled").get() && extra.decimal("speed").get() == 1.5
                    && extra.string("style").get().equals("plain") && extra.entry("style").side() == FConfig.Side.CLIENT
                    && extra.idList("items").get().equals(List.of(FId.of("minecraft", "apple"))),
                "config declared by a datapack, with typed lookups");
            check(!FConfig.of("configtest:absent").integer("x").present() && FConfig.of("configtest:absent").integer("x").getOr(11) == 11,
                "a handle on an undeclared entry reports absent and falls back");
            String text = read(global());
            check(Files.isRegularFile(global()) && text.contains("// \"count\": 7,") && text.contains("// \"flag\": true,"),
                "values file written with commented-out defaults: " + global());
            check(text.contains("How many things happen at once."), "comment written from the lang file");
            check(text.contains("int, 0 to 10, server, sent to players"), "type and range written beside each entry");
            check(Json5.parse(text).getAsJsonObject().size() == 0, "the written file reads back with nothing set");
            check(Files.isRegularFile(FConfig.of(EXTRA_ID).globalFile()), "values file written for the datapack config");
            check(slots.get() == 3 && !slots.needsRestart(), "startup entry read once");
        });

        step("datapack layering", 0, () -> {
            JsonObject low = json("{\"defaults\": {\"count\": 4}}").getAsJsonObject();
            JsonObject high = json("{\"entries\": {\"count\": {\"default\": 6}}}").getAsJsonObject();
            JsonObject extraDecl = json("{\"entries\": {\"enabled\": {\"type\": \"bool\", \"default\": true}, \"speed\": {\"type\": \"double\", \"default\": 1.5, \"min\": 0, \"max\": 4}}}").getAsJsonObject();
            JsonObject extraOver = json("{\"defaults\": {\"speed\": 2.5}, \"entries\": {\"enabled\": {\"type\": \"int\", \"default\": false}}}").getAsJsonObject();
            EVENTS.clear();
            FConfig.change(() -> {
                FConfig.applyData(Map.of(MAIN_ID, List.of(low, high), EXTRA_ID, List.of(extraDecl, extraOver)));
                for (FConfig c : FConfig.all()) c.reloadFiles();
            });
            check(count.get() == 6, "the higher pack's default wins over the lower pack's: " + count.get());
            FConfig extra = FConfig.of(EXTRA_ID);
            check(extra.decimal("speed").get() == 2.5 && !extra.bool("enabled").get(), "a higher pack only changes defaults of a datapack config");
            check(extra.entry("enabled").type() == ConfigEntry.Type.BOOL, "a higher pack cannot change an entry's type");
            check(extra.entry("style") == null, "entries leave with the pack that declared them");
            check(changed(MAIN_ID, "count") && changed(EXTRA_ID, "speed"), "datapack changes fire the change event");
            EVENTS.clear();
            reload = server.reloadResources(server.getPackRepository().getSelectedIds());
        });

        step("reload", 0, () -> {
            check(reload.isDone() && !reload.isCompletedExceptionally(), "/reload finished");
            reload = null;
            check(count.get() == 7 && FConfig.of(EXTRA_ID).decimal("speed").get() == 1.5 && FConfig.of(EXTRA_ID).entry("style") != null,
                "/reload reads the datapack files again");
            check(changed(MAIN_ID, "count"), "/reload fires the change event for values it moved");
            EVENTS.clear();
            write(global(), "{\n  // set by hand\n  count: 9,\n  \"mystery\": 1,\n  \"view\": 3,\n}\n");
        });

        step("wait for watcher", 60, () -> { });

        step("file layer", 0, () -> {
            check(count.get() == 9 && !count.isDefault(), "the watcher picks up an edited file within 2 seconds: " + count.get());
            check(view.get() == 3, "client entry read from the file");
            check(changed(MAIN_ID, "count") && changed(MAIN_ID, "view"), "file edit fires the change event with its keys");
            EVENTS.clear();
            write(global(), "{ \"count\": 99, \"mystery\": 1 }");
        });

        step("wait for watcher", 60, () -> { });

        step("range", 0, () -> {
            check(count.get() == 7, "an out-of-range value is not used; the default applies: " + count.get());
            write(global(), "{ \"count\": 9, \"mystery\": 1, \"slots\": 5, \"view\": 3 }");
            write(world(), "{ \"count\": 3, \"view\": 6 }");
            EVENTS.clear();
        });

        step("wait for watcher", 60, () -> { });

        step("world layer", 0, () -> {
            check(count.get() == 3, "the per-world file wins over the game's file: " + count.get());
            check(view.get() == 3, "client entries ignore the per-world file: " + view.get());
            check(slots.get() == 3 && slots.needsRestart(), "a changed startup entry keeps its value and needs a restart");
            boolean slotsFired = false;
            for (ConfigEvent.Changed e : EVENTS) slotsFired |= e.keys().contains("slots");
            check(!slotsFired, "startup entries never fire the change event");
            EVENTS.clear();
            count.set(4);
            check(count.get() == 4 && read(world()).contains("\"count\": 4") && read(global()).contains("\"count\": 9"),
                "set() writes the per-world file when there is one");
            check(changed(MAIN_ID, "count"), "set() fires the change event");
            count.reset();
            check(count.get() == 7 && count.isDefault() && !read(global()).contains("\"count\": 9") && !read(world()).contains("\"count\": 4"),
                "reset() removes the value from both files");
            try {
                Files.deleteIfExists(world());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            name.set("written");
            String g = read(global());
            check(name.get().equals("written") && g.contains("\"name\": \"written\"") && g.contains("\"mystery\": 1"),
                "set() writes the game's file and keeps keys no mod declares");
            boolean threw = false;
            try {
                count.set(42);
            } catch (IllegalArgumentException e) {
                threw = true;
            }
            check(threw && count.get() == 7, "set() refuses an out-of-range value");
        });

        step("wait for watcher", 50, () -> EVENTS.clear());

        step("no echo", 0, () -> check(EVENTS.isEmpty(), "the watcher does not report our own writes again (" + EVENTS + ")"));

        step("sync", 0, () -> {
            FConfig.Sync msg = main.sync();
            check(msg.values().containsKey("count") && msg.values().containsKey("tint") && !msg.values().containsKey("view") && !msg.values().containsKey("slots"),
                "sync carries server entries only: " + msg.values().keySet());
            FConfig.Sync back = FNet.roundTrip(msg, server.registryAccess());
            check(back.equals(msg), "sync message survives the network codec");
            FConfig.Edit edit = ConfigScreenModel.editMessage(main.entry("count"), 3);
            check(FNet.roundTrip(edit, server.registryAccess()).equals(edit) && edit.value().get().equals("3"), "edit message survives the network codec");

            // the client's view: a server that says 9, and a config only the server knows
            Map<String, String> values = new java.util.LinkedHashMap<>(msg.values());
            values.put("count", "9");
            FConfig.Sync remoteMsg = new FConfig.Sync(FId.of("configtest", "remote"),
                "{\"entries\": {\"power\": {\"type\": \"int\", \"default\": 1, \"min\": 0, \"max\": 5}, \"look\": {\"type\": \"bool\", \"default\": true, \"side\": \"client\"}}}",
                Map.of("power", "4"));
            FConfig.serverRunning = false;
            try {
                FConfig.applySync(new FConfig.Sync(msg.id(), msg.declaration(), values));
                FConfig.applySync(remoteMsg);
                check(count.get() == 9, "a client reads the server's value, not its own file: " + count.get());
                check(view.get() == 3, "client entries stay the client's own");
                FConfig remote = FConfig.of("configtest:remote");
                check(remote.dataDeclared() && remote.integer("power").get() == 4 && remote.bool("look").get(),
                    "a config only the server declares appears on the client with the server's values");
                FConfig.clientDisconnected();
                check(count.get() == 7 && !remote.integer("power").present(), "leaving the server drops its values and its configs");
            } finally {
                FConfig.serverRunning = true;
            }
        });

        step("edit", 0, () -> {
            ServerPlayer fake = FVanilla.fakePlayer(server.overworld(), "configtester");
            check(!FVanilla.isOp(fake), "a fake player is not an operator");
            boolean applied = FConfig.handleEdit(fake, ConfigScreenModel.editMessage(main.entry("count"), 2));
            check(!applied && count.get() == 7, "an edit from a player who is not an operator is refused");
        });

        step("screen model", 0, () -> {
            ConfigEntry c = main.entry("count");
            ConfigEntry v = main.entry("view");
            ConfigScreenModel guest = new ConfigScreenModel(false, true, k -> k.equals("config.configtest.main.count") ? "How many things happen at once." : null);
            check(guest.mods().contains("configtest") && guest.rows().contains(c) && guest.rows().contains(v), "screen lists the configs of every mod");
            guest.search("things happen");
            check(guest.rows().equals(List.of(c)), "search finds entries by their comment: " + guest.rows().size());
            guest.search("");
            guest.mod("configtest");
            guest.category("client");
            check(guest.rows().equals(List.of(v)), "category filter");
            check(!guest.editable(c) && guest.editable(v), "server values are read-only for a player who is not an operator");
            check(!guest.edit(c, "5") && guest.pendingCount() == 0, "a read-only entry takes no edit");
            check(guest.validate(c, "11") != null && guest.validate(c, "abc") != null && guest.validate(c, "4") == null,
                "typed text is checked against type and range");
            check(guest.validate(main.entry("tint"), "#FF00AA") == null && guest.validate(main.entry("tint"), "pink") != null, "colours as #RRGGBB");
            check(guest.validate(main.entry("mode"), "c") == null && guest.validate(main.entry("mode"), "d") != null, "enum values");
            check(guest.validate(main.entry("targets"), "minecraft:a, minecraft:b") == null && guest.validate(main.entry("targets"), "Bad Id") != null, "id lists");
            ConfigScreenModel local = new ConfigScreenModel(true, false, k -> null);
            check(local.edit(c, "5") && local.reset(v) && local.pendingCount() == 2 && local.text(c).equals("5") && local.text(v).equals("2"),
                "edits and resets wait for Done");
            EVENTS.clear();
            check(local.apply() == 2 && count.get() == 5 && read(global()).contains("\"count\": 5") && !read(global()).contains("  \"view\": 3"),
                "Done writes the files");
            check(changed(MAIN_ID, "count") && changed(MAIN_ID, "view"), "Done fires the change event");
        });
    }
}
