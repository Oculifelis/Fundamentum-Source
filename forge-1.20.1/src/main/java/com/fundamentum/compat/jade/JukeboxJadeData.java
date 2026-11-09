package com.fundamentum.compat.jade;

import com.fundamentum.Fundamentum;
import com.fundamentum.music.JukeboxProgress;
import com.fundamentum.music.JukeboxProgress.Track;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IServerDataProvider;

/** Server half: the jukebox's song and the server's tick count, read from its tick counters (Jade 11: a tag). */
public final class JukeboxJadeData implements IServerDataProvider<BlockAccessor> {
    public static final JukeboxJadeData INSTANCE = new JukeboxJadeData();
    public static final ResourceLocation UID = new ResourceLocation(Fundamentum.MODID, "jukebox_progress");
    private static final String KEY = "fundamentum_jukebox";

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        if (accessor.getBlockEntity() instanceof JukeboxBlockEntity j) {
            JukeboxProgress.of(j).ifPresent(t -> data.put(KEY, JukeboxProgress.toTag(t)));
        }
    }

    /** Client side: the copy the server sent, if any. */
    public Optional<Track> decodeFromData(BlockAccessor accessor) {
        CompoundTag data = accessor.getServerData();
        return data.contains(KEY) ? JukeboxProgress.fromTag(data.getCompound(KEY)) : Optional.empty();
    }

    @Override
    public ResourceLocation getUid() { return UID; }
}
