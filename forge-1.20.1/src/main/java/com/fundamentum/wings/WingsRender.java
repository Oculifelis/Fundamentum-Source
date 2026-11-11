package com.fundamentum.wings;

import com.fundamentum.curios.Slots;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.model.ElytraModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

/**
 * Draws wings worn in the {@code wings} Curios slot (1.28.0). This version has no equipment assets, so wings whose data
 * names a {@code texture} (see {@link Wings.Stats#texture()}) are drawn on the vanilla elytra model with it, posed by
 * {@link WingsAnim}; the cosmetic wings slot wins over the real one, and an elytra in the chest slot is left to vanilla.
 * Wings without a texture are still the owning mod's to draw.
 */
public final class WingsRender {
    private static final Map<String, ResourceLocation> TEXTURES = new ConcurrentHashMap<>();

    /** The wings stack to draw on this player, or empty. */
    static ItemStack drawn(AbstractClientPlayer player) {
        if (!Slots.curiosLoaded() || player.getItemBySlot(EquipmentSlot.CHEST).canElytraFly(player)) {
            return ItemStack.EMPTY;
        }
        ItemStack wings = Slots.cosmetic(player, Slots.WINGS);
        return wings.isEmpty() ? Slots.equipped(player, Slots.WINGS) : wings;
    }

    /**
     * The elytra-model texture: the wings data's {@code texture} if set, else
     * {@code <namespace>:textures/entity/equipment/wings/<item path>.png} when that file exists (the file a 26.x equipment
     * asset's wings layer uses), else null.
     */
    static @Nullable ResourceLocation texture(ItemStack wings) {
        String id = Wings.stats(wings).texture();
        if (id != null) {
            return TEXTURES.computeIfAbsent(id, ResourceLocation::new);
        }
        ResourceLocation item = ForgeRegistries.ITEMS.getKey(wings.getItem());
        if (item == null) {
            return null;
        }
        ResourceLocation found = TEXTURES.computeIfAbsent("item:" + item, k -> {
            ResourceLocation tex = new ResourceLocation(item.getNamespace(), "textures/entity/equipment/wings/" + item.getPath() + ".png");
            return Minecraft.getInstance().getResourceManager().getResource(tex).isPresent() ? tex : MISSING;
        });
        return found == MISSING ? null : found;
    }

    private static final ResourceLocation MISSING = new ResourceLocation("fundamentum", "missing");

    @Mod.EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    static final class ModBus {
        @SubscribeEvent
        public static void layers(EntityRenderersEvent.AddLayers event) {
            for (var skin : event.getSkins()) {
                if (event.getSkin(skin) instanceof PlayerRenderer renderer) {
                    renderer.addLayer(new Layer(renderer, event.getEntityModels()));
                }
            }
        }

        @SubscribeEvent
        public static void reload(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener((ResourceManagerReloadListener) manager -> TEXTURES.clear());
        }

        private ModBus() {}
    }

    /** The elytra model with its wings reachable, so {@link WingsAnim} can pose them. */
    static final class Model extends ElytraModel<AbstractClientPlayer> {
        private final ModelPart left;
        private final ModelPart right;

        Model(ModelPart root) {
            super(root);
            this.left = root.getChild("left_wing");
            this.right = root.getChild("right_wing");
        }

        void pose(WingsAnim.Pose pose) {
            left.xRot = pose.xRot();
            left.yRot = pose.yRot();
            left.zRot = pose.zRot();
            right.xRot = pose.xRot();
            right.yRot = -pose.yRot();
            right.zRot = -pose.zRot();
        }
    }

    /** The elytra-shaped layer, as vanilla's ElytraLayer but fed from the slot. */
    public static final class Layer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
        private final Model model;

        public Layer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent, EntityModelSet models) {
            super(parent);
            this.model = new Model(models.bakeLayer(ModelLayers.ELYTRA));
        }

        @Override
        public void render(PoseStack poseStack, MultiBufferSource buffers, int light, AbstractClientPlayer player, float limbSwing,
                           float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
            ItemStack wings = drawn(player);
            ResourceLocation texture = wings.isEmpty() ? null : texture(wings);
            if (texture == null || player.isInvisible()) {
                return;
            }
            poseStack.pushPose();
            poseStack.translate(0.0F, 0.0F, 0.125F);
            this.getParentModel().copyPropertiesTo(this.model);
            this.model.setupAnim(player, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
            WingsAnim.Pose pose = WingsAnim.pose(player, partialTick);
            if (pose != null) {
                this.model.pose(pose);
            }
            VertexConsumer consumer = ItemRenderer.getArmorFoilBuffer(buffers, RenderType.armorCutoutNoCull(texture), false, wings.hasFoil());
            this.model.renderToBuffer(poseStack, consumer, light, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);
            poseStack.popPose();
        }
    }

    private WingsRender() {}
}
