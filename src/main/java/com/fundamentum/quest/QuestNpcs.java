package com.fundamentum.quest;

import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.google.gson.JsonElement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The quest-giver NPC entity and its types from {@code data/<ns>/fundamentum/npcs/*.json} (see {@link NpcDef}). */
public final class QuestNpcs {
    private QuestNpcs() { }

    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, "fundamentum");
    public static final DeferredHolder<EntityType<?>, EntityType<QuestNpc>> QUEST_GIVER = ENTITIES.register("quest_giver",
        id -> EntityType.Builder.<QuestNpc>of(QuestNpc::new, MobCategory.MISC).sized(0.6F, 1.8F).eyeHeight(1.62F)
            .clientTrackingRange(10).build(ResourceKey.create(Registries.ENTITY_TYPE, id)));

    private static volatile Map<FId, NpcDef> defs = Map.of();
    private static boolean initialised;

    public static synchronized void init(IEventBus modBus) {
        if (initialised) return;
        initialised = true;
        ENTITIES.register(modBus);
        modBus.addListener((EntityAttributeCreationEvent e) -> e.put(QUEST_GIVER.get(), QuestNpc.createAttributes().build()));
        FLife.jsonData(FId.of("fundamentum", "npcs"), "fundamentum/npcs", QuestNpcs::load);
        Quests.npcType = e -> e instanceof QuestNpc n ? n.npcType() : null;
    }

    private static void load(Map<FId, List<JsonElement>> map) {
        Map<FId, NpcDef> out = new LinkedHashMap<>();
        map.forEach((id, list) -> {
            JsonElement top = list.get(list.size() - 1);
            if (top.isJsonObject()) out.put(id, NpcDef.parse(id, top.getAsJsonObject()));
        });
        defs = Map.copyOf(out);
    }

    public static Map<FId, NpcDef> all() {
        return defs;
    }

    public static NpcDef get(FId id) {
        return defs.get(id);
    }

    /** Spawns a quest giver of this type, or returns null when the type is unknown. */
    public static QuestNpc spawn(ServerLevel level, Vec3 pos, FId type) {
        if (get(type) == null) return null;
        QuestNpc npc = QUEST_GIVER.get().create(level, EntitySpawnReason.COMMAND);
        if (npc == null) return null;
        npc.setPos(pos.x, pos.y, pos.z);
        npc.setNpcType(type);
        level.addFreshEntity(npc);
        return npc;
    }
}
