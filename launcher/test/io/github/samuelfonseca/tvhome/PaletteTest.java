package io.github.samuelfonseca.tvhome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class PaletteTest {
    @Test void findsByName() {
        assertEquals("Teal", Palette.find(Palette.TILES, "Teal").name);
        assertEquals("Ocean", Palette.find(Palette.BACKGROUNDS, "Ocean").name);
    }

    @Test void unknownOrMissingNameIsTheDefault() {
        assertSame(Palette.TILES.get(0), Palette.find(Palette.TILES, null));
        assertSame(Palette.TILES.get(0), Palette.find(Palette.TILES, "Chartreuse"));
        assertTrue(Palette.has(Palette.BACKGROUNDS, "Black"));
        assertFalse(Palette.has(Palette.BACKGROUNDS, null));
    }

    /** Input tiles kept their old blue as the default, so nothing changes until you pick a colour. */
    @Test void defaultTileIsTheOriginalBlue() {
        Palette.Swatch blue = Palette.TILES.get(0);
        assertEquals(0xFF00246B, blue.from);
        assertEquals(0xFF0070D1, blue.to);
    }

    @Test void namesAreUniqueAndColoursOpaque() {
        for (List<Palette.Swatch> list : List.of(Palette.TILES, Palette.BACKGROUNDS)) {
            Set<String> names = new HashSet<>(List.of(Palette.names(list)));
            assertEquals(list.size(), names.size(), "duplicate swatch name");
            for (Palette.Swatch s : list) {
                assertEquals(0xFF, s.from >>> 24, s.name + " isn't opaque");
                assertEquals(0xFF, s.to >>> 24, s.name + " isn't opaque");
            }
        }
    }

    /** White text sits on the plain backgrounds, so even their lighter end stays dark. */
    @Test void backgroundsAreDark() {
        for (Palette.Swatch s : Palette.BACKGROUNDS) {
            for (int c : new int[] {s.from, s.to}) {
                double luma = 0.2126 * (c >> 16 & 0xFF) + 0.7152 * (c >> 8 & 0xFF) + 0.0722 * (c & 0xFF);
                assertTrue(luma < 80, s.name + " is too light for white text");
            }
        }
    }
}
