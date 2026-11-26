package com.fundamentum.guide.book;

import com.fundamentum.port.FId;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/** A guide book ({@link GuideBooks}): named for its book, and used it opens that book on the client. Per version. */
public class GuideBookItem extends Item {
    public GuideBookItem(Properties props) {
        super(props);
    }

    @Override
    public Component getName(ItemStack stack) {
        BookData.Book book = book(stack);
        if (book == null) return super.getName(stack);
        return Component.translatableWithFallback(book.title(), book.title());
    }

    static BookData.Book book(ItemStack stack) {
        FId id = GuideBooks.BOOK.get(stack);
        return id == null ? null : GuideBooks.library().books().get(id);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        FId id = GuideBooks.BOOK.get(held);
        if (id == null) return InteractionResultHolder.pass(held);
        if (level.isClientSide()) GuideBooks.open(id);
        return InteractionResultHolder.sidedSuccess(held, level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        BookData.Book book = book(stack);
        if (book != null && !book.subtitle().isEmpty()) {
            tooltip.add(Component.translatableWithFallback(book.subtitle(), book.subtitle()).withStyle(ChatFormatting.GRAY));
        }
    }
}
