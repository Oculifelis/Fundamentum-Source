package com.fundamentum.quest.client;

import com.fundamentum.guide.client.GuideSurface;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The screen the quest journal opens in; the journal itself is drawn by {@link QuestView}. Client only, per version. */
public final class QuestScreen extends Screen {
    private final QuestView view;

    public QuestScreen(QuestScreens.Def def, String focus) {
        super(Component.literal(QuestView.tr(def.title())));
        this.view = new QuestView(def, focus);
    }

    public void focus(String quest) {
        this.view.focus(quest);
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
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == InputConstants.KEY_ESCAPE || com.fundamentum.client.Keys.QUESTS.matches(keyCode, scanCode)) {
            this.view.close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
