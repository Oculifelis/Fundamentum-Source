package com.fundamentum.portal;

import com.fundamentum.recipeview.RecipeViews;
import com.fundamentum.recipeview.ViewCategory;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * The recipe viewer page of {@code fundamentum:portal_crafting} (JEI, EMI, REI): the thrown item, the result, and which
 * portal it takes. Reads the client's copy of the recipes, and asks the viewers to refresh when a newer copy arrives.
 */
public final class PortalViews {
    private PortalViews() {}

    public static void init() {
        ViewCategory<RecipeHolder<PortalRecipe>> c = RecipeViews.category("fundamentum:portal_crafting");
        c.title(Component.translatable("jei.fundamentum.portal_crafting"))
                .icon(() -> new ItemStack(Items.OBSIDIAN))
                .size(150, 50)
                .arrow(62, 4, 0)
                .catalysts(() -> List.of(new ItemStack(Items.OBSIDIAN), new ItemStack(Items.FLINT_AND_STEEL), new ItemStack(Items.ENDER_EYE)));
        int in = c.input(29, 3);
        int out = c.output(101, 3);
        c.recipes(PortalCraftingClient::recipes)
                .key(h -> h.id().identifier().toString().replace(':', '/'))
                .fill((holder, r) -> {
                    PortalRecipe p = holder.value();
                    r.items(in, p.ingredient().items().map(Holder::value).map(ItemStack::new).toList());
                    r.item(out, p.result().create());
                    Component portal = p.portal().flatMap(set -> set.stream().findFirst())
                            .<Component>map(block -> block.value().getName())
                            .orElse(Component.translatable("jei.fundamentum.portal_crafting.any_portal"));
                    r.text(p.dimension()
                            .map(d -> Component.translatable("jei.fundamentum.portal_crafting.rule_to", portal, dimension(d.identifier())))
                            .orElse(Component.translatable("jei.fundamentum.portal_crafting.rule", portal)), 26);
                    if (p.keepComponents()) r.text(Component.translatable("jei.fundamentum.portal_crafting.keeps"), 37);
                });
        PortalCraftingClient.listen(RecipeViews::refresh);
    }

    /** "The Nether" for minecraft:the_nether; other dimensions read fundamentum.dimension.<ns>.<path>, else their path. */
    private static Component dimension(Identifier id) {
        String path = id.getPath().replace('_', ' ');
        String plain = path.isEmpty() ? id.toString() : Character.toUpperCase(path.charAt(0)) + path.substring(1);
        return Component.translatableWithFallback("fundamentum.dimension." + id.getNamespace() + "." + id.getPath(), plain);
    }
}
