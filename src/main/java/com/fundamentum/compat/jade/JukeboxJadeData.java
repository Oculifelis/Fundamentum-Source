package com.fundamentum.compat.jade;

import com.fundamentum.Fundamentum;
import com.fundamentum.music.JukeboxProgress;
import com.fundamentum.music.JukeboxProgress.Track;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.StreamServerDataProvider;

/** Server half: the jukebox's song and the server's tick count, read from its song player. */
public final class JukeboxJadeData implements StreamServerDataProvider<BlockAccessor, Track> {
    public static final JukeboxJadeData INSTANCE = new JukeboxJadeData();
    public static final Identifier UID = Identifier.fromNamespaceAndPath(Fundamentum.MODID, "jukebox_progress");

    /** Jade asks this on the client, whose jukebox never knows its song: go by the block state's has_record. */
    @Override
    public boolean shouldRequestData(BlockAccessor accessor) {
        return accessor.getBlockEntity() instanceof JukeboxBlockEntity
            && accessor.getBlockState().getValue(JukeboxBlock.HAS_RECORD);
    }

    @Override
    public Track streamData(BlockAccessor accessor) {
        return accessor.getBlockEntity() instanceof JukeboxBlockEntity j ? JukeboxProgress.of(j).orElse(null) : null;
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, Track> streamCodec() {
        return JukeboxProgress.TRACK_STREAM_CODEC;
    }

    @Override
    public Identifier getUid() { return UID; }
}
