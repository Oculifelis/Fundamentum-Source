package com.fundamentum.curve;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurveTest {
    private static Curve.Vec v(double x, double y, double z) {
        return new Curve.Vec(x, y, z);
    }

    @Test
    void straightCurveIsTheLine() {
        Curve c = Curve.between(v(0, 0, 0), v(1, 0, 0), v(10, 0, 0), v(-1, 0, 0));
        assertEquals(10, c.length(), 1e-6);
        assertTrue(Double.isInfinite(c.minRadius()));
        for (Curve.Vec p : c.points(1)) {
            assertEquals(0, p.y(), 1e-9);
            assertEquals(0, p.z(), 1e-9);
        }
    }

    @Test
    void quarterTurnLeavesAndArrivesStraight() {
        // Leaves east, arrives going south (so it plugs into an end that faces north).
        Curve c = Curve.between(v(0, 0, 0), v(1, 0, 0), v(8, 0, 8), v(0, 0, -1));
        assertEquals(1, c.tangent(0).x(), 1e-6);
        assertEquals(1, c.tangent(1).z(), 1e-6);
        assertTrue(c.length() > 8 * Math.sqrt(2) && c.length() < 16, "between the chord and the two legs: " + c.length());
        assertTrue(c.minRadius() > 3, "a gentle bend: " + c.minRadius());
    }

    @Test
    void uTurnInOneBlockIsTight() {
        Curve c = Curve.between(v(0, 0, 0), v(1, 0, 0), v(0, 0, 1), v(1, 0, 0));
        assertTrue(c.minRadius() < 1, "too tight to lay: " + c.minRadius());
    }

    @Test
    void framesAreEvenAndDoNotTwist() {
        Curve c = Curve.between(v(0, 0, 0), v(1, 0, 0), v(6, 3, 6), v(0, 0, -1));
        List<Curve.Frame> fs = c.frames(0.5);
        for (int i = 1; i < fs.size(); i++) {
            double step = fs.get(i).pos().sub(fs.get(i - 1).pos()).length();
            assertTrue(step < 0.6, "even spacing: " + step);
            assertTrue(fs.get(i).normal().dot(fs.get(i - 1).normal()) > 0.95, "no sudden twist at " + i);
            assertEquals(0, fs.get(i).normal().dot(fs.get(i).tangent()), 1e-6);
        }
        assertEquals(c.length(), fs.get(fs.size() - 1).distance(), 1e-9);
    }

    @Test
    void framesDoNotFlipWhenTheCurveClimbs() {
        // Straight up, then levelling off eastwards: a normal taken from "up" each step would jump where the tube turns
        // from vertical; carried along it stays smooth.
        Curve c = Curve.between(v(0, 0, 0), v(0, 1, 0), v(6, 6, 0), v(-1, 0, 0));
        List<Curve.Frame> fs = c.frames(0.25);
        for (int i = 1; i < fs.size(); i++) {
            assertTrue(fs.get(i).normal().dot(fs.get(i - 1).normal()) > 0.9, "no flip at " + i + " of " + fs.size());
        }
    }

    @Test
    void endsTogetherMakeAnEmptyCurve() {
        Curve c = Curve.between(v(1, 1, 1), v(1, 0, 0), v(1, 1, 1), v(-1, 0, 0));
        assertEquals(0, c.length(), 1e-9);
        assertEquals(2, c.frames(0.5).size());
    }
}
