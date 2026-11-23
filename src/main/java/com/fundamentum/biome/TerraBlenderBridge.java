package com.fundamentum.biome;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.neoforged.fml.ModList;

/**
 * TerraBlender, reached by reflection only (no build or run dependency): biome slices become one data-driven region per
 * dimension ({@code fundamentum:overworld}, {@code fundamentum:nether}), and surface rules go through its rule manager.
 * Written against TerraBlender 26.1.2.0.3's {@code terrablender.api} (Region, Regions, RegionType, data.RegionDefinition,
 * data.BiomeMapping, data.DataDrivenRegion, SurfaceRuleManager).
 */
public final class TerraBlenderBridge {
    private TerraBlenderBridge() { }

    public static final Identifier OVERWORLD_REGION = Identifier.fromNamespaceAndPath("fundamentum", "overworld");
    public static final Identifier NETHER_REGION = Identifier.fromNamespaceAndPath("fundamentum", "nether");

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
        regions.getMethod("remove", regionType, Identifier.class).invoke(null, type, nether ? NETHER_REGION : OVERWORLD_REGION);
    }

    /** Registers the sliced list as our region, with that weight. */
    public static void register(boolean nether, Climate.ParameterList<Holder<Biome>> list, int weight) throws ReflectiveOperationException {
        Object type = regionType(nether);
        Class<?> regionType = Class.forName("terrablender.api.RegionType");
        Class<?> mapping = Class.forName("terrablender.api.data.BiomeMapping");
        Constructor<?> newMapping = mapping.getConstructor(Climate.ParameterPoint.class, ResourceKey.class);
        List<Object> mappings = new ArrayList<>();
        for (var pair : list.values()) {
            ResourceKey<Biome> key = pair.getSecond().unwrapKey().orElseThrow();
            mappings.add(newMapping.newInstance(pair.getFirst(), key));
        }
        Class<?> definition = Class.forName("terrablender.api.data.RegionDefinition");
        Object def = definition.getConstructor(Identifier.class, regionType, int.class, List.class)
            .newInstance(nether ? NETHER_REGION : OVERWORLD_REGION, type, weight, mappings);
        Object region = Class.forName("terrablender.api.data.DataDrivenRegion").getConstructor(definition).newInstance(def);
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
