package com.fundamentum.relic.client;

import com.fundamentum.relic.RelicBlock;
import com.fundamentum.relic.RelicDef;
import com.fundamentum.relic.Relics;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ModelRenderProperties;
import net.minecraft.client.renderer.item.SpecialModelWrapper;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.client.resources.model.cuboid.ItemTransforms;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The relic item ({@code fundamentum:relic}) draws as a small copy of the relic its data names
 * ({@link RelicRenderer#drawItem}), with a block's display transforms (turned three-quarters in the inventory, small on
 * the ground and in frames). A blank stack draws nothing. Set after the models bake; its particle is the stand's.
 */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class RelicItemRenderer implements SpecialModelRenderer<RelicDef> {
    private static final Logger LOG = LoggerFactory.getLogger("fundamentum");
    static final RelicItemRenderer INSTANCE = new RelicItemRenderer();

    /** Vanilla's {@code block/block} display settings (translations in blocks). */
    static final ItemTransforms BLOCK = new ItemTransforms(
        t(75, 45, 0, 0, 2.5F, 0, 0.375F),
        t(75, 45, 0, 0, 2.5F, 0, 0.375F),
        t(0, 225, 0, 0, 0, 0, 0.4F),
        t(0, 45, 0, 0, 0, 0, 0.4F),
        ItemTransform.NO_TRANSFORM,
        t(30, 225, 0, 0, 0, 0, 0.625F),
        t(0, 0, 0, 0, 3, 0, 0.25F),
        t(0, 0, 0, 0, 0, 0, 0.5F),
        t(0, 0, 0, 0, 0, 0, 0.5F));

    private RelicItemRenderer() {}

    private static ItemTransform t(float rx, float ry, float rz, float x, float y, float z, float s) {
        return new ItemTransform(new Vector3f(rx, ry, rz), new Vector3f(x / 16F, y / 16F, z / 16F), new Vector3f(s, s, s));
    }

    @Override
    public void submit(@Nullable RelicDef def, PoseStack pose, SubmitNodeCollector collector, int light, int overlay, boolean foil, int outline) {
        RelicRenderer r = RelicRenderer.current;
        if (def == null || r == null) {
            return;
        }
        try {
            r.drawItem(def, pose, collector, light, overlay, outline);
        } catch (RuntimeException ex) {
            LOG.warn("cannot draw a relic item", ex);
        }
    }

    @Override
    public void getExtents(Consumer<Vector3fc> output) {
        output.accept(new Vector3f(0F, 0F, 0F));
        output.accept(new Vector3f(1F, 1F, 1F));
    }

    @Override
    public @Nullable RelicDef extractArgument(ItemStack stack) {
        return Relics.RELIC.has(stack) ? Relics.TABLE.get(Relics.RELIC.get(stack).vanilla()) : null;
    }

    @SubscribeEvent
    public static void items(ModelEvent.ModifyBakingResult event) {
        var result = event.getBakingResult();
        Map<Identifier, ItemModel> items = result.itemStackModels();
        RelicBlock stand = Relics.STAND.anchor(1);
        BlockStateModel model = stand == null ? null : result.blockStateModels().get(stand.defaultBlockState());
        ModelRenderProperties props = new ModelRenderProperties(true,
            model != null ? model.particleMaterial() : result.missingModels().block().particleMaterial(), BLOCK);
        try {
            items.put(Relics.ITEM.id().vanilla(), new SpecialModelWrapper<>(INSTANCE, props, new Matrix4f()));
        } catch (UnsupportedOperationException ex) {
            LOG.warn("the relic item keeps its plain model: the baked item models cannot be changed");
        }
    }
}
