package com.fundamentum.music;

import java.lang.reflect.Field;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.RecordItem;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

/**
 * How far a jukebox is through its song: the song's name, total length and elapsed time, for any disc (vanilla or
 * modded) because the length comes from the disc itself ({@link RecordItem#getLengthInTicks}; 1.20.1 has no song registry).
 * <p>
 * Server side: {@link #of(JukeboxBlockEntity)} reads the jukebox's own tick counters (the client copy of a jukebox block
 * entity never knows its song, vanilla does not sync it). Client side: {@code music.client.JukeboxTracker} keeps every
 * jukebox the player can hear, from the song start and stop events vanilla already sends, and
 * {@code music.client.JukeboxBar} draws a {@link Track} as name, bar, scrubber and "1:12 / 3:05".
 */
public final class JukeboxProgress {
    private JukeboxProgress() { }

    /** A jukebox song in progress. {@code elapsedTicks} may be fractional (client side, between ticks). */
    public record Track(BlockPos pos, RecordItem record, double elapsedTicks) {
        /** The song's name as the disc shows it, e.g. "C418 - cat". */
        public Component name() { return record.getDisplayName(); }

        public int lengthTicks() { return JukeboxProgress.lengthTicks(record); }

        public float lengthSeconds() { return lengthTicks() / 20.0F; }

        public double elapsedSeconds() { return Math.min(elapsedTicks, lengthTicks()) / 20.0; }

        /** 0 at the start, 1 at the end (clamped). */
        public float progress() {
            int len = lengthTicks();
            return len <= 0 ? 1.0F : (float) Math.max(0.0, Math.min(1.0, elapsedTicks / len));
        }

        /** "1:12 / 3:05". */
        public String timeText() { return JukeboxProgress.timeText(elapsedSeconds(), lengthSeconds()); }

        /** How far away the song can still be heard, in blocks (64 for vanilla discs). */
        public float hearingRange() { return JukeboxProgress.hearingRange(record); }

        public boolean finished() { return elapsedTicks >= lengthTicks(); }
    }

    /** Song and server tick count, as sent to clients (1.20.1: a buffer write and read, no stream codecs). */
    public static void write(FriendlyByteBuf buf, Track track) {
        buf.writeBlockPos(track.pos());
        buf.writeResourceLocation(BuiltInRegistries.ITEM.getKey(track.record()));
        buf.writeDouble(track.elapsedTicks());
    }

    /** Null when the disc is unknown here. */
    public static Track read(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        ResourceLocation id = buf.readResourceLocation();
        double ticks = buf.readDouble();
        return BuiltInRegistries.ITEM.get(id) instanceof RecordItem record ? new Track(pos, record, ticks) : null;
    }

    /** The same as a tag (how Jade 11 sends server data). */
    public static CompoundTag toTag(Track track) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("pos", track.pos().asLong());
        tag.putString("record", BuiltInRegistries.ITEM.getKey(track.record()).toString());
        tag.putDouble("ticks", track.elapsedTicks());
        return tag;
    }

    public static Optional<Track> fromTag(CompoundTag tag) {
        ResourceLocation id = ResourceLocation.tryParse(tag.getString("record"));
        if (id == null || !(BuiltInRegistries.ITEM.get(id) instanceof RecordItem record)) {
            return Optional.empty();
        }
        return Optional.of(new Track(BlockPos.of(tag.getLong("pos")), record, tag.getDouble("ticks")));
    }

    private static final Field TICK_COUNT = ObfuscationReflectionHelper.findField(JukeboxBlockEntity.class, "f_238695_");
    private static final Field STARTED = ObfuscationReflectionHelper.findField(JukeboxBlockEntity.class, "f_238572_");

    /** Server side: the jukebox's song and how far it is, or empty when nothing plays. */
    public static Optional<Track> of(JukeboxBlockEntity jukebox) {
        if (!jukebox.isRecordPlaying() || jukebox.getLevel() == null) {
            return Optional.empty();
        }
        return record(jukebox.getFirstItem()).map(record -> {
            long elapsed;
            try {
                elapsed = TICK_COUNT.getLong(jukebox) - STARTED.getLong(jukebox);
            } catch (IllegalAccessException e) {
                elapsed = 0;
            }
            return new Track(jukebox.getBlockPos(), record, elapsed);
        });
    }

    /** The disc a stack is, if it is one. */
    public static Optional<RecordItem> record(ItemStack stack) {
        return stack.getItem() instanceof RecordItem record ? Optional.of(record) : Optional.empty();
    }

    public static int lengthTicks(RecordItem record) {
        return record.getLengthInTicks();
    }

    /** Range a jukebox plays the song at: vanilla plays discs at volume 4, so 64 blocks unless the sound fixes one. */
    public static float hearingRange(RecordItem record) {
        return record.getSound().getRange(4.0F);
    }

    /** "m:ss" (or "h:mm:ss" for an hour or more). */
    public static String clock(double seconds) {
        long s = (long) Math.max(0, Math.floor(seconds));
        if (s >= 3600) {
            return String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
        }
        return String.format("%d:%02d", s / 60, s % 60);
    }

    /** "1:12 / 3:05". */
    public static String timeText(double elapsedSeconds, double lengthSeconds) {
        return clock(Math.min(elapsedSeconds, lengthSeconds)) + " / " + clock(Math.ceil(lengthSeconds));
    }
}
