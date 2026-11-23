package com.fundamentum.flail;

import com.fundamentum.scythe.ScytheSpec;
import com.fundamentum.scythe.Scythes;
import com.fundamentum.whip.WhipVec;
import com.fundamentum.whip.Whips;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

/**
 * The client side of flails and scythes: each nearby player's flail runs its own copy of {@link FlailSim}, driven by the
 * server's announcements, and is drawn as a chain (a strip through the links) with a head on the end. Use (right click)
 * with a flail or a scythe goes to the server instead of using the item.
 */
@Mod.EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class FlailsClient {
    private static final ResourceLocation WHITE = new ResourceLocation("fundamentum", "textures/misc/white.png");
    /** Flails are only kept for players this close to the camera. */
    private static final double RANGE = 64;

    private static final class Shown {
        final ResourceLocation item;
        final FlailSpec spec;
        final FlailSim sim;
        WhipVec[][] before;

        Shown(ResourceLocation item, FlailSpec spec, FlailSim sim) {
            this.item = item;
            this.spec = spec;
            this.sim = sim;
            this.before = copy(sim.points);
        }
    }

    private static final Map<Integer, Shown> SHOWN = new ConcurrentHashMap<>();
    private static boolean flailUse;
    private static boolean scytheUse;

    static {
        Flails.onReload(SHOWN::clear);
    }

    private FlailsClient() {}

    /** A move announced by the server ({@link Flails.StatePayload}). */
    static void state(int entity, ResourceLocation item, int action, float value) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !(level.getEntity(entity) instanceof Player player)) {
            return;
        }
        Shown s = shown(player, item);
        if (s == null) {
            return;
        }
        switch (action) {
            case Flails.StatePayload.SWING -> s.sim.swing(value < 0 ? -1 : 1);
            case Flails.StatePayload.SPIN -> s.sim.startSpin();
            case Flails.StatePayload.RELEASE -> {
                s.sim.charge = value;
                s.sim.release();
            }
            case Flails.StatePayload.LAUNCH -> s.sim.launch(value);
            default -> {}
        }
    }

    private static @Nullable Shown shown(Player player, ResourceLocation item) {
        FlailSpec spec = Flails.spec(item);
        if (spec == null) {
            SHOWN.remove(player.getId());
            return null;
        }
        Shown s = SHOWN.get(player.getId());
        if (s == null || !s.item.equals(item) || s.spec != spec) {
            s = new Shown(item, spec, new FlailSim(spec, Flails.vec(Whips.hand(player, 1.0F))));
            SHOWN.put(player.getId(), s);
        }
        return s;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            SHOWN.clear();
            return;
        }
        Player own = mc.player;
        for (Player p : level.players()) {
            ResourceLocation item = BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem());
            if (p.isRemoved() || own != null && p.distanceToSqr(own) > RANGE * RANGE || Flails.spec(item) == null) {
                SHOWN.remove(p.getId());
                continue;
            }
            Shown s = shown(p, item);
            if (s != null) {
                s.before = copy(s.sim.points);
                s.sim.step(Flails.vec(Whips.hand(p, 1.0F)), Flails.vec(p.getLookAngle()), Whips.solids(level));
            }
        }
        SHOWN.keySet().removeIf(id -> !(level.getEntity(id) instanceof Player));
        // Use with a flail or a scythe in hand goes to the server, down and up.
        boolean flail = own != null && Flails.spec(own.getMainHandItem()) != null;
        boolean scythe = own != null && Scythes.spec(own.getMainHandItem()) != null;
        boolean down = mc.screen == null && mc.options.keyUse.isDown();
        if ((flail && down) != flailUse) {
            flailUse = flail && down;
            Flails.CHANNEL.sendToServer(new Flails.UsePayload(flailUse));
        }
        if ((scythe && down) != scytheUse) {
            scytheUse = scythe && down;
            Scythes.CHANNEL.sendToServer(new Scythes.UsePayload(scytheUse, own != null && own.isShiftKeyDown()));
        }
    }

    /** Use with a flail, or a scythe with a move on use, is ours: it doesn't use the item or the block. */
    @SubscribeEvent
    public static void use(InputEvent.InteractionKeyMappingTriggered event) {
        Player player = Minecraft.getInstance().player;
        if (player == null || !event.isUseItem() || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        ScytheSpec scythe = Scythes.spec(player.getMainHandItem());
        boolean ours = Flails.spec(player.getMainHandItem()) != null
            || scythe != null && scythe.moveOn(player.isShiftKeyDown() ? "sneak_use" : "use") != null;
        if (ours) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }

    @SubscribeEvent
    public static void draw(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || SHOWN.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        float pt = mc.getFrameTime();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        SHOWN.forEach((id, s) -> {
            if (!(level.getEntity(id) instanceof Player player) || player.isInvisible()
                || player == mc.getCameraEntity() && mc.options.getCameraType().isFirstPerson() && s.sim.mode == FlailSim.Mode.IDLE) {
                return;
            }
            Vec3 hand = Whips.hand(player, pt);
            for (int h = 0; h < s.sim.points.length; h++) {
                int n = s.sim.points[h].length;
                Vec3[] p = new Vec3[n];
                int[] light = new int[n];
                for (int i = 0; i < n; i++) {
                    WhipVec a = h < s.before.length && i < s.before[h].length ? s.before[h][i] : s.sim.points[h][i];
                    Vec3 w = Flails.vec(a.lerp(s.sim.points[h][i], pt));
                    if (i == 0) {
                        w = hand;
                    }
                    light[i] = LevelRenderer.getLightColor(level, BlockPos.containing(w));
                    p[i] = w.subtract(cam);
                }
                FlailSpec spec = s.spec;
                PoseStack.Pose at = event.getPoseStack().last();
                VertexConsumer buffer = buffers.getBuffer(RenderType.entityCutout(WHITE));
                chain(at, buffer, p, light, spec.chainColor);
                head(at, buffer, p[n - 1], light[n - 1], spec);
            }
        });
        buffers.endBatch(RenderType.entityCutout(WHITE));
    }

    /** A camera-facing strip through the links, with a darker bead at every link so it reads as a chain. */
    private static void chain(PoseStack.Pose at, VertexConsumer buffer, Vec3[] p, int[] light, int color) {
        int n = p.length;
        double half = 0.035;
        for (int i = 0; i < n - 1; i++) {
            Vec3 a = p[i], b = p[i + 1];
            Vec3 tangent = b.subtract(a);
            Vec3 toEye = a.add(b).scale(-0.5);
            Vec3 side = tangent.cross(toEye);
            side = side.lengthSqr() < 1.0E-10 ? new Vec3(0, 1, 0) : side.normalize().scale(half);
            Vec3 normal = toEye.lengthSqr() < 1.0E-10 ? new Vec3(0, 1, 0) : toEye.normalize();
            int c = (i & 1) == 0 ? color : shade(color, 0.75);
            quad(at, buffer, a.add(side), a.subtract(side), b.subtract(side), b.add(side), c, light[i], light[i + 1], normal);
        }
    }

    /** The head: a round, camera-facing disc with a spiked rim when it has spikes (a morning star). */
    private static void head(PoseStack.Pose at, VertexConsumer buffer, Vec3 c, int light, FlailSpec spec) {
        Vec3 toEye = c.lengthSqr() < 1.0E-8 ? new Vec3(0, 1, 0) : c.scale(-1).normalize();
        Vec3 a = toEye.cross(Math.abs(toEye.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0)).normalize();
        Vec3 b = toEye.cross(a).normalize();
        double r = Math.max(0.12, spec.impactRadius * 0.6);
        boolean spiked = spec.armorPierce > 0 || "fundamentum:chained_spikes".equals(spec.kind) || spec.kind.endsWith("morning_star");
        int seg = 12;
        int rim = shade(spec.headColor, 0.7);
        for (int i = 0; i < seg; i++) {
            double a0 = (Math.PI * 2) * i / seg, a1 = (Math.PI * 2) * (i + 1) / seg;
            Vec3 d0 = a.scale(Math.cos(a0)).add(b.scale(Math.sin(a0))), d1 = a.scale(Math.cos(a1)).add(b.scale(Math.sin(a1)));
            Vec3 p0 = c.add(d0.scale(r)), p1 = c.add(d1.scale(r));
            quad(at, buffer, c, p0, p1, c, spec.headColor, light, light, toEye);
            if (spiked) {
                double am = (a0 + a1) / 2;
                Vec3 tip = c.add(a.scale(Math.cos(am)).add(b.scale(Math.sin(am))).scale(r * 1.6));
                quad(at, buffer, p0, tip, tip, p1, rim, light, light, toEye);
            }
        }
    }

    private static int shade(int argb, double k) {
        int r = (int) (((argb >> 16) & 0xFF) * k), g = (int) (((argb >> 8) & 0xFF) * k), bl = (int) ((argb & 0xFF) * k);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** Both faces, so it shows from either side. */
    private static void quad(PoseStack.Pose at, VertexConsumer buffer, Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, int color, int l0, int l1, Vec3 n) {
        int c = 0xFF000000 | color;
        vertex(at, buffer, p0, c, 0, 0, l0, n);
        vertex(at, buffer, p1, c, 1, 0, l0, n);
        vertex(at, buffer, p2, c, 1, 1, l1, n);
        vertex(at, buffer, p3, c, 0, 1, l1, n);
        vertex(at, buffer, p3, c, 0, 1, l1, n);
        vertex(at, buffer, p2, c, 1, 1, l1, n);
        vertex(at, buffer, p1, c, 1, 0, l0, n);
        vertex(at, buffer, p0, c, 0, 0, l0, n);
    }

    private static void vertex(PoseStack.Pose at, VertexConsumer buffer, Vec3 p, int color, float u, float v, int light, Vec3 n) {
        buffer.vertex(at.pose(), (float) p.x, (float) p.y, (float) p.z).color(color).uv(u, v).overlayCoords(OverlayTexture.NO_OVERLAY)
            .uv2(light).normal(at.normal(), (float) n.x, (float) n.y, (float) n.z).endVertex();
    }

    private static WhipVec[][] copy(WhipVec[][] points) {
        WhipVec[][] out = new WhipVec[points.length][];
        for (int h = 0; h < points.length; h++) {
            out[h] = points[h].clone();
        }
        return out;
    }
}
