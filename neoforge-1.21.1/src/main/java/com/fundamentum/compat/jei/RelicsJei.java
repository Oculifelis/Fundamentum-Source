package com.fundamentum.compat.jei;

import com.fundamentum.relic.RelicBlock;
import com.fundamentum.relic.RelicDef;
import com.fundamentum.relic.RelicSet;
import com.fundamentum.relic.Relics;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredBlock;

/**
 * The JEI information page of every relic: the declared {@code jei} key, else {@code jei.<modid>.info.<name>} when the
 * mod's lang has it, else lines made from the declaration (whose trophy it is, how it is won, the grand relic's room).
 */
final class RelicsJei {
    private RelicsJei() {}

    static void info(IRecipeRegistration r) {
        Language lang = Language.getInstance();
        for (RelicSet set : Relics.sets()) {
            for (Map.Entry<String, DeferredBlock<RelicBlock>> e : set.blocks().entrySet()) {
                RelicBlock block = e.getValue().get();
                RelicDef def = Relics.def(block);
                String own = "jei." + set.modid() + ".info." + e.getKey();
                List<Component> lines = new ArrayList<>();
                if (def.jei != null && !def.jei.isEmpty()) {
                    lines.add(Component.translatable(def.jei));
                } else if (lang.has(own)) {
                    lines.add(Component.translatable(own));
                } else {
                    Component boss = boss(def.boss);
                    lines.add(Component.translatable("jei.fundamentum.relic", boss));
                    if (def.drop == RelicDef.Drop.DROPS) {
                        lines.add(Component.translatable("jei.fundamentum.relic.drops", boss));
                    } else if (def.drop == RelicDef.Drop.KILLER) {
                        lines.add(Component.translatable("jei.fundamentum.relic.killer", boss));
                    }
                    if (def.grand()) {
                        lines.add(Component.translatable("jei.fundamentum.relic.grand"));
                    }
                }
                r.addItemStackInfo(new ItemStack(block), lines.toArray(Component[]::new));
            }
        }
    }

    private static Component boss(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        EntityType<?> type = key == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(key).orElse(null);
        return type != null ? type.getDescription() : Component.literal(id);
    }
}
