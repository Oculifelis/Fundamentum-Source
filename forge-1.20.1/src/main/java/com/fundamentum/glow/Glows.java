package com.fundamentum.glow;

import com.fundamentum.net.Net;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.eventbus.api.IEventBus;
import com.fundamentum.net.IPayloadContext;

/**
 * Coloured glowing: the Glowing outline in a colour of the mod's choosing instead of white (or the team's). Server side,
 * {@link #apply} gives a living target Glowing for {@code ticks} and tells the clients tracking it the colour; the
 * outline takes that colour while the target glows (a team colour is overridden). A later apply replaces the colour.
 * <pre>Glows.apply(target, 0xF19645, 160);</pre>
 */
public final class Glows {
    /** Client side: entity id to (colour, game time it ends). */
    private static final Map<Integer, long[]> COLOURS = new ConcurrentHashMap<>();
    private static final SimpleChannel CHANNEL = Net.channel("glow", "1");

    /** Glowing for {@code ticks} with an RGB outline colour (server side). */
    public static void apply(LivingEntity target, int rgb, int ticks) {
        target.addEffect(new MobEffectInstance(MobEffects.GLOWING, ticks, 0, false, false));
        Net.sendTrackingAndSelf(CHANNEL, target, new GlowPayload(target.getId(), rgb & 0xFFFFFF, ticks));
    }

    /** The outline colour set for this entity, or -1 (client side; read by the Entity mixin). */
    public static int colour(Entity entity) {
        long[] c = COLOURS.get(entity.getId());
        if (c == null) {
            return -1;
        }
        if (entity.level().getGameTime() > c[1]) {
            COLOURS.remove(entity.getId());
            return -1;
        }
        return (int) c[0];
    }

    /** Forgets colours whose time is up (the client calls this now and then). */
    public static void prune(long gameTime) {
        for (Iterator<long[]> it = COLOURS.values().iterator(); it.hasNext(); ) {
            if (gameTime > it.next()[1]) {
                it.remove();
            }
        }
    }

    public static void init(IEventBus modBus) {
        Net.toClient(CHANNEL, 0, GlowPayload.class, GlowPayload::encode, GlowPayload::decode, GlowPayload::handle);
    }

    /** Server to clients: entity {@code entity} glows {@code rgb} for {@code ticks}. */
    public record GlowPayload(int entity, int rgb, int ticks) {
        void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(this.entity);
            buf.writeInt(this.rgb);
            buf.writeVarInt(this.ticks);
        }

        static GlowPayload decode(FriendlyByteBuf buf) {
            return new GlowPayload(buf.readVarInt(), buf.readInt(), buf.readVarInt());
        }

        static void handle(GlowPayload p, IPayloadContext context) {
            long now = context.player().level().getGameTime();
            COLOURS.put(p.entity(), new long[] {p.rgb(), now + p.ticks()});
            prune(now);
        }
    }

    private Glows() {}
}
