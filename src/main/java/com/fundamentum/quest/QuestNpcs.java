package com.fundamentum.quest;

import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.google.gson.JsonElement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** The quest-giver NPC entity and its types from {@code data/<ns>/fundamentum/npcs/*.json} (see {@link NpcDef}). */
public final class QuestNpcs {
    private QuestNpcs() { }

    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, "fundamentum");
    public static final RegistryObject<EntityType<QuestNpc>> QUEST_GIVER = ENTITIES.register("quest_giver",
        () -> EntityType.Builder.<QuestNpc>of(QuestNpc::new, MobCategory.MISC).sized(0.6F, 1.8F)
            .clientTrackingRange(10).build("quest_giver"));

    private static volatile Map<FId, NpcDef> defs = Map.of();
    private static boolean initialised;

    public static synchronized void init(IEventBus modBus) {
        if (initialised) return;
        initialised = true;
        ENTITIES.register(modBus);
        modBus.addListener(QuestNpcs::attributes);
        FLife.jsonData(FId.of("fundamentum", "npcs"), "fundamentum/npcs", QuestNpcs::load);
        Quests.npcType = e -> e instanceof QuestNpc n ? n.npcType() : null;
    }

    private static void attributes(EntityAttributeCreationEvent e) {
        e.put(QUEST_GIVER.get(), QuestNpc.createAttributes().build());
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
        QuestNpc npc = QUEST_GIVER.get().create(level);
        if (npc == null) return null;
        npc.setPos(pos.x, pos.y, pos.z);
        npc.setNpcType(type);
        level.addFreshEntity(npc);
        return npc;
    }
}
