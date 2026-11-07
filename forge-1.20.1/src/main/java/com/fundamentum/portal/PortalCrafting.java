package com.fundamentum.portal;

import com.fundamentum.Fundamentum;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityTravelToDimensionEvent;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.registries.DeferredRegister;

/**
 * Portal crafting: an item entity about to travel through any portal (Nether, End, End gateway, or a modded portal
 * that teleports the usual way) is checked against the {@link PortalRecipe}s first. On a match it changes in place,
 * stays on this side and is pushed back out of the portal. The recipes are sent to clients for JEI
 * ({@code compat.jei}). When Architect's Palette is installed, an item one of its {@code warping} recipes takes is left
 * to it.
 */
public final class PortalCrafting {
    private PortalCrafting() { }

    private static final DeferredRegister<RecipeType<?>> TYPES = DeferredRegister.create(Registries.RECIPE_TYPE, Fundamentum.MODID);
    private static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS = DeferredRegister.create(Registries.RECIPE_SERIALIZER, Fundamentum.MODID);

    public static final RegistryObject<RecipeType<PortalRecipe>> TYPE = TYPES.register("portal_crafting",
            () -> RecipeType.simple(new ResourceLocation(Fundamentum.MODID, "portal_crafting")));
    public static final RegistryObject<RecipeSerializer<PortalRecipe>> SERIALIZER =
            SERIALIZERS.register("portal_crafting", () -> PortalRecipe.SERIALIZER);

    /** Set by the self-test: one line per item checked at a portal. */
    static java.util.function.Consumer<String> trace;

    private static final ResourceLocation WARPING = new ResourceLocation("architects_palette", "warping");

    public static void init(IEventBus modBus) {
        TYPES.register(modBus);
        SERIALIZERS.register(modBus);
        // 1.20.1 sends every recipe to clients itself
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGH, PortalCrafting::travel);
    }

    private static void travel(EntityTravelToDimensionEvent event) {
        if (!(event.getEntity() instanceof ItemEntity item) || !(item.level() instanceof ServerLevel level)) return;
        ItemStack stack = item.getItem();
        if (stack.isEmpty() || warping(level, stack)) return;
        for (PortalRecipe recipe : level.getServer().getRecipeManager().getAllRecipesFor(TYPE.get())) {
            if (!recipe.ingredient().test(stack)) continue;
            BlockState portal = portalAt(level, item, recipe);
            if (trace != null) trace.accept(stack + " to " + event.getDimension().location() + ": " + recipe.getId()
                    + " wants " + recipe.dimension().map(d -> d.location().toString()).orElse("any") + ", portal block " + portal);
            if (recipe.dimension().isPresent() && recipe.dimension().get() != event.getDimension()) continue;
            if (portal == null) continue;
            event.setCanceled(true);
            craft(level, item, recipe, portal);
            return;
        }
    }

    /** The portal block the item is in that the recipe accepts, or null. A recipe with no portal accepts any block. */
    private static BlockState portalAt(Level level, Entity item, PortalRecipe recipe) {
        if (recipe.portal().isEmpty()) return level.getBlockState(item.blockPosition());
        AABB box = item.getBoundingBox().inflate(0.1);
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            BlockState state = level.getBlockState(pos);
            if (recipe.portal().get().matches(state)) return state;
        }
        return null;
    }

    /** Replaces the thrown stack with its results (split into full stacks) and pushes them back out of the portal. */
    private static void craft(ServerLevel level, ItemEntity item, PortalRecipe recipe, BlockState portal) {
        ItemStack thrown = item.getItem();
        int total = thrown.getCount() * Math.max(1, recipe.result().getCount());
        Vec3 push = pushOut(item, portal);
        boolean first = true;
        while (total > 0) {
            ItemStack out = recipe.craft(thrown, 1);
            int n = Math.min(total, out.getMaxStackSize());
            out.setCount(n);
            total -= n;
            ItemEntity target = item;
            if (first) {
                item.setItem(out);
                first = false;
            } else {
                target = new ItemEntity(level, item.getX(), item.getY(), item.getZ(), out);
                level.addFreshEntity(target);
            }
            target.setPortalCooldown();
            target.setDeltaMovement(push);
            target.setPickUpDelay(10);
        }
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, item.getX(), item.getY() + 0.2, item.getZ(), 24, 0.2, 0.2, 0.2, 0.05);
        level.playSound(null, item.getX(), item.getY(), item.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 0.8F, 1.4F);
    }

    /** Back the way the item came, across a standing portal's face; straight up out of a lying one. */
    private static Vec3 pushOut(ItemEntity item, BlockState portal) {
        if (!portal.hasProperty(BlockStateProperties.HORIZONTAL_AXIS)) return new Vec3(0, 0.45, 0);
        Direction.Axis axis = portal.getValue(BlockStateProperties.HORIZONTAL_AXIS);
        Vec3 normal = axis == Direction.Axis.X ? new Vec3(0, 0, 1) : new Vec3(1, 0, 0);
        double side = -item.getDeltaMovement().dot(normal);
        Entity owner = item.getOwner();
        if (Math.abs(side) < 1.0E-3 && owner != null) side = owner.position().subtract(item.position()).dot(normal);
        if (Math.abs(side) < 1.0E-3) side = 1;
        return normal.scale(Math.signum(side) * 0.3).add(0, 0.25, 0);
    }

    /** True when Architect's Palette is installed and one of its warping recipes takes this stack. */
    private static boolean warping(ServerLevel level, ItemStack stack) {
        if (!ModList.get().isLoaded("architects_palette")) return false;
        for (Recipe<?> recipe : level.getServer().getRecipeManager().getRecipes()) {
            if (!WARPING.equals(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()))) continue;
            if (recipe.getIngredients().stream().anyMatch(i -> i.test(stack))) return true;
        }
        return false;
    }

    /** For the self-test: the recipe this stack would use through a portal leading to {@code dimension}, if any. */
    static Optional<PortalRecipe> find(ServerLevel level, ItemStack stack, ResourceKey<Level> dimension) {
        return level.getServer().getRecipeManager().getAllRecipesFor(TYPE.get()).stream()
                .filter(r -> r.ingredient().test(stack) && r.dimension().map(d -> d == dimension).orElse(true)).findFirst();
    }
}
