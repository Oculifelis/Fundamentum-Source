package com.fundamentum.hudshot;

import com.fundamentum.guide.client.GuideScreen;
import com.fundamentum.guide.client.GuideThemePicker;
import com.fundamentum.port.FId;
import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Test runs only ({@code gradlew runClient -Phudshot=guideextras}): opens the two theme pages, then the theme picker, and
 * clicks its folder line, which must open the install page. Saves screenshots/hudshot-extras-*.png; the last log line is
 * DONE failed=N.
 */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class GuideExtrasShot {
    private GuideExtrasShot() { }

    private static final FId INSTALL = FId.of("fundamentum", "themes/install");
    private static final FId MAKING = FId.of("fundamentum", "themes/making");
    private static int t;
    private static int failed;

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!"guideextras".equals(HudPerf.MODE) || !HudPerf.done) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        t++;
        try {
            step(mc);
        } catch (Exception e) {
            check(false, " step " + t + " threw " + e);
            e.printStackTrace();
        }
    }

    private static void step(Minecraft mc) throws Exception {
        switch (t) {
            case 5 -> mc.setScreen(GuideScreen.at(INSTALL));
            case 25 -> {
                HudPerf.shot(mc, "extras-1-install");
                check(page(mc).equals(INSTALL), " the install page opens: " + page(mc));
            }
            case 30 -> mc.setScreen(GuideScreen.at(MAKING));
            case 50 -> {
                HudPerf.shot(mc, "extras-2-making");
                check(page(mc).equals(MAKING), " the making page opens: " + page(mc));
            }
            case 55 -> mc.setScreen(new GuideThemePicker(mc.screen));
            case 75 -> {
                HudPerf.shot(mc, "extras-3-picker");
                Object frame = frame(mc);
                int x = i(frame, "folderX") + 2;
                int y = i(frame, "ownY") + 2;
                check(i(frame, "folderW") > 0, " the folder line is drawn at " + x + "," + y);
                MouseButtonEvent e = new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0));
                mc.screen.mouseClicked(e, false);
                mc.screen.mouseReleased(e);
            }
            case 95 -> {
                HudPerf.shot(mc, "extras-4-after-click");
                check(mc.screen instanceof GuideScreen && page(mc).equals(INSTALL), " the folder line opens the install page: " + mc.screen);
            }
            case 100 -> {
                HudPerf.say("DONE failed=" + failed);
                mc.stop();
            }
            default -> { }
        }
    }

    private static FId page(Minecraft mc) throws Exception {
        if (!(mc.screen instanceof GuideScreen)) return FId.of("none", "none");
        Object view = field(mc.screen, GuideScreen.class, "view");
        Field f = view.getClass().getDeclaredField("page");
        f.setAccessible(true);
        Object p = f.get(view);
        return p == null ? FId.of("none", "home") : (FId) p;
    }

    private static Object frame(Minecraft mc) throws Exception {
        for (var child : mc.screen.children()) {
            for (Field f : child.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object v = f.get(child);
                if (v != null && v.getClass().getSimpleName().equals("Frame")) return v;
            }
        }
        throw new IllegalStateException("no picker frame among " + mc.screen.children());
    }

    private static Object field(Object o, Class<?> c, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static int i(Object o, String name) throws Exception {
        return (Integer) field(o, o.getClass(), name);
    }

    private static void check(boolean ok, String what) {
        HudPerf.say((ok ? "PASS" : "FAIL") + what);
        if (!ok) failed++;
    }
}
