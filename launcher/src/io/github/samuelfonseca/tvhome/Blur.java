package io.github.samuelfonseca.tvhome;

/**
 * Box blur on ARGB pixels, run on a small thumbnail of the wallpaper for the glass panels.
 * Three passes look close to a Gaussian. Plain Java, so the tests run without Android.
 */
final class Blur {
    private Blur() {}

    /** Blurs {@code px} ({@code w} x {@code h}) in place; edges repeat the border pixel. */
    static void box(int[] px, int w, int h, int radius, int passes) {
        int[] tmp = new int[px.length];
        for (int p = 0; p < passes; p++) {
            pass(px, tmp, w, h, radius, 1, w); // rows
            pass(tmp, px, h, w, radius, w, 1); // columns, back into px
        }
    }

    /**
     * One direction: {@code lines} lines of {@code len} pixels; {@code step} is the distance
     * between neighbours in a line, {@code lineStep} between the starts of lines.
     */
    private static void pass(int[] in, int[] out, int len, int lines, int r, int step, int lineStep) {
        int n = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int start = line * lineStep;
            int a = 0, red = 0, g = 0, b = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[start + clamp(i, len) * step];
                a += c >>> 24; red += (c >> 16) & 0xFF; g += (c >> 8) & 0xFF; b += c & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                out[start + i * step] = (a / n) << 24 | (red / n) << 16 | (g / n) << 8 | (b / n);
                int add = in[start + clamp(i + r + 1, len) * step];
                int drop = in[start + clamp(i - r, len) * step];
                a += (add >>> 24) - (drop >>> 24);
                red += ((add >> 16) & 0xFF) - ((drop >> 16) & 0xFF);
                g += ((add >> 8) & 0xFF) - ((drop >> 8) & 0xFF);
                b += (add & 0xFF) - (drop & 0xFF);
            }
        }
    }

    private static int clamp(int i, int len) {
        return i < 0 ? 0 : i >= len ? len - 1 : i;
    }
}
