package com.fundamentum.hudshot;

import com.fundamentum.guide.client.GuideScreen;
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
 * Test runs only ({@code gradlew runClient -Phudshot=guidesplit}): opens the guide and drags the divider between the list
 * and the page, then folds the list with its tab and opens it again, saving screenshots/hudshot-split-*.png. Log lines
 * start with HUDSHOT; the last is DONE failed=N.
 */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class GuideSplitShot {
    private GuideSplitShot() { }

    private static final FId PAGE = FId.of("guidetest", "basics");
    private static int t;
    private static int failed;
    private static int startW;

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!"guidesplit".equals(HudPerf.MODE) || !HudPerf.done) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        t++;
        try {
            step(mc);
        } catch (Exception e) {
            fail("step " + t + " threw " + e);
            e.printStackTrace();
        }
    }

    private static void step(Minecraft mc) throws Exception {
        switch (t) {
            case 5 -> mc.setScreen(GuideScreen.at(PAGE));
            case 25 -> {
                HudPerf.shot(mc, "split-1-expanded");
                startW = i(mc, "tw");
                check(!b(mc, "folded") && startW > 0, " the list shows at its default width " + startW);
            }
            case 30 -> {
                // grab the divider below the tab and drag it 80 px right
                int x = divider(mc);
                int y = i(mc, "py") + 40;
                MouseButtonEvent down = new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0));
                check(mc.screen.mouseClicked(down, false), " the divider takes the click");
                check(b(mc, "dragging"), " the divider is being dragged");
                for (int i = 1; i <= 8; i++) mc.screen.mouseDragged(new MouseButtonEvent(x + i * 10, y, new MouseButtonInfo(0, 0)), 10, 0);
                mc.screen.mouseReleased(new MouseButtonEvent(x + 80, y, new MouseButtonInfo(0, 0)));
                check(!b(mc, "dragging"), " letting go ends the drag");
                check(i(mc, "tw") > startW + 40, " the list is wider: " + startW + " -> " + i(mc, "tw"));
            }
            case 50 -> {
                HudPerf.shot(mc, "split-2-dragged");
                check(Integer.valueOf(i(mc, "tw")).equals(prefs("navWidth")), " the width is saved: " + prefs("navWidth"));
            }
            case 55 -> {
                int x = divider(mc);
                int y = mid(mc);
                MouseButtonEvent e = new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0));
                check(mc.screen.mouseClicked(e, false), " the fold tab takes the click");
                mc.screen.mouseReleased(e);
                check(b(mc, "folded"), " the list folded away");
                check(Boolean.TRUE.equals(prefs("navFolded")), " folded is saved");
            }
            case 75 -> HudPerf.shot(mc, "split-3-folded");
            case 80 -> {
                mc.setScreen(GuideScreen.at(PAGE));
                check(b(mc, "folded"), " a reopened guide stays folded");
                int x = divider(mc);
                int y = mid(mc);
                MouseButtonEvent e = new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0));
                mc.screen.mouseClicked(e, false);
                mc.screen.mouseReleased(e);
                check(!b(mc, "folded") && i(mc, "tw") > startW + 40, " the tab brings the list back at the dragged width: " + i(mc, "tw"));
            }
            case 100 -> HudPerf.shot(mc, "split-4-unfolded");
            case 105 -> {
                HudPerf.say("DONE failed=" + failed);
                mc.stop();
            }
            default -> { }
        }
    }

    private static int divider(Minecraft mc) throws Exception {
        return b(mc, "folded") ? i(mc, "px") + 10 : i(mc, "tx") + i(mc, "tw") + 6;
    }

    private static int mid(Minecraft mc) throws Exception {
        return (i(mc, "py") + 24 + i(mc, "py") + i(mc, "ph") - 16) / 2;
    }

    private static Object get(Minecraft mc, String name) throws Exception {
        Field f = GuideScreen.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(mc.screen);
    }

    private static int i(Minecraft mc, String name) throws Exception {
        return (Integer) get(mc, name);
    }

    private static boolean b(Minecraft mc, String name) throws Exception {
        return (Boolean) get(mc, name);
    }

    private static Object prefs(String name) throws Exception {
        var m = Class.forName("com.fundamentum.guide.client.GuidePrefs").getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(null);
    }

    private static void check(boolean ok, String what) {
        HudPerf.say((ok ? "PASS" : "FAIL") + what);
        if (!ok) failed++;
    }

    private static void fail(String what) {
        HudPerf.say("FAIL " + what);
        failed++;
    }
}
