package com.fundamentum.music;

import com.fundamentum.port.FStreamCodec;
import java.util.ArrayList;
import java.util.List;

/**
 * What the server tells one player about their music: the boss track (or "" for none), the boss type, the fade for
 * the latest boss change, whether they are in combat, and the structure ids and {@code #tags} they stand in (only the
 * ones music rules name). Sent only when it changes.
 */
public record MusicState(String bossSound, String bossType, int bossFade, boolean combat, List<String> structures) {
    public static final MusicState NONE = new MusicState("", "", 40, false, List.of());

    public static final FStreamCodec<MusicState> CODEC = FStreamCodec.of((buf, s) -> {
        buf.writeUtf(s.bossSound);
        buf.writeUtf(s.bossType);
        buf.writeVarInt(s.bossFade);
        buf.writeBoolean(s.combat);
        buf.writeVarInt(s.structures.size());
        for (String x : s.structures) buf.writeUtf(x);
    }, buf -> {
        String sound = buf.readUtf();
        String type = buf.readUtf();
        int fade = buf.readVarInt();
        boolean combat = buf.readBoolean();
        int n = buf.readVarInt();
        List<String> structures = new ArrayList<>(n);
        for (int i = 0; i < n; i++) structures.add(buf.readUtf());
        return new MusicState(sound, type, fade, combat, List.copyOf(structures));
    });

    public boolean bossFight() {
        return !bossSound.isEmpty();
    }
}
