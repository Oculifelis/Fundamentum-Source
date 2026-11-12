package com.fundamentum.relic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** RelicDef: the relic declaration's fields and Ultima Ratio's defaults. */
class RelicDefTest {
    private static RelicDef parse(String json, String name) {
        return RelicDef.parse(JsonParser.parseString(json).getAsJsonObject(), name);
    }

    @Test
    void theDefaultsAreUltimaRatios() {
        RelicDef d = parse("{\"boss\": \"ultimaratio:cyvercry\"}", "relic_cyvercry");
        assertEquals("ultimaratio:cyvercry", d.boss);
        assertFalse(d.grand());
        assertNull(d.part);
        assertEquals("rare", d.rarity);
        assertEquals(16, d.stack);
        assertEquals(9, d.light);
        assertEquals(2.0F, d.strength);
        assertEquals(6.0F, d.resistance);
        assertArrayEquals(new float[] {1, 0, 1, 15, 9, 15}, d.shape.get(0));
        assertEquals(RelicDef.Drop.NONE, d.drop);
        assertNull(d.base.layer);
        assertTrue(Float.isNaN(d.base.top));
        assertEquals(RelicDef.DEFAULT_BASE, d.base.id);
        assertNull(d.base.tint);
        assertEquals(1, d.span);
        assertFalse(d.tinted);
        assertNull(d.name);
        assertEquals("ultimaratio:cyvercry", d.figure.entity);
        assertArrayEquals(new int[] {1}, d.figure.phases);
        assertEquals(18F, d.figure.wide);
        assertEquals(21F, d.figure.tall);
        assertEquals(2.5F, d.figure.floatBy);
        assertTrue(Float.isNaN(d.figure.scale));
        assertFalse(d.figure.model());
        assertArrayEquals(RelicDef.GOLD, d.tint);
        assertEquals(1.0F, d.bobHeight);
        assertEquals(100F, d.bobPeriod);
        assertEquals(900F, d.turnTicks);
    }

    @Test
    void aGrandRelicGetsItsPartAndTheGrandFit() {
        RelicDef d = parse("{\"boss\": \"ultimaratio:tergeminus\", \"size\": \"grand\", \"part\": \"relic_part\", \"figure\": {\"phases\": [3, 4, 5]}}",
            "relic_tergeminus");
        assertTrue(d.grand());
        assertEquals("relic_part", d.part);
        assertEquals("epic", d.rarity);
        assertEquals(3, d.span);
        assertEquals(26F, d.figure.wide);
        assertEquals(36F, d.figure.tall);
        assertEquals(2, d.shape.size());
        assertEquals("relic_hollow_sun_part", parse("{\"boss\": \"x:y\", \"size\": \"grand\"}", "relic_hollow_sun").part);
        assertEquals(46F, parse("{\"boss\": \"x:y\", \"size\": \"grand\"}", "a").figure.wide);
    }

    @Test
    void aModelFigureReadsItsLayerOffsetAndScale() {
        RelicDef d = parse("""
            {"boss": "minecraft:wither", "drop": "drops", "light": 6, "strength": 3, "resistance": 1200, "rarity": "epic",
             "shape": [[1, 0, 1, 15, 2, 15], [2, 2, 2, 14, 10.25, 14]],
             "base": {"layer": "ostentum:relic_base_1x1", "texture": "ostentum:textures/entity/relic/base_1x1.png",
                      "glow": "ostentum:textures/entity/relic/base_1x1_glow.png"},
             "figure": {"layer": "ostentum:wither_relic", "texture": "ostentum:textures/entity/relic/wither_gold.png",
                        "offset": [0, -23.6721, -1.6735], "scale": 0.5833},
             "bob": {"height": 1.2, "period": 104.72, "centred": true}, "tint": ["#000000", "0xFFFFFF", "808080"]}
            """, "wither_relic");
        assertEquals(RelicDef.Drop.DROPS, d.drop);
        assertEquals(6, d.light);
        assertEquals(1200F, d.resistance);
        assertEquals(2, d.shape.size());
        assertArrayEquals(new float[] {2, 2, 2, 14, 10.25F, 14}, d.shape.get(1));
        assertEquals("ostentum:relic_base_1x1", d.base.layer);
        assertTrue(d.figure.model());
        assertEquals(0.5833F, d.figure.scale);
        assertArrayEquals(new float[] {0, -23.6721F, -1.6735F}, d.figure.offset);
        assertNull(d.figure.glow);
        assertArrayEquals(new int[] {0, 0xFFFFFF, 0x808080}, d.tint);
        assertTrue(d.bobCentred);
        // a centred bob swings round the rest height: +-0.6 pixels here
        assertEquals(-0.6F / 16F, d.bob(0F), 1e-6F);
        assertEquals(0.6F / 16F, d.bob(52.36F), 1e-6F);
    }

    @Test
    void anOverBobRisesFromTheRestHeight() {
        RelicDef d = parse("{\"boss\": \"a:b\"}", "x");
        assertEquals(0F, d.bob(0F), 1e-6F);
        assertEquals(1F / 16F, d.bob(50F), 1e-6F);
    }

    @Test
    void oneBoxNeedNotBeWrapped() {
        RelicDef d = parse("{\"boss\": \"a:b\", \"shape\": [0, 0, 0, 16, 16, 16]}", "x");
        assertEquals(1, d.shape.size());
    }

    @Test
    void sizesOneToFiveAndAStringBase() {
        RelicDef d = parse("{\"boss\": \"a:b\", \"size\": 4, \"base\": \"mymod:altar\", \"name\": \"Big one\"}", "x");
        assertEquals(4, d.span);
        assertTrue(d.grand());
        assertEquals("mymod:altar", d.base.id);
        assertEquals("Big one", d.name);
        assertEquals(46F * 4 / 3, d.figure.wide, 1e-4F);
        assertEquals(2, parse("{\"boss\": \"a:b\", \"size\": 2}", "x").span);
        assertEquals(1, parse("{\"boss\": \"a:b\", \"size\": \"single\"}", "x").span);
        assertEquals("minecraft:stone", parse("{\"boss\": \"a:b\", \"base\": \"stone\"}", "x").base.id);
        RelicDef o = parse("{\"boss\": \"a:b\", \"base\": {\"id\": \"m:s\", \"top\": 12, \"tint\": \"#ff0000\"}}", "x");
        assertEquals("m:s", o.base.id);
        assertEquals(12F, o.base.top);
        assertEquals(5, o.base.tint.length);
        assertEquals(0xFF0000, o.base.tint[3]);
    }

    @Test
    void oneTintColourMakesARampAndBlankMeansGold() {
        RelicDef d = parse("{\"boss\": \"a:b\", \"tint\": \"#4080c0\"}", "x");
        assertTrue(d.tinted);
        assertEquals(5, d.tint.length);
        assertEquals(0x4080C0, d.tint[3]);
        assertTrue((d.tint[0] & 255) < (d.tint[3] & 255) && (d.tint[4] & 255) > (d.tint[3] & 255));
        RelicDef f = parse("{\"boss\": \"a:b\", \"figure\": {\"tint\": \"#00ff00\"}}", "x");
        assertEquals(0x00FF00, f.tint[3]);
        RelicDef blank = parse("{\"boss\": \"a:b\", \"tint\": \"\"}", "x");
        assertFalse(blank.tinted);
        assertArrayEquals(RelicDef.GOLD, blank.tint);
    }

    @Test
    void badFilesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> parse("{}", "x"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"boss\": \"a:b\", \"size\": \"huge\"}", "x"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"boss\": \"a:b\", \"figure\": {\"layer\": \"a:b\"}}", "x"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"boss\": \"a:b\", \"figure\": {\"bounds\": [1, 2]}}", "x"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"boss\": \"a:b\", \"tint\": [\"#fff\"]}", "x"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"boss\": \"a:b\", \"figure\": {\"phases\": []}}", "x"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"boss\": \"a:b\", \"size\": 6}", "x"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"boss\": \"a:b\", \"size\": 0}", "x"));
    }

    @Test
    void theJsonIsNotChanged() {
        JsonObject o = JsonParser.parseString("{\"boss\": \"a:b\"}").getAsJsonObject();
        RelicDef.parse(o, "x");
        assertEquals(1, o.size());
    }
}
