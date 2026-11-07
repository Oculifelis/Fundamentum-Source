package com.fundamentum.item;

import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Matches items by id ({@code "minecraft:apple"}) or tag ({@code "#c:foods"}); JSON may give one string or a list. An id
 * of an item that is not installed simply never matches, so data can name other mods' items freely.
 */
public record ItemMatcher(List<ResourceLocation> ids, List<TagKey<Item>> tags) {
    public static ItemMatcher parse(JsonElement json) {
        List<ResourceLocation> ids = new ArrayList<>();
        List<TagKey<Item>> tags = new ArrayList<>();
        List<JsonElement> entries = new ArrayList<>();
        if (json.isJsonArray()) {
            json.getAsJsonArray().forEach(entries::add);
        } else {
            entries.add(json);
        }
        for (JsonElement e : entries) {
            String s = e.getAsString();
            if (s.startsWith("#")) {
                tags.add(TagKey.create(Registries.ITEM, ResourceLocation.parse(s.substring(1))));
            } else {
                ids.add(ResourceLocation.parse(s));
            }
        }
        return new ItemMatcher(List.copyOf(ids), List.copyOf(tags));
    }

    public boolean test(ItemStack stack) {
        return !stack.isEmpty() && test(stack.getItem());
    }

    public boolean test(Item item) {
        if (!this.ids.isEmpty() && this.ids.contains(BuiltInRegistries.ITEM.getKey(item))) {
            return true;
        }
        for (TagKey<Item> tag : this.tags) {
            if (item.builtInRegistryHolder().is(tag)) {
                return true;
            }
        }
        return false;
    }
}
