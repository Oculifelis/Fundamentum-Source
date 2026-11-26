package com.fundamentum.guide.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Every shipped book style names layers whose textures exist at the size the style says. */
class BookStyleFilesTest {
    private static final Path ASSETS = Path.of("src/main/resources/assets/fundamentum");
    private static final List<String> STYLES = List.of("parchment_tome", "bestiary", "lab_notebook", "grimoire",
        "engineers_manual", "ships_log", "almanac", "infernal_grimoire", "dig_journal", "redstone_pad");
    private static final List<String> LAYERS = List.of("spread", "closed", "title", "portrait", "stamp_on", "stamp_off", "tab", "tab_up");

    @Test
    void everyStyleShips() throws IOException {
        try (Stream<Path> files = Files.list(ASSETS.resolve("fundamentum/book_styles"))) {
            assertEquals(STYLES.size(), files.filter(f -> f.toString().endsWith(".json")).count());
        }
    }

    @Test
    void layersMatchTheirTextures() throws IOException {
        for (String style : STYLES) {
            JsonObject o = JsonParser.parseString(Files.readString(ASSETS.resolve("fundamentum/book_styles/" + style + ".json"))).getAsJsonObject();
            JsonObject layers = o.getAsJsonObject("layers");
            assertNotNull(layers, style);
            for (String layer : LAYERS) {
                JsonObject l = layers.getAsJsonObject(layer);
                assertNotNull(l, style + " " + layer);
                Path png = ASSETS.resolve("textures/gui/book/" + style + "/" + layer + ".png");
                assertTrue(Files.isRegularFile(png), png.toString());
                BufferedImage img = ImageIO.read(png.toFile());
                assertEquals(l.get("tw").getAsInt(), img.getWidth(), style + " " + layer + " width");
                assertEquals(l.get("th").getAsInt(), img.getHeight(), style + " " + layer + " height");
                assertTrue(l.get("w").getAsInt() <= img.getWidth() && l.get("h").getAsInt() <= img.getHeight(), style + " " + layer);
            }
            for (String key : List.of("ink", "dim", "accent", "page", "card", "card_edge")) {
                assertTrue(o.getAsJsonObject("colors").has(key), style + " colour " + key);
            }
            for (Map.Entry<String, com.google.gson.JsonElement> s : o.getAsJsonObject("sounds").entrySet()) {
                assertTrue(s.getValue().getAsString().contains(":"), style + " sound " + s.getKey());
            }
        }
    }
}
