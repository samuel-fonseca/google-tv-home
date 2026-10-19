package io.github.samuelfonseca.tvhome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class BlurTest {
    private static int red(int c) {
        return (c >> 16) & 0xFF;
    }

    @Test void flatColourStaysFlat() {
        int[] px = new int[20 * 10];
        Arrays.fill(px, 0xFF336699);
        Blur.box(px, 20, 10, 3, 3);
        for (int c : px) assertEquals(0xFF336699, c);
    }

    @Test void aBrightDotSpreadsAndFades() {
        int w = 21, h = 21;
        int[] px = new int[w * h];
        Arrays.fill(px, 0xFF000000);
        px[10 * w + 10] = 0xFFFFFFFF;
        Blur.box(px, w, h, 2, 3);
        int centre = red(px[10 * w + 10]), near = red(px[10 * w + 12]), far = red(px[10 * w + 16]);
        assertTrue(centre > near && near > far, centre + " " + near + " " + far);
        assertTrue(centre < 255, "the dot should have spread out");
        assertEquals(0, red(px[0]), "a far corner stays black");
    }

    @Test void edgesDoNotDarken() {
        // A white image with clamped edges must stay white right up to the border.
        int[] px = new int[8 * 8];
        Arrays.fill(px, 0xFFFFFFFF);
        Blur.box(px, 8, 8, 3, 2);
        assertEquals(0xFFFFFFFF, px[0]);
        assertEquals(0xFFFFFFFF, px[63]);
    }

    @Test void blursAcrossBothDirections() {
        int w = 9, h = 9;
        int[] px = new int[w * h];
        Arrays.fill(px, 0xFF000000);
        for (int x = 0; x < w; x++) px[4 * w + x] = 0xFFFFFFFF; // a horizontal white line
        Blur.box(px, w, h, 1, 1);
        assertTrue(red(px[3 * w + 4]) > 0, "spread to the row above");
        assertEquals(red(px[3 * w + 0]), red(px[3 * w + 8]), "symmetric along the line");
    }
}
