package com.fundamentum.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/** Fundamentum's hotkeys, in its own key category: the mount (R), the grappling hook (H), the stats screen and the HUD
 * editor (both unbound). */
@Mod.EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class Keys {
    public static final String CATEGORY = "key.category.fundamentum.fundamentum";
    public static final KeyMapping MOUNT = new KeyMapping("key.fundamentum.mount", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, CATEGORY);
    public static final KeyMapping HOOK = new KeyMapping("key.fundamentum.hook", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, CATEGORY);
    /** Opens the stats screen; unbound until the player picks a key. */
    public static final KeyMapping STATS = new KeyMapping("key.fundamentum.stats", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY);

    /** Opens the HUD editor; unbound until the player picks a key. */
    public static final KeyMapping HUD_EDITOR = new KeyMapping("key.fundamentum.hud_editor", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY);

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(MOUNT);
        event.register(HOOK);
        event.register(STATS);
        event.register(HUD_EDITOR);
    }

    private Keys() {}
}
