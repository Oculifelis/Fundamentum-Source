package com.fundamentum.multiblock.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterPictureInPictureRenderersEvent;
import org.joml.Quaternionf;
import org.joml.Vector2f;

/**
 * Draws a {@link GuiMultiblockRenderState}: every block of the scene as its real block model, lit like a block item. The
 * picture is kept and only drawn again when the scene, the angles, the box or the block models change. Screens call
 * {@link #submit}.
 */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class GuiMultiblockRenderer extends PictureInPictureRenderer<GuiMultiblockRenderState> {
    /** The usual view: a three-quarter angle from the front left, looking down. */
    public static final float YAW = 225;
    public static final float PITCH = 30;

    private record Placed(BlockPos pos, List<BakedQuad> quads) { }

    private static final Map<MultiblockScene.Scene, List<Placed>> QUADS = new WeakHashMap<>();
    private static BlockStateModelSet quadsWith;

    private GuiMultiblockRenderState drawn;
    private BlockStateModelSet drawnWith;

    public GuiMultiblockRenderer(MultiBufferSource.BufferSource buffers) {
        super(buffers);
    }

    @SubscribeEvent
    public static void register(RegisterPictureInPictureRenderersEvent event) {
        event.register(GuiMultiblockRenderState.class, GuiMultiblockRenderer::new);
    }

    /**
     * Queues a scene drawn to fit the box {@code x}, {@code y}, {@code w} by {@code h} in GUI pixels, turned {@code yaw}
     * and tilted {@code pitch} degrees.
     */
    public static void submit(GuiGraphicsExtractor g, MultiblockScene.Scene scene, int x, int y, int w, int h, float yaw, float pitch) {
        if (scene == null || w <= 0 || h <= 0) return;
        // the picture is placed in screen space, so the box goes through the current pose (viewers draw translated)
        Vector2f a = g.pose().transformPosition(x, y, new Vector2f());
        Vector2f b = g.pose().transformPosition(x + w, y + h, new Vector2f());
        int x0 = Math.round(Math.min(a.x, b.x));
        int y0 = Math.round(Math.min(a.y, b.y));
        int x1 = Math.round(Math.max(a.x, b.x));
        int y1 = Math.round(Math.max(a.y, b.y));
        if (x1 <= x0 || y1 <= y0) return;
        float scale = fit(scene, x1 - x0, y1 - y0, pitch);
        g.submitPictureInPictureRenderState(new GuiMultiblockRenderState(scene, yaw, pitch, x0, y0, x1, y1, scale, g.peekScissorStack()));
    }

    /**
     * Pixels per block so the scene fills the box at any yaw: the widest the footprint gets is its diagonal, and the
     * height is the column seen at the pitch plus the footprint seen from above. A tenth is left as a margin.
     */
    static float fit(MultiblockScene.Scene scene, int w, int h, float pitch) {
        float dx = scene.max().getX() - scene.min().getX() + 1;
        float dy = scene.max().getY() - scene.min().getY() + 1;
        float dz = scene.max().getZ() - scene.min().getZ() + 1;
        float diag = (float) Math.sqrt(dx * dx + dz * dz);
        double p = Math.toRadians(pitch);
        float tall = (float) (dy * Math.cos(p) + diag * Math.abs(Math.sin(p)));
        return 0.9F * Math.min(w / diag, h / Math.max(1F, tall));
    }

    @Override
    public Class<GuiMultiblockRenderState> getRenderStateClass() {
        return GuiMultiblockRenderState.class;
    }

    @Override
    protected boolean textureIsReadyToBlit(GuiMultiblockRenderState state) {
        return state.equals(this.drawn) && Minecraft.getInstance().getModelManager().getBlockStateModelSet() == this.drawnWith;
    }

    @Override
    protected float getTranslateY(int height, int guiScale) {
        return height / 2.0F;
    }

    @Override
    protected String getTextureLabel() {
        return "fundamentum multiblock";
    }

    @Override
    protected void renderToTexture(GuiMultiblockRenderState state, PoseStack ps) {
        Minecraft mc = Minecraft.getInstance();
        BlockStateModelSet models = mc.getModelManager().getBlockStateModelSet();
        mc.gameRenderer.getLighting().setupFor(Lighting.Entry.ITEMS_3D);
        MultiblockScene.Scene scene = state.scene();
        ps.mulPose(new Quaternionf().rotateZ((float) Math.PI));
        ps.mulPose(new Quaternionf().rotateX((float) Math.toRadians(-state.pitch())));
        ps.mulPose(new Quaternionf().rotateY((float) Math.toRadians(state.yaw())));
        float cx = (scene.min().getX() + scene.max().getX() + 1) / 2F;
        float cy = (scene.min().getY() + scene.max().getY() + 1) / 2F;
        float cz = (scene.min().getZ() + scene.max().getZ() + 1) / 2F;
        VertexConsumer buffer = this.bufferSource.getBuffer(RenderTypes.itemTranslucent(TextureAtlas.LOCATION_BLOCKS));
        QuadInstance q = new QuadInstance();
        q.setColor(0xFFFFFFFF);
        q.setLightCoords(LightCoordsUtil.FULL_BRIGHT);
        for (Placed p : quads(scene, models)) {
            ps.pushPose();
            ps.translate(p.pos().getX() - cx, p.pos().getY() - cy, p.pos().getZ() - cz);
            for (BakedQuad quad : p.quads()) buffer.putBakedQuad(ps.last(), quad, q);
            ps.popPose();
        }
        this.drawn = state;
        this.drawnWith = models;
    }

    private static synchronized List<Placed> quads(MultiblockScene.Scene scene, BlockStateModelSet models) {
        if (models != quadsWith) {
            QUADS.clear();
            quadsWith = models;
        }
        return QUADS.computeIfAbsent(scene, s -> {
            List<Placed> out = new ArrayList<>(s.cells().size());
            List<BlockStateModelPart> parts = new ArrayList<>();
            RandomSource random = RandomSource.create();
            for (MultiblockScene.Cell c : s.cells()) {
                parts.clear();
                random.setSeed(c.state().getSeed(c.pos()));
                models.get(c.state()).collectParts(random, parts);
                List<BakedQuad> quads = new ArrayList<>();
                for (BlockStateModelPart part : parts) {
                    quads.addAll(part.getQuads(null));
                    for (Direction dir : Direction.values()) quads.addAll(part.getQuads(dir));
                }
                out.add(new Placed(c.pos(), quads));
            }
            return out;
        });
    }
}
