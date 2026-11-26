package com.fundamentum.quest;

import com.fundamentum.port.FId;
import com.fundamentum.port.FNet;
import com.fundamentum.port.FStreamCodec;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Keeps each client's copy of its quests: a JSON snapshot of active quests, open offers and progress, sent when it
 * changes, and the actions the quest screen sends back (open, accept, abandon). Clients that never said hello (no
 * quest screen, or a test player) keep the chat messages instead.
 */
public final class QuestSync {
    private QuestSync() { }

    /** The default quest screen, {@code assets/fundamentum/fundamentum/quest_screens/journal.json}. */
    public static final FId JOURNAL = FId.of("fundamentum", "journal");

    /** Server to client: the player's quests as JSON. {@code open} asks the client to open {@code screen}. */
    public record Snapshot(String json) { }

    /** Client to server: {@code hello}, {@code open}, {@code accept} or {@code abandon}; quest may be empty. */
    public record Action(String kind, String quest) { }

    private static final int MAX_JSON = 30000;
    private static final Map<ServerPlayer, Integer> SENT = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<ServerPlayer> READY = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /** Where snapshots land on the client; set by the client's quest code. */
    public static volatile Consumer<String> sink = s -> { };

    private static boolean initialised;

    static synchronized void init() {
        if (initialised) return;
        initialised = true;
        FNet.register(FId.of("fundamentum", "quest_snapshot"), Snapshot.class,
            FStreamCodec.composite(FStreamCodec.STRING, Snapshot::json, Snapshot::new), FNet.Side.TO_CLIENT,
            (m, ctx) -> sink.accept(m.json()));
        FNet.register(FId.of("fundamentum", "quest_action"), Action.class,
            FStreamCodec.composite(FStreamCodec.STRING, Action::kind, FStreamCodec.STRING, Action::quest, Action::new),
            FNet.Side.TO_SERVER, (m, ctx) -> {
                if (ctx.player() instanceof ServerPlayer p) act(p, m);
            });
    }

    /** Whether this player's client shows quest screens. */
    public static boolean ready(ServerPlayer player) {
        return READY.contains(player);
    }

    private static void act(ServerPlayer p, Action m) {
        FId quest = m.quest().isEmpty() ? null : FId.tryOf(m.quest());
        switch (m.kind()) {
            case "hello" -> {
                READY.add(p);
                send(p, false, null, null);
            }
            case "open" -> open(p, JOURNAL, null);
            case "accept" -> {
                if (quest == null || !Quests.offered(p, quest)) return;
                Quests.accept(p, quest);
                open(p, null, quest);
            }
            case "abandon" -> {
                if (quest == null) return;
                Quests.fail(p, quest, true);
                open(p, null, null);
            }
            default -> { }
        }
    }

    /** Opens a quest screen on the player's client; null screen keeps whichever one is open. Returns false when it can't. */
    public static boolean open(ServerPlayer p, FId screen, FId focus) {
        if (!ready(p)) return false;
        send(p, true, screen, focus);
        return true;
    }

    /** Called once a second per player: sends a fresh snapshot when anything the screen shows has changed. */
    static void tick(ServerPlayer p) {
        if (!ready(p)) return;
        Integer last = SENT.get(p);
        if (last == null || last != signature(p)) send(p, false, null, null);
    }

    private static int signature(ServerPlayer p) {
        List<String> offered = new ArrayList<>();
        for (QuestDef d : Quests.all().values()) if (Quests.offered(p, d.id())) offered.add(d.id().toString());
        return Objects.hash(Quests.state(p), offered, System.identityHashCode(Quests.all()));
    }

    private static void send(ServerPlayer p, boolean open, FId screen, FId focus) {
        JsonObject o = new JsonObject();
        o.addProperty("open", open);
        if (screen != null) o.addProperty("screen", screen.toString());
        if (focus != null) o.addProperty("focus", focus.toString());
        long now = p.level().getGameTime();
        o.addProperty("time", now);
        Quests.State s = Quests.state(p);
        JsonArray active = new JsonArray();
        for (Map.Entry<String, Quests.Active> e : s.active().entrySet()) {
            QuestDef d = Quests.get(FId.tryOf(e.getKey()));
            if (d == null) continue;
            JsonObject q = quest(d);
            JsonArray have = new JsonArray();
            for (int i = 0; i < d.objectives().size(); i++) {
                List<Integer> pr = e.getValue().progress();
                have.add(i < pr.size() ? pr.get(i) : 0);
            }
            q.add("have", have);
            q.addProperty("ready", Quests.ready(p, d.id()));
            if (d.timeLimit() > 0) q.addProperty("ends", e.getValue().started() + d.timeLimit());
            active.add(q);
        }
        o.add("active", active);
        JsonArray offered = new JsonArray();
        for (QuestDef d : Quests.all().values()) {
            if (Quests.offered(p, d.id()) && !s.active().containsKey(d.id().toString())) offered.add(quest(d));
        }
        o.add("offered", offered);
        o.addProperty("done", s.done().size());
        String json = o.toString();
        while (json.length() > MAX_JSON && !offered.isEmpty()) {
            offered.remove(offered.size() - 1);
            json = o.toString();
        }
        if (json.length() > MAX_JSON) return;
        SENT.put(p, signature(p));
        FNet.toPlayer(p, new Snapshot(json));
    }

    private static JsonObject quest(QuestDef d) {
        JsonObject q = new JsonObject();
        q.addProperty("id", d.id().toString());
        q.addProperty("name", d.displayName());
        q.addProperty("description", d.description());
        q.addProperty("group", d.group());
        q.addProperty("turn_in", d.turnIn());
        if (d.timeLimit() > 0) q.addProperty("time_limit", d.timeLimit());
        JsonArray obj = new JsonArray();
        for (JsonObject o : d.objectives()) {
            JsonObject x = new JsonObject();
            x.addProperty("text", o.has("text") ? o.get("text").getAsString() : o.get("type").getAsString());
            x.addProperty("need", QuestDef.count(o));
            obj.add(x);
        }
        q.add("objectives", obj);
        return q;
    }
}
