package io.github.samuelfonseca.tvhome;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 16 tiles, 6 wide: rows 0-5, 6-11, and a short last row 12-15. */
class GridTest {
    @Test void downGoesStraightBelow() {
        assertEquals(8, Grid.below(2, 16, 6));
    }

    @Test void downIntoAShortRowLandsOnItsLastTile() {
        assertEquals(15, Grid.below(11, 16, 6));
        assertEquals(15, Grid.below(10, 16, 6));
    }

    @Test void downIntoAShortRowWithATileBelowGoesStraightDown() {
        assertEquals(15, Grid.below(9, 16, 6));
        assertEquals(12, Grid.below(6, 16, 6));
    }

    @Test void lastRowGoesNowhere() {
        assertEquals(-1, Grid.below(13, 16, 6));
        assertEquals(-1, Grid.below(15, 16, 6));
    }

    @Test void fullLastRow() {
        assertEquals(-1, Grid.below(6, 12, 6));
        assertEquals(11, Grid.below(5, 12, 6));
    }
}
