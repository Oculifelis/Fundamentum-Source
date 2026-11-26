package com.fundamentum.quest.client;

import com.fundamentum.port.FId;
import com.fundamentum.quest.NpcDef;
import com.fundamentum.quest.QuestNpc;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

/** Draws a quest giver with the player model (wide or slim) and the skin its NPC type names. */
public class QuestNpcRenderer extends LivingEntityRenderer<QuestNpc, AvatarRenderState, PlayerModel> {
    private static final Map<String, PlayerSkin> SKINS = new ConcurrentHashMap<>();
    private final PlayerModel wide;
    private final PlayerModel slim;

    public QuestNpcRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new PlayerModel(ctx.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        wide = model;
        slim = new PlayerModel(ctx.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        addLayer(new ItemInHandLayer<>(this));
    }

    @Override
    public AvatarRenderState createRenderState() {
        return new AvatarRenderState();
    }

    @Override
    public void extractRenderState(QuestNpc npc, AvatarRenderState state, float partialTicks) {
        super.extractRenderState(npc, state, partialTicks);
        HumanoidMobRenderer.extractHumanoidRenderState(npc, state, partialTicks, itemModelResolver);
        state.skin = skin(npc.texture(), npc.slim());
        state.showCape = false;
        state.id = npc.getId();
    }

    private static PlayerSkin skin(String texture, boolean slim) {
        return SKINS.computeIfAbsent(texture + (slim ? "|slim" : "|wide"), k -> {
            FId id = FId.tryOf(texture);
            Identifier path = (id == null ? FId.of(NpcDef.DEFAULT_TEXTURE) : id).vanilla();
            return PlayerSkin.insecure(new ClientAsset.ResourceTexture(path, path), null, null,
                slim ? PlayerModelType.SLIM : PlayerModelType.WIDE);
        });
    }

    @Override
    public Identifier getTextureLocation(AvatarRenderState state) {
        return state.skin.body().texturePath();
    }

    @Override
    public void submit(AvatarRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        model = state.skin.model() == PlayerModelType.SLIM ? slim : wide;
        super.submit(state, poseStack, collector, camera);
    }

    @Override
    protected void scale(AvatarRenderState state, PoseStack poseStack) {
        poseStack.scale(0.9375F, 0.9375F, 0.9375F);
    }
}
