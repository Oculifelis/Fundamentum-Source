package com.fundamentum.relic.client;

import com.fundamentum.relic.RelicDef;
import com.fundamentum.relic.RelicFigure;
import com.fundamentum.relic.Relics;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * The relic renderer on the relic block entity, the declared description under a relic's name, the relic tab rebuilt
 * when relic files reach the client, and the stand-in set-up hook for bosses that are not {@link RelicFigure}s.
 */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class RelicClient {
    /** The mod-bus half: the renderer and the tab rebuild. */
    @EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Mod {
        private Mod() {}

        @SubscribeEvent
        public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerBlockEntityRenderer(Relics.ENTITY.get(), RelicRenderer::new);
        }

        /** The relic tab lists the relic files the client has, so it is rebuilt each time they arrive. */
        @SubscribeEvent
        public static void setup(FMLClientSetupEvent event) {
            Relics.TABLE.onLoad(t -> Minecraft.getInstance().execute(RelicClient::rebuildTab));
        }
    }

    private static final Map<EntityType<?>, BiConsumer<Entity, Integer>> POSERS = new ConcurrentHashMap<>();

    private RelicClient() {}

    /** Sets up stand-ins of {@code type} for a relic (the entity and the phase), for a boss that is no {@link RelicFigure}. */
    public static void poser(EntityType<?> type, BiConsumer<Entity, Integer> poser) {
        POSERS.put(type, poser);
    }

    static void pose(Entity e, int phase) {
        if (e instanceof RelicFigure f) {
            f.relicPose(phase);
        }
        BiConsumer<Entity, Integer> p = POSERS.get(e.getType());
        if (p != null) {
            p.accept(e, phase);
        }
    }

    /** {@code description}: one grey line under the name. */
    @SubscribeEvent
    public static void tooltip(ItemTooltipEvent event) {
        if (event.getItemStack().is(Relics.ITEM.get()) && Relics.RELIC.has(event.getItemStack())) {
            RelicDef def = Relics.TABLE.get(Relics.RELIC.get(event.getItemStack()).vanilla());
            if (def != null && def.description != null && !def.description.isEmpty()) {
                event.getToolTip().add(Math.min(1, event.getToolTip().size()), Component.translatable(def.description).withStyle(ChatFormatting.GRAY));
            }
        }
    }

    private static void rebuildTab() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        try {
            Relics.TAB.get().buildContents(new CreativeModeTab.ItemDisplayParameters(mc.level.enabledFeatures(),
                mc.player.canUseGameMasterBlocks(), mc.level.registryAccess()));
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger("fundamentum").warn("could not rebuild the relic tab", e);
        }
    }
}
