package com.fundamentum.flail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Reading flail files and their presets: the shipped presets parse, fields resolve in order, and mistakes are named. */
class FlailSpecTest {
    static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    static FlailSpec resolve(String flail) {
        return FlailSpec.resolve(json(flail), ShippedPresets.materials(), ShippedPresets.kinds());
    }

    static String error(String flail) {
        return assertThrows(IllegalArgumentException.class, () -> resolve(flail)).getMessage();
    }

    @Test
    void shippedPresetsAreValid() {
        assertEquals(11, ShippedPresets.materials().size(), "materials: " + ShippedPresets.materials().keySet());
        assertEquals(6, ShippedPresets.kinds().size(), "kinds: " + ShippedPresets.kinds().keySet());
        ShippedPresets.materials().forEach((id, m) -> FlailSpec.checkPreset("material " + id, m, true));
        ShippedPresets.kinds().forEach((id, k) -> FlailSpec.checkPreset("kind " + id, k, false));
    }

    @Test
    void defaultsAreIronSingleLinks() {
        FlailSpec s = resolve("{}");
        assertEquals(FlailPhysics.LINKS, s.physics);
        assertEquals("fundamentum:iron", s.material);
        assertEquals("fundamentum:single", s.kind);
        assertEquals(1, s.headCount);
        assertNull(s.launch);
        assertNull(s.groundSlam);
    }

    @Test
    void fieldsResolveKindThenMaterialThenFlail() {
        FlailSpec s = resolve("{\"material\":\"fundamentum:molten\",\"kind\":\"fundamentum:multi\",\"chain_length\":2.2,\"head_mass\":3}");
        assertEquals(3, s.headCount);
        assertEquals(2.2, s.chainLength);
        assertEquals(3.0, s.headMass);
        assertEquals(4, s.impact.fireSeconds());
        assertEquals("minecraft:flame", s.impact.particle());
        assertEquals(0xFFE8701A, s.headColor);
    }

    @Test
    void launchPhysicsGetsDefaultLaunch() {
        FlailSpec s = resolve("{\"physics\":\"launch\"}");
        assertEquals(FlailSpec.Launch.DEFAULT, s.launch);
        FlailSpec h = resolve("{\"physics\":\"hybrid\",\"launch\":{\"range\":12,\"pull\":0.5}}");
        assertEquals(12.0, h.launch.range());
        assertEquals(0.5, h.launch.pull());
    }

    @Test
    void groundSlamAndImpactParse() {
        FlailSpec s = resolve("{\"ground_slam\":{\"radius\":4},\"material\":\"fundamentum:ice\",\"on_hit\":[\"mymod:shock\"]}");
        assertNotNull(s.groundSlam);
        assertEquals(4.0, s.groundSlam.radius());
        assertEquals(0.6, s.groundSlam.minCharge());
        assertEquals(140, s.impact.freezeTicks());
        assertEquals("minecraft:slowness", s.impact.effects().get(0).id());
        assertEquals(1, s.impact.effects().get(0).amplifier());
        assertEquals(java.util.List.of("mymod:shock"), s.onHit);
    }

    @Test
    void mistakesAreNamed() {
        assertTrue(error("{\"physics\":\"rope\"}").contains("unknown physics \"rope\""));
        assertTrue(error("{\"material\":\"mymod:gold\"}").contains("unknown material \"mymod:gold\""));
        assertTrue(error("{\"kind\":\"mymod:triple\"}").contains("unknown kind"));
        assertTrue(error("{\"dammage\":5}").contains("unknown field \"dammage\""));
        assertTrue(error("{\"physics\":\"links\",\"launch\":{}}").contains("needs physics launch or hybrid"));
        assertTrue(error("{\"physics\":\"pendulum\",\"wraps_target\":20}").contains("can't wrap"));
        assertTrue(error("{\"double_ended\":true}").contains("needs head_count 2"));
        assertTrue(error("{\"physics\":\"launch\",\"chain_length\":6,\"launch\":{\"range\":4}}").contains("shorter than chain_length"));
        assertTrue(error("{\"head_count\":9}").contains("\"head_count\" is 9, must be 1 to 4"));
        assertTrue(error("{\"head_count\":1.5}").contains("whole number"));
        assertTrue(error("{\"chain_hits\":\"yes\"}").contains("true or false"));
        assertTrue(error("{\"head_color\":\"red\"}").contains("colour"));
        assertTrue(error("{\"ground_slam\":{\"size\":3}}").contains("unknown field \"size\""));
        assertTrue(error("{\"material\":\"fundamentum:ice\",\"launch\":{\"range\":40},\"physics\":\"launch\"}").contains("must be 1.0 to 32.0"));
    }

    @Test
    void aPresetMayOnlyHoldItsOwnFields() {
        Map<String, JsonObject> materials = Map.of("x:bad", json("{\"head_count\":2}"));
        String m = assertThrows(IllegalArgumentException.class,
            () -> FlailSpec.resolve(json("{\"material\":\"x:bad\"}"), materials, ShippedPresets.kinds())).getMessage();
        assertTrue(m.contains("\"head_count\" is not a material field"), m);
        String k = assertThrows(IllegalArgumentException.class,
            () -> FlailSpec.checkPreset("kind x:bad", json("{\"bounce\":0.5}"), false)).getMessage();
        assertTrue(k.contains("\"bounce\" is not a kind field"), k);
        String r = assertThrows(IllegalArgumentException.class,
            () -> FlailSpec.checkPreset("material x:heavy", json("{\"head_mass\":50}"), true)).getMessage();
        assertTrue(r.startsWith("material x:heavy: \"head_mass\" is 50.0"), r);
    }

    @Test
    void speedFactorAndSwingTicks() {
        FlailSpec s = resolve("{}");
        assertEquals(0.2, s.speedFactor(0));
        assertEquals(1.0, s.speedFactor(s.fullSpeed()), 1.0E-9);
        assertEquals(1.25, s.speedFactor(s.fullSpeed() * 9));
        assertEquals(6, s.swingTicks());
        FlailSpec heavy = resolve("{\"material\":\"fundamentum:chain_mace\"}");
        assertTrue(heavy.swingTicks() > s.swingTicks());
    }

    @Test
    void colours() {
        assertEquals(0xFF112233, FlailSpec.parseColor("c", JsonParser.parseString("\"#112233\"")));
        assertEquals(0x80112233, FlailSpec.parseColor("c", JsonParser.parseString("\"#80112233\"")));
        assertEquals(0xFF000010, FlailSpec.parseColor("c", JsonParser.parseString("16")));
    }
}
