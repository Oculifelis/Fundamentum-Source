package com.fundamentum.biome;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The box arithmetic behind biome slices, free of game classes so it can be unit tested. A box is a {@code long[12]} of
 * quantized (x10000, as in {@code Climate.quantizeCoord}) min/max pairs in the order of a climate parameter point:
 * temperature, humidity, continentalness, erosion, depth, weirdness. Ranges are closed, as in vanilla; two ranges that
 * only touch at an edge do not overlap unless one of them is a single value.
 */
public final class SliceMath {
    private SliceMath() { }

    public static final int TEMPERATURE = 0, HUMIDITY = 1, CONTINENTALNESS = 2, EROSION = 3, DEPTH = 4, WEIRDNESS = 5, DIMS = 6;
    public static final String[] AXIS_NAMES = {"temperature", "humidity", "continentalness", "erosion", "depth", "weirdness"};
    /** Unset climate axes span this far, wider than any noise value. */
    public static final long OPEN = 1_000_000L;

    /** One point of a parameter list: the box, its offset and what it maps to. */
    public record Entry<T>(long[] box, long offset, T value) { }

    public static long quantize(double v) {
        return (long) ((float) v * 10000.0F);
    }

    public static long[] open() {
        long[] box = new long[DIMS * 2];
        for (int d = 0; d < DIMS; d++) {
            box[2 * d] = -OPEN;
            box[2 * d + 1] = OPEN;
        }
        return box;
    }

    public static int axis(String name) {
        for (int i = 0; i < DIMS; i++) if (AXIS_NAMES[i].equals(name)) return i;
        return -1;
    }

    static boolean wide(long[] box, int d) {
        return box[2 * d] < box[2 * d + 1];
    }

    public static boolean overlaps(long[] a, long[] b) {
        for (int d = 0; d < DIMS; d++) {
            long lo = Math.max(a[2 * d], b[2 * d]);
            long hi = Math.min(a[2 * d + 1], b[2 * d + 1]);
            if (lo > hi) return false;
            if (lo == hi && wide(a, d) && wide(b, d)) return false;
        }
        return true;
    }

    public static long[] intersect(long[] a, long[] b) {
        long[] r = new long[DIMS * 2];
        for (int d = 0; d < DIMS; d++) {
            r[2 * d] = Math.max(a[2 * d], b[2 * d]);
            r[2 * d + 1] = Math.min(a[2 * d + 1], b[2 * d + 1]);
        }
        return r;
    }

    /**
     * The pieces of {@code host} outside {@code cut}, which must lie inside it: one slab below and one above the cut on
     * each axis in turn, so the pieces and the cut tile the host with shared edges only.
     */
    public static List<long[]> subtract(long[] host, long[] cut) {
        List<long[]> pieces = new ArrayList<>();
        long[] rest = host.clone();
        for (int d = 0; d < DIMS; d++) {
            if (rest[2 * d] < cut[2 * d]) {
                long[] slab = rest.clone();
                slab[2 * d + 1] = cut[2 * d];
                pieces.add(slab);
                rest[2 * d] = cut[2 * d];
            }
            if (rest[2 * d + 1] > cut[2 * d + 1]) {
                long[] slab = rest.clone();
                slab[2 * d] = cut[2 * d + 1];
                pieces.add(slab);
                rest[2 * d + 1] = cut[2 * d + 1];
            }
        }
        return pieces;
    }

    /** True for a surface point: depth is a single value between 0 and 1, as vanilla's surface biomes are. */
    public static boolean surface(long[] box) {
        return box[2 * DEPTH] == box[2 * DEPTH + 1] && box[2 * DEPTH] >= 0 && box[2 * DEPTH] <= 10000;
    }

    /**
     * Splits each matching host: the part of it inside {@code climate} (null: all of it) is narrowed on {@code axis},
     * by {@code band} when given, else to {@code weight} of its span on {@code side} (-1 low, 0 middle, 1 high), and
     * that part goes to {@code value}; the rest keeps the host. A host that is a single value on the axis (the Nether's
     * points) is kept whole and {@code value} gets a point beside it, moved {@code shift} along the axis.
     *
     * @return how many hosts were split or joined
     */
    public static <T> int split(List<Entry<T>> in, List<Entry<T>> out, Predicate<T> host, long[] climate, boolean surfaceOnly,
                                int axis, double weight, int side, long[] band, long shift, T value) {
        int matched = 0;
        for (Entry<T> e : in) {
            if (!host.test(e.value()) || (surfaceOnly && !surface(e.box()))
                || (climate != null && !overlaps(e.box(), climate))) {
                out.add(e);
                continue;
            }
            long[] region = climate == null ? e.box().clone() : intersect(e.box(), climate);
            long lo = region[2 * axis], hi = region[2 * axis + 1];
            if (lo == hi) {
                out.add(e);
                long[] point = e.box().clone();
                long by = band != null ? (band[0] + band[1]) / 2 - lo : (side < 0 ? -shift : shift);
                point[2 * axis] += by;
                point[2 * axis + 1] += by;
                out.add(new Entry<>(point, e.offset(), value));
                matched++;
                continue;
            }
            if (band != null) {
                lo = Math.max(lo, band[0]);
                hi = Math.min(hi, band[1]);
            } else {
                long len = Math.round((hi - lo) * Math.min(1.0, Math.max(0.0, weight)));
                if (side > 0) lo = hi - len;
                else if (side < 0) hi = lo + len;
                else {
                    lo = lo + (hi - lo - len) / 2;
                    hi = lo + len;
                }
            }
            if (hi <= lo) {
                out.add(e);
                continue;
            }
            region[2 * axis] = lo;
            region[2 * axis + 1] = hi;
            for (long[] piece : subtract(e.box(), region)) out.add(new Entry<>(piece, e.offset(), e.value()));
            out.add(new Entry<>(region, e.offset(), value));
            matched++;
        }
        return matched;
    }

    /**
     * Adds {@code box} for {@code value}, first cutting it out of every point that is wide on each axis the box is wide
     * on (single-value points, such as the surface layers under a cave box or the Nether's points, are left alone and
     * compete by distance, as vanilla's do).
     *
     * @return how many points were cut
     */
    public static <T> int add(List<Entry<T>> in, List<Entry<T>> out, long[] box, long offset, T value) {
        int carved = 0;
        for (Entry<T> e : in) {
            boolean carve = overlaps(e.box(), box);
            for (int d = 0; carve && d < DIMS; d++) if (wide(box, d) && !wide(e.box(), d)) carve = false;
            if (!carve) {
                out.add(e);
                continue;
            }
            long[] cut = intersect(e.box(), box);
            for (long[] piece : subtract(e.box(), cut)) out.add(new Entry<>(piece, e.offset(), e.value()));
            carved++;
        }
        out.add(new Entry<>(box.clone(), offset, value));
        return carved;
    }
}
