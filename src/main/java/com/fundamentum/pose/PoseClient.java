package com.fundamentum.pose;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.ClientAvatarEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent;
import net.neoforged.neoforge.client.renderstate.AvatarRenderStateModifier;

/**
 * Puts every player's arms in their held item's pose ({@link PosePlayer}, {@link ArmPoses}). A playing clip wins; then
 * the binding's "arms" hold. Either only replaces vanilla's plain poses (empty hand, ITEM), so a mod that picks its own
 * pose in {@code getArmPose} keeps it.
 */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class PoseClient {
    private static final AvatarRenderStateModifier MODIFIER = new AvatarRenderStateModifier() {
        @Override
        public <T extends Avatar & ClientAvatarEntity> void accept(T entity, AvatarRenderState state) {
            float pt = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
            PosePlayer.Sample sample = PosePlayer.sample(entity, pt);
            state.setRenderData(ArmPoses.SAMPLE, sample);
            ItemStack stack = entity.getMainHandItem();
            PosePlayer.Binding binding = PosePlayer.binding(stack);
            state.setRenderData(ArmPoses.BINDING, binding);
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
            boolean right = state.mainArm == HumanoidArm.RIGHT;
            HumanoidModel.ArmPose current = right ? state.rightArmPose : state.leftArmPose;
            if (current != HumanoidModel.ArmPose.EMPTY && current != HumanoidModel.ArmPose.ITEM && current != pose) {
                return;
            }
            if (right) {
                state.rightArmPose = pose;
                if (pose.isTwoHanded()) {
                    state.leftArmPose = HumanoidModel.ArmPose.EMPTY;
                }
            } else {
                state.leftArmPose = pose;
                if (pose.isTwoHanded()) {
                    state.rightArmPose = HumanoidModel.ArmPose.EMPTY;
                }
            }
        }
    };

    @SubscribeEvent
    public static void renderStates(RegisterRenderStateModifiersEvent event) {
        event.registerAvatarEntityModifier(MODIFIER);
    }

    /** Bindings and clips are read again after a resource reload. */
    @SubscribeEvent
    public static void reloadListeners(AddClientReloadListenersEvent event) {
        event.addListener(Identifier.fromNamespaceAndPath("fundamentum", "player_poses"), new ResourceManagerReloadListener() {
            @Override
            public void onResourceManagerReload(ResourceManager manager) {
                PosePlayer.reset();
            }
        });
    }

    private PoseClient() {}
}
