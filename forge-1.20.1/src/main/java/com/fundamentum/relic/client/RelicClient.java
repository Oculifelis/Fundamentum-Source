package com.fundamentum.relic.client;

import com.fundamentum.relic.RelicBlock;
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
import net.minecraft.world.item.BlockItem;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;

/**
 * The relic renderer on every relic block entity type, the declared description under a relic's name, and the stand-in
 * set-up hook for bosses that are not {@link RelicFigure}s.
 */
@Mod.EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class RelicClient {
    /** The mod-bus half: the renderer on every relic block entity type. */
    @Mod.EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBus {
        private ModBus() {}

        @SubscribeEvent
        public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
            for (var type : Relics.entityTypes()) {
                event.registerBlockEntityRenderer(type.get(), RelicRenderer::new);
            }
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
        if (event.getItemStack().getItem() instanceof BlockItem bi && bi.getBlock() instanceof RelicBlock block) {
            RelicDef def = Relics.def(block);
            if (def.description != null && !def.description.isEmpty()) {
                event.getToolTip().add(Math.min(1, event.getToolTip().size()), Component.translatable(def.description).withStyle(ChatFormatting.GRAY));
            }
        }
    }
}
