package com.fundamentum.compat.jei;

import com.fundamentum.portal.PortalCrafting;
import com.fundamentum.portal.PortalRecipe;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.drawable.IDrawableStatic;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.types.IRecipeHolderType;
import mezz.jei.api.recipe.types.IRecipeType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;

/** JEI category for {@code fundamentum:portal_crafting}: the thrown item, the result, and which portal it takes. */
final class PortalCategory implements IRecipeCategory<RecipeHolder<PortalRecipe>> {
    static final IRecipeHolderType<PortalRecipe> TYPE = IRecipeType.create(PortalCrafting.TYPE.get());
    private static final int WIDTH = 150, HEIGHT = 50, TEXT = 0xFF404040;

    private final IDrawable icon;
    private final IDrawableStatic arrow;

    PortalCategory(IGuiHelper gui) {
        this.icon = gui.createDrawableItemLike(Items.OBSIDIAN);
        this.arrow = gui.getRecipeArrow();
    }

    @Override public IRecipeHolderType<PortalRecipe> getRecipeType() { return TYPE; }
    @Override public Component getTitle() { return Component.translatable("jei.fundamentum.portal_crafting"); }
    @Override public IDrawable getIcon() { return icon; }
    @Override public int getWidth() { return WIDTH; }
    @Override public int getHeight() { return HEIGHT; }

    @Override
    public void setRecipe(IRecipeLayoutBuilder b, RecipeHolder<PortalRecipe> holder, IFocusGroup focuses) {
        PortalRecipe r = holder.value();
        b.addSlot(RecipeIngredientRole.INPUT, 30, 4).setStandardSlotBackground().add(r.ingredient());
        b.addSlot(RecipeIngredientRole.OUTPUT, 102, 4).setOutputSlotBackground().add(r.result());
    }

    @Override
    public void draw(RecipeHolder<PortalRecipe> holder, IRecipeSlotsView slots, GuiGraphicsExtractor g, double mx, double my) {
        arrow.draw(g, 63, 4);
        PortalRecipe r = holder.value();
        Component portal = r.portal().flatMap(set -> set.stream().findFirst())
                .<Component>map(block -> block.value().getName())
                .orElse(Component.translatable("jei.fundamentum.portal_crafting.any_portal"));
        Component line = r.dimension()
                .map(d -> Component.translatable("jei.fundamentum.portal_crafting.rule_to", portal, dimension(d.identifier())))
                .orElse(Component.translatable("jei.fundamentum.portal_crafting.rule", portal));
        if (r.keepComponents()) line = line.copy().append(" ").append(Component.translatable("jei.fundamentum.portal_crafting.keeps"));
        Font f = Minecraft.getInstance().font;
        int y = 26;
        for (FormattedCharSequence s : f.split(line, WIDTH)) {
            g.text(f, s, (WIDTH - f.width(s)) / 2, y, TEXT, false);
            y += 10;
        }
    }

    /** "The Nether" for minecraft:the_nether; other dimensions read fundamentum.dimension.<ns>.<path>, else their path. */
    private static Component dimension(Identifier id) {
        String path = id.getPath().replace('_', ' ');
        String plain = path.isEmpty() ? id.toString() : Character.toUpperCase(path.charAt(0)) + path.substring(1);
        return Component.translatableWithFallback("fundamentum.dimension." + id.getNamespace() + "." + id.getPath(), plain);
    }
}
