package com.fundamentum.compat.jade;

import com.fundamentum.port.FLife;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import snownee.jade.api.IWailaCommonRegistration;

/**
 * The Jade test, only in {@code gradlew runServer -Pjadetest} (Jade on the run class path, world under run-jadetest).
 * On a dedicated server it runs our plugin's common registration the way Jade does and checks that it loads no client
 * class and registers both server data providers. Writes jadetest-result.txt and stops the server. Log lines start with
 * JADETEST.
 */
public final class JadeSelfTest {
    private JadeSelfTest() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    private static final List<String> LINES = new ArrayList<>();
    private static boolean failed;
    private static int ticks;

    public static void init() {
        if (System.getProperty("fundamentum.jadetest") == null) return;
        FLife.serverTick(JadeSelfTest::tick);
    }

    private static void check(boolean ok, String what) {
        String line = (ok ? "PASS " : "FAIL ") + what;
        if (!ok) failed = true;
        LINES.add(line);
        if (ok) LOG.info("JADETEST {}", line);
        else LOG.error("JADETEST {}", line);
    }

    private static void tick(MinecraftServer server) {
        if (++ticks != 40) return;
        try {
            run(server);
        } catch (Throwable t) {
            LOG.error("JADETEST threw", t);
            check(false, "threw " + t);
        }
        LINES.add(failed ? "RESULT FAIL" : "RESULT PASS");
        LOG.info("JADETEST RESULT {}", failed ? "FAIL" : "PASS");
        try {
            Files.write(Path.of("jadetest-result.txt"), LINES);
        } catch (IOException e) {
            LOG.error("JADETEST could not write the result file", e);
        }
        server.halt(false);
    }

    private static void run(MinecraftServer server) {
        check(server.isDedicatedServer(), "this is a dedicated server");
        check(ModList.get().isLoaded("jade"), "Jade is loaded");
        List<Object> data = new ArrayList<>();
        IWailaCommonRegistration reg = (IWailaCommonRegistration) Proxy.newProxyInstance(
            IWailaCommonRegistration.class.getClassLoader(), new Class<?>[] {IWailaCommonRegistration.class}, (p, m, a) -> {
                if (m.getName().equals("registerBlockDataProvider")) data.add(a[0]);
                return null;
            });
        Throwable thrown = null;
        try {
            new FundamentumJadePlugin().register(reg);
        } catch (Throwable t) {
            thrown = t;
            LOG.error("JADETEST register threw", t);
        }
        check(thrown == null, "common register() runs on the server" + (thrown == null ? "" : ": " + thrown));
        check(data.contains(JukeboxJadeData.INSTANCE), "jukebox data provider registered");
        check(data.contains(DiscShelfJade.INSTANCE), "disc shelf data provider registered");
        check(DiscShelfJade.INSTANCE.getUid() != null, "disc shelf uid " + DiscShelfJade.INSTANCE.getUid());
        boolean clientClass;
        try {
            Class.forName("net.minecraft.client.gui.layouts.LayoutElement", false, JadeSelfTest.class.getClassLoader());
            clientClass = true;
        } catch (Throwable t) {
            clientClass = false;
        }
        check(!clientClass, "client classes are absent here, so the check means something");
    }
}
