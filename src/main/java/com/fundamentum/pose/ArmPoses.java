package com.fundamentum.pose;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraftforge.client.IArmPoseTransformer;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jetbrains.annotations.Nullable;

/**
 * The third-person holds a {@link PosePlayer} binding names in "arms". Vanilla's: two_handed (CROSSBOW_HOLD), bow
 * (BOW_AND_ARROW), spear (THROW_SPEAR), block (BLOCK), item (ITEM). Fundamentum's own, added to vanilla's ArmPose
 * through Forge's extensible enum ({@code ArmPose.create}, made the first time each is asked for):
 * <ul>
 * <li>pistol: only the main arm comes up, along the head's look; the other hand stays free.
 * <li>staff: the main arm held at the binding's "main" angles (default a little forward), the item upright in it.
 * <li>support: the main arm at "main" (default forward and across the body) and the off hand reaching for the "support" point
 * (pixels from the main hand, in the main arm's frame; default 3 behind it along the grip), for anything held in both
 * hands, like a greatsword.
 * <li>clip: the arms of a playing pose clip ({@link PosePlayer#applyArms}).
 * </ul>
 * Mods that pick arm poses in their own {@code IClientItemExtensions.getArmPose} may return these too ({@link #of}).
 */
public final class ArmPoses {
    /** One added arm pose, created on first use (1.20.1 has no enum extension file; Forge extends ArmPose at runtime). */
    public static final class Extension {
        private final String name;
        private final boolean twoHanded;
        private final IArmPoseTransformer transform;
        private volatile HumanoidModel.ArmPose value;

        Extension(String name, boolean twoHanded, IArmPoseTransformer transform) {
            this.name = name;
            this.twoHanded = twoHanded;
            this.transform = transform;
        }

        public HumanoidModel.ArmPose getValue() {
            HumanoidModel.ArmPose v = this.value;
            if (v == null) {
                synchronized (this) {
                    if (this.value == null) {
                        this.value = HumanoidModel.ArmPose.create(this.name, this.twoHanded, this.transform);
                    }
                    v = this.value;
                }
            }
            return v;
        }
    }

    public static final Extension PISTOL = new Extension("FUNDAMENTUM_PISTOL", false, ArmPoses::pistol);
    public static final Extension STAFF = new Extension("FUNDAMENTUM_STAFF", false, ArmPoses::staff);
    public static final Extension SUPPORT = new Extension("FUNDAMENTUM_SUPPORT", true, ArmPoses::support);
    public static final Extension CLIP = new Extension("FUNDAMENTUM_CLIP", false, ArmPoses::clip);

    /** Makes the four arm poses now (client setup), so none is first made while a frame draws. */
    public static void bootstrap() {
        PISTOL.getValue();
        STAFF.getValue();
        SUPPORT.getValue();
        CLIP.getValue();
    }

    private static final float[] STAFF_MAIN = {-30.0F, 0.0F, 0.0F};
    private static final float[] SUPPORT_MAIN = {-45.0F, -25.0F, 0.0F};
    private static final float[] SUPPORT_POINT = {0.0F, 0.0F, 3.0F};
    /** From an arm's pivot to the middle of its hand, in model pixels. */
    private static final float HAND = 10.0F;

    /** The arm pose a binding's "arms" names, or null for an unknown name. */
    public static HumanoidModel.@Nullable ArmPose of(@Nullable String arms) {
        if (arms == null) {
            return null;
        }
        return switch (arms) {
            case "two_handed" -> HumanoidModel.ArmPose.CROSSBOW_HOLD;
            case "bow" -> HumanoidModel.ArmPose.BOW_AND_ARROW;
            case "spear" -> HumanoidModel.ArmPose.THROW_SPEAR;
            case "block" -> HumanoidModel.ArmPose.BLOCK;
            case "item" -> HumanoidModel.ArmPose.ITEM;
            case "pistol" -> PISTOL.getValue();
            case "staff" -> STAFF.getValue();
            case "support" -> SUPPORT.getValue();
            default -> null;
        };
    }

    private static void pistol(HumanoidModel<?> model, LivingEntity state, HumanoidArm arm) {
        ModelPart a = arm == HumanoidArm.RIGHT ? model.rightArm : model.leftArm;
        a.xRot = model.head.xRot - Mth.HALF_PI;
        a.yRot = model.head.yRot + (arm == HumanoidArm.RIGHT ? -0.08F : 0.08F);
        a.zRot = 0.0F;
    }

    private static void staff(HumanoidModel<?> model, LivingEntity state, HumanoidArm arm) {
        PosePlayer.Binding b = PosePlayer.binding(state.getMainHandItem());
        set(arm == HumanoidArm.RIGHT ? model.rightArm : model.leftArm, b == null || b.main() == null ? STAFF_MAIN : b.main(), arm);
    }

    private static void support(HumanoidModel<?> model, LivingEntity state, HumanoidArm arm) {
        PosePlayer.Binding b = PosePlayer.binding(state.getMainHandItem());
        ModelPart main = arm == HumanoidArm.RIGHT ? model.rightArm : model.leftArm;
        ModelPart off = arm == HumanoidArm.RIGHT ? model.leftArm : model.rightArm;
        set(main, b == null || b.main() == null ? SUPPORT_MAIN : b.main(), arm);
        float side = arm == HumanoidArm.RIGHT ? 1.0F : -1.0F;
        float[] point = b == null || b.support() == null ? SUPPORT_POINT : b.support();
        // ModelPart turns by rotationZYX(z, y, x): the main hand, then the support point in the main arm's frame
        Quaternionf turn = new Quaternionf().rotationZYX(main.zRot, main.yRot, main.xRot);
        Vector3f grip = turn.transform(new Vector3f(side * point[0], HAND + point[1], point[2])).add(main.x, main.y, main.z);
        Vector3f d = grip.sub(off.x, off.y, off.z).normalize();
        // aim the off arm's length (0, 1, 0) along d: with z = 0 it points at (sin x sin y, cos x, sin x cos y)
        off.xRot = -(float) Math.acos(Mth.clamp(d.y, -1.0F, 1.0F));
        float s = Mth.sin(off.xRot);
        off.yRot = Math.abs(s) < 1.0E-4F ? 0.0F : (float) Mth.atan2(d.x / s, d.z / s);
        off.zRot = 0.0F;
    }

    private static void set(ModelPart part, float[] degrees, HumanoidArm arm) {
        float m = arm == HumanoidArm.RIGHT ? 1.0F : -1.0F;
        part.xRot = degrees[0] * Mth.DEG_TO_RAD;
        part.yRot = m * degrees[1] * Mth.DEG_TO_RAD;
        part.zRot = m * degrees[2] * Mth.DEG_TO_RAD;
    }

    private static void clip(HumanoidModel<?> model, LivingEntity state, HumanoidArm arm) {
        PosePlayer.applyArms(model, PosePlayer.sample(state, Minecraft.getInstance().getFrameTime()), state.getMainArm());
    }

    private ArmPoses() {}
}
