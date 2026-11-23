package com.fundamentum.pose;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import org.jetbrains.annotations.Nullable;

/**
 * The player's own arms holding an item in first person, from the "first_person" block of its {@link PosePlayer}
 * binding, so items need no code. The held item is drawn in vanilla's place and swing, the "first_person" bone of a
 * playing clip moves it and the arms together (turning about the grip), then the arms are drawn. "arm" is one of:
 * <ul>
 * <li>grip: the fist closed on "grip" (pixels in the held item's frame, default [0.4, -0.6, -1.2], about where vanilla
 * holds a sword's handle), the arm at "angles" (pitch up, yaw inward, roll; default [18, 30, 0]). Swords, maces, staves.
 * "fist_in" pushes the fist that many pixels on past the point, so a thick handle sits inside it.
 * <li>palm: an open hand palm up under "grip" (default [-2, 7, -2]), fingers forward. Orbs and other floating items.
 * Its "angles" are the forearm's tilt back, the fingers' turn inward and the elbow's turn out (default [20, 18, 12]).
 * <li>gun, wand: the fist closed on a gun's grip (wand: a handle running through the fist), laid along the grip frame a
 * {@link Frame} gives ("angles": pitch down and yaw out about the grip, default [0, 15, 0]). Without one, a grip hold
 * pushed "fist_in" pixels on (default 2.25).
 * <li>none: no arm, only the item (the clip still moves it).
 * </ul>
 * A "support" block, in the same form, draws the other hand too while the off hand is empty (hafts, forends,
 * two-handed grips). "swing": false holds the item still on attack.
 *
 * <p>A mod that draws its items itself in first person (guns with their own draw, kick and reload motion) registers a
 * {@link Frame}: for its items Fundamentum leaves the item to vanilla and the mod's own renderer and draws only the
 * arms, in the frame the mod places. A frame may also give the hold of items without a binding file. Mods may call
 * {@link #arm}, {@link #palm} and {@link #gun} from their own code too.
 *
 * <p>Moved here from Ultima Ratio (its client.FirstPersonArms).
 */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class FirstPersonHands {
    private static final float[] GRIP = {0.4F, -0.6F, -1.2F};
    private static final float[] GRIP_ANGLES = {18.0F, 30.0F, 0.0F};
    private static final float[] PALM = {-2.0F, 7.0F, -2.0F};
    private static final float[] PALM_ANGLES = {20.0F, 18.0F, 12.0F};
    private static final float[] GUN_ANGLES = {0.0F, 15.0F, 0.0F};
    /** How far below the palm point the palm's surface sits, and how far the hand's end reaches past it, in pixels. */
    private static final float PALM_DROP = 1.8F, PALM_REACH = 2.0F;
    /** The gun fallback's push of the fist past the grip, in pixels. */
    private static final float GUN_FIST_IN = 2.25F;
    /**
     * The vanilla hand as a fist, in pixels: FIST_H from its top face to its bottom face and FIST_LEN along the arm; the
     * grip's top lies GRIP_DEPTH inside the top face, GRIP_Y back from the fist's end.
     */
    private static final float FIST_H = 4.0F, FIST_LEN = 4.0F, GRIP_Y = 0.8F, GRIP_DEPTH = -0.15F;

    /** Where a mod draws its own items in first person; see the class comment. */
    public interface Frame {
        /**
         * For the mod's own items: moves {@code pose} to where the mod draws {@code stack} held in {@code arm} (with its
         * draw and kick) and returns true. Returns false, leaving {@code pose} alone, for any other item.
         */
        boolean place(PoseStack pose, AbstractClientPlayer player, ItemStack stack, HumanoidArm arm, float pt, float equip);

        /** For "gun" and "wand": turns the placed frame into the grip's frame (the fist closes on it); false if it has none. */
        default boolean gripFrame(PoseStack pose, ItemStack stack, boolean leftHanded) {
            return false;
        }

        /** The hold of a stack with no "first_person" binding, or null. */
        default PosePlayer.@Nullable FirstPerson firstPerson(ItemStack stack) {
            return null;
        }
    }

    private static final List<Frame> FRAMES = new CopyOnWriteArrayList<>();

    public static void registerFrame(Frame frame) {
        FRAMES.add(frame);
    }

    @SubscribeEvent
    public static void render(RenderHandEvent event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        AbstractClientPlayer player = mc.player;
        ItemStack stack = event.getItemStack();
        PosePlayer.Binding b = PosePlayer.binding(stack);
        PosePlayer.FirstPerson fp = b == null ? null : b.firstPerson();
        for (Frame f : FRAMES) {
            if (fp == null) {
                fp = f.firstPerson(stack);
            }
        }
        if (player == null || fp == null || player.isScoping()) {
            return;
        }
        HumanoidArm main = player.getMainArm();
        float pt = event.getPartialTick();
        int light = event.getPackedLight();
        MultiBufferSource out = event.getMultiBufferSource();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        Frame frame = null;
        for (Frame f : FRAMES) {
            if (f.place(pose, player, stack, main, pt, event.getEquipProgress())) {
                frame = f;
                break;
            }
        }
        if (frame == null) {
            // Fundamentum draws the item: vanilla's place and swing for a held item (ItemInHandRenderer.renderArmWithItem)
            event.setCanceled(true);
            float inv = main == HumanoidArm.RIGHT ? 1.0F : -1.0F;
            pose.translate(inv * 0.56F, -0.52F + event.getEquipProgress() * -0.6F, -0.72F);
            float attack = event.getSwingProgress();
            if (fp.swing() && attack > 0.0F) {
                float s = Mth.sqrt(attack);
                pose.translate(inv * -0.4F * Mth.sin(s * Mth.PI), 0.2F * Mth.sin(s * Mth.TWO_PI), -0.2F * Mth.sin(attack * Mth.PI));
                pose.mulPose(Axis.YP.rotationDegrees(inv * (45.0F + Mth.sin(attack * attack * Mth.PI) * -20.0F)));
                float xz = Mth.sin(s * Mth.PI);
                pose.mulPose(Axis.ZP.rotationDegrees(inv * xz * -20.0F));
                pose.mulPose(Axis.XP.rotationDegrees(xz * -80.0F));
                pose.mulPose(Axis.YP.rotationDegrees(inv * -45.0F));
            }
            float[] g = point(fp);
            PosePlayer.firstPerson(pose, player, main, pt, new float[] {inv * g[0] / 16.0F, g[1] / 16.0F, g[2] / 16.0F});
            mc.getEntityRenderDispatcher().getItemInHandRenderer().renderItem(player, stack,
                main == HumanoidArm.RIGHT ? ItemDisplayContext.FIRST_PERSON_RIGHT_HAND : ItemDisplayContext.FIRST_PERSON_LEFT_HAND,
                main == HumanoidArm.LEFT, pose, out, light);
        }
        if (!player.isInvisible()) {
            draw(fp, pose, out, light, player, main, frame, stack);
            if (fp.support() != null && player.getOffhandItem().isEmpty()) {
                draw(fp.support(), pose, out, light, player, main.getOpposite(), frame, stack);
            }
        }
        pose.popPose();
    }

    private static float[] point(PosePlayer.FirstPerson fp) {
        return fp.grip() != null ? fp.grip() : "palm".equals(fp.arm()) ? PALM : GRIP;
    }

    private static void draw(PosePlayer.FirstPerson fp, PoseStack pose, MultiBufferSource out, int light, AbstractClientPlayer player, HumanoidArm arm,
                             @Nullable Frame frame, ItemStack stack) {
        float[] g = point(fp);
        switch (fp.arm()) {
            case "grip" -> {
                float[] a = fp.angles() != null ? fp.angles() : GRIP_ANGLES;
                arm(pose, out, light, player, arm, pushed(g, a, fp.fistIn()), a);
            }
            case "palm" -> palm(pose, out, light, player, arm, g, fp.angles() != null ? fp.angles() : PALM_ANGLES);
            case "gun", "wand" -> {
                pose.pushPose();
                if (frame != null && frame.gripFrame(pose, stack, arm == HumanoidArm.LEFT)) {
                    float[] a = fp.angles() != null ? fp.angles() : GUN_ANGLES;
                    gun(pose, out, light, player, arm, a[0], a[1], "wand".equals(fp.arm()));
                } else {
                    float[] a = fp.angles() != null ? fp.angles() : GRIP_ANGLES;
                    arm(pose, out, light, player, arm, pushed(g, a, fp.fistIn() != 0.0F ? fp.fistIn() : GUN_FIST_IN), a);
                }
                pose.popPose();
            }
            default -> {}
        }
    }

    /**
     * {@code grip} pushed {@code by} pixels on along an arm turned as {@link #arm} turns it (pitch, then yaw), so the
     * point ends up inside the fist rather than at its end.
     */
    private static float[] pushed(float[] grip, float[] angles, float by) {
        if (by == 0.0F) {
            return grip;
        }
        double a = Math.toRadians(-90.0F + angles[0]);
        double y = Math.toRadians(angles[1]);
        float sa = (float) Math.sin(a);
        return new float[] {grip[0] + (float) (sa * Math.sin(y)) * by, grip[1] + (float) Math.cos(a) * by, grip[2] + (float) (sa * Math.cos(y)) * by};
    }

    /**
     * Draws {@code arm} with its fist at {@code grip} (pixels, written for the right hand; mirrored for the left main
     * hand) in the current frame, turned by {@code angles} (pitch up, yaw inward, roll, degrees).
     */
    public static void arm(PoseStack pose, MultiBufferSource out, int light, AbstractClientPlayer player, HumanoidArm arm, float[] grip, float[] angles) {
        float inv = player.getMainArm() == HumanoidArm.RIGHT ? 1.0F : -1.0F;
        float own = arm == HumanoidArm.RIGHT ? 1.0F : -1.0F;
        pose.pushPose();
        pose.translate(inv * grip[0] / 16.0F, grip[1] / 16.0F, grip[2] / 16.0F);
        pose.mulPose(Axis.YP.rotationDegrees(inv * angles[1]));
        pose.mulPose(Axis.XP.rotationDegrees(-90.0F + angles[0]));
        pose.mulPose(Axis.YP.rotationDegrees(own * angles[2]));
        // the palm (the bottom of the arm cube) at the grip
        pose.translate(own * 6.0F / 16.0F, -12.0F / 16.0F, 0.0F);
        hand(pose, out, light, player, arm);
        pose.popPose();
    }

    /**
     * Draws {@code arm} as an open hand palm up under {@code at} (pixels), fingers forward: {@code angles} are the
     * forearm's tilt back, the fingers' turn inward and the elbow's turn out (degrees).
     */
    public static void palm(PoseStack pose, MultiBufferSource out, int light, AbstractClientPlayer player, HumanoidArm arm, float[] at, float[] angles) {
        float inv = player.getMainArm() == HumanoidArm.RIGHT ? 1.0F : -1.0F;
        float own = arm == HumanoidArm.RIGHT ? 1.0F : -1.0F;
        pose.pushPose();
        pose.translate(inv * at[0] / 16.0F, (at[1] - PALM_DROP) / 16.0F, at[2] / 16.0F);
        // the palm's frame: x across, y the fingers (forward), z up out of the palm
        pose.mulPose(Axis.XP.rotationDegrees(-90.0F));
        pose.mulPose(Axis.ZP.rotationDegrees(inv * angles[1]));
        pose.translate(0.0F, PALM_REACH / 16.0F, -2.0F / 16.0F);
        pose.mulPose(Axis.ZP.rotationDegrees(inv * angles[2]));
        pose.mulPose(Axis.XP.rotationDegrees(angles[0]));
        pose.translate(own * 6.0F / 16.0F, -12.0F / 16.0F, 0.0F);
        hand(pose, out, light, player, arm);
        pose.popPose();
    }

    /**
     * Draws {@code arm} as a fist closed on a gun's grip, with the pose at the grip's frame (see {@link Frame#gripFrame}):
     * the fist's top face under the frame, the forearm along the barrel, turned {@code yaw} degrees out and {@code pitch}
     * down about the grip. A wand's handle runs through the middle of the fist instead.
     */
    public static void gun(PoseStack pose, MultiBufferSource out, int light, AbstractClientPlayer player, HumanoidArm arm, float pitch, float yaw, boolean wand) {
        float own = arm == HumanoidArm.RIGHT ? 1.0F : -1.0F;
        pose.pushPose();
        pose.mulPose(Axis.ZP.rotationDegrees(own * yaw));
        pose.mulPose(Axis.XP.rotationDegrees(pitch));
        if (wand) {
            pose.translate(0.0F, FIST_LEN / 2.0F / 16.0F, 0.0F);
        } else {
            pose.translate(0.0F, GRIP_Y / 16.0F, -(FIST_H / 2.0F - GRIP_DEPTH) / 16.0F);
        }
        pose.translate(own * 6.0F / 16.0F, -12.0F / 16.0F, 0.0F);
        hand(pose, out, light, player, arm);
        pose.popPose();
    }

    private static void hand(PoseStack pose, MultiBufferSource out, int light, AbstractClientPlayer player, HumanoidArm arm) {
        if (!(Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(player) instanceof PlayerRenderer renderer)) {
            return;
        }
        if (arm == HumanoidArm.RIGHT) {
            renderer.renderRightHand(pose, out, light, player);
        } else {
            renderer.renderLeftHand(pose, out, light, player);
        }
    }

    private FirstPersonHands() {}
}
