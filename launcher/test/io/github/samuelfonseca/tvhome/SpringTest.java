package io.github.samuelfonseca.tvhome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SpringTest {
    private static final float FRAME = 1 / 60f;

    /** Steps until settled; returns the number of frames, or fails after 10 seconds. */
    private static int settle(Spring s) {
        for (int i = 1; i <= 600; i++) if (!s.step(FRAME)) return i;
        throw new AssertionError("never settled");
    }

    @Test void settlesExactlyOnTheTarget() {
        Spring s = new Spring(500, 0.8f);
        s.snap(0);
        s.target = 300;
        int frames = settle(s);
        assertEquals(300f, s.value);
        assertEquals(0f, s.velocity);
        assertTrue(frames < 60, "took " + frames + " frames");
    }

    @Test void underdampedOvershootsALittle() {
        Spring s = new Spring(500, 0.7f);
        s.snap(0);
        s.target = 100;
        float peak = 0;
        while (s.step(FRAME)) peak = Math.max(peak, s.value);
        assertTrue(peak > 100 && peak < 110, "peak " + peak);
    }

    @Test void criticallyDampedNeverOvershoots() {
        Spring s = new Spring(500, 1f);
        s.snap(0);
        s.target = 100;
        while (s.step(FRAME)) assertTrue(s.value <= 100.001f, "overshot to " + s.value);
    }

    @Test void retargetKeepsVelocity() {
        Spring s = new Spring(500, 0.8f);
        s.snap(0);
        s.target = 100;
        for (int i = 0; i < 5; i++) s.step(FRAME);
        float v = s.velocity;
        s.target = 200;
        assertEquals(v, s.velocity);
        settle(s);
        assertEquals(200f, s.value);
    }

    @Test void offsetMovesValueAndTargetTogether() {
        Spring s = new Spring(500, 0.8f);
        s.snap(50);
        s.offset(-20);
        assertEquals(30f, s.value);
        assertFalse(s.step(FRAME), "an offset alone shouldn't start it moving");
    }

    @Test void aLongFrameDoesNotBlowUp() {
        Spring s = new Spring(500, 0.8f);
        s.snap(0);
        s.target = 100;
        s.step(0.5f); // a half-second hitch
        assertTrue(Math.abs(s.value - 100) < 20, "value " + s.value);
    }
}
