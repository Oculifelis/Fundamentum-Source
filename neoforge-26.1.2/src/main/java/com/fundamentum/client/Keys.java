package com.fundamentum.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

/** Fundamentum's hotkeys, in its own key category: the mount (R) and the grappling hook (H). */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class Keys {
    public static final KeyMapping.Category CATEGORY = new KeyMapping.Category(Identifier.fromNamespaceAndPath("fundamentum", "fundamentum"));
    public static final KeyMapping MOUNT = new KeyMapping("key.fundamentum.mount", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, CATEGORY);
    public static final KeyMapping HOOK = new KeyMapping("key.fundamentum.hook", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, CATEGORY);

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        event.register(MOUNT);
        event.register(HOOK);
    }

    private Keys() {}
}
