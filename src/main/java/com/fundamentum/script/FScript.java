package com.fundamentum.script;

import com.fundamentum.event.FEvents;
import com.fundamentum.faction.FactionDef;
import com.fundamentum.faction.Factions;
import com.fundamentum.mana.Mana;
import com.fundamentum.poise.Poise;
import com.fundamentum.port.FId;
import com.fundamentum.quest.Quests;
import com.fundamentum.religion.Religion;
import com.fundamentum.stamina.Stamina;
import com.fundamentum.stat.Stats;
import com.fundamentum.unlock.Unlocks;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * The script surface of Fundamentum: plain static methods that take ids as text, so KubeJS ({@code Fundamentum.rep(p,
 * 'mymod:guild')}) and CraftTweaker reach every system without loader or registry types. Each method has one shape
 * only, so Rhino never has to pick between overloads. Anything a mod registers into these systems (factions, quests,
 * gods, stats, unlocks, mana and stamina sources, events) shows up here with no work from that mod.
 */
public final class FScript {
    private FScript() { }

    /** A script listener for {@link #on}: the event id, its fields by name, and a way to cancel it. */
    @FunctionalInterface
    public interface Listener {
        void on(ScriptEvent event);
    }

    /** What a script sees of a Fundamentum event. */
    public static final class ScriptEvent {
        private final String id;
        private final Record event;
        private final FEvents.Call call;

        public ScriptEvent(String id, Record event, FEvents.Call call) {
            this.id = id;
            this.event = event;
            this.call = call;
        }

        public String getId() { return id; }

        /** The event record itself (its accessors work too, e.g. {@code e.event.player()}). */
        public Record getEvent() { return event; }

        /** The fields by name, in declaration order. */
        public Map<String, Object> getFields() { return FEvents.fields(event); }

        public Object get(String field) { return FEvents.fields(event).get(field); }

        /** The player the event is about, or null. */
        public ServerPlayer getPlayer() { return FEvents.player(event); }

        public boolean isCancellable() { return call.cancellable(); }

        public void cancel() { call.cancel(); }

        public boolean isCancelled() { return call.cancelled(); }
    }

    private static FId id(String id) {
        return FId.of(id);
    }

    // ---- events ----

    /** Every registered event id, from Fundamentum and from mods. */
    public static List<String> eventIds() {
        List<String> out = new ArrayList<>();
        for (FEvents.Type t : FEvents.types()) out.add(t.id().toString());
        return out;
    }

    /** Listens to one event by id. Listeners added by scripts stay until the game restarts. */
    public static void on(String eventId, Listener listener) {
        FId fid = id(eventId);
        if (FEvents.type(fid) == null) throw new IllegalArgumentException("No Fundamentum event " + eventId);
        FEvents.listen(fid, (e, call) -> listener.on(new ScriptEvent(eventId, e, call)));
    }

    // ---- factions ----

    public static List<String> factions() {
        List<String> out = new ArrayList<>();
        for (FId f : Factions.all().keySet()) out.add(f.toString());
        return out;
    }

    public static int rep(ServerPlayer player, String faction) { return Factions.rep(player, id(faction)); }

    public static boolean setRep(ServerPlayer player, String faction, int value) { return Factions.setRep(player, id(faction), value); }

    public static void addRep(ServerPlayer player, String faction, int delta) { Factions.addRep(player, id(faction), delta); }

    /** The player's rank id in a faction, or null. */
    public static String rank(ServerPlayer player, String faction) {
        FactionDef.Rank r = Factions.rank(player, id(faction));
        return r == null ? null : r.id();
    }

    public static boolean isMember(ServerPlayer player, String faction) { return Factions.member(player, id(faction)); }

    /** Joins a faction; null on success, or why not. */
    public static String join(ServerPlayer player, String faction) { return Factions.join(player, id(faction)); }

    public static boolean leave(ServerPlayer player, String faction) { return Factions.leave(player, id(faction)); }

    public static double alignment(ServerPlayer player, String axis) { return Factions.alignment(player, id(axis)); }

    public static void addAlignment(ServerPlayer player, String axis, double delta) { Factions.addAlignment(player, id(axis), delta); }

    /** How a mob feels about the player: ally, neutral or hostile (or whatever its faction's data says). */
    public static String attitude(Entity mob, ServerPlayer player) { return Factions.attitude(mob, player); }

    /** Runs a named rep action (as data defines it) for every faction that has it; returns how many applied. */
    public static int act(ServerPlayer player, String action) { return Factions.act(player, action); }

    // ---- quests ----

    public static List<String> quests() {
        List<String> out = new ArrayList<>();
        for (FId q : Quests.all().keySet()) out.add(q.toString());
        return out;
    }

    /** Takes a quest; null on success, or why not. */
    public static String acceptQuest(ServerPlayer player, String quest) { return Quests.accept(player, id(quest)); }

    public static boolean completeQuest(ServerPlayer player, String quest) { return Quests.complete(player, id(quest)); }

    public static boolean failQuest(ServerPlayer player, String quest) { return Quests.fail(player, id(quest), false); }

    public static boolean questActive(ServerPlayer player, String quest) { return Quests.active(player, id(quest)); }

    public static boolean questDone(ServerPlayer player, String quest) { return Quests.done(player, id(quest)); }

    public static List<Integer> questProgress(ServerPlayer player, String quest) { return Quests.progress(player, id(quest)); }

    /**
     * Counts progress for every active objective of this type (for a "script" objective, the context is the key the
     * quest names). Returns how many objectives moved.
     */
    public static int questSignal(ServerPlayer player, String type, Object context, int amount) {
        return Quests.signal(player, type, context, amount);
    }

    // ---- religion ----

    public static List<String> gods() {
        List<String> out = new ArrayList<>();
        for (FId g : Religion.deities().keySet()) out.add(g.toString());
        return out;
    }

    public static int devotion(ServerPlayer player, String god) { return Religion.devotion(player, id(god)); }

    public static void addDevotion(ServerPlayer player, String god, int delta) { Religion.addDevotion(player, id(god), delta); }

    public static boolean follows(ServerPlayer player, String god) { return Religion.follows(player, id(god)); }

    /** Takes a god as patron; null on success, or why not. */
    public static String follow(ServerPlayer player, String god) { return Religion.follow(player, id(god)); }

    public static boolean forsake(ServerPlayer player, String god) { return Religion.forsake(player, id(god)); }

    public static double favour(ServerPlayer player, String god) { return Religion.favour(player, id(god)); }

    public static double addFavour(ServerPlayer player, String god, double delta) { return Religion.addFavour(player, id(god), delta); }

    /** Spends favour on a boon; null on success, or why not. */
    public static String boon(ServerPlayer player, String god, String boon) { return Religion.boon(player, id(god), boon); }

    // ---- unlocks and stats ----

    public static boolean hasUnlock(ServerPlayer player, String unlock) { return Unlocks.has(player, id(unlock)); }

    public static int unlockLevel(ServerPlayer player, String unlock) { return Unlocks.level(player, id(unlock)); }

    public static boolean grantUnlock(ServerPlayer player, String unlock, int level) { return Unlocks.grant(player, id(unlock), level); }

    public static double stat(LivingEntity entity, String stat) { return Stats.get(entity, id(stat)); }

    // ---- mana, stamina and poise ----

    public static double mana(LivingEntity entity) { return Mana.get(entity); }

    public static double manaMax(LivingEntity entity) { return Mana.max(entity); }

    public static boolean spendMana(LivingEntity entity, double amount) { return Mana.spend(entity, amount); }

    public static double restoreMana(LivingEntity entity, double amount) { return Mana.restore(entity, amount); }

    public static double stamina(LivingEntity entity) { return Stamina.get(entity); }

    public static boolean spendStamina(LivingEntity entity, double amount) { return Stamina.spend(entity, amount); }

    public static double restoreStamina(LivingEntity entity, double amount) { return Stamina.restore(entity, amount); }

    public static double poise(LivingEntity entity) { return Poise.current(entity); }

    public static boolean staggered(LivingEntity entity) { return Poise.staggered(entity); }

    public static boolean stagger(LivingEntity target, Entity from, int ticks) { return Poise.stagger(target, from, ticks); }
}
