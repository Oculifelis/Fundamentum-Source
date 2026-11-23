package com.fundamentum.armorset;

import com.fundamentum.attribute.AttributeBonus;
import com.fundamentum.item.ItemMatcher;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * One armour set, from {@code data/<ns>/fundamentum/armor_sets/<name>.json}:
 * <pre>{
 *   "name": "armor_set.mypack.ironclad",
 *   "pieces": {"head": "minecraft:iron_helmet", "chest": "minecraft:iron_chestplate",
 *              "legs": "#mypack:ironclad_legs", "feet": ["minecraft:iron_boots", "minecraft:chainmail_boots"]},
 *   "curios": ["mypack:ironclad_ring"],
 *   "tiers": [
 *     {"pieces": 2, "attributes": [{"attribute": "minecraft:armor", "amount": 2}]},
 *     {"pieces": 4, "flags": ["ironclad"], "effects": [{"effect": "minecraft:resistance", "amplifier": 0}],
 *      "rewards": [{"type": "mymod:aura", "radius": 4}]}
 *   ]
 * }</pre>
 * {@code pieces} maps equipment slots ({@code head}, {@code chest}, {@code legs}, {@code feet}, also {@code body},
 * {@code mainhand}, {@code offhand}) to item ids or {@code #tags}; each {@code curios} entry counts as one more piece when any
 * worn Curios item matches it. Tiers add up: at 4 pieces both tiers above are active. A tier's {@code effects} may be
 * Fundamentum status effects or ordinary MobEffects, kept up while the tier holds. {@code name} is a lang key (optional;
 * without one the file name is shown).
 */
public record ArmorSetDefinition(Identifier id, Component name, Map<EquipmentSlot, ItemMatcher> pieces, List<ItemMatcher> curios,
                                 List<Tier> tiers) {

    /** One effect a tier keeps up. */
    public record EffectGrant(Identifier effect, int amplifier) {}

    /** One tier: active from {@code pieces} worn pieces. */
    public record Tier(int index, int pieces, List<AttributeBonus> attributes, List<EffectGrant> effects, Set<String> flags,
                       List<SetReward> rewards) {}

    public int maxPieces() {
        return this.pieces.size() + this.curios.size();
    }

    /** Whether the stack belongs to this set, in an armour slot or as a Curios piece. */
    public boolean isMember(ItemStack stack) {
        for (ItemMatcher m : this.pieces.values()) {
            if (m.test(stack)) {
                return true;
            }
        }
        for (ItemMatcher m : this.curios) {
            if (m.test(stack)) {
                return true;
            }
        }
        return false;
    }

    public static ArmorSetDefinition fromJson(Identifier id, JsonObject json) {
        Map<EquipmentSlot, ItemMatcher> pieces = new EnumMap<>(EquipmentSlot.class);
        for (Map.Entry<String, JsonElement> e : GsonHelper.getAsJsonObject(json, "pieces", new JsonObject()).entrySet()) {
            pieces.put(EquipmentSlot.byName(e.getKey().toLowerCase(Locale.ROOT)), ItemMatcher.parse(e.getValue()));
        }
        List<ItemMatcher> curios = new ArrayList<>();
        if (json.has("curios")) {
            json.getAsJsonArray("curios").forEach(e -> curios.add(ItemMatcher.parse(e)));
        }
        List<Tier> tiers = new ArrayList<>();
        for (JsonElement t : GsonHelper.getAsJsonArray(json, "tiers")) {
            JsonObject o = t.getAsJsonObject();
            List<EffectGrant> effects = new ArrayList<>();
            if (o.has("effects")) {
                for (JsonElement e : o.getAsJsonArray("effects")) {
                    if (e.isJsonPrimitive()) {
                        effects.add(new EffectGrant(Identifier.parse(e.getAsString()), 0));
                    } else {
                        JsonObject eo = e.getAsJsonObject();
                        effects.add(new EffectGrant(Identifier.parse(GsonHelper.getAsString(eo, "effect")), GsonHelper.getAsInt(eo, "amplifier", 0)));
                    }
                }
            }
            Set<String> flags = new LinkedHashSet<>();
            if (o.has("flags")) {
                o.getAsJsonArray("flags").forEach(f -> flags.add(f.getAsString()));
            }
            List<SetReward> rewards = new ArrayList<>();
            if (o.has("rewards")) {
                o.getAsJsonArray("rewards").forEach(r -> rewards.add(SetRewards.parse(r.getAsJsonObject())));
            }
            tiers.add(new Tier(0, GsonHelper.getAsInt(o, "pieces"), AttributeBonus.parseList(o.get("attributes")), List.copyOf(effects),
                Set.copyOf(flags), List.copyOf(rewards)));
        }
        tiers.sort(Comparator.comparingInt(Tier::pieces));
        List<Tier> indexed = new ArrayList<>();
        for (int i = 0; i < tiers.size(); i++) {
            Tier t = tiers.get(i);
            indexed.add(new Tier(i, t.pieces(), t.attributes(), t.effects(), t.flags(), t.rewards()));
        }
        String fallback = prettify(id.getPath());
        Component name = json.has("name") ? Component.translatableWithFallback(GsonHelper.getAsString(json, "name"), fallback)
            : Component.translatableWithFallback("armor_set." + id.getNamespace() + "." + id.getPath().replace('/', '.'), fallback);
        return new ArmorSetDefinition(id, name, Map.copyOf(pieces), List.copyOf(curios), List.copyOf(indexed));
    }

    private static String prettify(String path) {
        String last = path.substring(path.lastIndexOf('/') + 1);
        StringBuilder out = new StringBuilder();
        for (String word : last.split("_")) {
            if (!word.isEmpty()) {
                if (!out.isEmpty()) {
                    out.append(' ');
                }
                out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return out.toString();
    }
}
