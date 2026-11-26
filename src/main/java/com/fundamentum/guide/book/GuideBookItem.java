package com.fundamentum.guide.book;

import com.fundamentum.port.FId;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
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
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        FId id = GuideBooks.BOOK.get(player.getItemInHand(hand));
        if (id == null) return InteractionResult.PASS;
        if (level.isClientSide()) GuideBooks.open(id);
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
        BookData.Book book = book(stack);
        if (book != null && !book.subtitle().isEmpty()) {
            tooltip.accept(Component.translatableWithFallback(book.subtitle(), book.subtitle()).withStyle(ChatFormatting.GRAY));
        }
    }
}
