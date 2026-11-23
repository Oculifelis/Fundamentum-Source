package com.fundamentum.damage;

import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Elements added by datapacks (1.26.0): {@code data/<ns>/fundamentum/elements/<name>.json} adds the element
 * {@code <ns>:<name>}, which works everywhere an element from code does ({@link Elements.Element#matches}, auras,
 * reactions, affinities, tooltips, {@code /fundamentum element}). Every field is optional:
 * <pre>{
 *   "parent": "fundamentum:fire",
 *   "damage_types": "#mypack:element/frost",
 *   "weapons": "#mypack:element/frost",
 *   "name": "element.mypack.frost",
 *   "color": "#9FE8FF",
 *   "damage_attribute": "fundamentum:ice_damage",
 *   "resistance_attribute": "fundamentum:ice_resistance"
 * }</pre>
 * The tags default to {@code #<ns>:element/<name>} (damage types and items), the name to the lang key
 * {@code element.<ns>.element.<name>}. Attributes are registered in code, so a datapack can't make new ones: it points
 * at any attribute that exists, or leaves them out (the element then has no damage or resistance stat, and creatures
 * resist it through {@code element_affinity} data instead). The shared damage type a datapack element brings is
 * {@code <ns>:<name>} when the datapack adds one ({@code data/<ns>/damage_type/<name>.json}). The table is synced, so
 * clients know the elements for names and tooltips.
 */
public final class DataElements {
    static final Logger LOG = LoggerFactory.getLogger("fundamentum");

    /** One parsed file; {@code color} is -1 for none. */
    public record Definition(ResourceLocation id, TagKey<DamageType> damageTypes, TagKey<Item> weapons, @Nullable ResourceLocation parent,
                             @Nullable String name, int color, @Nullable Supplier<Attribute> damage, @Nullable Supplier<Attribute> resistance) {}

    public static final DataTable<JsonObject> TABLE = DataTables.registerJson(new ResourceLocation("fundamentum", "elements"),
            "fundamentum/elements")
        .synced()
        .onLoad(t -> load(t.rawEntries()));

    /** Registers the table (Fundamentum's constructor). */
    public static void init() {
        TABLE.id();
    }

    private static void load(Map<ResourceLocation, JsonObject> files) {
        List<Definition> out = new ArrayList<>();
        files.forEach((id, json) -> {
            try {
                out.add(parse(id, json));
            } catch (RuntimeException e) {
                LOG.error("Fundamentum: data element {} is broken: {}", id, e.getMessage());
            }
        });
        Elements.replaceData(out);
    }

    public static Definition parse(ResourceLocation id, JsonObject json) {
        ResourceLocation own = new ResourceLocation(id.getNamespace(), "element/" + id.getPath());
        ResourceLocation types = tag(json, "damage_types", own);
        ResourceLocation weapons = tag(json, "weapons", own);
        ResourceLocation parent = json.has("parent") ? new ResourceLocation(GsonHelper.getAsString(json, "parent")) : null;
        String name = json.has("name") ? GsonHelper.getAsString(json, "name") : null;
        int color = json.has("color") ? color(json) : -1;
        return new Definition(id, TagKey.create(Registries.DAMAGE_TYPE, types), TagKey.create(Registries.ITEM, weapons), parent, name, color,
            attribute(json, "damage_attribute"), attribute(json, "resistance_attribute"));
    }

    private static ResourceLocation tag(JsonObject json, String key, ResourceLocation fallback) {
        if (!json.has(key)) {
            return fallback;
        }
        String text = GsonHelper.getAsString(json, key);
        return new ResourceLocation(text.startsWith("#") ? text.substring(1) : text);
    }

    private static int color(JsonObject json) {
        if (json.get("color").isJsonPrimitive() && json.getAsJsonPrimitive("color").isNumber()) {
            return json.get("color").getAsInt() & 0xFFFFFF;
        }
        String text = GsonHelper.getAsString(json, "color");
        return Integer.parseInt(text.startsWith("#") ? text.substring(1) : text, 16) & 0xFFFFFF;
    }

    private static @Nullable Supplier<Attribute> attribute(JsonObject json, String key) {
        if (!json.has(key)) {
            return null;
        }
        ResourceLocation id = new ResourceLocation(GsonHelper.getAsString(json, key));
        Attribute attribute = BuiltInRegistries.ATTRIBUTE.getOptional(id)
            .orElseThrow(() -> new IllegalArgumentException(key + " " + id + " is not an attribute"));
        return () -> attribute;
    }

    private DataElements() {}
}
