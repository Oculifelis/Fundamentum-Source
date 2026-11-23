package com.fundamentum.portal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeBookCategory;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * A {@code fundamentum:portal_crafting} recipe: an item thrown through a portal changes into {@code result} instead of
 * travelling. {@code portal} (a block, block list or #tag) limits it to portals made of those blocks, and
 * {@code dimension} to portals leading there; leave both out for any portal. With {@code keep_components} the result
 * keeps the thrown item's components (enchantments, damage, name), with the result's own on top. Each thrown item
 * becomes {@code result.count} results.
 * <pre>{@code
 * { "type": "fundamentum:portal_crafting", "ingredient": "mymod:dormant_pick",
 *   "portal": "minecraft:nether_portal", "dimension": "minecraft:the_nether",
 *   "result": { "id": "mymod:awakened_pick" }, "keep_components": true }
 * }</pre>
 */
public final class PortalRecipe implements Recipe<SingleRecipeInput> {
    public static final MapCodec<PortalRecipe> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Recipe.CommonInfo.MAP_CODEC.forGetter(r -> r.info),
            Ingredient.CODEC.fieldOf("ingredient").forGetter(PortalRecipe::ingredient),
            ItemStackTemplate.CODEC.fieldOf("result").forGetter(PortalRecipe::result),
            RegistryCodecs.homogeneousList(Registries.BLOCK).optionalFieldOf("portal").forGetter(PortalRecipe::portal),
            ResourceKey.codec(Registries.DIMENSION).optionalFieldOf("dimension").forGetter(PortalRecipe::dimension),
            Codec.BOOL.optionalFieldOf("keep_components", false).forGetter(PortalRecipe::keepComponents)
    ).apply(i, PortalRecipe::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, PortalRecipe> STREAM_CODEC = StreamCodec.composite(
            Recipe.CommonInfo.STREAM_CODEC, r -> r.info,
            Ingredient.CONTENTS_STREAM_CODEC, PortalRecipe::ingredient,
            ItemStackTemplate.STREAM_CODEC, PortalRecipe::result,
            ByteBufCodecs.optional(ByteBufCodecs.holderSet(Registries.BLOCK)), PortalRecipe::portal,
            ByteBufCodecs.optional(ResourceKey.streamCodec(Registries.DIMENSION)), PortalRecipe::dimension,
            ByteBufCodecs.BOOL, PortalRecipe::keepComponents,
            PortalRecipe::new);

    public static final RecipeSerializer<PortalRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    private final Recipe.CommonInfo info;
    private final Ingredient ingredient;
    private final ItemStackTemplate result;
    private final Optional<HolderSet<Block>> portal;
    private final Optional<ResourceKey<Level>> dimension;
    private final boolean keepComponents;
    private PlacementInfo placement;

    public PortalRecipe(Recipe.CommonInfo info, Ingredient ingredient, ItemStackTemplate result, Optional<HolderSet<Block>> portal,
            Optional<ResourceKey<Level>> dimension, boolean keepComponents) {
        this.info = info;
        this.ingredient = ingredient;
        this.result = result;
        this.portal = portal;
        this.dimension = dimension;
        this.keepComponents = keepComponents;
    }

    public Ingredient ingredient() { return ingredient; }
    public ItemStackTemplate result() { return result; }
    public Optional<HolderSet<Block>> portal() { return portal; }
    public Optional<ResourceKey<Level>> dimension() { return dimension; }
    public boolean keepComponents() { return keepComponents; }

    @Override
    public boolean matches(SingleRecipeInput input, Level level) { return ingredient.test(input.item()); }

    /** One result for one thrown item; {@link PortalCrafting} multiplies it by the stack. */
    @Override
    public ItemStack assemble(SingleRecipeInput input) { return craft(input.item(), result.count()); }

    /** {@code count} results made from {@code thrown}. */
    public ItemStack craft(ItemStack thrown, int count) {
        if (!keepComponents) return result.apply(count, net.minecraft.core.component.DataComponentPatch.EMPTY);
        ItemStack out = thrown.transmuteCopy(result.item().value(), count);
        out.applyComponents(result.components());
        return out;
    }

    @Override public boolean showNotification() { return info.showNotification(); }
    @Override public String group() { return ""; }
    @Override public RecipeSerializer<PortalRecipe> getSerializer() { return SERIALIZER; }
    @Override public RecipeType<PortalRecipe> getType() { return PortalCrafting.TYPE.get(); }
    @Override public RecipeBookCategory recipeBookCategory() { return PortalCrafting.BOOK.get(); }

    @Override
    public PlacementInfo placementInfo() {
        if (placement == null) placement = PlacementInfo.create(ingredient);
        return placement;
    }
}
