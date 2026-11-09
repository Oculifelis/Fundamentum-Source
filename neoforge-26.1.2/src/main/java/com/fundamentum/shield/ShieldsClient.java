package com.fundamentum.shield;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.object.equipment.ShieldModel;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BannerRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.SpriteGetter;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Unit;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterSpecialModelRendererEvent;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Client side of {@link Shields}: the {@code fundamentum:shield} special model, vanilla's shield drawn with its own
 * texture. In an item model: {@code {"type": "fundamentum:shield", "texture": "<ns>:<name>"}} reads
 * {@code <ns>:textures/entity/shield/<name>.png} (under banners) and {@code <name>_nopattern.png} (plain).
 */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class ShieldsClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Every shield texture ({@code <ns>:<name>}) an item model has asked for since the game started. */
    public static final Set<Identifier> BAKED = ConcurrentHashMap.newKeySet();
    /** Those of them with a picture missing from the shield atlas: they draw as the magenta and black missing texture. */
    public static final Set<Identifier> MISSING = ConcurrentHashMap.newKeySet();

    @SubscribeEvent
    public static void specials(RegisterSpecialModelRendererEvent event) {
        event.register(Identifier.fromNamespaceAndPath("fundamentum", "shield"), Unbaked.MAP_CODEC);
    }

    /** Vanilla's shield renderer with the sprites swapped. */
    public static final class Renderer implements SpecialModelRenderer<DataComponentMap> {
        private final SpriteGetter sprites;
        private final ShieldModel model;
        private final SpriteId base;
        private final SpriteId plain;

        Renderer(SpriteGetter sprites, ShieldModel model, Identifier texture) {
            this.sprites = sprites;
            this.model = model;
            this.base = Sheets.SHIELD_MAPPER.apply(texture);
            this.plain = Sheets.SHIELD_MAPPER.apply(texture.withSuffix("_nopattern"));
            BAKED.add(texture);
            for (SpriteId sprite : new SpriteId[] {base, plain}) {
                if (sprites.get(sprite).contents().name().equals(MissingTextureAtlasSprite.getLocation())) {
                    MISSING.add(texture);
                    LOGGER.error("Shield texture missing: {} wants {}:textures/{}.png", texture, sprite.texture().getNamespace(),
                        sprite.texture().getPath());
                }
            }
        }

        @Override
        public @Nullable DataComponentMap extractArgument(ItemStack stack) {
            return stack.immutableComponents();
        }

        @Override
        public void submit(@Nullable DataComponentMap components, PoseStack poseStack, SubmitNodeCollector collector,
            int light, int overlay, boolean foil, int outline) {
            BannerPatternLayers patterns = components != null
                ? components.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY) : BannerPatternLayers.EMPTY;
            DyeColor color = components != null ? components.get(DataComponents.BASE_COLOR) : null;
            boolean hasPatterns = !patterns.layers().isEmpty() || color != null;
            SpriteId sprite = hasPatterns ? base : plain;
            collector.submitModel(model, Unit.INSTANCE, poseStack, light, overlay, -1, sprite, sprites, outline, null);
            if (hasPatterns) {
                BannerRenderer.submitPatterns(sprites, poseStack, collector, light, overlay, model, Unit.INSTANCE, false,
                    Objects.requireNonNullElse(color, DyeColor.WHITE), patterns, null);
            }
            if (foil) {
                collector.submitModel(model, Unit.INSTANCE, poseStack, RenderTypes.entityGlint(), light, overlay, -1, sprites.get(sprite), 0, null);
            }
        }

        @Override
        public void getExtents(Consumer<Vector3fc> output) {
            model.root().getExtentsForGui(new PoseStack(), output);
        }
    }

    /** {@code {"type": "fundamentum:shield", "texture": "..."}}. */
    public record Unbaked(Identifier texture) implements SpecialModelRenderer.Unbaked<DataComponentMap> {
        public static final MapCodec<Unbaked> MAP_CODEC = Identifier.CODEC.fieldOf("texture").xmap(Unbaked::new, Unbaked::texture);

        @Override
        public MapCodec<Unbaked> type() {
            return MAP_CODEC;
        }

        @Override
        public Renderer bake(SpecialModelRenderer.BakingContext context) {
            return new Renderer(context.sprites(), new ShieldModel(context.entityModelSet().bakeLayer(ModelLayers.SHIELD)), texture);
        }
    }

    private ShieldsClient() {}
}
