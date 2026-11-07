package com.fundamentum.moon;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.ModFileScanData;

/** Which mod a hook (listener, lambda) another mod handed Fundamentum belongs to, so a failure can name it. */
final class Owner {
    private static final Map<Class<?>, String> CACHE = new ConcurrentHashMap<>();

    private Owner() {}

    /** The owning mod id, or "unknown (class name)" when no loaded mod file holds the class. */
    static String of(Object hook) {
        return CACHE.computeIfAbsent(hook.getClass(), Owner::find);
    }

    private static String find(Class<?> c) {
        String name = c.getName();
        try {
            // a mod jar is its own module, named after the mod file
            Module m = c.getModule();
            if (m.isNamed()) {
                for (IModFileInfo f : ModList.get().getModFiles()) {
                    if (m.getName().equals(f.getFile().getId()) && !f.getMods().isEmpty()) {
                        return f.getMods().get(0).getModId();
                    }
                }
            }
            // dev runs and odd loaders: look for the top-level class in each mod file's scan
            String top = name.replace('.', '/');
            int cut = top.indexOf('$');
            if (cut > 0) {
                top = top.substring(0, cut);
            }
            for (IModFileInfo f : ModList.get().getModFiles()) {
                if (f.getMods().isEmpty() || f.getFile().getScanResult() == null) {
                    continue;
                }
                for (ModFileScanData.ClassData d : f.getFile().getScanResult().getClasses()) {
                    if (d.clazz().getInternalName().equals(top)) {
                        return f.getMods().get(0).getModId();
                    }
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // no mod list (a unit test): fall through to the class name, which still points at the culprit
        }
        return "unknown (" + name + ")";
    }
}
