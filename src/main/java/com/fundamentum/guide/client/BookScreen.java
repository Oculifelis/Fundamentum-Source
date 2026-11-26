package com.fundamentum.guide.client;

import com.fundamentum.guide.book.BookData;
import com.fundamentum.guide.book.GuideBooks;
import com.fundamentum.port.FId;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** The screen a guide book opens in; the book itself is drawn by {@link BookView}. Client only, per version. */
public final class BookScreen extends Screen {
    private final BookView view;

    private BookScreen(BookData.Book book) {
        super(Component.literal(book.title()));
        this.view = new BookView(book, GuideBooks.library().style(book));
    }

    /** The screen for {@code id}, or null when no such book is loaded. */
    public static BookScreen of(FId id) {
        BookData.Book book = GuideBooks.library().books().get(id);
        if (book == null || GuideBooks.library().style(book) == null) return null;
        return new BookScreen(book);
    }

    @Override
    protected void init() {
        this.view.resize(this.width, this.height);
        addRenderableWidget(new GuideSurface(this.width, this.height, this.view));
    }

    @Override
    public void tick() {
        if (this.view.finished() && this.minecraft != null) this.minecraft.setScreen(null);
    }

    @Override
    public void onClose() {
        this.view.close();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        switch (event.key()) {
            case InputConstants.KEY_ESCAPE -> this.view.close();
            case InputConstants.KEY_BACKSPACE -> this.view.contents();
            case InputConstants.KEY_LEFT -> this.view.previous();
            case InputConstants.KEY_RIGHT -> this.view.next();
            default -> {
                return super.keyPressed(event);
            }
        }
        return true;
    }

    @Override
    public void removed() {
        BookProgress.save();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
