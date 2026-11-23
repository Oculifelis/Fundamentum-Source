package com.fundamentum.biome;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraftforge.fml.ModList;

/**
 * TerraBlender, reached by reflection only (no build or run dependency): biome slices become one data-driven region per
 * dimension ({@code fundamentum:overworld}, {@code fundamentum:nether}), and surface rules go through its rule manager.
 * Written against TerraBlender 3.0.1.10 (1.20.1)'s {@code terrablender.api} (Regions, RegionType, SurfaceRuleManager); the region
 * itself is {@link TerraBlenderRegion}, compiled against TerraBlender.
 */
public final class TerraBlenderBridge {
    private TerraBlenderBridge() { }

    public static final ResourceLocation OVERWORLD_REGION = new ResourceLocation("fundamentum", "overworld");
    public static final ResourceLocation NETHER_REGION = new ResourceLocation("fundamentum", "nether");

    public static boolean present() {
        return ModList.get().isLoaded("terrablender");
    }

    private static Object enumValue(String className, String name) throws ReflectiveOperationException {
        Class<?> type = Class.forName(className);
        for (Object c : type.getEnumConstants()) if (((Enum<?>) c).name().equals(name)) return c;
        throw new NoSuchFieldException(className + "." + name);
    }

    private static Object regionType(boolean nether) throws ReflectiveOperationException {
        return enumValue("terrablender.api.RegionType", nether ? "NETHER" : "OVERWORLD");
    }

    /** Drops our region of that type, if registered (on every world load, so a reload never stacks two). */
    public static void remove(boolean nether) throws ReflectiveOperationException {
        Object type = regionType(nether);
        Class<?> regions = Class.forName("terrablender.api.Regions");
        Class<?> regionType = Class.forName("terrablender.api.RegionType");
        // Regions.remove checks the name itself (getIndex would throw for a region that is not there)
        regions.getMethod("remove", regionType, ResourceLocation.class).invoke(null, type, nether ? NETHER_REGION : OVERWORLD_REGION);
    }

    /**
     * Registers the sliced list as our region, with that weight. TerraBlender 3.0 has no data-driven regions, so the
     * region is our own subclass (TerraBlenderRegion), loaded only here, when TerraBlender is installed.
     */
    public static void register(boolean nether, Climate.ParameterList<Holder<Biome>> list, int weight) throws ReflectiveOperationException {
        List<com.mojang.datafixers.util.Pair<Climate.ParameterPoint, ResourceKey<Biome>>> points = new ArrayList<>();
        for (var pair : list.values()) points.add(com.mojang.datafixers.util.Pair.of(pair.getFirst(), pair.getSecond().unwrapKey().orElseThrow()));
        Object region = TerraBlenderRegion.create(nether ? NETHER_REGION : OVERWORLD_REGION, nether, weight, points);
        Class<?> regionClass = Class.forName("terrablender.api.Region");
        Class.forName("terrablender.api.Regions").getMethod("register", regionClass).invoke(null, region);
    }

    private static Object ruleCategory(boolean nether) throws ReflectiveOperationException {
        return enumValue("terrablender.api.SurfaceRuleManager$RuleCategory", nether ? "NETHER" : "OVERWORLD");
    }

    /**
     * True when TerraBlender already holds surface rules for that namespace in that dimension (another mod's, as ours are
     * removed first). Its rule manager overwrites a namespace's rules on add, so such a namespace is left alone.
     */
    public static boolean hasSurfaceRules(boolean nether, String namespace) throws ReflectiveOperationException {
        java.lang.reflect.Field field = Class.forName("terrablender.api.SurfaceRuleManager").getDeclaredField("surfaceRules");
        field.setAccessible(true);
        Object byCategory = ((java.util.Map<?, ?>) field.get(null)).get(ruleCategory(nether));
        return byCategory instanceof java.util.Map<?, ?> rules && rules.containsKey(namespace);
    }

    /** Sets (null: clears) the surface rules TerraBlender adds for one namespace in one dimension. */
    public static void surfaceRules(boolean nether, String namespace, SurfaceRules.RuleSource rule) throws ReflectiveOperationException {
        Class<?> manager = Class.forName("terrablender.api.SurfaceRuleManager");
        Class<?> category = Class.forName("terrablender.api.SurfaceRuleManager$RuleCategory");
        Object cat = ruleCategory(nether);
        manager.getMethod("removeSurfaceRules", category, String.class).invoke(null, cat, namespace);
        if (rule != null) {
            Method add = manager.getMethod("addSurfaceRules", category, String.class, SurfaceRules.RuleSource.class);
            add.invoke(null, cat, namespace, rule);
        }
    }
}
