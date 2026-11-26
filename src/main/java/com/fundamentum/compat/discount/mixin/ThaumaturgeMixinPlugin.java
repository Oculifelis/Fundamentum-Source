package com.fundamentum.compat.discount.mixin;

import java.util.List;
import java.util.Set;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Applies the mixins in this config only when Thaumaturge is installed, so it stays an optional dependency. */
public final class ThaumaturgeMixinPlugin implements IMixinConfigPlugin {
    private boolean present;

    @Override public void onLoad(String mixinPackage) {
        LoadingModList mods = LoadingModList.get();
        this.present = mods != null && mods.getModFileById("thaumaturge") != null;
    }
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return this.present; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String t, ClassNode c, String m, IMixinInfo i) {}
    @Override public void postApply(String t, ClassNode c, String m, IMixinInfo i) {}
}
