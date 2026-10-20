package io.github.samuelfonseca.tvhome;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ListsTest {
    private static BufferedReader text(String s) {
        return new BufferedReader(new StringReader(s));
    }

    @Test void entriesDropCommentsBlankLinesAndSpace() throws IOException {
        List<String> got = Lists.entries(text(
                "# header\n\ncom.a.one\n  com.b.two   # trailing note\n#com.c.off\n   \n"));
        assertEquals(List.of("com.a.one", "com.b.two"), got);
    }

    @Test void entriesKeepInputIds() throws IOException {
        List<String> got = Lists.entries(text("input:com.mediatek.tvinput/.hdmi.HDMIInputService/HW5\n"));
        assertEquals(List.of("input:com.mediatek.tvinput/.hdmi.HDMIInputService/HW5"), got);
    }

    @Test void wallpapersSplitIntoFiveFields() throws IOException {
        List<String[]> got = Lists.wallpapers(text(
                "wall_03|Spullersee, Austria|Böhringer Friedrich|CC BY-SA 3.0 at|Mountains\n"));
        assertEquals(1, got.size());
        assertArrayEquals(new String[] {"wall_03", "Spullersee, Austria", "Böhringer Friedrich", "CC BY-SA 3.0 at",
                "Mountains"}, got.get(0));
    }

    @Test void wallpapersSkipMalformedLines() throws IOException {
        List<String[]> got = Lists.wallpapers(text(
                "\nwall_01|Place|Author|Licence\nwall_02|a|b|c|d|e\nwall_03|a|b|c|d\n"));
        assertEquals(1, got.size());
        assertEquals("wall_03", got.get(0)[0]);
    }

    private static final List<String[]> WALLS = List.of(
            new String[] {"wall_01", "", "", "", "Lakes"},
            new String[] {"wall_02", "", "", "", "Forests"},
            new String[] {"wall_03", "", "", "", "Lakes"});

    @Test void categoriesInFirstSeenOrder() {
        assertEquals(List.of("Lakes", "Forests"), Lists.categories(WALLS));
    }

    @Test void inCategoriesKeepsOrder() {
        List<String[]> got = Lists.inCategories(WALLS, Set.of("Lakes"));
        assertEquals(2, got.size());
        assertEquals("wall_01", got.get(0)[0]);
        assertEquals("wall_03", got.get(1)[0]);
    }

    @Test void inCategoriesFallsBackToEverything() {
        assertEquals(WALLS, Lists.inCategories(WALLS, Set.of()));
        assertEquals(WALLS, Lists.inCategories(WALLS, Set.of("Deserts"))); // a category since removed
    }
}
