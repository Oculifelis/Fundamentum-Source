package com.fundamentum.material;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

/**
 * Average block colours (the block texture on the classpath when there is one, otherwise the block's map colour) and the
 * sRGB to CIE Lab (D65) conversion used for colour matching. A dedicated server has no vanilla textures on its classpath,
 * so there vanilla blocks use map colours; set {@code color_source = "map"} in fundamentum-materials.toml to use map
 * colours everywhere (same ranking on every side).
 */
public final class MaterialColor {
    private MaterialColor() { }

    private static final Map<Block, Integer> CACHE = new ConcurrentHashMap<>();

    /** The block's average colour, 0xRRGGBB. */
    public static int of(Block block) {
        return CACHE.computeIfAbsent(block, MaterialColor::compute);
    }

    static void clearCache() {
        CACHE.clear();
    }

    private static int compute(Block block) {
        if (MaterialConfig.textureColors()) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            for (String suffix : new String[] {"", "_side", "_top", "_front"}) {
                Integer c = texture(id.getNamespace(), id.getPath() + suffix);
                if (c != null) return c;
            }
        }
        try {
            return block.defaultMapColor().col & 0xFFFFFF;
        } catch (Throwable t) {
            return 0x808080;
        }
    }

    private static Integer texture(String ns, String path) {
        String res = "/assets/" + ns + "/textures/block/" + path + ".png";
        try (InputStream in = MaterialColor.class.getResourceAsStream(res)) {
            if (in == null) return null;
            BufferedImage img = ImageIO.read(in);
            if (img == null) return null;
            long r = 0, g = 0, b = 0, n = 0;
            int h = Math.min(img.getHeight(), img.getWidth()); // first frame of animated strips
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    int argb = img.getRGB(x, y);
                    if ((argb >>> 24) < 128) continue;
                    r += (argb >> 16) & 255;
                    g += (argb >> 8) & 255;
                    b += argb & 255;
                    n++;
                }
            }
            if (n == 0) return null;
            return (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
        } catch (Throwable t) {
            return null;
        }
    }

    /** sRGB 0xRRGGBB to CIE Lab (D65). */
    public static float[] lab(int rgb) {
        double r = lin(((rgb >> 16) & 255) / 255.0);
        double g = lin(((rgb >> 8) & 255) / 255.0);
        double b = lin((rgb & 255) / 255.0);
        double x = (r * 0.4124564 + g * 0.3575761 + b * 0.1804375) / 0.95047;
        double y = r * 0.2126729 + g * 0.7151522 + b * 0.0721750;
        double z = (r * 0.0193339 + g * 0.1191920 + b * 0.9503041) / 1.08883;
        double fx = f(x), fy = f(y), fz = f(z);
        return new float[] {(float) (116 * fy - 16), (float) (500 * (fx - fy)), (float) (200 * (fy - fz))};
    }

    /** "#rrggbb" or "rrggbb" to 0xRRGGBB. */
    public static int parseHex(String hex) {
        String s = hex.startsWith("#") ? hex.substring(1) : hex;
        return Integer.parseInt(s, 16) & 0xFFFFFF;
    }

    public static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06x", rgb & 0xFFFFFF);
    }

    /** CIE76 colour distance between two Lab colours. */
    public static double distance(float[] a, float[] b) {
        double dl = a[0] - b[0], da = a[1] - b[1], db = a[2] - b[2];
        return Math.sqrt(dl * dl + da * da + db * db);
    }

    /** Rough colour words for theme tags: "dark"/"light", plus a hue word when saturated enough. */
    static String[] words(int rgb) {
        float[] lab = lab(rgb);
        String tone = lab[0] < 35 ? "dark" : lab[0] > 70 ? "light" : "mid";
        double chroma = Math.hypot(lab[1], lab[2]);
        if (chroma < 12) return new String[] {tone, "grey"};
        double hue = Math.toDegrees(Math.atan2(lab[2], lab[1]));
        if (hue < 0) hue += 360;
        String word = hue < 30 ? "red" : hue < 70 ? "orange" : hue < 100 ? "yellow" : hue < 160 ? "green" : hue < 250 ? "blue" : hue < 320 ? "purple" : "red";
        if ("orange".equals(word) && lab[0] < 55) word = "brown";
        return new String[] {tone, word};
    }

    private static double lin(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double f(double t) {
        return t > 216.0 / 24389 ? Math.cbrt(t) : (24389.0 / 27 * t + 16) / 116;
    }
}
