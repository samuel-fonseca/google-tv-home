package io.github.samuelfonseca.tvhome;

import java.util.ArrayList;
import java.util.List;

/**
 * Named two-colour gradients, for TV input tiles and for plain backgrounds. Saved by name, so the
 * lists can be reordered. Plain Java, so the tests run without Android.
 */
final class Palette {
    static final class Swatch {
        final String name;
        final int from, to; // top left to bottom right

        Swatch(String name, int from, int to) {
            this.name = name;
            this.from = from;
            this.to = to;
        }
    }

    /** Input tiles, behind a white glyph and name. The first, the default, is the blue they started with. */
    static final List<Swatch> TILES = List.of(
            new Swatch("Blue", 0xFF00246B, 0xFF0070D1),
            new Swatch("Green", 0xFF0B3D14, 0xFF158C2A),
            new Swatch("Red", 0xFF6E0A14, 0xFFD3202E),
            new Swatch("Purple", 0xFF2A1563, 0xFF6A3FD0),
            new Swatch("Orange", 0xFF7A2A00, 0xFFE3631C),
            new Swatch("Teal", 0xFF00434A, 0xFF00989E),
            new Swatch("Graphite", 0xFF2E3236, 0xFF50555C),
            new Swatch("Black", 0xFF050505, 0xFF242424));

    /** Plain backgrounds instead of photos: dark, so white text reads on them, as it does on the photos. */
    static final List<Swatch> BACKGROUNDS = List.of(
            new Swatch("Midnight", 0xFF070B1C, 0xFF1D2A5C),
            new Swatch("Ocean", 0xFF021A2C, 0xFF0A5A78),
            new Swatch("Forest", 0xFF06170F, 0xFF1E5034),
            new Swatch("Plum", 0xFF16081D, 0xFF552762),
            new Swatch("Ember", 0xFF1C0805, 0xFF7A2D12),
            new Swatch("Graphite", 0xFF0E0F11, 0xFF36393E),
            new Swatch("Black", 0xFF000000, 0xFF000000));

    private Palette() {}

    /** The swatch called {@code name}, or the list's first, the default, when there's none. */
    static Swatch find(List<Swatch> list, String name) {
        for (Swatch s : list) if (s.name.equals(name)) return s;
        return list.get(0);
    }

    /** Whether {@code list} has a swatch called {@code name}. */
    static boolean has(List<Swatch> list, String name) {
        for (Swatch s : list) if (s.name.equals(name)) return true;
        return false;
    }

    static String[] names(List<Swatch> list) {
        List<String> names = new ArrayList<>();
        for (Swatch s : list) names.add(s.name);
        return names.toArray(new String[0]);
    }
}
