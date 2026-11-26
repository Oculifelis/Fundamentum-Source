package com.fundamentum.guide.book;

import com.fundamentum.Fundamentum;
import com.fundamentum.port.FId;
import com.fundamentum.port.FReg;
import com.fundamentum.port.FStackData;
import com.fundamentum.port.FStreamCodec;
import java.util.function.Consumer;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

/**
 * Guide books in game: the {@code fundamentum:guide_book} item, which book a stack opens, the books the client has
 * loaded, and the hook the client sets to open one.
 */
public final class GuideBooks {
    private GuideBooks() { }

    /** The book a guide book stack opens. */
    public static final FStackData<FId> BOOK = FStackData.of(FId.of(Fundamentum.MODID, "guide_book"), FId.CODEC, FStreamCodec.ID);

    public static final FReg.Ref<GuideBookItem> ITEM = FReg.item(FId.of(Fundamentum.MODID, "guide_book"), GuideBookItem::new,
        new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON));

    private static volatile BookResources.Library library = BookResources.Library.EMPTY;
    private static Consumer<FId> opener = id -> { };

    public static void init(IEventBus modBus) {
        modBus.addListener(BuildCreativeModeTabContentsEvent.class, GuideBooks::tabs);
    }

    public static BookResources.Library library() {
        return library;
    }

    public static void set(BookResources.Library lib) {
        library = lib;
    }

    public static void setOpener(Consumer<FId> open) {
        opener = open;
    }

    public static void open(FId book) {
        opener.accept(book);
    }

    /** A guide book that opens {@code book}. */
    public static ItemStack stack(FId book) {
        ItemStack s = new ItemStack(ITEM.get());
        BOOK.set(s, book);
        return s;
    }

    private static void tabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() != CreativeModeTabs.TOOLS_AND_UTILITIES) return;
        for (FId book : library.books().keySet()) event.accept(stack(book));
    }
}
