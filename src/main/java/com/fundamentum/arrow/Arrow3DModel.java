package com.fundamentum.arrow;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.entity.state.ArrowRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * A small 3D arrow along +X (head at +X): a 1x1 shaft, a 2x2 head with a 1x1 tip and two crossed feather fins.
 * Texture layout (32x32), so mods can paint their own: shaft at (0,0), head at (0,4), tip at (10,4), upright fin at
 * (0,10), flat fin at (0,16); see fundamentum:textures/entity/arrow/arrow.png.
 */
public class Arrow3DModel extends EntityModel<ArrowRenderState> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath("fundamentum", "arrow"), "main");

    public Arrow3DModel(ModelPart root) {
        super(root, RenderTypes::entityCutoutCull);
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("shaft", CubeListBuilder.create().texOffs(0, 0).addBox(-11.0F, -0.5F, -0.5F, 14.0F, 1.0F, 1.0F), PartPose.ZERO);
        root.addOrReplaceChild("head", CubeListBuilder.create()
            .texOffs(0, 4).addBox(3.0F, -1.0F, -1.0F, 2.0F, 2.0F, 2.0F)
            .texOffs(10, 4).addBox(5.0F, -0.5F, -0.5F, 1.0F, 1.0F, 1.0F), PartPose.ZERO);
        root.addOrReplaceChild("fin_upright", CubeListBuilder.create().texOffs(0, 10).addBox(-11.0F, -1.5F, 0.0F, 4.0F, 3.0F, 0.0F), PartPose.ZERO);
        root.addOrReplaceChild("fin_flat", CubeListBuilder.create().texOffs(0, 16).addBox(-11.0F, 0.0F, -1.5F, 4.0F, 0.0F, 3.0F), PartPose.ZERO);
        return LayerDefinition.create(mesh, 32, 32);
    }

    @Override
    public void setupAnim(ArrowRenderState state) {
        super.setupAnim(state);
        if (state.shake > 0.0F) {
            float pow = -Mth.sin(state.shake * 3.0F) * state.shake;
            this.root.zRot += pow * (float) (Math.PI / 180.0);
        }
    }
}
