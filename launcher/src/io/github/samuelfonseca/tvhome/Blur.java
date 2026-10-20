package io.github.samuelfonseca.tvhome;

/**
 * Box blur on ARGB pixels: a small thumbnail of the wallpaper for the glass panels (with the
 * glass's tint laid over it), and the focus highlight's halo. Three passes look close to a
 * Gaussian. Plain Java, so the tests run without Android.
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
     * between neighbors in a line, {@code lineStep} between the starts of lines.
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

    /**
     * Lays the translucent {@code color} over each opaque pixel of {@code px} in place, as
     * drawing it on top would.
     */
    static void over(int[] px, int color) {
        int a = color >>> 24, keep = 255 - a;
        int r = ((color >> 16) & 0xFF) * a, g = ((color >> 8) & 0xFF) * a, b = (color & 0xFF) * a;
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            px[i] = 0xFF000000
                    | (((c >> 16) & 0xFF) * keep + r + 127) / 255 << 16
                    | (((c >> 8) & 0xFF) * keep + g + 127) / 255 << 8
                    | ((c & 0xFF) * keep + b + 127) / 255;
        }
    }

    /**
     * Turns the shape in {@code px} (its alpha) into a soft glow of {@code color} around it, like
     * an outer blur: the shape blurred by three box passes of {@code radius}, with nothing left
     * where the shape itself was.
     */
    static void glow(int[] px, int w, int h, int radius, int color) {
        int[] shape = new int[px.length];
        for (int i = 0; i < px.length; i++) shape[i] = px[i] >>> 24;
        box(px, w, h, radius, 3);
        int a = color >>> 24, rgb = color & 0xFFFFFF;
        for (int i = 0; i < px.length; i++) {
            int outside = (px[i] >>> 24) * (255 - shape[i]) / 255;
            px[i] = (outside * a / 255) << 24 | rgb;
        }
    }

    private static int clamp(int i, int len) {
        return i < 0 ? 0 : i >= len ? len - 1 : i;
    }
}
