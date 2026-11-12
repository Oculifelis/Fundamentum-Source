package com.fundamentum.relic;

import com.fundamentum.port.FId;
import com.fundamentum.recipeview.RecipeViews;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;

/**
 * Relics in recipe viewers: one entry per relic file (the relic item told apart by its relic id), the blank item and every
 * base block hidden, and an information page per relic: the declared {@code jei} key, else {@code jei.<ns>.info.<name>}
 * when a lang file has it, else lines made from the file (whose trophy it is, how it is won, the room it takes).
 */
public final class RelicViews {
    private RelicViews() {}

    public static void init() {
        RecipeViews.hide(s -> s.is(Relics.ITEM.get()) && !Relics.RELIC.has(s));
        RecipeViews.hide(s -> s.getItem() instanceof net.minecraft.world.item.BlockItem bi
            && (bi.getBlock() instanceof RelicPartBlock || bi.getBlock() instanceof RelicBlock) && !s.is(Relics.ITEM.get()));
        RecipeViews.subtypes(Relics.ITEM::get, s -> Relics.RELIC.has(s) ? Relics.RELIC.get(s).toString() : "");
        RecipeViews.infoPages(RelicViews::pages);
        Relics.TABLE.onLoad(t -> RecipeViews.refresh());
    }

    private static List<RecipeViews.Info> pages() {
        Language lang = Language.getInstance();
        List<RecipeViews.Info> out = new ArrayList<>();
        for (Map.Entry<ResourceLocation, RelicDef> e : Relics.TABLE.entries().entrySet()) {
            RelicDef def = e.getValue();
            FId id = FId.from(e.getKey());
            String own = "jei." + id.namespace() + ".info." + id.path().replace('/', '.');
            List<Component> lines = new ArrayList<>();
            if (def.jei != null && !def.jei.isEmpty()) {
                lines.add(Component.translatable(def.jei));
            } else if (lang.has(own)) {
                lines.add(Component.translatable(own));
            } else {
                Component boss = boss(def.boss);
                lines.add(Component.translatable("jei.fundamentum.relic", boss));
                if (def.drop == RelicDef.Drop.DROPS) lines.add(Component.translatable("jei.fundamentum.relic.drops", boss));
                else if (def.drop == RelicDef.Drop.KILLER) lines.add(Component.translatable("jei.fundamentum.relic.killer", boss));
                if (def.span > 1) lines.add(Component.translatable("jei.fundamentum.relic.grand", def.span, def.span));
            }
            List<ItemStack> stacks = List.of(Relics.stack(id));
            out.add(new RecipeViews.Info(() -> stacks, () -> lines));
        }
        return out;
    }

    private static Component boss(String id) {
        ResourceLocation key = id == null ? null : ResourceLocation.tryParse(id);
        EntityType<?> type = key == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(key).orElse(null);
        return type != null ? type.getDescription() : Component.literal(String.valueOf(id));
    }
}
