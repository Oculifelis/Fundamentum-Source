package com.fundamentum.portal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.crafting.CraftingHelper;
import org.jetbrains.annotations.Nullable;

/**
 * A {@code fundamentum:portal_crafting} recipe: an item thrown through a portal changes into {@code result} instead of
 * travelling. {@code portal} (a block, block list or #tag) limits it to portals made of those blocks, and
 * {@code dimension} to portals leading there; leave both out for any portal. With {@code keep_components} (1.20.1:
 * the thrown item's NBT; {@code keep_nbt} is read too) the result keeps the thrown item's enchantments, damage and
 * name, with the result's own NBT on top. Each thrown item becomes {@code result.count} results.
 * <pre>{@code
 * { "type": "fundamentum:portal_crafting", "ingredient": {"item": "mymod:dormant_pick"},
 *   "portal": "minecraft:nether_portal", "dimension": "minecraft:the_nether",
 *   "result": { "item": "mymod:awakened_pick" }, "keep_components": true }
 * }</pre>
 * The same files as 1.21 work too: the ingredient may be a plain item id or {@code "#tag"}, and the result may be a
 * plain item id or name its item with {@code "id"}.
 */
public final class PortalRecipe implements Recipe<Container> {
    /** The portal blocks a recipe accepts: blocks and block tags (1.20.1 reads recipes without registry access). */
    public record Blocks(List<ResourceLocation> blocks, List<TagKey<Block>> tags) {
        public boolean matches(BlockState state) {
            for (TagKey<Block> tag : this.tags) {
                if (state.is(tag)) {
                    return true;
                }
            }
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            return this.blocks.contains(id);
        }

        /** Every block it names, tags resolved (for JEI). */
        public List<Block> resolve() {
            List<Block> out = new ArrayList<>();
            for (ResourceLocation id : this.blocks) {
                BuiltInRegistries.BLOCK.getOptional(id).ifPresent(out::add);
            }
            for (TagKey<Block> tag : this.tags) {
                BuiltInRegistries.BLOCK.getTagOrEmpty(tag).forEach(h -> out.add(h.value()));
            }
            return out;
        }

        static Blocks parse(JsonElement json) {
            List<ResourceLocation> blocks = new ArrayList<>();
            List<TagKey<Block>> tags = new ArrayList<>();
            List<JsonElement> entries = new ArrayList<>();
            if (json.isJsonArray()) {
                json.getAsJsonArray().forEach(entries::add);
            } else {
                entries.add(json);
            }
            for (JsonElement e : entries) {
                String s = e.getAsString();
                if (s.startsWith("#")) {
                    tags.add(TagKey.create(Registries.BLOCK, new ResourceLocation(s.substring(1))));
                } else {
                    blocks.add(new ResourceLocation(s));
                }
            }
            return new Blocks(List.copyOf(blocks), List.copyOf(tags));
        }

        void write(FriendlyByteBuf buf) {
            buf.writeCollection(this.blocks, FriendlyByteBuf::writeResourceLocation);
            buf.writeCollection(this.tags, (b, t) -> b.writeResourceLocation(t.location()));
        }

        static Blocks read(FriendlyByteBuf buf) {
            List<ResourceLocation> blocks = buf.readList(FriendlyByteBuf::readResourceLocation);
            List<TagKey<Block>> tags = buf.readList(b -> TagKey.create(Registries.BLOCK, b.readResourceLocation()));
            return new Blocks(List.copyOf(blocks), List.copyOf(tags));
        }
    }

    public static final RecipeSerializer<PortalRecipe> SERIALIZER = new RecipeSerializer<>() {
        @Override
        public PortalRecipe fromJson(ResourceLocation id, JsonObject json) {
            JsonElement ing = json.get("ingredient");
            if (ing == null) {
                throw new JsonParseException("portal recipe " + id + ": no ingredient");
            }
            Ingredient ingredient;
            if (ing.isJsonPrimitive()) {
                // 1.21 writes a plain item id or "#tag"
                String s = ing.getAsString();
                JsonObject o = new JsonObject();
                o.addProperty(s.startsWith("#") ? "tag" : "item", s.startsWith("#") ? s.substring(1) : s);
                ingredient = Ingredient.fromJson(o, false);
            } else {
                ingredient = Ingredient.fromJson(ing, false);
            }
            JsonElement resEl = json.get("result");
            JsonObject res;
            if (resEl != null && resEl.isJsonPrimitive()) {
                // 1.21 may write the result as a plain item id
                res = new JsonObject();
                res.addProperty("item", resEl.getAsString());
            } else {
                res = GsonHelper.getAsJsonObject(json, "result");
            }
            if (!res.has("item") && res.has("id")) {
                JsonObject copy = res.deepCopy();
                copy.add("item", res.get("id"));
                res = copy;
            }
            ItemStack result = CraftingHelper.getItemStack(res, true, true);
            Optional<Blocks> portal = json.has("portal") ? Optional.of(Blocks.parse(json.get("portal"))) : Optional.empty();
            Optional<ResourceKey<Level>> dimension = json.has("dimension")
                ? Optional.of(ResourceKey.create(Registries.DIMENSION, new ResourceLocation(GsonHelper.getAsString(json, "dimension"))))
                : Optional.empty();
            boolean keep = GsonHelper.getAsBoolean(json, "keep_components", GsonHelper.getAsBoolean(json, "keep_nbt", false));
            return new PortalRecipe(id, ingredient, result, portal, dimension, keep, GsonHelper.getAsBoolean(json, "show_notification", true));
        }

        @Override
        public @Nullable PortalRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buf) {
            Ingredient ingredient = Ingredient.fromNetwork(buf);
            ItemStack result = buf.readItem();
            Optional<Blocks> portal = buf.readOptional(Blocks::read);
            Optional<ResourceKey<Level>> dimension = buf.readOptional(b -> b.readResourceKey(Registries.DIMENSION));
            boolean keep = buf.readBoolean();
            return new PortalRecipe(id, ingredient, result, portal, dimension, keep, buf.readBoolean());
        }

        @Override
        public void toNetwork(FriendlyByteBuf buf, PortalRecipe recipe) {
            recipe.ingredient.toNetwork(buf);
            buf.writeItem(recipe.result);
            buf.writeOptional(recipe.portal, (b, p) -> p.write(b));
            buf.writeOptional(recipe.dimension, FriendlyByteBuf::writeResourceKey);
            buf.writeBoolean(recipe.keepComponents);
            buf.writeBoolean(recipe.showNotification);
        }
    };

    private final ResourceLocation id;
    private final Ingredient ingredient;
    private final ItemStack result;
    private final Optional<Blocks> portal;
    private final Optional<ResourceKey<Level>> dimension;
    private final boolean keepComponents;
    private final boolean showNotification;

    public PortalRecipe(ResourceLocation id, Ingredient ingredient, ItemStack result, Optional<Blocks> portal,
            Optional<ResourceKey<Level>> dimension, boolean keepComponents) {
        this(id, ingredient, result, portal, dimension, keepComponents, true);
    }

    /** With {@code show_notification} (vanilla's recipe field: whether unlocking it shows a toast; default true). */
    public PortalRecipe(ResourceLocation id, Ingredient ingredient, ItemStack result, Optional<Blocks> portal,
            Optional<ResourceKey<Level>> dimension, boolean keepComponents, boolean showNotification) {
        this.showNotification = showNotification;
        this.id = id;
        this.ingredient = ingredient;
        this.result = result;
        this.portal = portal;
        this.dimension = dimension;
        this.keepComponents = keepComponents;
    }

    public Ingredient ingredient() { return ingredient; }
    /** The result as written. Copy it before changing it. */
    public ItemStack result() { return result; }
    public Optional<Blocks> portal() { return portal; }
    public Optional<ResourceKey<Level>> dimension() { return dimension; }
    public boolean keepComponents() { return keepComponents; }

    @Override
    public boolean matches(Container input, Level level) { return ingredient.test(input.getItem(0)); }

    /** One result for one thrown item; {@link PortalCrafting} multiplies it by the stack. */
    @Override
    public ItemStack assemble(Container input, RegistryAccess registries) { return craft(input.getItem(0), result.getCount()); }

    /** {@code count} results made from {@code thrown}. */
    public ItemStack craft(ItemStack thrown, int count) {
        if (!keepComponents) return result.copyWithCount(count);
        ItemStack out = new ItemStack(result.getItem(), count);
        CompoundTag tag = thrown.getTag() == null ? null : thrown.getTag().copy();
        if (result.getTag() != null) {
            CompoundTag own = result.getTag().copy();
            // 1.20.1 gives every damageable stack a Damage tag; an undamaged result must not wipe the thrown item's damage
            if (own.getInt("Damage") == 0) own.remove("Damage");
            tag = tag == null ? own : tag.merge(own);
        }
        out.setTag(tag);
        return out;
    }

    @Override public boolean canCraftInDimensions(int width, int height) { return true; }
    @Override public ItemStack getResultItem(RegistryAccess registries) { return result; }
    @Override public NonNullList<Ingredient> getIngredients() { return NonNullList.of(Ingredient.EMPTY, ingredient); }
    /** Filed under {@link PortalRecipeBook}'s category, which is in no book tab (as on 26). */
    @Override public boolean showNotification() { return showNotification; }
    @Override public ResourceLocation getId() { return id; }
    @Override public RecipeSerializer<PortalRecipe> getSerializer() { return SERIALIZER; }
    @Override public RecipeType<PortalRecipe> getType() { return PortalCrafting.TYPE.get(); }
}
