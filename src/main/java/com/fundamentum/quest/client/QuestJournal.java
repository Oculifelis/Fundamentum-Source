package com.fundamentum.quest.client;

import com.fundamentum.port.FNet;
import com.fundamentum.quest.QuestSync;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * The client's copy of its quests (the last snapshot from the server), which quests the HUD tracker hides, and the
 * tracker's lines. The same file on every version.
 */
public final class QuestJournal {
    private QuestJournal() { }

    /** One quest as the screen shows it. */
    public record Quest(String id, String name, String description, String group, boolean turnIn, boolean active,
                        List<String> objectives, List<Integer> need, List<Integer> have, boolean ready, long ends) {
        public boolean objectiveDone(int i) {
            return i < this.have.size() && this.have.get(i) >= this.need.get(i);
        }
    }

    private static volatile List<Quest> active = List.of();
    private static volatile List<Quest> offered = List.of();
    private static volatile int done;
    private static volatile int version;
    private static Set<String> hidden;

    /** Bumped on every snapshot, so screens can notice. */
    public static int version() {
        return version;
    }

    public static List<Quest> active() {
        return active;
    }

    public static List<Quest> offered() {
        return offered;
    }

    public static int done() {
        return done;
    }

    /** Parses a snapshot; returns {open, screen, focus} with nulls when the server did not ask for a screen. */
    static String[] accept(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        active = quests(o.getAsJsonArray("active"), true);
        offered = quests(o.getAsJsonArray("offered"), false);
        done = o.has("done") ? o.get("done").getAsInt() : 0;
        version++;
        if (!o.has("open") || !o.get("open").getAsBoolean()) return null;
        return new String[] {"open", o.has("screen") ? o.get("screen").getAsString() : null,
            o.has("focus") ? o.get("focus").getAsString() : null};
    }

    private static List<Quest> quests(JsonArray a, boolean isActive) {
        List<Quest> out = new ArrayList<>();
        if (a == null) return List.of();
        for (JsonElement e : a) {
            JsonObject q = e.getAsJsonObject();
            List<String> text = new ArrayList<>();
            List<Integer> need = new ArrayList<>();
            for (JsonElement x : q.getAsJsonArray("objectives")) {
                text.add(x.getAsJsonObject().get("text").getAsString());
                need.add(x.getAsJsonObject().get("need").getAsInt());
            }
            List<Integer> have = new ArrayList<>();
            if (q.has("have")) for (JsonElement h : q.getAsJsonArray("have")) have.add(h.getAsInt());
            out.add(new Quest(q.get("id").getAsString(), q.get("name").getAsString(), q.get("description").getAsString(),
                q.get("group").getAsString(), q.get("turn_in").getAsBoolean(), isActive, List.copyOf(text), List.copyOf(need),
                List.copyOf(have), q.has("ready") && q.get("ready").getAsBoolean(), q.has("ends") ? q.get("ends").getAsLong() : -1));
        }
        return List.copyOf(out);
    }

    public static void send(String kind, String quest) {
        FNet.toServer(new QuestSync.Action(kind, quest == null ? "" : quest));
    }

    /** Ticks left on a timed quest, -1 when it has no limit. */
    public static long ticksLeft(Quest q) {
        if (q.ends() < 0) return -1;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return -1;
        return Math.max(0, q.ends() - mc.level.getGameTime());
    }

    public static String clock(long ticks) {
        long s = ticks / 20;
        return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format("%d:%02d", s / 60, s % 60);
    }

    // ---- HUD tracker

    public static boolean onHud(String id) {
        return !hidden().contains(id);
    }

    public static void toggleHud(String id) {
        Set<String> h = hidden();
        if (!h.remove(id)) h.add(id);
        try {
            Files.createDirectories(file().getParent());
            Files.write(file(), h, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            // the choice still holds until the game closes
        }
    }

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("fundamentum").resolve("quest_hud_hidden.txt");
    }

    private static synchronized Set<String> hidden() {
        if (hidden == null) {
            hidden = new LinkedHashSet<>();
            try {
                if (Files.exists(file())) for (String line : Files.readAllLines(file(), StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) hidden.add(line.trim());
                }
            } catch (IOException ex) {
                // start with everything shown
            }
        }
        return hidden;
    }

    private static final int HUD_QUESTS = 3;

    /** The tracker's lines: up to three active quests the player has not hidden, each with its objectives. */
    public static List<Component> hudLines() {
        List<Component> out = new ArrayList<>();
        int shown = 0;
        for (Quest q : active) {
            if (!onHud(q.id())) continue;
            if (shown++ == HUD_QUESTS) break;
            MutableComponent name = Component.literal(QuestView.tr(q.name())).withStyle(ChatFormatting.YELLOW);
            long left = ticksLeft(q);
            if (left >= 0) name.append(Component.literal(" " + clock(left)).withStyle(left < 1200 ? ChatFormatting.RED : ChatFormatting.GRAY));
            out.add(name);
            if (q.ready() && q.turnIn()) {
                out.add(Component.literal(" " + QuestView.tr("fundamentum.quests.hand_in", "Ready to hand in")).withStyle(ChatFormatting.GREEN));
                continue;
            }
            for (int i = 0; i < q.objectives().size(); i++) {
                int have = i < q.have().size() ? q.have().get(i) : 0;
                boolean ok = q.objectiveDone(i);
                out.add(Component.literal(" " + QuestView.tr(q.objectives().get(i)) + " " + Math.min(have, q.need().get(i)) + "/" + q.need().get(i))
                    .withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.GRAY));
            }
        }
        return out;
    }
}
