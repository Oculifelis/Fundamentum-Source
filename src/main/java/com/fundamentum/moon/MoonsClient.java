package com.fundamentum.moon;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * The moon and the night event over this client's level, as the server last sent them ({@link Moons},
 * {@link NightEvents}): their ids for mods' own skies and effects, and their fog and sky tints, each eased in and out
 * by its {@link Moon.Fade}. An event's tints are laid over the moon's.
 */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class MoonsClient {
    /** One tint as it stands now: the moon's colour and strength, and how far it has come in. */
    private static final class Track {
        Moon.Tint tint = Moon.Tint.NONE;
        float target;
        float mix;
        float mixO;

        void set(boolean up, Moon.Tint moonTint) {
            if (up) {
                this.tint = moonTint;
            }
            this.target = up ? moonTint.strength() : 0.0F;
        }

        void tick() {
            this.mixO = this.mix;
            this.mix = this.tint.fade().step(this.mix, this.target, this.tint.strength());
        }

        float mix(float pt) {
            return this.mixO + (this.mix - this.mixO) * pt;
        }
    }

    private static final Track FOG = new Track();
    private static final Track SKY = new Track();
    private static final Track EVENT_FOG = new Track();
    private static final Track EVENT_SKY = new Track();
    private static String moon = "";
    private static String event = "";

    static void set(String id, Moon.Tint fog, Moon.Tint sky) {
        moon = id;
        FOG.set(!id.isEmpty(), fog);
        SKY.set(!id.isEmpty(), sky);
    }

    static void setEvent(String id, Moon.Tint fog, Moon.Tint sky) {
        event = id;
        EVENT_FOG.set(!id.isEmpty(), fog);
        EVENT_SKY.set(!id.isEmpty(), sky);
    }

    /** The night event over this client's level, or "". */
    public static String event() {
        return event;
    }

    /** How strongly the event tints the fog and the sky now, 0..1. */
    public static float eventFogMix(float pt) {
        return EVENT_FOG.mix(pt);
    }

    public static float eventSkyMix(float pt) {
        return EVENT_SKY.mix(pt);
    }

    /** The moon over this client's level, or "". */
    public static String moon() {
        return moon;
    }

    /** How strongly the moon tints the fog now, 0..1. */
    public static float mix(float pt) {
        return FOG.mix(pt);
    }

    public static float fogMix(float pt) {
        return FOG.mix(pt);
    }

    /** How strongly the moon tints the sky and sunset now, 0..1. */
    public static float skyMix(float pt) {
        return SKY.mix(pt);
    }

    public static int color() {
        return FOG.tint.color();
    }

    public static int fogColor() {
        return FOG.tint.color();
    }

    public static int skyColor() {
        return SKY.tint.color();
    }

    /** An RGB colour (alpha kept) tinted toward the moon's sky colour, then the event's, for sky renderers. */
    public static int tintSky(int argb, float pt) {
        return blend(blend(argb, SKY, pt), EVENT_SKY, pt);
    }

    private static int blend(int argb, Track track, float pt) {
        float k = track.mix(pt);
        if (k <= 0.0F) {
            return argb;
        }
        int c = track.tint.colorAt(net.minecraft.Util.getMillis() / 1000.0);
        int r = Math.round((argb >> 16 & 255) * (1.0F - k) + (c >> 16 & 255) * k);
        int g = Math.round((argb >> 8 & 255) * (1.0F - k) + (c >> 8 & 255) * k);
        int b = Math.round((argb & 255) * (1.0F - k) + (c & 255) * k);
        return argb & 0xFF000000 | r << 16 | g << 8 | b;
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (Minecraft.getInstance().level == null) {
            set("", Moon.Tint.NONE, Moon.Tint.NONE);
            setEvent("", Moon.Tint.NONE, Moon.Tint.NONE);
        }
        FOG.tick();
        SKY.tick();
        EVENT_FOG.tick();
        EVENT_SKY.tick();
    }


    @SubscribeEvent
    public static void fog(ViewportEvent.ComputeFogColor event) {
        float pt = (float) event.getPartialTick();
        fog(event, FOG, pt);
        fog(event, EVENT_FOG, pt);
    }

    private static void fog(ViewportEvent.ComputeFogColor event, Track track, float pt) {
        float k = track.mix(pt);
        if (k <= 0.0F) {
            return;
        }
        int color = track.tint.colorAt(net.minecraft.Util.getMillis() / 1000.0);
        event.setRed(event.getRed() * (1.0F - k) + (color >> 16 & 255) / 255.0F * k);
        event.setGreen(event.getGreen() * (1.0F - k) + (color >> 8 & 255) / 255.0F * k);
        event.setBlue(event.getBlue() * (1.0F - k) + (color & 255) / 255.0F * k);
    }

    private MoonsClient() {}
}
