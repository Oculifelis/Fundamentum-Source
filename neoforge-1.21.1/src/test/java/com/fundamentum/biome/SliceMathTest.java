package com.fundamentum.biome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SliceMathTest {
    private static long[] box(double... v) {
        long[] b = new long[12];
        for (int i = 0; i < 12; i++) b[i] = SliceMath.quantize(v[i]);
        return b;
    }

    /** A plain box: temperature, humidity, continentalness, erosion as ranges, depth 0, weirdness range. */
    private static long[] surface(double t0, double t1, double w0, double w1) {
        return box(t0, t1, -1, 1, -1, 1, -1, 1, 0, 0, w0, w1);
    }

    private static long volume(long[] b) {
        long v = 1;
        for (int d = 0; d < SliceMath.DIMS; d++) {
            long len = b[2 * d + 1] - b[2 * d];
            if (len > 0) v *= len;
        }
        return v;
    }

    @Test
    void touchingEdgesDoNotOverlap() {
        assertFalse(SliceMath.overlaps(surface(0, 0.5, -1, 1), surface(0.5, 1, -1, 1)));
        assertTrue(SliceMath.overlaps(surface(0, 0.6, -1, 1), surface(0.5, 1, -1, 1)));
        // a single value at the edge of a range does overlap
        assertTrue(SliceMath.overlaps(surface(0.5, 0.5, -1, 1), surface(0, 0.5, -1, 1)));
    }

    @Test
    void subtractTilesTheHost() {
        long[] host = surface(-1, 1, -1, 1);
        long[] cut = surface(-0.2, 0.3, 0.1, 0.4);
        List<long[]> pieces = SliceMath.subtract(host, cut);
        long total = volume(cut);
        for (long[] p : pieces) {
            total += volume(p);
            assertFalse(SliceMath.overlaps(p, cut));
            for (long[] q : pieces) if (q != p) assertFalse(SliceMath.overlaps(p, q));
        }
        assertEquals(volume(host), total);
        assertEquals(4, pieces.size());
    }

    @Test
    void shareSplitsTheHostHighWeirdness() {
        List<SliceMath.Entry<String>> in = List.of(
            new SliceMath.Entry<>(surface(0.5, 1, -1, 1), 0, "jungle"),
            new SliceMath.Entry<>(surface(-1, 0.5, -1, 1), 0, "plains"));
        List<SliceMath.Entry<String>> out = new ArrayList<>();
        int n = SliceMath.split(in, out, "jungle"::equals, null, false, SliceMath.WEIRDNESS, 0.25, 1, null, 1500, "ruins");
        assertEquals(1, n);
        assertEquals(3, out.size());
        SliceMath.Entry<String> ruins = out.stream().filter(e -> e.value().equals("ruins")).findFirst().orElseThrow();
        assertEquals(5000, ruins.box()[10]);
        assertEquals(10000, ruins.box()[11]);
        assertEquals(5000, ruins.box()[0]);
        SliceMath.Entry<String> rest = out.stream().filter(e -> e.value().equals("jungle")).findFirst().orElseThrow();
        assertEquals(-10000, rest.box()[10]);
        assertEquals(5000, rest.box()[11]);
    }

    @Test
    void bandAndClimateNarrowTheSplit() {
        List<SliceMath.Entry<String>> in = List.of(new SliceMath.Entry<>(surface(-1, 1, -1, 1), 0, "plains"));
        List<SliceMath.Entry<String>> out = new ArrayList<>();
        long[] climate = SliceMath.open();
        climate[0] = 0;
        climate[1] = 10000;
        SliceMath.split(in, out, s -> true, climate, true, SliceMath.HUMIDITY, 1, 1, new long[] {2000, 4000}, 0, "x");
        SliceMath.Entry<String> x = out.stream().filter(e -> e.value().equals("x")).findFirst().orElseThrow();
        assertEquals(0, x.box()[0]);
        assertEquals(2000, x.box()[2]);
        assertEquals(4000, x.box()[3]);
        long total = 0;
        for (SliceMath.Entry<String> e : out) total += volume(e.box());
        assertEquals(volume(in.get(0).box()), total);
    }

    @Test
    void surfaceOnlySkipsCaves() {
        long[] cave = box(-1, 1, 0.7, 1, -1, 1, -1, 1, 0.2, 0.9, -1, 1);
        List<SliceMath.Entry<String>> in = List.of(new SliceMath.Entry<>(cave, 0, "lush_caves"));
        List<SliceMath.Entry<String>> out = new ArrayList<>();
        assertEquals(0, SliceMath.split(in, out, s -> true, SliceMath.open(), true, SliceMath.WEIRDNESS, 1, 1, null, 0, "x"));
        assertEquals(1, out.size());
    }

    @Test
    void singleValueHostsGetANeighbourPoint() {
        long[] wastes = box(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        List<SliceMath.Entry<String>> in = List.of(new SliceMath.Entry<>(wastes, 0, "nether_wastes"));
        List<SliceMath.Entry<String>> out = new ArrayList<>();
        assertEquals(1, SliceMath.split(in, out, s -> true, null, false, SliceMath.HUMIDITY, 0.5, -1, null, 1500, "x"));
        assertEquals(2, out.size());
        assertEquals(-1500, out.get(1).box()[2]);
        assertEquals(-1500, out.get(1).box()[3]);
    }

    @Test
    void addCarvesWideCellsOnly() {
        long[] lush = box(-1, 1, 0.7, 1, -1, 1, -1, 1, 0.2, 0.9, -1, 1);
        long[] plains = surface(-1, 1, -1, 1);
        List<SliceMath.Entry<String>> in = List.of(new SliceMath.Entry<>(lush, 0, "lush"), new SliceMath.Entry<>(plains, 0, "plains"));
        List<SliceMath.Entry<String>> out = new ArrayList<>();
        long[] cave = box(0.5, 1, -1, 1, -1, 1, -1, 1, 0.2, 0.9, -1, 1);
        assertEquals(1, SliceMath.add(in, out, cave, 0, "cave"));
        assertTrue(out.stream().anyMatch(e -> e.value().equals("plains") && e.box() == plains));
        for (SliceMath.Entry<String> e : out) {
            if (e.value().equals("lush")) assertFalse(SliceMath.overlaps(e.box(), cave));
        }
    }

    @Test
    void specParsesShareAndDefaults() {
        SliceSpec s = SliceSpec.parse("t:a", JsonParser.parseString(
            "{\"biome\":\"t:ruins\",\"share\":{\"with\":[\"minecraft:jungle\",\"#c:is_jungle\"],\"side\":\"low\"},\"weight\":0.3,\"requires\":\"t\"}")
            .getAsJsonObject());
        assertEquals(SliceSpec.OVERWORLD, s.dimension());
        assertEquals(SliceMath.WEIRDNESS, s.axis());
        assertEquals(-1, s.side());
        assertEquals(0.3, s.weight());
        assertEquals(SliceSpec.Placement.SPLIT, s.placement());
        assertEquals(List.of("t"), s.requires());

        SliceSpec cave = SliceSpec.parse("t:b", JsonParser.parseString(
            "{\"biome\":\"t:cave\",\"climate\":{\"temperature\":[0.5,1],\"depth\":[0.2,0.9]}}").getAsJsonObject());
        assertEquals(SliceSpec.Placement.ADD, cave.placement());
        assertEquals(2000, cave.climate()[8]);

        SliceSpec nether = SliceSpec.parse("t:c", JsonParser.parseString(
            "{\"biome\":\"t:n\",\"dimension\":\"minecraft:the_nether\",\"share\":{\"with\":\"minecraft:nether_wastes\"}}").getAsJsonObject());
        assertEquals(SliceMath.HUMIDITY, nether.axis());

        assertThrows(IllegalArgumentException.class, () -> SliceSpec.parse("t:d", JsonParser.parseString("{\"biome\":\"t:x\"}").getAsJsonObject()));
        assertThrows(IllegalArgumentException.class, () -> SliceSpec.parse("t:e", JsonParser.parseString(
            "{\"biome\":\"t:x\",\"climate\":{\"heat\":1}}").getAsJsonObject()));
    }
}
