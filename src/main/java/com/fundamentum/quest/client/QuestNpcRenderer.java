package com.fundamentum.quest.client;

import com.fundamentum.port.FId;
import com.fundamentum.quest.NpcDef;
import com.fundamentum.quest.QuestNpc;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;

/** Draws a quest giver with the player model (wide or slim) and the skin its NPC type names. */
public class QuestNpcRenderer extends HumanoidMobRenderer<QuestNpc, PlayerModel<QuestNpc>> {
    private static final ResourceLocation DEFAULT = FId.of(NpcDef.DEFAULT_TEXTURE).vanilla();
    private final PlayerModel<QuestNpc> wide;
    private final PlayerModel<QuestNpc> slim;

    public QuestNpcRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        wide = model;
        slim = new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER_SLIM), true);
    }

    @Override
    public ResourceLocation getTextureLocation(QuestNpc npc) {
        FId id = FId.tryOf(npc.texture());
        return id == null ? DEFAULT : id.vanilla();
    }

    @Override
    public void render(QuestNpc npc, float yaw, float partialTicks, PoseStack poseStack, MultiBufferSource buffers, int light) {
        model = npc.slim() ? slim : wide;
        super.render(npc, yaw, partialTicks, poseStack, buffers, light);
    }

    @Override
    protected void scale(QuestNpc npc, PoseStack poseStack, float partialTicks) {
        poseStack.scale(0.9375F, 0.9375F, 0.9375F);
    }
}
