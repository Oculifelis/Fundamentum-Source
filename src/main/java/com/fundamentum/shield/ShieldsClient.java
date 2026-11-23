package com.fundamentum.shield;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ShieldModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.player.Input;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BannerRenderer;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.client.resources.model.Material;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Client side of {@link Shields}: every {@link ShieldTypeItem} is drawn as vanilla's shield with its own texture,
 * {@code <ns>:textures/entity/shield/<name>.png} (under banners) and {@code <name>_nopattern.png} (plain), gets the
 * {@code minecraft:blocking} item property, and moves at its {@code speed_while_blocking} while raised.
 */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ShieldsClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Shield items with a picture missing from the shield atlas: they draw as the magenta and black missing texture. */
    public static final Set<ResourceLocation> MISSING = ConcurrentHashMap.newKeySet();
    private static final ResourceLocation BLOCKING = ResourceLocation.withDefaultNamespace("blocking");

    @SubscribeEvent
    public static void extensions(RegisterClientExtensionsEvent event) {
        Item[] items = shieldItems();
        if (items.length > 0) {
            IClientItemExtensions ext = new IClientItemExtensions() {
                @Override
                public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                    return Renderer.INSTANCE;
                }
            };
            event.registerItem(ext, items);
        }
    }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            for (Item item : shieldItems()) {
                ItemProperties.register(item, BLOCKING, (stack, level, entity, seed) ->
                    entity != null && entity.isUsingItem() && entity.getUseItem() == stack ? 1.0F : 0.0F);
            }
        });
        NeoForge.EVENT_BUS.addListener(ShieldsClient::movement);
    }

    private static Item[] shieldItems() {
        return BuiltInRegistries.ITEM.stream().filter(i -> i instanceof ShieldTypeItem).toArray(Item[]::new);
    }

    /** Vanilla multiplies the input by 0.2 while an item is used, after this event; scale it to the shield's own speed. */
    private static void movement(MovementInputUpdateEvent event) {
        Player player = event.getEntity();
        if (!player.isUsingItem() || player.isPassenger()) {
            return;
        }
        ShieldStats stats = Shields.stats(player.getUseItem());
        if (stats == null || stats.speedWhileBlocking() == 0.2) {
            return;
        }
        float factor = (float) (stats.speedWhileBlocking() / 0.2);
        Input input = event.getInput();
        input.leftImpulse *= factor;
        input.forwardImpulse *= factor;
    }

    /** Vanilla's shield drawing with the item's own sprites. */
    static final class Renderer extends BlockEntityWithoutLevelRenderer {
        static final Renderer INSTANCE = new Renderer();
        private final Map<Item, Material[]> materials = new ConcurrentHashMap<>();
        private ShieldModel model;

        private Renderer() {
            super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
        }

        private Material[] materials(Item item) {
            return materials.computeIfAbsent(item, i -> {
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(i);
                ResourceLocation tex = ResourceLocation.fromNamespaceAndPath(id.getNamespace(), "entity/shield/" + id.getPath());
                Material[] m = {new Material(Sheets.SHIELD_SHEET, tex), new Material(Sheets.SHIELD_SHEET, tex.withSuffix("_nopattern"))};
                for (Material material : m) {
                    if (material.sprite().contents().name().equals(MissingTextureAtlasSprite.getLocation())) {
                        MISSING.add(id);
                        LOGGER.error("Shield texture missing: {} wants {}:textures/{}.png", id, material.texture().getNamespace(), material.texture().getPath());
                    }
                }
                return m;
            });
        }

        @Override
        public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
            if (model == null) {
                model = new ShieldModel(Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.SHIELD));
            }
            BannerPatternLayers patterns = stack.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY);
            DyeColor color = stack.get(DataComponents.BASE_COLOR);
            boolean hasPatterns = !patterns.layers().isEmpty() || color != null;
            Material[] m = materials(stack.getItem());
            Material material = hasPatterns ? m[0] : m[1];
            pose.pushPose();
            pose.scale(1.0F, -1.0F, -1.0F);
            VertexConsumer consumer = material.sprite()
                .wrap(ItemRenderer.getFoilBufferDirect(buffers, model.renderType(material.atlasLocation()), true, stack.hasFoil()));
            model.handle().render(pose, consumer, light, overlay);
            if (hasPatterns) {
                BannerRenderer.renderPatterns(pose, buffers, light, overlay, model.plate(), material, false,
                    Objects.requireNonNullElse(color, DyeColor.WHITE), patterns, stack.hasFoil());
            } else {
                model.plate().render(pose, consumer, light, overlay);
            }
            pose.popPose();
        }
    }

    private ShieldsClient() {}
}
