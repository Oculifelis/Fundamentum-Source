package com.fundamentum.attribute;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lifts vanilla's 1024 ceiling on max health, so a boss can have the health it was designed with (Tergeminus: 3,000).
 * Nothing below 1024 changes: the floor, the default and every value a vanilla mob uses stay as they were.
 * The ceiling lives in RangedAttribute's final maxValue field (f_22308_ under Forge's SRG names); it is found by name,
 * else as the one instance double holding 1024, and raised once at mod construction, on both sides.
 */
public final class HealthCap {
    private static final Logger LOG = LoggerFactory.getLogger("fundamentum");
    /** The new ceiling for max health. */
    public static final double MAX_HEALTH = 1_000_000.0;
    private static final double VANILLA = 1024.0;

    public static void lift() {
        Attribute a = Attributes.MAX_HEALTH.value();
        if (!(a instanceof RangedAttribute ranged)) {
            LOG.warn("max health is not a RangedAttribute ({}), its ceiling is left alone", a.getClass().getName());
            return;
        }
        try {
            Field f = ceiling(ranged);
            f.setAccessible(true);
            if (f.getDouble(ranged) < MAX_HEALTH) {
                f.setDouble(ranged, MAX_HEALTH);
            }
            LOG.info("max health ceiling is now {}", ranged.getMaxValue());
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.error("could not lift the max health ceiling; it stays at {}", VANILLA, e);
        }
    }

    private static Field ceiling(RangedAttribute a) throws NoSuchFieldException {
        for (String name : new String[] {"maxValue", "f_22308_"}) {
            try {
                return RangedAttribute.class.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // another mapping
            }
        }
        // unknown names: the instance double that holds 1024 on max health
        for (Field f : RangedAttribute.class.getDeclaredFields()) {
            if (f.getType() == double.class && !Modifier.isStatic(f.getModifiers())) {
                f.setAccessible(true);
                try {
                    if (f.getDouble(a) == VANILLA) {
                        return f;
                    }
                } catch (IllegalAccessException ignored) {
                    // try the next
                }
            }
        }
        throw new NoSuchFieldException("RangedAttribute ceiling");
    }

    private HealthCap() {}
}
