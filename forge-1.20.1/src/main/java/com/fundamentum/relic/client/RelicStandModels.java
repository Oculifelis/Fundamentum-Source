package com.fundamentum.relic.client;

import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.EntityRenderersEvent;

/**
 * Fundamentum's relic stand ({@code fundamentum:relic_stand}): a gold pedestal with a diamond, at 1x1
 * ({@code fundamentum:relic_stand_1x1}, top 10.25 px) and 3x3 ({@code fundamentum:relic_stand_3x3}, top 30.75 px). The
 * renderer scales them for the other sizes. Model space as for mobs: y down, front toward -z.
 */
@Mod.EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class RelicStandModels {
    public static final ModelLayerLocation SMALL = new ModelLayerLocation(new ResourceLocation("fundamentum", "relic_stand_1x1"), "main");
    public static final ModelLayerLocation LARGE = new ModelLayerLocation(new ResourceLocation("fundamentum", "relic_stand_3x3"), "main");

    private RelicStandModels() {}

    @SubscribeEvent
    public static void layers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(SMALL, RelicStandModels::small);
        event.registerLayerDefinition(LARGE, RelicStandModels::large);
    }

    public static LayerDefinition small() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition base = mesh.getRoot().addOrReplaceChild("base", CubeListBuilder.create()
                .texOffs(0, 0).addBox(-7.0F, -2.0F, -7.0F, 14.0F, 2.0F, 14.0F)
                .texOffs(0, 45).addBox(-6.0F, -3.0F, -6.0F, 12.0F, 1.0F, 12.0F)
                .texOffs(0, 16).addBox(-5.0F, -8.0F, -5.0F, 10.0F, 5.0F, 10.0F)
                .texOffs(16, 82).addBox(-4.5F, -7.5F, -5.2F, 9.0F, 4.0F, 1.0F)
                .texOffs(36, 82).addBox(-1.5F, -7.0F, -5.6F, 3.0F, 3.0F, 1.0F)
                .texOffs(0, 58).addBox(-6.0F, -9.0F, -6.0F, 12.0F, 1.0F, 12.0F)
                .texOffs(0, 31).addBox(-6.5F, -10.0F, -6.5F, 13.0F, 1.0F, 13.0F)
                .texOffs(0, 71).addBox(-5.0F, -10.25F, -5.0F, 10.0F, 1.0F, 10.0F),
            PartPose.ZERO);
        base.addOrReplaceChild("scroll-1", CubeListBuilder.create()
                .texOffs(40, 71).addBox(-1.0F, -2.5F, -3.0F, 2.0F, 5.0F, 6.0F),
            PartPose.offsetAndRotation(-5.5F, -6.0F, 0.0F, 0.0F, 0.0F, 0.35F));
        base.addOrReplaceChild("scroll1", CubeListBuilder.create()
                .texOffs(0, 82).addBox(-1.0F, -2.5F, -3.0F, 2.0F, 5.0F, 6.0F),
            PartPose.offsetAndRotation(5.5F, -6.0F, 0.0F, 0.0F, 0.0F, -0.35F));
        return LayerDefinition.create(mesh, 64, 128);
    }

    public static LayerDefinition large() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition base = mesh.getRoot().addOrReplaceChild("base", CubeListBuilder.create()
                .texOffs(0, 0).addBox(-21.0F, -6.0F, -21.0F, 42.0F, 6.0F, 42.0F)
                .texOffs(0, 135).addBox(-18.0F, -9.0F, -18.0F, 36.0F, 3.0F, 36.0F)
                .texOffs(0, 48).addBox(-15.0F, -24.0F, -15.0F, 30.0F, 15.0F, 30.0F)
                .texOffs(0, 246).addBox(-13.5F, -22.5F, -15.6F, 27.0F, 12.0F, 3.0F)
                .texOffs(60, 246).addBox(-4.5F, -21.0F, -16.8F, 9.0F, 9.0F, 3.0F)
                .texOffs(0, 174).addBox(-18.0F, -27.0F, -18.0F, 36.0F, 3.0F, 36.0F)
                .texOffs(0, 93).addBox(-19.5F, -30.0F, -19.5F, 39.0F, 3.0F, 39.0F)
                .texOffs(0, 213).addBox(-15.0F, -30.75F, -15.0F, 30.0F, 3.0F, 30.0F),
            PartPose.ZERO);
        base.addOrReplaceChild("scroll-1", CubeListBuilder.create()
                .texOffs(120, 213).addBox(-3.0F, -7.5F, -9.0F, 6.0F, 15.0F, 18.0F),
            PartPose.offsetAndRotation(-16.5F, -18.0F, 0.0F, 0.0F, 0.0F, 0.35F));
        base.addOrReplaceChild("scroll1", CubeListBuilder.create()
                .texOffs(168, 213).addBox(-3.0F, -7.5F, -9.0F, 6.0F, 15.0F, 18.0F),
            PartPose.offsetAndRotation(16.5F, -18.0F, 0.0F, 0.0F, 0.0F, -0.35F));
        return LayerDefinition.create(mesh, 256, 512);
    }
}
