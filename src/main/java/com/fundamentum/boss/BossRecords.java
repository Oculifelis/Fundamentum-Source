package com.fundamentum.boss;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The per-world defeat record: how often each boss (by spec id) was defeated in this world, and by each player. Kept in
 * the overworld's saved data ({@code data/fundamentum/bosses.dat} in the overworld folder).
 */
public final class BossRecords extends SavedData {
    static final SavedDataType<BossRecords> TYPE = new SavedDataType<>(Identifier.fromNamespaceAndPath("fundamentum", "bosses"),
        BossRecords::new, CompoundTag.CODEC.xmap(BossRecords::load, r -> r.save(new CompoundTag())));

    private final Map<String, Integer> world = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> players = new HashMap<>();

    public static BossRecords get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** Whether the boss was ever defeated in this world. */
    public boolean defeated(Identifier boss) {
        return count(boss) > 0;
    }

    /** Whether this player ever helped defeat the boss. */
    public boolean defeated(Identifier boss, UUID player) {
        return count(boss, player) > 0;
    }

    public int count(Identifier boss) {
        return this.world.getOrDefault(boss.toString(), 0);
    }

    public int count(Identifier boss, UUID player) {
        Map<String, Integer> m = this.players.get(player);
        return m == null ? 0 : m.getOrDefault(boss.toString(), 0);
    }

    /** Adds one world defeat. */
    public void addWorld(Identifier boss) {
        this.world.merge(boss.toString(), 1, Integer::sum);
        setDirty();
    }

    /** Adds one defeat for this player. */
    public void addPlayer(Identifier boss, UUID player) {
        this.players.computeIfAbsent(player, k -> new HashMap<>()).merge(boss.toString(), 1, Integer::sum);
        setDirty();
    }

    /** Forgets the boss (world and players), e.g. for a command or a test. */
    public void clear(Identifier boss) {
        this.world.remove(boss.toString());
        this.players.values().forEach(m -> m.remove(boss.toString()));
        setDirty();
    }

    static BossRecords load(CompoundTag tag) {
        BossRecords r = new BossRecords();
        String raw = tag.getStringOr("data", "");
        if (raw.isEmpty()) return r;
        JsonObject json = JsonParser.parseString(raw).getAsJsonObject();
        if (json.has("world")) json.getAsJsonObject("world").entrySet().forEach(e -> r.world.put(e.getKey(), e.getValue().getAsInt()));
        if (json.has("players")) {
            json.getAsJsonObject("players").entrySet().forEach(p -> {
                Map<String, Integer> m = new HashMap<>();
                p.getValue().getAsJsonObject().entrySet().forEach(e -> m.put(e.getKey(), e.getValue().getAsInt()));
                r.players.put(UUID.fromString(p.getKey()), m);
            });
        }
        return r;
    }

    CompoundTag save(CompoundTag tag) {
        JsonObject json = new JsonObject();
        JsonObject w = new JsonObject();
        this.world.forEach(w::addProperty);
        json.add("world", w);
        JsonObject ps = new JsonObject();
        this.players.forEach((id, m) -> {
            JsonObject o = new JsonObject();
            m.forEach(o::addProperty);
            ps.add(id.toString(), o);
        });
        json.add("players", ps);
        tag.putString("data", json.toString());
        return tag;
    }
}
