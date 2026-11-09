package com.fundamentum.compat.jade;

import com.fundamentum.music.JukeboxProgress.Track;
import com.fundamentum.music.client.JukeboxBar;
import com.fundamentum.music.client.JukeboxTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec2;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.Identifiers;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.Element;

/**
 * Client half: corrects {@link JukeboxTracker} with the server's tick count, then adds a line that draws itself every
 * frame from the tracker (so the scrubber glides between Jade's data updates). The song name is left to Jade's own
 * "Now playing" line unless that one is switched off.
 */
public final class JukeboxJadeClient implements IBlockComponentProvider {
    public static final JukeboxJadeClient INSTANCE = new JukeboxJadeClient();
    static final int BAR_WIDTH = 80;
    private static Track lastData;

    /** The last server copy Jade delivered (for the dev test). */
    public static Track lastData() { return lastData; }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        BlockPos pos = accessor.getPosition();
        JukeboxJadeData.INSTANCE.decodeFromData(accessor).ifPresent(t -> {
            // Jade keeps handing out the same data until its next request: sync each new copy once only
            if (t != null && !t.equals(lastData)) {
                lastData = t;
                JukeboxTracker.sync(pos, t.record(), t.elapsedTicks());
            }
        });
        JukeboxTracker.at(pos).ifPresent(t -> {
            if (!t.finished()) {
                tooltip.add(new BarElement(pos, t, !config.get(Identifiers.MC_JUKEBOX)));
            }
        });
    }

    @Override
    public ResourceLocation getUid() { return JukeboxJadeData.UID; }

    /** One tooltip line: the shared {@link JukeboxBar}, re-read from the tracker each frame. */
    static final class BarElement extends Element {
        private final BlockPos pos;
        private final boolean withName;
        private final Vec2 size;
        private Track last;

        BarElement(BlockPos pos, Track track, boolean withName) {
            this.pos = pos;
            this.last = track;
            this.withName = withName;
            Font font = Minecraft.getInstance().font;
            this.size = new Vec2(JukeboxBar.width(font, track, BAR_WIDTH, withName), JukeboxBar.height(font, withName));
            message(track.timeText());
        }

        @Override
        public Vec2 getSize() {
            return size;
        }

        @Override
        public void render(GuiGraphics g, float x, float y, float maxX, float maxY) {
            last = JukeboxTracker.at(pos).orElse(last);
            JukeboxBar.draw(g, Minecraft.getInstance().font, last, Math.round(x), Math.round(y), BAR_WIDTH, withName);
        }
    }
}
