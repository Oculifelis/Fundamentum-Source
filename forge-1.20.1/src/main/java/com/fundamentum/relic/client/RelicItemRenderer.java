package com.fundamentum.relic.client;

import com.fundamentum.relic.RelicBlock;
import com.fundamentum.relic.RelicSet;
import com.fundamentum.relic.Relics;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.ItemTransform;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import net.minecraftforge.client.model.BakedModelWrapper;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Every relic item draws as a small copy of its relic ({@link RelicRenderer#drawItem}), with a block's display transforms
 * (turned three-quarters in the inventory, small on the ground and in frames). Set on every registered relic's item, so a
 * mod's own item model for a relic only lends its particle.
 */
@Mod.EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class RelicItemRenderer extends BlockEntityWithoutLevelRenderer {
    private static final Logger LOG = LoggerFactory.getLogger("fundamentum");
    private static RelicItemRenderer instance;

    /** Vanilla's {@code block/block} display settings (translations in blocks). */
    static final ItemTransforms BLOCK = new ItemTransforms(
        t(75, 45, 0, 0, 2.5F, 0, 0.375F),
        t(75, 45, 0, 0, 2.5F, 0, 0.375F),
        t(0, 225, 0, 0, 0, 0, 0.4F),
        t(0, 45, 0, 0, 0, 0, 0.4F),
        ItemTransform.NO_TRANSFORM,
        t(30, 225, 0, 0, 0, 0, 0.625F),
        t(0, 0, 0, 0, 3, 0, 0.25F),
        t(0, 0, 0, 0, 0, 0, 0.5F));

    private RelicItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    private static ItemTransform t(float rx, float ry, float rz, float x, float y, float z, float s) {
        return new ItemTransform(new Vector3f(rx, ry, rz), new Vector3f(x / 16F, y / 16F, z / 16F), new Vector3f(s, s, s));
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        RelicRenderer r = RelicRenderer.current;
        if (r == null || !(stack.getItem() instanceof BlockItem bi) || !(bi.getBlock() instanceof RelicBlock block)) {
            return;
        }
        try {
            r.drawItem(block, pose, buffers, light, overlay);
        } catch (RuntimeException ex) {
            LOG.warn("cannot draw the relic item {}", block, ex);
        }
    }

    private static List<Item> items() {
        List<Item> out = new ArrayList<>();
        for (RelicSet set : Relics.sets()) {
            for (var holder : set.items().values()) {
                out.add(holder.get());
            }
        }
        return out;
    }

    /** Given to every relic item ({@link com.fundamentum.relic.RelicItem#initializeClient}). */
    public static final IClientItemExtensions EXTENSIONS = new IClientItemExtensions() {
        @Override
        public BlockEntityWithoutLevelRenderer getCustomRenderer() {
            if (instance == null) {
                instance = new RelicItemRenderer();
            }
            return instance;
        }
    };

    /** The item's own model stays for its particle; it is drawn by {@link #renderByItem} with a block's transforms. */
    @SubscribeEvent
    public static void models(ModelEvent.ModifyBakingResult event) {
        Map<ResourceLocation, BakedModel> models = event.getModels();
        for (Item item : items()) {
            ModelResourceLocation key = new ModelResourceLocation(BuiltInRegistries.ITEM.getKey(item), "inventory");
            BakedModel own = models.get(key);
            if (own != null) {
                models.put(key, new Small(own));
            }
        }
    }

    private static final class Small extends BakedModelWrapper<BakedModel> {
        Small(BakedModel own) {
            super(own);
        }

        @Override
        public boolean isCustomRenderer() {
            return true;
        }

        @Override
        public ItemTransforms getTransforms() {
            return BLOCK;
        }

        @Override
        public BakedModel applyTransform(ItemDisplayContext context, PoseStack pose, boolean leftHand) {
            BLOCK.getTransform(context).apply(leftHand, pose);
            return this;
        }

        @Override
        public boolean usesBlockLight() {
            return true;
        }

        @Override
        public boolean isGui3d() {
            return true;
        }
    }
}
