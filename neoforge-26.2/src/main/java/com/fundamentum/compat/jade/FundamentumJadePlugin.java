package com.fundamentum.compat.jade;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/**
 * Optional Jade integration: a playing jukebox shows its song with a progress bar, a moving scrubber and
 * "1:12 / 3:05" (toggle: Jade's plugin settings, Fundamentum, "Jukebox song progress"), and a shelf in the
 * {@code fundamentum:disc_shelves} tag lists its discs by slot ("Disc shelf contents"). Jade finds this class by its
 * annotation; nothing else refers to it, so Fundamentum runs the same without Jade.
 */
@WailaPlugin
public final class FundamentumJadePlugin implements IWailaPlugin {
    @Override
    public void register(IWailaCommonRegistration r) {
        r.registerBlockDataProvider(JukeboxJadeData.INSTANCE, JukeboxBlockEntity.class);
        r.registerBlockDataProvider(DiscShelfJade.INSTANCE, BlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration r) {
        r.registerBlockComponent(JukeboxJadeClient.INSTANCE, JukeboxBlock.class);
        r.registerBlockComponent(DiscShelfJade.INSTANCE, Block.class);
    }
}
