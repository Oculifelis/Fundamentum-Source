package com.fundamentum.relic;

import com.fundamentum.port.FId;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import java.util.function.Consumer;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import org.jetbrains.annotations.Nullable;

/**
 * The one relic item, {@code fundamentum:relic}. Which relic a stack is lives in its {@link Relics#RELIC} data; the stack
 * takes that relic's name and places it on the base and size its file names. A stack without one (it cannot be had in
 * survival) shows "Unknown relic" and places nothing.
 */
public class RelicItem extends BlockItem {
    public RelicItem(Block block, Properties props) {
        super(block, props);
    }

    @Override
    protected @Nullable BlockState getPlacementState(BlockPlaceContext ctx) {
        ItemStack stack = ctx.getItemInHand();
        FId id = Relics.RELIC.has(stack) ? Relics.RELIC.get(stack) : null;
        RelicDef def = id == null ? null : Relics.def(id.toString());
        if (def == null) {
            if (ctx.getPlayer() != null && ctx.getLevel().isClientSide()) {
                ctx.getPlayer().displayClientMessage(Component.translatable("item.fundamentum.relic.unknown"), true);
            }
            return null;
        }
        RelicBlock anchor = RelicBases.anchorFor(def);
        if (anchor == null) {
            return null;
        }
        BlockState state = anchor.getStateForPlacement(ctx);
        return state != null && this.canPlace(ctx, state) ? state : null;
    }

    @Override
    public Component getName(ItemStack stack) {
        return Relics.RELIC.has(stack) ? Relics.name(Relics.RELIC.get(stack)) : Component.translatable("item.fundamentum.relic.unknown");
    }

    /** The relic's own rarity (1.20.1 has no per-stack rarity data, so the item reads it from the relic's file). */
    @Override
    public Rarity getRarity(ItemStack stack) {
        RelicDef def = Relics.RELIC.has(stack) ? Relics.def(Relics.RELIC.get(stack).toString()) : null;
        return def == null ? super.getRarity(stack) : Relics.rarity(def.rarity);
    }

    /** The relic's own stack size, from its file. */
    @Override
    public int getMaxStackSize(ItemStack stack) {
        RelicDef def = Relics.RELIC.has(stack) ? Relics.TABLE.get(Relics.RELIC.get(stack).vanilla()) : null;
        return def == null ? super.getMaxStackSize(stack) : Math.max(1, Math.min(64, def.stack));
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(com.fundamentum.relic.client.RelicItemRenderer.EXTENSIONS);
    }
}
