package com.fundamentum.multiblock.client;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.jspecify.annotations.Nullable;

/**
 * A multiblock drawn as real block models inside a GUI box, turned {@code yaw} degrees around and tilted {@code pitch}
 * degrees down. {@code scale} is GUI pixels per block. Two states are equal only for the same scene, angles and box, so
 * the picture is drawn again only when one of those changes.
 */
public record GuiMultiblockRenderState(MultiblockScene.Scene scene, float yaw, float pitch, int x0, int y0, int x1, int y1,
    float scale, @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds) implements PictureInPictureRenderState {

    public GuiMultiblockRenderState(MultiblockScene.Scene scene, float yaw, float pitch, int x0, int y0, int x1, int y1, float scale,
        @Nullable ScreenRectangle scissorArea) {
        this(scene, yaw, pitch, x0, y0, x1, y1, scale, scissorArea, PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissorArea));
    }
}
