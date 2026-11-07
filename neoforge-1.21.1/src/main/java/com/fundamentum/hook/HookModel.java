package com.fundamentum.hook;

import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;

/**
 * The grappling hook (layer {@link #HOOK}: a shank along +X with a head, a point and four claws curling back) and one
 * chain link ({@link #LINK}: a 4x3 plate, drawn alternately flat and upright). Both share one 32x32 texture, so mods can
 * paint their own: shank at (0,0), head at (0,4), point at (10,4), up/down claws at (0,10), side claws at (0,14), claw
 * tips at (10,10), link at (0,20); see fundamentum:textures/entity/hook/hook.png.
 */
public final class HookModel {
    public static final ModelLayerLocation HOOK = new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath("fundamentum", "hook"), "hook");
    public static final ModelLayerLocation LINK = new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath("fundamentum", "hook"), "link");

    public static LayerDefinition createHookLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("shank", CubeListBuilder.create().texOffs(0, 0).addBox(-5.0F, -0.5F, -0.5F, 6.0F, 1.0F, 1.0F), PartPose.ZERO);
        root.addOrReplaceChild("head", CubeListBuilder.create()
            .texOffs(0, 4).addBox(1.0F, -1.0F, -1.0F, 2.0F, 2.0F, 2.0F)
            .texOffs(10, 4).addBox(3.0F, -0.5F, -0.5F, 1.0F, 1.0F, 1.0F), PartPose.ZERO);
        root.addOrReplaceChild("claws", CubeListBuilder.create()
            .texOffs(0, 10).addBox(1.0F, 1.0F, -0.5F, 1.0F, 2.0F, 1.0F)
            .texOffs(0, 10).addBox(1.0F, -3.0F, -0.5F, 1.0F, 2.0F, 1.0F)
            .texOffs(0, 14).addBox(1.0F, -0.5F, 1.0F, 1.0F, 1.0F, 2.0F)
            .texOffs(0, 14).addBox(1.0F, -0.5F, -3.0F, 1.0F, 1.0F, 2.0F)
            .texOffs(10, 10).addBox(0.0F, 2.0F, -0.5F, 1.0F, 1.0F, 1.0F)
            .texOffs(10, 10).addBox(0.0F, -3.0F, -0.5F, 1.0F, 1.0F, 1.0F)
            .texOffs(10, 10).addBox(0.0F, -0.5F, 2.0F, 1.0F, 1.0F, 1.0F)
            .texOffs(10, 10).addBox(0.0F, -0.5F, -3.0F, 1.0F, 1.0F, 1.0F), PartPose.ZERO);
        return LayerDefinition.create(mesh, 32, 32);
    }

    public static LayerDefinition createLinkLayer() {
        MeshDefinition mesh = new MeshDefinition();
        mesh.getRoot().addOrReplaceChild("link", CubeListBuilder.create().texOffs(0, 20).addBox(-2.0F, -1.5F, -0.5F, 4.0F, 3.0F, 1.0F), PartPose.ZERO);
        return LayerDefinition.create(mesh, 32, 32);
    }

    private HookModel() {}
}
