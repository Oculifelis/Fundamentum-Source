package com.fundamentum.hook;

import com.fundamentum.client.Keys;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The hook hotkey ({@link Keys#HOOK}), and the pull: movement is the client's, as in vanilla, so the local player is
 * drawn toward their latched hooks (the middle of them, with several) and held there; jumping lets go.
 */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class HooksClient {
    /** Hooks this client already let go of, until the server removes them. */
    private static final Set<Integer> RELEASED = new HashSet<>();
    private static boolean jumpWasDown;

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        LocalPlayer player = Minecraft.getInstance().player;
        while (Keys.HOOK.consumeClick()) {
            if (player != null) {
                PacketDistributor.sendToServer(Hooks.FirePayload.INSTANCE);
            }
        }
        if (player == null) {
            return;
        }
        boolean jumpDown = Minecraft.getInstance().options.keyJump.isDown();
        boolean jumped = jumpDown && !jumpWasDown;
        jumpWasDown = jumpDown;
        List<HookEntity> mine = player.level().getEntitiesOfClass(HookEntity.class, player.getBoundingBox().inflate(64), h -> h.getOwner() == player);
        if (mine.isEmpty()) {
            RELEASED.clear();
            return;
        }
        List<HookEntity> latched = mine.stream().filter(h -> h.isLatched() && !RELEASED.contains(h.getId())).toList();
        if (latched.isEmpty() || player.isPassenger() || player.isSpectator()) {
            return;
        }
        if (jumped) {
            latched.forEach(h -> RELEASED.add(h.getId()));
            PacketDistributor.sendToServer(Hooks.ReleasePayload.INSTANCE);
            Vec3 v = player.getDeltaMovement();
            player.setDeltaMovement(v.x, Math.max(v.y, 0.42), v.z);
            return;
        }
        Vec3 target = Vec3.ZERO;
        for (HookEntity hook : latched) {
            target = target.add(hook.position());
        }
        target = target.scale(1.0 / latched.size());
        Vec3 toTarget = target.subtract(player.position().add(0.0, player.getBbHeight() * 0.5, 0.0));
        double distance = toTarget.length();
        double pull = latched.get(0).def().pullSpeed();
        // A still hook closes the last of the gap at once and holds the player dead still; others give a little.
        boolean still = latched.get(0).def().still();
        player.setDeltaMovement(distance > pull ? toTarget.scale(pull / distance) : toTarget.scale(still ? 1.0 : 0.5));
        player.resetFallDistance();
    }

    private HooksClient() {}

    /** The mod bus half (1.21.1's subscribers do not pick their bus by the event). */
    @EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    static final class ModEvents {
        @SubscribeEvent
        public static void layers(EntityRenderersEvent.RegisterLayerDefinitions event) {
            event.registerLayerDefinition(HookModel.HOOK, HookModel::createHookLayer);
            event.registerLayerDefinition(HookModel.LINK, HookModel::createLinkLayer);
        }

        @SubscribeEvent
        public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(Hooks.HOOK.get(), HookRenderer::new);
        }
    }
}
