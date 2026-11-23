package com.fundamentum.pose;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;

/**
 * Puts every player's arms in their held item's pose ({@link PosePlayer}, {@link ArmPoses}). A playing clip wins; then
 * the binding's "arms" hold. Either only replaces vanilla's plain poses (empty hand, ITEM), so a mod that picks its own
 * pose in {@code getArmPose} keeps it. On 1.20.1 (no render states) this sets the player model's arm poses just before
 * it is drawn, after vanilla picked its own.
 */
@Mod.EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class PoseClient {
    @SubscribeEvent
    public static void pose(RenderPlayerEvent.Pre event) {
        Player entity = event.getEntity();
        PlayerModel<?> model = event.getRenderer().getModel();
        PosePlayer.Sample sample = PosePlayer.sample(entity, event.getPartialTick());
        ItemStack stack = entity.getMainHandItem();
        PosePlayer.Binding binding = PosePlayer.binding(stack);
        HumanoidModel.ArmPose pose = null;
        if (sample != null && sample.thirdPerson()) {
            pose = ArmPoses.CLIP.getValue();
        } else if (binding != null && ("always".equals(binding.when())
            || "using".equals(binding.when()) && entity.isUsingItem() && entity.getUseItem() == stack)) {
            pose = ArmPoses.of(binding.arms());
        }
        if (pose == null) {
            return;
        }
        boolean right = entity.getMainArm() == HumanoidArm.RIGHT;
        HumanoidModel.ArmPose current = right ? model.rightArmPose : model.leftArmPose;
        if (current != HumanoidModel.ArmPose.EMPTY && current != HumanoidModel.ArmPose.ITEM && current != pose) {
            return;
        }
        if (right) {
            model.rightArmPose = pose;
            if (pose.isTwoHanded()) {
                model.leftArmPose = HumanoidModel.ArmPose.EMPTY;
            }
        } else {
            model.leftArmPose = pose;
            if (pose.isTwoHanded()) {
                model.rightArmPose = HumanoidModel.ArmPose.EMPTY;
            }
        }
    }

    /** Bindings and clips are read again after a resource reload. */
    @Mod.EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBus {
        @SubscribeEvent
        public static void setup(net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event) {
            event.enqueueWork(ArmPoses::bootstrap);
        }

        @SubscribeEvent
        public static void reloadListeners(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener(new ResourceManagerReloadListener() {
                @Override
                public void onResourceManagerReload(ResourceManager manager) {
                    PosePlayer.reset();
                }
            });
        }
    }

    private PoseClient() {}
}
