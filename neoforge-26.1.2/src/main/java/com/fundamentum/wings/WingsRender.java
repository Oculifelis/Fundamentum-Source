package com.fundamentum.wings;

import com.fundamentum.slots.Slots;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.entity.ClientAvatarEntity;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.object.equipment.ElytraModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.renderstate.AvatarRenderStateModifier;
import net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent;

/**
 * Draws wings worn in the {@code wings} Curios slot (1.15.0). A wings item whose {@code minecraft:equippable} component
 * names an equipment asset with a {@code wings} layer is drawn on the vanilla elytra model with that asset's texture,
 * like an elytra in the chest slot; the cosmetic wings slot wins over the real one, and a glider in the chest slot is
 * left to vanilla. Give the item an equippable with {@code "slot": "body"} and no allowed entities, so nothing can wear
 * it anywhere but the slot. Wings without such a component are still the owning mod's to draw. Since 1.28.0 the
 * drawn wings take their pose from {@link WingsAnim} (folded, rising with beats, gliding) instead of vanilla's elytra
 * swing, which only knows fall-flying.
 */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class WingsRender {
    /** The wings stack to draw on a player this frame, set from the wings slot. */
    public static final ContextKey<ItemStack> WORN = new ContextKey<>(Identifier.fromNamespaceAndPath("fundamentum", "worn_wings"));

    @SubscribeEvent
    public static void renderStates(RegisterRenderStateModifiersEvent event) {
        event.registerAvatarEntityModifier(new AvatarRenderStateModifier() {
            @Override
            public <T extends Avatar & ClientAvatarEntity> void accept(T entity, AvatarRenderState state) {
                if (!(entity instanceof Player player) || state.chestEquipment.has(DataComponents.GLIDER)) {
                    return;
                }
                ItemStack wings = Slots.shown(player, Slots.WINGS);
                Equippable equippable = wings.get(DataComponents.EQUIPPABLE);
                if (equippable != null && equippable.assetId().isPresent()) {
                    state.setRenderData(WORN, wings);
                    WingsAnim.Pose pose = WingsAnim.pose(player, state.partialTick);
                    if (pose != null) {
                        state.elytraRotX = pose.xRot();
                        state.elytraRotY = pose.yRot();
                        state.elytraRotZ = pose.zRot();
                    }
                }
            }
        });
    }

    @SubscribeEvent
    public static void layers(EntityRenderersEvent.AddLayers event) {
        for (var skin : event.getSkins()) {
            AvatarRenderer<AbstractClientPlayer> renderer = event.getPlayerRenderer(skin);
            if (renderer != null) {
                renderer.addLayer(new Layer(renderer, event.getEntityModels(), event.getContext().getEquipmentRenderer()));
            }
        }
    }

    /** The elytra-shaped layer, as vanilla's WingsLayer but fed from the slot. */
    public static final class Layer extends RenderLayer<AvatarRenderState, PlayerModel> {
        private final ElytraModel model;
        private final ElytraModel babyModel;
        private final EquipmentLayerRenderer equipment;

        public Layer(RenderLayerParent<AvatarRenderState, PlayerModel> parent, EntityModelSet models, EquipmentLayerRenderer equipment) {
            super(parent);
            this.model = new ElytraModel(models.bakeLayer(ModelLayers.ELYTRA));
            this.babyModel = new ElytraModel(models.bakeLayer(ModelLayers.ELYTRA_BABY));
            this.equipment = equipment;
        }

        @Override
        public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state, float yRot, float xRot) {
            ItemStack wings = state.getRenderData(WORN);
            if (wings == null || wings.isEmpty()) {
                return;
            }
            Equippable equippable = wings.get(DataComponents.EQUIPPABLE);
            if (equippable == null || equippable.assetId().isEmpty()) {
                return;
            }
            poseStack.pushPose();
            poseStack.translate(0.0F, 0.0F, 0.125F);
            this.equipment.renderLayers(EquipmentClientInfo.LayerType.WINGS, equippable.assetId().get(), state.isBaby ? this.babyModel : this.model,
                state, wings, poseStack, collector, light, null, state.outlineColor, 0);
            poseStack.popPose();
        }
    }

    private WingsRender() {}
}
