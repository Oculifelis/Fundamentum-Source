package com.fundamentum.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/** Fundamentum's hotkeys, in its own key category: the mount (V), the grappling hook (H), dodge (Left Alt), summons on
 * and off (K), the stats screen and the HUD editor (both unbound). */
@Mod.EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class Keys {
    public static final String CATEGORY = "key.category.fundamentum.fundamentum";
    public static final KeyMapping MOUNT = new KeyMapping("key.fundamentum.mount", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, CATEGORY);
    public static final KeyMapping HOOK = new KeyMapping("key.fundamentum.hook", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, CATEGORY);
    /** Dodges toward the movement keys (see com.fundamentum.dodge). Left Alt by default. */
    public static final KeyMapping DODGE = new KeyMapping("key.fundamentum.dodge", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT, CATEGORY);
    /** Switches the player's minions and sentries off and on (see com.fundamentum.summon.SummonToggle). K by default. */
    public static final KeyMapping SUMMONS = new KeyMapping("key.fundamentum.summons", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, CATEGORY);
    /** Opens the stats screen; unbound until the player picks a key. */
    public static final KeyMapping STATS = new KeyMapping("key.fundamentum.stats", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY);

    /** Opens the quest journal; unbound until the player picks a key ({@code /quests} works too). */
    public static final KeyMapping QUESTS = new KeyMapping("key.fundamentum.quests", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY);

    /** Opens the HUD editor; unbound until the player picks a key. */
    public static final KeyMapping HUD_EDITOR = new KeyMapping("key.fundamentum.hud_editor", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY);

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(MOUNT);
        event.register(HOOK);
        event.register(DODGE);
        event.register(SUMMONS);
        event.register(STATS);
        event.register(QUESTS);
        event.register(HUD_EDITOR);
    }

    private Keys() {}
}
